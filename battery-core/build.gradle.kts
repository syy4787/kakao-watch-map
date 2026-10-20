// 배터리 닥터 공용 라이브러리: 표본·CSV·소모율·게이지 이상·용량 추정·판정.
// 안드로이드에 기대지 않는 순수 Kotlin(JVM) — 워치 앱(app)과 PC 툴킷(battery-doctor)이 같이 쓴다.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
