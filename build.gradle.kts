// 依赖下载加固：Maven Central 共享出口会返回 429 Too Many Requests。
// Gradle 会把 429 归类为「瞬时网络错误」并自动重试，但默认上限只有 3 次尝试
// （org.gradle.internal.repository.max.tentatives=3，退避 1s 起翻倍），
// 限流窗口稍长就会重试耗尽、整个构建失败（见构建 cnb-55v-1k4l8999k：
// 获取 dev.jdtech.mpv:libmpv:1.0.0 的 .pom 时返回 429）。
// 顶层策略已把尝试次数提到 8 次，见 gradle.properties；本文件负责让
// 依赖尽量落在镜像上、减少回落到共享出口的请求量。
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
//   3. 官方 mavenCentral 作为最终兜底，配合 gradle.properties 里调大的
//      重试次数（max.tentatives）与 HTTP 超时。
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

/**
 * 镜像仓库，但保留 Gradle Module Metadata（GMM）。
 *
 * KMP 库（jellyfin-sdk / coil3 / okhttp5 / kotlin-logging 等）的根制品只有一个几 KB 的
 * 空壳：真实 Android 实现在 `-android` 变体里，且依赖列表在 .module 中才完整（POM 只有
 * common 依赖）。根制品缺失或变体属性判定为「无匹配变体」时会直接失败，不会回落到后面
 * 的仓库。这些镜像（阿里云 public / 腾讯云 maven-public）的 .module 与 `-android` 制品
 * 都完整，所以按 group 限定交给它们，其余依赖继续走关 GMM 的 publicMirror。
 */
fun RepositoryHandler.mirrorWithGradleMetadata(url: String, vararg groups: String) = maven {
    setUrl(url)
    content { groups.forEach { includeGroup(it) } }
    metadataSources { mavenPom(); gradleMetadata(); artifact() }
}

allprojects {
    repositories {
        // KMP 布局的库（coil3 3.6+ 根制品是几 KB 的空壳 jar，真实 Android 实现在
        // coil-compose-android 等变体中）必须读 Gradle Module Metadata 才能完成
        // 变体重定向。为避免与下面「全局关 GMM」的镜像兜底策略冲突，这里为 coil3
        // 单独声明限定 group 的仓库并开启 GMM（阿里云 public 镜像制品完整）。
        mirrorWithGradleMetadata("https://maven.aliyun.com/repository/public/", "io.coil-kt.coil3")
        mavenCentral {
            content { includeGroup("io.coil-kt.coil3") }
        }
        // jellyfin-sdk 是 KMP（根制品空壳 + `-android` 变体）：GMM 被关掉的镜像里，
        // 它会在本地开发（直连 Maven Central）能编译、流水线却报
        // `Unresolved reference 'client'`（见构建 cnb-e7k-1k4t44rap）。
        // 阿里云 public / 腾讯云 maven-public 的 .module 与 `-android` 制品都完整，
        // 因此限定 group、保留 GMM 交给它们。
        mirrorWithGradleMetadata(
            "https://maven.aliyun.com/repository/public/",
            JellyfinSdk.GROUP,
        )
        mirrorWithGradleMetadata(
            "https://mirrors.cloud.tencent.com/nexus/repository/maven-public/",
            JellyfinSdk.GROUP,
        )
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
