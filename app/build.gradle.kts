import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
}

/**
 * 비밀 값(REST API 키, 공공데이터 키, 서명 비밀번호)은 코드에 넣지 않는다.
 *  · GitHub Actions: signing 폴더의 암호화 파일(.enc)을 풀어 만든 파일 + 환경 변수로 주입
 *  · 내 PC: local.properties 에 적어 두면 사용 (이 파일은 Git 에 올라가지 않음)
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val secretProps = Properties().apply {
    val path = System.getenv("SECRETS_FILE")
    if (path != null && file(path).exists()) file(path).inputStream().use { load(it) }
}

/** 값이 비어 있으면 없는 것으로 본다 (GitHub 에 등록 안 된 secret 은 빈 문자열로 들어옴) */
fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

fun secret(name: String, env: String): String =
    System.getenv(env).orNullIfBlank()
        ?: secretProps.getProperty(name).orNullIfBlank()
        ?: localProps.getProperty(name).orNullIfBlank()
        ?: ""

val releaseStoreFile: String? = System.getenv("RELEASE_STORE_FILE") ?: localProps.getProperty("release.storeFile")
val releasePassword: String? = System.getenv("RELEASE_KEY_PASSWORD") ?: localProps.getProperty("release.password")
val canSignRelease = releaseStoreFile != null && releasePassword != null && file(releaseStoreFile).exists()

android {
    namespace = "com.mywatch.kakaomap"
    compileSdk = 36

    defaultConfig {
        // ⚠️ 카카오 디벨로퍼스 > 플랫폼 키 > 네이티브 앱 키 > Android '패키지명'에 이 값을 그대로 등록
        applicationId = "com.mywatch.kakaomap"
        minSdk = 30          // Wear OS 3 이상
        targetSdk = 36
        // GitHub Actions 는 실행 번호로 버전을 자동 증가시킨다 (업데이트가 되려면 숫자가 커져야 함)
        versionCode = (System.getenv("VERSION_CODE") ?: localProps.getProperty("version.code") ?: "2").toInt()
        versionName = System.getenv("VERSION_NAME") ?: localProps.getProperty("version.name") ?: "2.0"

        buildConfigField("String", "KAKAO_REST_API_KEY", "\"${secret("kakao.rest.key", "KAKAO_REST_API_KEY")}\"")
        buildConfigField("String", "TAGO_SERVICE_KEY", "\"${secret("tago.service.key", "TAGO_SERVICE_KEY")}\"")
        // 유튜브 음악: 유튜브 자체 검색(YouTube Data API v3) 키. 없으면 카카오 동영상 검색으로 찾는다
        buildConfigField("String", "YOUTUBE_API_KEY", "\"${secret("youtube.api.key", "YOUTUBE_API_KEY")}\"")
        // 자동 업데이트를 받아 올 GitHub 저장소 (아이디/kakao-watch-map). Actions 에선 자동으로 채워짐
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: localProps.getProperty("update.repo") ?: ""}\"")
    }

    signingConfigs {
        create("release") {
            if (canSignRelease) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releasePassword
                keyAlias = "release"
                keyPassword = releasePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (canSignRelease) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        // 화면 그림 테스트(Robolectric)가 앱의 레이아웃·글꼴·색을 그대로 쓰도록
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        // 카카오맵 네이티브 라이브러리와 코드(dex)를 압축해서 APK 다운로드 크기를 줄인다 (워치 업데이트용).
        // 플레이 스토어를 거치지 않고 워치가 APK 를 그대로 받기 때문에 압축하는 쪽이 절반 가까이 작다.
        jniLibs {
            useLegacyPackaging = true
        }
        dex {
            useLegacyPackaging = true
        }
    }

    // CPU 종류별 APK 도 따로 만든다: 워치(32비트 armeabi-v7a)는 자기 CPU 용 카카오맵 라이브러리만 받으면 된다.
    // 모든 CPU 용(universal)도 함께 만들어 처음 설치·예전 버전 업데이트에 쓴다.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.kakao.maps.open:android:2.15.2")                    // 카카오맵 SDK v2
    implementation("androidx.wear:wear:1.4.0")                              // 둥근 화면 목록·스와이프 닫기
    implementation("androidx.wear:wear-input:1.2.0")                        // 음성·키보드 입력
    implementation("androidx.wear:wear-ongoing:1.1.0")                      // 안내 중 워치페이스에 아이콘(진행 중 활동)
    implementation("androidx.wear:wear-remote-interactions:1.2.0")          // 유튜브 음악: 고른 곡을 폰의 유튜브 앱에서 열기
    implementation("androidx.wear.tiles:tiles:1.6.2")                       // 타일: 집으로·회사로·검색
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material3:1.4.2")
    implementation("androidx.fragment:fragment:1.9.1")                      // wear 가 끌어오는 옛 버전 교체
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")                 // 자동 업데이트 (확인·내려받기·설치)
    implementation("com.google.android.gms:play-services-location:21.4.0") // 현재 위치

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")                            // 단위 테스트용 JSON 구현
    testImplementation("org.robolectric:robolectric:4.17")                  // 워치 화면을 실제로 그려 보는 테스트
    testImplementation("androidx.work:work-testing:2.10.5")                 // 업데이트 작업(WorkManager) 테스트
}

/**
 * 카카오 디벨로퍼스에 등록할 '키 해시'를 출력한다 (디버그 키 + 릴리스 키).
 * Android Studio 아래쪽 Terminal 에서:  Windows → .\gradlew kakaoKeyHash   /   macOS → ./gradlew kakaoKeyHash
 */
tasks.register("kakaoKeyHash") {
    group = "kakao"
    description = "카카오 디벨로퍼스에 등록할 키 해시를 출력합니다."
    val home = System.getProperty("user.home")
    val release = releaseStoreFile
    val pass = releasePassword
    doLast {
        fun hashOf(file: File, storePass: String, alias: String): String? {
            if (!file.exists()) return null
            for (type in listOf("PKCS12", "JKS")) {
                try {
                    val ks = KeyStore.getInstance(type)
                    file.inputStream().use { ks.load(it, storePass.toCharArray()) }
                    val cert = ks.getCertificate(alias) ?: return null
                    return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(cert.encoded))
                } catch (e: Exception) {
                    // 다른 형식으로 재시도
                }
            }
            return null
        }
        println()
        println("패키지명: com.mywatch.kakaomap")
        println("디버그 키 해시 : " + (hashOf(File(home, ".android/debug.keystore"), "android", "androiddebugkey")
            ?: "(debug.keystore 없음 — Build > Make Project 후 다시 실행)"))
        if (release != null && pass != null) {
            println("릴리스 키 해시 : " + (hashOf(File(release), pass, "release") ?: "(릴리스 키를 읽지 못함)"))
        }
    }
}

// 화면 그림 테스트(render 패키지)는 ./gradlew testDebugUnitTest -Prender 일 때만 — 평소·CI 테스트는 빠르게
tasks.withType<Test>().configureEach {
    if (!project.hasProperty("render")) exclude("**/render/**")
    // 유튜브 화면 무작위 사용자 시뮬레이션 씨앗 범위 (예: -Ptube.seeds=1..1000) — 평소엔 테스트에 적힌 기본값
    project.findProperty("tube.seeds")?.let { systemProperty("tube.seeds", it) }
}
