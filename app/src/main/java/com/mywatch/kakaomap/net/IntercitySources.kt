package com.mywatch.kakaomap.net

import android.content.Context
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.IntercityKind
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.RouteStatusException
import com.mywatch.kakaomap.model.ScheduleParsers
import com.mywatch.kakaomap.model.ScheduledRun
import com.mywatch.kakaomap.model.TagoNode
import com.mywatch.kakaomap.model.TransitOptions
import com.mywatch.kakaomap.model.TransitRoute
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** 실제 카카오·TAGO 로 장거리 경로 재료를 받는다 (역·터미널 목록은 [TagoCatalog] 가 한 달씩 저장해 둠) */
class LiveIntercitySource(private val context: Context) : IntercitySource {

    override fun places(query: String, near: Coord, radiusM: Int): List<Place> = KakaoApi.searchNear(query, near, radiusM)

    override fun trainStations(cityCode: Int): List<TagoNode> =
        TagoCatalog.nodes(context, "train_$cityCode", ScheduleParsers::trainStations) { page -> TagoApi.trainStations(cityCode, page) }

    override fun expressTerminals(): List<TagoNode> =
        TagoCatalog.nodes(context, "express_terminals", ScheduleParsers::terminals, TagoApi::expressTerminals)

    override fun intercityTerminals(): List<TagoNode> =
        TagoCatalog.nodes(context, "intercity_terminals", ScheduleParsers::terminals, TagoApi::intercityTerminals)

    override fun runs(kind: IntercityKind, depId: String, arrId: String, day: LocalDate): List<ScheduledRun> {
        val ymd = day.format(YMD)
        return when (kind) {
            IntercityKind.TRAIN -> ScheduleParsers.trains(TagoApi.trainRuns(depId, arrId, ymd))
            IntercityKind.EXPRESS -> ScheduleParsers.buses(TagoApi.expressRuns(depId, arrId, ymd), kind)
            IntercityKind.INTERCITY -> ScheduleParsers.buses(TagoApi.intercityRuns(depId, arrId, ymd), kind)
        }
    }

    override fun transit(from: Coord, to: Coord, toName: String): TransitRoute? {
        val routes = try {
            KakaoApi.transit(from, to, toName)
        } catch (e: RouteStatusException) {
            return null
        }
        return TransitOptions.fastestIndex(routes)?.let(routes::get)
    }

    private companion object {
        val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}

/**
 * TAGO 역·터미널 목록 저장소. 목록은 거의 바뀌지 않으므로 받은 것을 앱 파일(짧은 JSON)로 30일 두고,
 * 앱이 켜져 있는 동안은 메모리에 둔다 (시외버스 터미널 목록은 150KB 라 검색마다 받기엔 무겁다).
 * 새로 받지 못하면 오래된 파일이라도 쓴다.
 */
object TagoCatalog {
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val MAX_PAGES = 5

    private class Entry(val at: Long, val nodes: List<TagoNode>)
    private val memory = HashMap<String, Entry>()
    /** 같은 목록을 동시에 받거나(출발·도착이 같은 도) 같은 파일에 쓰지 않게 이름마다 하나씩 */
    private val locks = ConcurrentHashMap<String, Any>()

    fun nodes(
        context: Context,
        name: String,
        parse: (JSONObject) -> List<TagoNode>,
        fetchPage: (Int) -> JSONObject,
    ): List<TagoNode> = synchronized(locks.getOrPut(name) { Any() }) { load(context, name, parse, fetchPage) }

    private fun load(
        context: Context,
        name: String,
        parse: (JSONObject) -> List<TagoNode>,
        fetchPage: (Int) -> JSONObject,
    ): List<TagoNode> {
        val now = System.currentTimeMillis()
        synchronized(memory) { memory[name]?.let { if (now - it.at < TTL_MS) return it.nodes } }
        val file = File(File(context.filesDir, "tago"), "$name.json")
        val stored = runCatching { if (file.exists()) decode(file.readText()) else null }.getOrNull()
        val nodes = if (stored != null && stored.isNotEmpty() && now - file.lastModified() < TTL_MS) {
            stored
        } else {
            try {
                download(parse, fetchPage).also { fresh -> if (fresh.isNotEmpty()) save(file, fresh) }
            } catch (e: Exception) {
                if (stored.isNullOrEmpty()) throw e
                stored
            }
        }
        synchronized(memory) { memory[name] = Entry(now, nodes) }
        return nodes
    }

    /** 한 쪽에 다 안 들어오면 다음 쪽까지 (최대 5쪽) */
    private fun download(parse: (JSONObject) -> List<TagoNode>, fetchPage: (Int) -> JSONObject): List<TagoNode> {
        val all = ArrayList<TagoNode>()
        for (page in 1..MAX_PAGES) {
            val json = fetchPage(page)
            val got = parse(json)
            all += got
            val total = json.optJSONObject("response")?.optJSONObject("body")?.optInt("totalCount", 0) ?: 0
            if (got.isEmpty() || all.size >= total) break
        }
        return all.distinctBy { it.id }
    }

    private fun save(file: File, nodes: List<TagoNode>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(encode(nodes))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    /** [[id, 이름, 시도], …] */
    fun encode(nodes: List<TagoNode>): String =
        JSONArray().apply { nodes.forEach { put(JSONArray().put(it.id).put(it.name).put(it.cityName)) } }.toString()

    fun decode(text: String): List<TagoNode> {
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONArray(i) ?: return@mapNotNull null
            val id = a.optString(0).ifBlank { null } ?: return@mapNotNull null
            TagoNode(id, a.optString(1), a.optString(2))
        }
    }
}
