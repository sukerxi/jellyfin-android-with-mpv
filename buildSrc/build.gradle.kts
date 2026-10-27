plugins {
    `kotlin-dsl`
}

// 与根项目一致：公共镜像在前，官方源只给遗漏 group 兜底。
// 详见根 build.gradle.kts 的仓库说明。
fun RepositoryHandler.publicMirror(url: String) = maven {
    setUrl(url)
    metadataSources { mavenPom(); artifact() }
}

repositories {
    // buildSrc 要解析 kotlin-dsl 插件自身：org.gradle.kotlin:gradle-kotlin-dsl-plugins
    // → org.jetbrains.kotlin:kotlin-gradle-plugin 及 compiler-runner / build-statistics
    // 一长串传递依赖。此前 mavenCentral() 与镜像并列，镜像未命中就打到
    // repo.maven.apache.org，共享出口下 429 → 仓库被 disable → kotlin-dsl 解析全链失败
    // （见构建 cnb-7p7-1k5ujrqnf）。现改成镜像优先，官方源只兜底 JetBrains/Kotlin group。
    publicMirror("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    publicMirror("https://maven.aliyun.com/repository/public/")
    // org.gradle.* 制品（gradle-kotlin-dsl-plugins）只在 Plugin Portal 镜像上有。
    publicMirror("https://maven.aliyun.com/repository/gradle-plugin/")
    publicMirror("https://repo.huaweicloud.com/repository/maven/")
    // coil3 采用 KMP 布局（根制品是空壳 jar，真实 Android 实现在变体里），必须读 GMM
    // 才能完成变体重定向，所以按 group 限定单独开启；其余依赖继续走关 GMM 的镜像+回落。
    maven("https://maven.aliyun.com/repository/public/") {
        content { includeGroup("io.coil-kt.coil3") }
        metadataSources { mavenPom(); gradleMetadata(); artifact() }
    }
    mavenCentral {
        content {
            includeGroup("io.coil-kt.coil3")
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
