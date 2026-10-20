// 배터리 닥터 PC 툴킷(Windows): ADB로 워치의 배터리 통계를 수집·분석하고, 차단 조치와 증거 패키지를 만든다.
// 순수 Kotlin(JVM 17) CLI — Android Studio 에 들어 있는 Java(JBR)와 adb 로 설치 없이 돌아간다.
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
    implementation(project(":battery-core"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

/** 모든 의존성을 넣은 실행 JAR (battery-doctor.jar) */
val fatJar by tasks.registering(Jar::class) {
    group = "distribution"
    archiveFileName.set("battery-doctor.jar")
    manifest { attributes("Main-Class" to "doctor.MainKt") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class", "module-info.class")
}

/** 배포물: BatteryDoctor.bat + battery-doctor.jar + README.txt → build/dist/battery-doctor.zip  (./gradlew :battery-doctor:doctorZip) */
val doctorZip by tasks.registering(Zip::class) {
    group = "distribution"
    archiveFileName.set("battery-doctor.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    from(fatJar)
    from(layout.projectDirectory.file("BatteryDoctor.bat"))
    from(layout.projectDirectory.file("README.txt"))
}
