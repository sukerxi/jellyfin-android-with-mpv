plugins {
    `kotlin-dsl`
}

// 与根项目一致：公共镜像只读 POM、关闭 GMM，官方源兜底。
// 详见根 build.gradle.kts 的仓库说明。
fun RepositoryHandler.publicMirror(url: String) = maven {
    setUrl(url)
    metadataSources { mavenPom(); artifact() }
}

repositories {
    publicMirror("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    publicMirror("https://maven.aliyun.com/repository/public/")
    publicMirror("https://repo.huaweicloud.com/repository/maven/")
    mavenCentral()
    google()
}
