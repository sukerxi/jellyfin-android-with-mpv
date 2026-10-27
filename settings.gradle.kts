include(":app")

pluginManagement {
    // Google Maven 专属的插件 group（AndroidX 与 AGP）单独走限定 group 的 Google 源。
    //
    // AndroidX 的 Gradle 插件（androidx.room / androidx.navigation 等）用 marker POM
    // 把真实实现指向同 group 的 artifact（androidx.room:room-gradle-plugin:2.8.5），
    // 这个 artifact 只在 Google Maven（dl.google.com）上，阿里云/腾讯云/华为云镜像
    // 都没有（marker POM 有，实现没有）。聚合镜像一旦命中 marker POM，Gradle 就认为
    // 插件已解析到该仓库、不再回落，最终报
    //   Plugin [id: 'androidx.room', version: '2.8.5'] was not found in any of the following sources
    // （见构建 cnb-rbs-1k4t3s203）。
    //
    // AGP 同理且更常见：com.android.application 的 marker 与实现
    // com.android.tools.build:gradle 都只在 Google Maven 上。此前只放行 androidx，
    // AGP 只能落到最后的 google() 或聚合镜像；冷缓存构建遇到 dl.google.com 限流/抖动时
    // 所有仓库都报未命中：
    //   Plugin [id: 'com.android.application', version: '9.4.0'] was not found in any of the following sources
    // （2026-10-27 的发版构建）。把 com.android.* 一并纳入后，AGP 直接从 dl.google.com
    // 或阿里云 Google 镜像解析，与聚合镜像的限流互不影响。
    fun RepositoryHandler.googleHosted(artifact: String) = maven(artifact) {
        content {
            includeGroupByRegex("androidx(\\..*)?")
            includeGroupByRegex("com\\.android(\\..*)?")
        }
    }

    // 公共镜像只读 POM、关闭 GMM，保证跨仓库回落生效。
    // 详见根 build.gradle.kts 的仓库说明（腾讯云镜像缺 jellyfin-core-android 的 .aar
    // 却有 .module，GMM 会把组件绑定到该仓库、阻断回落，导致构建失败）。
    fun RepositoryHandler.publicMirror(url: String) = maven {
        setUrl(url)
        metadataSources { mavenPom(); artifact() }
    }

    repositories {
        // Google 专属插件（AndroidX / AGP）的 marker POM 与实际 artifact 必须由同一仓库提供
        googleHosted("https://dl.google.com/dl/android/maven2/")
        googleHosted("https://maven.aliyun.com/repository/google/")
        // 插件与依赖都先走公共镜像：Gradle 会为每个插件按顺序在每个仓库查一次，
        // 把共享出口的 Gradle Plugin Portal / Maven Central 排在前面，遇到 429 时
        // 会直接 disable 该仓库、连带整条解析链失败（见构建 cnb-7p7-1k5ujrqnf）。
        publicMirror("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        publicMirror("https://maven.aliyun.com/repository/public/")
        // 阿里云另有独立的 Gradle Plugin Portal 镜像：部分插件（gradle-kotlin-dsl-plugins
        // 等 org.gradle.* 制品）在 repository/public 里没有，只在该镜像上。
        publicMirror("https://maven.aliyun.com/repository/gradle-plugin/")
        publicMirror("https://repo.huaweicloud.com/repository/maven/")
        // 上面几个镜像仍未覆盖的插件（如 de.mannodermaus:android-junit5）走 Plugin Portal。
        gradlePluginPortal()
        // 官方源只兜底镜像同步空窗期可能缺失的 group（插件生态多为这些 group）。
        mavenCentral {
            content {
                includeGroupByRegex("org\\.jetbrains(\\..*)?")
                includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
                includeGroupByRegex("org\\.kotlin(\\..*)?")
                includeGroupByRegex("com\\.gradle(\\..*)?")
            }
        }
        google {
            content {
                includeGroupByRegex("androidx(\\..*)?")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google(\\..*)?")
            }
        }
    }
}
