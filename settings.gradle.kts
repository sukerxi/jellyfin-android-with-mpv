include(":app")

pluginManagement {
    // 公共镜像只读 POM、关闭 GMM，保证跨仓库回落生效。
    // 详见根 build.gradle.kts 的仓库说明（腾讯云镜像缺 jellyfin-core-android 的 .aar
    // 却有 .module，GMM 会把组件绑定到该仓库、阻断回落，导致构建失败）。
    fun RepositoryHandler.publicMirror(url: String) = maven {
        setUrl(url)
        metadataSources { mavenPom(); artifact() }
    }

    repositories {
        gradlePluginPortal()
        publicMirror("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        publicMirror("https://maven.aliyun.com/repository/public/")
        publicMirror("https://repo.huaweicloud.com/repository/maven/")
        publicMirror("https://maven.aliyun.com/repository/google/")
        mavenCentral()
        google()
    }
}
