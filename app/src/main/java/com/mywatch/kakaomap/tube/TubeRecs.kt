package com.mywatch.kakaomap.tube

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 재생 화면이 알려 준 유튜브 믹스 하나: [seed] = 처음 동영상(본 것), [ids] = 유튜브가 이어 준 추천, [at] = 받은 때(ms).
 * 동영상 번호만 — 제목·그림은 보여 줄 때 새로 받는다
 */
data class Harvest(val seed: String, val ids: List<String>, val at: Long)

/**
 * 받아 둔 믹스 추천 (워치 안에만): 7일까지 · 최근 믹스 12개까지 · 믹스마다 49개까지, 새것이 앞.
 * 점수를 매기지 않고 받은 차례대로 번갈아 쓴다 ([order]) — 유튜브 API 정책: 받은 데이터로 새 지표를 만들지 않음
 */
object MixHarvests {
    const val KEEP_MS = 7L * 24 * 3_600_000
    const val MAX = 12
    /** 믹스 하나에서 받아 두는 추천 수 (재생 화면이 한 번에 받는 50개 중 처음 것을 뺀 수) */
    const val MAX_IDS = 49

    /**
     * [h] 를 넣는다 (같은 씨앗의 예전 것은 바꾼다). 추천은 다듬고([clean]), 남는 것이 없거나 씨앗이 이상하면 넣지 않는다.
     * 받은 때가 새것부터 (같으면 방금 넣은 것이 앞), 지난 것은 빼고 [MAX] 개까지
     */
    fun add(list: List<Harvest>, h: Harvest, now: Long): List<Harvest> {
        val ids = clean(h.seed, h.ids)
        val all = if (!TubeLinks.isId(h.seed) || ids.isEmpty()) list else listOf(h.copy(ids = ids)) + list.filterNot { it.seed == h.seed }
        return prune(all.sortedByDescending { it.at }, now).take(MAX)
    }

    /** [KEEP_MS] 가 지난 것을 뺀다 (시계가 크게 뒤로 가 그만큼 앞날이 된 것도 — 언제까지나 남지 않게) */
    fun prune(list: List<Harvest>, now: Long): List<Harvest> = list.filter { fresh(it.at, now, KEEP_MS) }

    /**
     * 추천 차례: 새 믹스부터 번갈아 — 믹스마다 첫째, 그다음 둘째, … ([list] 는 새것부터).
     * 같은 번호는 한 번, 어느 믹스의 씨앗이든(이미 본 것) 빼고 [max] 개까지
     */
    fun order(list: List<Harvest>, max: Int = 100): List<String> {
        val seeds = list.mapTo(HashSet()) { it.seed }
        val out = LinkedHashSet<String>()
        val rounds = list.maxOfOrNull { it.ids.size } ?: 0
        for (i in 0 until rounds) {
            for (h in list) {
                if (out.size >= max) return out.toList()
                val id = h.ids.getOrNull(i) ?: continue
                if (id !in seeds && TubeLinks.isId(id)) out.add(id)
            }
        }
        return out.toList()
    }

    fun encode(list: List<Harvest>): String = JSONArray().apply {
        list.forEach { h ->
            put(JSONObject().apply {
                put("seed", h.seed)
                put("ids", JSONArray(h.ids))
                put("at", h.at)
            })
        }
    }.toString()

    /** 저장된 것 읽기 — 망가진 것은 빼고 번호를 다시 확인한다 (같은 씨앗은 앞의 것만). 지난 것은 [prune] 으로 */
    fun decode(text: String?): List<Harvest> {
        val arr = try {
            JSONArray(text ?: return emptyList())
        } catch (e: JSONException) {
            return emptyList()
        }
        val out = LinkedHashMap<String, Harvest>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val seed = (o.opt("seed") as? String)?.takeIf(TubeLinks::isId) ?: continue
            val raw = o.optJSONArray("ids") ?: continue
            val at = o.optLong("at", 0).takeIf { it > 0 } ?: continue
            val ids = clean(seed, (0 until raw.length()).mapNotNull { raw.opt(it) as? String })
            if (ids.isNotEmpty()) out.putIfAbsent(seed, Harvest(seed, ids, at))
        }
        return out.values.toList()
    }

    /** 추천 번호 다듬기: 모양이 맞는 것만 · 같은 것은 한 번 · 씨앗은 빼고 · [MAX_IDS] 개까지 (순서 그대로) */
    internal fun clean(seed: String, ids: List<String>): List<String> =
        ids.asSequence().filter { TubeLinks.isId(it) && it != seed }.distinct().take(MAX_IDS).toList()
}

/** 표시해 둔 번호와 그때(ms) */
data class Mark(val id: String, val at: Long)

/**
 * 표시 목록 — '관심 없음' 동영상 · '채널 추천 안 함' 채널 · 본 Shorts. 워치 안에만 30일까지 · [MAX] 개까지, 새것이 앞.
 * 번호 모양은 쓰는 쪽([TubeState])이 확인하고, 여기서는 빈 것·너무 긴 것만 막는다
 */
object Marks {
    const val KEEP_MS = 30L * 24 * 3_600_000
    const val MAX = 300
    private const val MAX_ID = 64

    /** [id] 를 [at](표시한 때 — 기본은 지금)으로 맨 앞에 (있던 것은 옮긴다). 지난 것은 빼고 [MAX] 개까지 */
    fun add(list: List<Mark>, id: String, now: Long, at: Long = now): List<Mark> {
        val all = if (valid(id)) listOf(Mark(id, at)) + list.filterNot { it.id == id } else list
        return prune(all, now).take(MAX)
    }

    /** [KEEP_MS] 가 지난 것을 뺀다 (시계가 크게 뒤로 가 그만큼 앞날이 된 것도) */
    fun prune(list: List<Mark>, now: Long): List<Mark> = list.filter { fresh(it.at, now, KEEP_MS) }

    fun encode(list: List<Mark>): String = JSONArray().apply {
        list.forEach { m ->
            put(JSONObject().apply {
                put("id", m.id)
                put("at", m.at)
            })
        }
    }.toString()

    /** 저장된 것 읽기 — 망가진 것은 빼고 (같은 번호는 앞의 것만). 지난 것은 [prune] 으로 */
    fun decode(text: String?): List<Mark> {
        val arr = try {
            JSONArray(text ?: return emptyList())
        } catch (e: JSONException) {
            return emptyList()
        }
        val out = LinkedHashMap<String, Mark>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = (o.opt("id") as? String)?.takeIf(::valid) ?: continue
            val at = o.optLong("at", 0).takeIf { it > 0 } ?: continue
            out.putIfAbsent(id, Mark(id, at))
        }
        return out.values.toList()
    }

    private fun valid(id: String) = id.isNotBlank() && id.length <= MAX_ID
}

/** 받은 때 [at] 이 지금에서 [keep] 안쪽인지 (앞날도 [keep] 까지만) */
private fun fresh(at: Long, now: Long, keep: Long): Boolean = at >= now - keep && at <= now + keep
