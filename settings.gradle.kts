pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 카카오맵 SDK v2 저장소 — 카카오맵 라이브러리는 '오직' 여기서만 찾도록 지정
        exclusiveContent {
            forRepository {
                maven { url = uri("https://devrepo.kakao.com/nexus/repository/kakaomap-releases/") }
            }
            filter { includeGroup("com.kakao.maps.open") }
        }
        // 유튜브 재생 엔진(GeckoView, 모질라 공식 저장소) — 워치엔 앱 안에서 웹을 띄우는 시스템 부품(WebView)이 없다
        exclusiveContent {
            forRepository {
                maven { url = uri("https://maven.mozilla.org/maven2/") }
            }
            filter { includeGroup("org.mozilla.geckoview") }
        }
    }
}

rootProject.name = "KakaoWatchMap"
include(":app")
