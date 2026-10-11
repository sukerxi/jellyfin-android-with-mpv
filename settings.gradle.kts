include(":app")

pluginManagement {
    repositories {
        gradlePluginPortal()
        // 与根项目一致：优先走腾讯云公共镜像，规避 Maven Central 共享出口的 429。
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
        mavenCentral()
        google()
    }
}
