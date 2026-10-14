package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * 개인정보 처리방침대로 유튜브 관련 정보는 워치 안에만: 클라우드 백업·기기 옮기기에서 뺀다 —
 * 로그인 토큰, 본 동영상·검색어, 오류 기록, 재생 엔진의 쿠키·저장소(mozilla), 엔진 충돌 기록(minidumps)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupRulesTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    /** 규칙 파일에서 [section] 안(없으면 어디든)의 exclude (domain, path) */
    private fun excludes(xml: Int, section: String?): Set<Pair<String, String>> {
        val p = context.resources.getXml(xml)
        val out = HashSet<Pair<String, String>>()
        var inSection = section == null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == section) inSection = true
            if (p.eventType == XmlPullParser.END_TAG && p.name == section) inSection = false
            if (inSection && p.eventType == XmlPullParser.START_TAG && p.name == "exclude") {
                out += p.getAttributeValue(null, "domain") to p.getAttributeValue(null, "path")
            }
        }
        return out
    }

    private val mustStay = setOf(
        "sharedpref" to "tube_account.xml",
        "sharedpref" to "tube.xml",
        "sharedpref" to "error_log.xml",
        "file" to "error_log.jsonl",
        "file" to "mozilla",
        "file" to "minidumps",
    )

    @Test
    fun youTubeDataStaysOnTheWatch() {
        val old = excludes(R.xml.backup_rules, null)
        assertTrue("Android 11 백업: ${mustStay - old}", old.containsAll(mustStay))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val got = excludes(R.xml.data_extraction_rules, section)
            assertTrue("$section: ${mustStay - got}", got.containsAll(mustStay))
        }
    }
}
