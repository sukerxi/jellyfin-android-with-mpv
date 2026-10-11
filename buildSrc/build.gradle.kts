plugins {
    `kotlin-dsl`
}

// 与根项目一致：公共镜像在前（多镜像并列互补缺口），官方源兜底。
// 详见根 build.gradle.kts 的仓库说明。
repositories {
    maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    maven("https://maven.aliyun.com/repository/public/")
    maven("https://repo.huaweicloud.com/repository/maven/")
    mavenCentral()
    google()
}
