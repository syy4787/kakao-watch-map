package com.mywatch.kakaomap

/** 자주 바꾸는 설정을 한곳에 모아 둔 파일 */
object AppConfig {

    /**
     * 카카오 디벨로퍼스 > 앱 > 플랫폼 키 > '네이티브 앱 키' (지도 표시용).
     * 검색·길찾기용 REST API 키와 공공데이터 키는 코드에 넣지 않고 빌드할 때 주입한다(README 참고).
     */
    const val KAKAO_NATIVE_APP_KEY = "ac1c65114d18dd7221b75631d59b11c9"

    /** 위치를 모를 때 처음 보여 줄 곳 (서울시청) */
    const val DEFAULT_LATITUDE = 37.5666805
    const val DEFAULT_LONGITUDE = 126.9784147

    /** 시작 줌 레벨 — 숫자가 클수록 확대 */
    const val DEFAULT_ZOOM_LEVEL = 16

    /** 베젤(또는 크라운)을 시계 방향으로 돌리면 확대. 반대가 편하면 false */
    const val CLOCKWISE_ZOOMS_IN = true

    /** 버튼이 저절로 숨겨지기까지의 시간(ms). 0 이면 항상 보임 */
    const val CONTROLS_AUTO_HIDE_MS = 4000L

    /** 지도를 보는 동안 화면 꺼짐 방지. 배터리를 더 쓰므로 기본은 꺼 둠 */
    const val KEEP_SCREEN_ON = false

    /** 내 위치 방향(부채꼴)이 반대로 돌면 -1 로 바꾼다 */
    const val HEADING_SIGN = 1
}
