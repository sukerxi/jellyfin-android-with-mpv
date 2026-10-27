// 依赖下载加固：公共源共享出口下会返回 429 Too Many Requests。
// Gradle 会把 429 归类为「瞬时网络错误」并自动重试，但默认上限只有 3 次尝试
// （org.gradle.internal.repository.max.tentatives=3，退避 1s 起翻倍），
// 限流窗口稍长就会重试耗尽、整个构建失败（见构建 cnb-55v-1k4l8999k：
// 获取 dev.jdtech.mpv:libmpv:1.0.0 的 .pom 时返回 429）。
// gradle.properties 已把尝试次数提到 8 次；本文件负责让依赖尽量落在镜像上。
//
// 多层防护：
//   1. 公共镜像（阿里云 / 腾讯云 / 华为云）在前，官方源只给遗漏 group 兜底，
//      见下方 allprojects 里的仓库顺序说明。
//   2. 【关键】镜像仓库显式关闭 Gradle Module Metadata（GMM），只读 POM + 制品。
//      原因：一旦某个仓库存放了模块的 .module（GMM），Gradle 会把该组件「绑定」到
//      这个仓库——后续制品也只在同一仓库查找，不会再回落到后面的仓库。
//      腾讯云镜像正是这种情况：它有 jellyfin-core-android 的 .pom/.module，
//      却没有对应的 .aar（404），于是 Gradle 只查腾讯云、拿到 404 就直接报
//      `Could not find jellyfin-core-release.aar ... Searched in: mirrors.cloud.tencent.com/...`，
//      后面的阿里云/华为云/Maven Central 明明有这个 AAR 却永远不会被访问。
//      见构建 cnb-7pd-1k4lev2r1（本注释对应的失败）。
//      关闭 GMM 后按 POM 解析，Gradle 才会在每个仓库逐个尝试制品，镜像缺口能真正互补。
//   3. 官方 mavenCentral 只兜底镜像同步空窗期里可能缺失的 group，配合
//      gradle.properties 里调大的重试次数（max.tentatives）与 HTTP 超时。
//   4. 本地开发不受影响：官方源仍在列表内，镜像不可达时自动回落。
//
// 关闭 GMM 的影响：GMM 主要用于 KMP 的变体重定向（如 jellyfin-core -> jellyfin-core-android）。
// POM 路径同样能完成该重定向；必须读 GMM 的 KMP 库（coil3 / jellyfin-sdk）单独开
// 限定 group 的 GMM 仓库，见 mirrorWithGradleMetadata。
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
        // ── 仓库顺序：镜像在前，官方源只给遗漏的 group 兜底 ──
        //
        // 为什么官方源不能和公共镜像并列（2026-10-27 的发版构建 cnb-7p7-1k5ujrqnf 失败原因）：
        // Gradle 对每个模块按声明顺序逐个仓库查找元数据，而本文件的镜像都关掉了 GMM
        // （见上方 publicMirror 说明），于是每个模块都要先在各镜像上各发一次 POM 请求，
        // 未命中才轮到下一个。仓库列表里原本并列着 mavenCentral() 与 google()，
        // 它们永远排在 4 个公共镜像之后：镜像一旦缺某个 artifact / 同步滞后 / 抖动，
        // 请求就打到 repo.maven.apache.org，共享出口下直接 429 Too Many Requests；
        // 重试 8 次仍 429 后 Gradle 把该仓库标记为「disabled due to earlier error」，
        // 此后所有模块的兜底查找全部失效，报
        //   Repository ... is disabled due to earlier error
        //   Could not resolve org.jetbrains.kotlin:kotlin-util-klib-metadata:2.4.0
        // 实际只需要一次 429 就能拖垮整条解析链。
        //
        // 改法：镜像（阿里云 / 腾讯云 / 华为云，同内容不同源）负责绝大多数请求，
        // 官方 mavenCentral / google 只保留镜像同步空窗期里可能缺失的 group
        // （JetBrains、Kotlin、Gradle 插件生态与 Jellyfin 自身）。
        // 新增依赖若只存在于 Maven Central 且不在下方清单里，按其 group 加一行即可。

        // KMP 布局的库（coil3 3.6+ 根制品是几 KB 的空壳 jar，真实 Android 实现在
        // coil-compose-android 等变体中）必须读 GMM 才能完成变体重定向。为避免与
        // 下面「全局关 GMM」的镜像兜底策略冲突，这里为 coil3 单独声明限定 group 的仓库。
        mirrorWithGradleMetadata("https://maven.aliyun.com/repository/public/", "io.coil-kt.coil3")
        // jellyfin-sdk 同样是 KMP：GMM 被关掉的镜像会让它只在本地开发（直连 Maven Central）
        // 编译通过、流水线报 `Unresolved reference 'client'`（见构建 cnb-e7k-1k4t44rap）。
        mirrorWithGradleMetadata(
            "https://maven.aliyun.com/repository/public/",
            JellyfinSdk.GROUP,
            JellyfinMedia3.GROUP,
        )
        mirrorWithGradleMetadata(
            "https://mirrors.cloud.tencent.com/nexus/repository/maven-public/",
            JellyfinSdk.GROUP,
            JellyfinMedia3.GROUP,
        )
        // 公共镜像：聚合 Maven Central / Google 等。多个镜像并列用于互补缺口
        // （各镜像同步进度与缓存状态不同，单点存在 artifact 缺失风险）。
        publicMirror("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        publicMirror("https://maven.aliyun.com/repository/public/")
        publicMirror("https://repo.huaweicloud.com/repository/maven/")
        // 插件相关 group（org.gradle.* 等）在聚合镜像里经常缺失，阿里云单独提供了
        // Gradle Plugin Portal 镜像；插件自身的传递依赖同样会走这里。
        publicMirror("https://maven.aliyun.com/repository/gradle-plugin/")
        // Google 制品（AGP / androidx）镜像源；官方 google() 亦稳定，两者互为冗余。
        publicMirror("https://maven.aliyun.com/repository/google/")
        // 镜像未覆盖到的 group 才回落官方源，避免把共享出口的 Maven Central 拖进
        // 每个模块的解析链（429 → 仓库被 disable → 全链失败）。
        mavenCentral {
            content {
                includeGroupByRegex("org\\.jetbrains(\\..*)?")
                includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
                includeGroupByRegex("org\\.kotlin(\\..*)?")
                includeGroupByRegex("org\\.jellyfin(\\..*)?")
            }
        }
        google {
            content {
                includeGroupByRegex("androidx(\\..*)?")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google(\\..*)?")
            }
        }
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
