include(":app")

pluginManagement {
    repositories {
        gradlePluginPortal()
        // 与根项目一致：公共镜像在前（聚合 Maven Central / Google，规避共享出口 429），
        // 多镜像并列互补缺口，官方源兜底。详见根 build.gradle.kts 的仓库说明。
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://repo.huaweicloud.com/repository/maven/")
        maven("https://maven.aliyun.com/repository/google/")
        mavenCentral()
        google()
    }
}
