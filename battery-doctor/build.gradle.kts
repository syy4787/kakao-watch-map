// 배터리 닥터 PC 툴킷(Windows): ADB로 워치의 배터리 통계를 수집·분석하고, 차단 조치와 증거 패키지를 만든다.
// 순수 Kotlin(JVM 17) CLI — 배포 zip 에 Java(Eclipse Temurin JRE, runtime\)가 함께 들어 있어 설치 없이 돌아가고(-PdoctorJre),
// adb 는 없으면 처음 쓸 때 물어본 뒤 구글 공식 platform-tools 를 받는다(AdbSetup).
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
    // 신고문 파일 이름이 한글이라 테스트 JVM 도 UTF-8 로케일이어야 한다 (POSIX 로케일 환경에서는 sun.jnu.encoding 이 ASCII 가 됨)
    environment("LC_ALL", "C.UTF-8")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8", "-Djava.awt.headless=true")
}

// 기본 jar 도 battery-doctor.jar 라는 이름을 쓰므로 fatJar 와 출력이 겹친다 → 기본(얇은) jar 에 분류자를 붙여 비켜 준다
tasks.jar {
    archiveClassifier.set("thin")
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

/**
 * 배포물: BatteryDoctor.bat + battery-doctor.jar + README.txt (+ runtime\) → build/dist/battery-doctor.zip
 * (./gradlew :battery-doctor:doctorZip [-PdoctorJre=OpenJDK21U-jre_x64_windows_hotspot_….zip]).
 * -PdoctorJre 를 주면 그 JRE zip 의 내용을 맨 위 폴더(jdk-…-jre/)를 걷어 runtime/ 에 그대로 넣는다 — Java 가 없는 PC 에서도
 * .bat 이 runtime\bin\java.exe 로 돈다. 릴리스(CI)는 버전·sha256 을 고정한 Temurin JRE 를 받아 넣는다
 */
val doctorJre = providers.gradleProperty("doctorJre")
val doctorZip by tasks.registering(Zip::class) {
    group = "distribution"
    archiveFileName.set("battery-doctor.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    from(fatJar)
    from(layout.projectDirectory.file("BatteryDoctor.bat"))
    from(layout.projectDirectory.file("README.txt"))
    val jre = doctorJre.orNull
    if (jre != null) {
        val jreZip = file(jre)
        require(jreZip.isFile) { "doctorJre: JRE zip 이 없습니다 — $jre" }
        from(zipTree(jreZip)) {
            // jdk-21.0.x+y-jre/bin/java.exe → runtime/bin/java.exe
            eachFile { relativePath = RelativePath(true, "runtime", *relativePath.segments.drop(1).toTypedArray()) }
            includeEmptyDirs = false
        }
    }
}
