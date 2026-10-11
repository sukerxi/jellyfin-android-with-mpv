// 依赖下载加固：Maven Central 共享出口会返回 429 Too Many Requests，
// 而 Gradle 默认把 429 视为硬失败、不做重试，导致下载依赖时流水线随机失败
// 见构建 cnb-55v-1k4l8999k：获取 dev.jdtech.mpv:libmpv:1.0.0 的 .pom 时返回 429。
// 两层防护：
//   1. 优先使用腾讯云公共镜像，聚合 Maven Central / Google 等；官方源降级为兜底，
//      镜像不可访问时仍能回落，不影响本地开发；
//   2. gradle.properties 开启 HTTP 重试，见 org.gradle.internal.http.* 相关 systemProp。
allprojects {
    repositories {
        // 镜像排在官方源之前：公共依赖先走镜像，未命中再回落 mavenCentral / google。
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
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
