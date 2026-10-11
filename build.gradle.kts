// 依赖下载加固：Maven Central 共享出口会返回 429 Too Many Requests，
// 而 Gradle 默认把 429 视为硬失败、不做重试，导致下载依赖时流水线随机失败
// 见构建 cnb-55v-1k4l8999k：获取 dev.jdtech.mpv:libmpv:1.0.0 的 .pom 时返回 429。
//
// 多层防护：
//   1. 多个公共镜像并列：Gradle 按声明顺序逐仓库解析，前一个仓库返回 404 会继续
//      往后找，只有所有仓库都拿不到才失败。因此镜像之间的缺口可以互相补齐。
//      仅配单个镜像是危险的——见构建 cnb-f08-1k4lb60ci：mirrors.tencent.com 对
//      jellyfin-model 的 .jar 间歇性返回 404（同一 URL 连打 8 次有 3 次 404），
//      而它是当时唯一配置的镜像源，404 继续落到 mavenCentral 又撞上 429，
//      两个来源同时不可用，构建即失败。
//   2. 官方 mavenCentral 作为最终兜底，配合 gradle.properties 的 HTTP 重试
//      （429 属瞬时错误，重试通常可恢复）。
//   3. 本地开发不受影响：官方源仍在列表内，镜像不可达时自动回落。
allprojects {
    repositories {
        // 公共镜像：聚合 Maven Central / Google 等。多个镜像并列用于互补缺口
        // （各镜像同步进度与缓存状态不同，单点存在 artifact 缺失风险）。
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://repo.huaweicloud.com/repository/maven/")
        // Google 制品（AGP / androidx）镜像源；官方 google() 亦稳定，两者互为冗余。
        maven("https://maven.aliyun.com/repository/google/")
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
