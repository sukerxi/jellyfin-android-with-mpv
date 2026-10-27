// buildSrc 自带 settings 文件后不再继承根 settings，
// 因此根项目 pluginManagement 的镜像配置不会自动生效，这里显式对齐。
//
// kotlin-dsl 插件由 buildSrc 自己的 pluginManagement 解析，共享出口下
// Gradle Plugin Portal / Maven Central 的 429 会让该仓库被 disable、
// 连带整条 kotlin-dsl 解析链失败（见构建 cnb-7p7-1k5ujrqnf）。
pluginManagement {
    fun RepositoryHandler.publicMirror(url: String) = maven {
        setUrl(url)
        metadataSources { mavenPom(); artifact() }
    }

    repositories {
        publicMirror("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        publicMirror("https://maven.aliyun.com/repository/public/")
        // org.gradle.kotlin:gradle-kotlin-dsl-plugins 只在该镜像上。
        publicMirror("https://maven.aliyun.com/repository/gradle-plugin/")
        publicMirror("https://repo.huaweicloud.com/repository/maven/")
        mavenCentral {
            content {
                includeGroupByRegex("org\\.jetbrains(\\..*)?")
                includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
                includeGroupByRegex("org\\.kotlin(\\..*)?")
                includeGroupByRegex("com\\.gradle(\\..*)?")
            }
        }
        // 兜底：镜像全部未命中时仍能拉到（含 de.mannodermaus 一类只有 Portal 有的插件）。
        gradlePluginPortal()
    }
}
