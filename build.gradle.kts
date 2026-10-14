// 최상위 빌드 파일. AGP 9부터 Kotlin 지원이 내장되어 있어서 kotlin-android 플러그인은 넣지 않습니다.
// 다만 내장 Kotlin 버전(2.2)은 유튜브 재생 엔진(GeckoView, Kotlin 2.4 로 만들어짐)을 읽지 못해 새 버전을 쓴다.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.1.1" apply false
}
