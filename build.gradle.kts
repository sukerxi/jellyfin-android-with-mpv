allprojects {
    repositories {
        // 阿里云镜像（优先）
//        maven ( "https://maven.aliyun.com/repository/public" )
//        maven ( "https://maven.aliyun.com/repository/central" )
//        maven ( "https://maven.aliyun.com/repository/google" )
//        maven ( "https://maven.aliyun.com/repository/gradle-plugin" )
        maven ( "https://mirrors.cloud.tencent.com/nexus/repository/maven-public" )

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
