include(":app")

pluginManagement {
    // AndroidX 的 Gradle 插件（androidx.room / androidx.navigation 等）用 marker POM
    // 把真实实现指向同 group 的 artifact（androidx.room:room-gradle-plugin:2.8.5），
    // 这个 artifact 只在 Google Maven（dl.google.com）上，阿里云/腾讯云/华为云镜像
    // 都没有（marker POM 有，实现没有）。聚合镜像一旦命中 marker POM，Gradle 就认为
    // 插件已解析到该仓库、不再回落，最终报
    //   Plugin [id: 'androidx.room', version: '2.8.5'] was not found in any of the following sources
    // （见构建 cnb-rbs-1k4t3s203）。因此 AndroidX 插件单独走限定 group 的官方源，
    // 与根 build.gradle.kts 里 coil3 的处理同一思路；其余插件仍走镜像加速。
    fun RepositoryHandler.googleAndroidx(artifact: String) = maven(artifact) {
        content { includeGroupByRegex("androidx(\\..*)?") }
    }

    // 公共镜像只读 POM、关闭 GMM，保证跨仓库回落生效。
    // 详见根 build.gradle.kts 的仓库说明（腾讯云镜像缺 jellyfin-core-android 的 .aar
    // 却有 .module，GMM 会把组件绑定到该仓库、阻断回落，导致构建失败）。
    fun RepositoryHandler.publicMirror(url: String) = maven {
        setUrl(url)
        metadataSources { mavenPom(); artifact() }
    }

    repositories {
        gradlePluginPortal()
        // AndroidX 插件的 marker POM 与实际 artifact 必须由同一仓库提供
        googleAndroidx("https://dl.google.com/dl/android/maven2/")
        googleAndroidx("https://maven.aliyun.com/repository/google/")
        publicMirror("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        publicMirror("https://maven.aliyun.com/repository/public/")
        publicMirror("https://repo.huaweicloud.com/repository/maven/")
        mavenCentral()
        google()
    }
}
