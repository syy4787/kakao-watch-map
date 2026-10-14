package com.mywatch.kakaomap

import com.mywatch.kakaomap.tube.Harvest
import com.mywatch.kakaomap.tube.Mark
import com.mywatch.kakaomap.tube.Marks
import com.mywatch.kakaomap.tube.MixHarvests
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 워치 안에 받아 두는 추천 정보 (순수 계산): 재생 화면이 알려 준 유튜브 믹스(7일 · 최근 12개 · 믹스마다 49개)와
 * 표시 목록('관심 없음' · '채널 추천 안 함' · 본 Shorts — 30일 · 300개). 점수 없이 받은 차례대로 번갈아 쓴다
 */
class TubeRecsTest {

    private val day = 24 * 3_600_000L
    private val now = 100 * day

    private fun id(n: Int) = "rec%08d".format(n)
    private fun seed(n: Int) = "sed%08d".format(n)

    @Test
    fun aNewMixGoesFirstAndTheSameSeedReplacesTheOldOne() {
        var list = MixHarvests.add(emptyList(), Harvest(seed(1), listOf(id(1), id(2)), now - 2 * day), now)
        list = MixHarvests.add(list, Harvest(seed(2), listOf(id(3)), now - day), now)
        assertEquals(listOf(seed(2), seed(1)), list.map { it.seed })
        // 같은 씨앗을 다시 받으면 예전 것을 바꾸고 맨 앞으로
        list = MixHarvests.add(list, Harvest(seed(1), listOf(id(4)), now), now)
        assertEquals(listOf(Harvest(seed(1), listOf(id(4)), now), Harvest(seed(2), listOf(id(3)), now - day)), list)
        // 최근 믹스 12개까지 (오래된 것부터 버림)
        val many = (1..15).fold(emptyList<Harvest>()) { acc, i -> MixHarvests.add(acc, Harvest(seed(i), listOf(id(i)), now), now) }
        assertEquals(MixHarvests.MAX, many.size)
        assertEquals(12, MixHarvests.MAX)
        assertEquals((15 downTo 4).map(::seed), many.map { it.seed })
    }

    @Test
    fun idsAreCleanedAndAMixWithNothingLeftIsIgnored() {
        val raw = listOf(id(1), seed(1), "bad", id(1), id(2), "", "x\"<script>") + (1..60).map(::id)
        val h = MixHarvests.add(emptyList(), Harvest(seed(1), raw, now), now).single()
        assertEquals("모양이 맞는 것만 · 같은 것은 한 번 · 씨앗은 빼고 · 49개까지 (순서 그대로)", (1..49).map(::id), h.ids)
        assertEquals(49, MixHarvests.MAX_IDS)

        val list = listOf(Harvest(seed(2), listOf(id(2)), now))
        assertEquals("남는 추천이 없으면 넣지 않는다", list, MixHarvests.add(list, Harvest(seed(3), listOf(seed(3), "bad"), now), now))
        assertEquals(list, MixHarvests.add(list, Harvest(seed(3), emptyList(), now), now))
        assertEquals("씨앗이 이상하면 넣지 않는다", list, MixHarvests.add(list, Harvest("bad", listOf(id(1)), now), now))
    }

    @Test
    fun mixesAreKeptSevenDays() {
        assertEquals(7 * day, MixHarvests.KEEP_MS)
        val list = listOf(
            Harvest(seed(1), listOf(id(1)), now - day),
            Harvest(seed(2), listOf(id(2)), now - 7 * day),
            Harvest(seed(3), listOf(id(3)), now - 7 * day - 1),
        )
        assertEquals(listOf(seed(1), seed(2)), MixHarvests.prune(list, now).map { it.seed })
        // 넣을 때도 지난 것은 뺀다
        assertEquals(listOf(seed(4), seed(1)), MixHarvests.add(list, Harvest(seed(4), listOf(id(4)), now + day), now + day).map { it.seed })
        // 시계가 크게 뒤로 가서 받은 때가 7일 넘게 앞날이 된 것도 지운다 (언제까지나 남지 않게)
        assertEquals(listOf(seed(2), seed(3)), MixHarvests.prune(list, now - 9 * day).map { it.seed })
        assertEquals(emptyList<Harvest>(), MixHarvests.prune(list, now - 15 * day))
    }

    @Test
    fun recommendationsTakeTurnsStartingWithTheNewestMix() {
        val list = listOf(
            Harvest(seed(3), listOf(id(31), id(32), id(33)), 3),
            Harvest(seed(2), listOf(id(21), id(31), seed(1)), 2),
            Harvest(seed(1), listOf(id(11), seed(3), id(12), id(13), id(14)), 1),
        )
        // 믹스마다 첫째 (새 믹스부터) → 둘째 → … · 같은 번호는 한 번 · 씨앗(이미 본 것)은 어느 믹스의 것이든 빼고
        assertEquals(
            listOf(id(31), id(21), id(11), id(32), id(33), id(12), id(13), id(14)),
            MixHarvests.order(list),
        )
        assertEquals(listOf(id(31), id(21), id(11), id(32)), MixHarvests.order(list, max = 4))
        assertEquals(emptyList<String>(), MixHarvests.order(list, max = 0))
        assertEquals(emptyList<String>(), MixHarvests.order(emptyList()))
        // 많아도 100개까지
        val full = (1..12).map { m -> Harvest(seed(m), (1..49).map { id(m * 100 + it) }, 100L - m) }
        val order = MixHarvests.order(full)
        assertEquals(100, order.size)
        assertEquals((1..12).map { id(it * 100 + 1) }, order.take(12))
    }

    @Test
    fun mixesSurviveSavingAndBrokenEntriesAreSkipped() {
        val list = listOf(Harvest(seed(2), listOf(id(1), id(2)), 2_000), Harvest(seed(1), listOf(id(3)), 1_000))
        assertEquals(list, MixHarvests.decode(MixHarvests.encode(list)))
        assertEquals(emptyList<Harvest>(), MixHarvests.decode(MixHarvests.encode(emptyList())))
        listOf(null, "", "not json", "{}", "[1,2,3]", "[null]").forEach {
            assertEquals("$it", emptyList<Harvest>(), MixHarvests.decode(it))
        }
        val messy = """[
            {"seed":"${seed(1)}","ids":["${id(1)}","bad","${seed(1)}","${id(1)}",7,"${id(2)}"],"at":1000},
            {"seed":"bad","ids":["${id(3)}"],"at":1000},
            {"seed":"${seed(2)}","ids":["bad"],"at":1000},
            {"seed":"${seed(3)}","ids":"${id(4)}","at":1000},
            {"seed":"${seed(4)}","ids":["${id(5)}"]},
            {"seed":"${seed(5)}","ids":["${id(5)}"],"at":"soon"},
            {"seed":12345678901,"ids":["${id(6)}"],"at":1000},
            {"seed":"${seed(1)}","ids":["${id(7)}"],"at":900},
            42, null, "x"
        ]"""
        assertEquals(listOf(Harvest(seed(1), listOf(id(1), id(2)), 1_000)), MixHarvests.decode(messy))
    }

    @Test
    fun marksKeepTheNewestThirtyDays() {
        assertEquals(30 * day, Marks.KEEP_MS)
        var marks = Marks.add(emptyList(), "BzYnNdJhZQw", now - 2 * day)
        marks = Marks.add(marks, "UCweOkPb1wVVH0Q0Tlj4a5Pw", now - day)
        marks = Marks.add(marks, "BzYnNdJhZQw", now)
        assertEquals("같은 번호는 맨 앞으로", listOf(Mark("BzYnNdJhZQw", now), Mark("UCweOkPb1wVVH0Q0Tlj4a5Pw", now - day)), marks)
        // 30일이 지나면 지운다 (시계가 크게 뒤로 가 앞날이 된 것도)
        assertEquals(marks, Marks.prune(marks, now + 29 * day))
        assertEquals(listOf(Mark("BzYnNdJhZQw", now)), Marks.prune(marks, now + 29 * day + 1))
        assertEquals(listOf(Mark("UCweOkPb1wVVH0Q0Tlj4a5Pw", now - day)), Marks.prune(marks, now - 31 * day + 1))
        assertEquals(listOf(Mark("x", now + 31 * day)), Marks.add(marks, "x", now + 31 * day))
        // 300개까지 (오래된 것부터 버림)
        val many = (1..310).fold(emptyList<Mark>()) { acc, i -> Marks.add(acc, "id$i", now + i) }
        assertEquals(Marks.MAX, many.size)
        assertEquals(300, Marks.MAX)
        assertEquals("id310", many.first().id)
        assertEquals("id11", many.last().id)
        // 빈 것·너무 긴 것(64자 넘게)은 받지 않는다 — 모양은 쓰는 쪽이 확인
        listOf("", "  ", "x".repeat(65)).forEach { assertEquals("'$it'", marks, Marks.add(marks, it, now)) }
        assertEquals("x".repeat(64), Marks.add(emptyList(), "x".repeat(64), now).single().id)
    }

    @Test
    fun marksSurviveSavingAndBrokenEntriesAreSkipped() {
        val marks = listOf(Mark("BzYnNdJhZQw", 2_000), Mark("UCweOkPb1wVVH0Q0Tlj4a5Pw", 1_000))
        assertEquals(marks, Marks.decode(Marks.encode(marks)))
        listOf(null, "", "garbage", "{}", "[1]").forEach { assertEquals("$it", emptyList<Mark>(), Marks.decode(it)) }
        val messy = """[{"id":"","at":1},{"id":"a"},{"at":5},{"id":"${"x".repeat(65)}","at":1},{"id":7,"at":1},
            {"id":"ok","at":7},{"id":"ok","at":3},{"id":"also","at":0},1,null]"""
        assertEquals(listOf(Mark("ok", 7)), Marks.decode(messy))
    }
}
