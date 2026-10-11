// 依赖下载加固：Maven Central 共享出口会返回 429 Too Many Requests，
// 而 Gradle 默认把 429 视为硬失败、不做重试，导致下载依赖时流水线随机失败
// 见构建 cnb-55v-1k4l8999k：获取 dev.jdtech.mpv:libmpv:1.0.0 的 .pom 时返回 429。
//
// 多层防护：
//   1. 多个公共镜像并列，官方源兜底。
//   2. 【关键】镜像仓库显式关闭 Gradle Module Metadata（GMM），只读 POM + 制品。
//      原因：一旦某个仓库存放了模块的 .module（GMM），Gradle 会把该组件「绑定」到
//      这个仓库——后续制品也只在同一仓库查找，不会再回落到后面的仓库。
//      腾讯云镜像正是这种情况：它有 jellyfin-core-android 的 .pom/.module，
//      却没有对应的 .aar（404），于是 Gradle 只查腾讯云、拿到 404 就直接报
//      `Could not find jellyfin-core-release.aar ... Searched in: mirrors.cloud.tencent.com/...`，
//      后面的阿里云/华为云/Maven Central 明明有这个 AAR 却永远不会被访问。
//      见构建 cnb-7pd-1k4lev2r1（本注释对应的失败）。
//      关闭 GMM 后按 POM 解析，Gradle 才会在每个仓库逐个尝试制品，镜像缺口能真正互补。
//   3. 官方 mavenCentral 作为最终兜底，配合 gradle.properties 的 HTTP 重试
//      （429 属瞬时错误，重试通常可恢复）。
//   4. 本地开发不受影响：官方源仍在列表内，镜像不可达时自动回落。
//
// 关闭 GMM 的影响：GMM 主要用于 KMP 的变体重定向（如 jellyfin-core -> jellyfin-core-android）。
// 已验证 POM 路径同样能完成该重定向，且 androidx / media3 / jellyfin 各依赖均能正常解析，
// 因此对变体选择无影响。
fun RepositoryHandler.publicMirror(url: String) = maven {
    setUrl(url)
    // 只读 POM 与制品，不读 .module，保证跨仓库回落生效。
    metadataSources { mavenPom(); artifact() }
}

allprojects {
    repositories {
        // 公共镜像：聚合 Maven Central / Google 等。多个镜像并列用于互补缺口
        // （各镜像同步进度与缓存状态不同，单点存在 artifact 缺失风险）。
        publicMirror("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        publicMirror("https://maven.aliyun.com/repository/public/")
        publicMirror("https://repo.huaweicloud.com/repository/maven/")
        // Google 制品（AGP / androidx）镜像源；官方 google() 亦稳定，两者互为冗余。
        publicMirror("https://maven.aliyun.com/repository/google/")
        // 官方源兜底：镜像未命中时回落。
        mavenCentral()
        google()
        mavenLocal {
            content {
                includeVersionByRegex(JellyfinSdk.GROUP, ".*", JellyfinSdk.LOCAL)
            }
        }
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/") {
            content {
                includeVersionByRegex(JellyfinSdk.GROUP, ".*", JellyfinSdk.SNAPSHOT)
                includeVersionByRegex(JellyfinSdk.GROUP, ".*", JellyfinSdk.SNAPSHOT_UNSTABLE)
                includeVersionByRegex(JellyfinMedia3.GROUP, ".*", JellyfinMedia3.SNAPSHOT)
            }
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
