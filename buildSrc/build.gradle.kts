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
    // coil3 采用 KMP 布局（根制品是空壳 jar，真实 Android 实现在变体里），必须读 GMM
    // 才能完成变体重定向，所以按 group 限定单独开启；其余依赖继续走关 GMM 的镜像+回落。
    maven("https://maven.aliyun.com/repository/public/") {
        content { includeGroup("io.coil-kt.coil3") }
        metadataSources { mavenPom(); gradleMetadata(); artifact() }
    }
    mavenCentral {
        content { includeGroup("io.coil-kt.coil3") }
    }
    mavenCentral()
    google()
}
