import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * 根据构建来源决定应用内更新检查渠道：
 * - CNB 流水线（存在 CNB_COMMIT / CNB_BRANCH 环境变量）-> "cnb"
 * - GitHub Actions（GITHUB_ACTIONS=true）-> "github"
 * - 本地开发构建默认 "cnb"
 */
fun resolveUpdateChannel(): String = when {
    System.getenv("CNB_COMMIT") != null || System.getenv("CNB_BRANCH") != null -> "cnb"
    System.getenv("GITHUB_ACTIONS") == "true" -> "github"
    else -> "cnb"
}

plugins {
    alias(libs.plugins.android.app)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.detekt)
    alias(libs.plugins.android.junit5)
}

detekt {
    buildUponDefaultConfig = true
    ignoreFailures = true
    config.setFrom(files("$rootDir/detekt.yaml"))
    parallel = true
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        optIn.add("kotlin.RequiresOptIn")
    }
}

android {
    namespace = "org.jellyfin.mobile"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionName = project.getVersionName()
        versionCode = getVersionCode(versionName!!)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // 更新检查渠道由构建来源决定：
        // CNB 流水线构建 -> 走 CNB 检查；GitHub Actions 构建 -> 走 GitHub 检查；本地默认 CNB。
        buildConfigField(
            "String",
            "UPDATE_CHANNEL",
            "\"" + resolveUpdateChannel() + "\"",
        )
    }

    signingConfigs {
        val keystoreFile = getProperty("keystore.file")
        val keystorePassword = getProperty("keystore.password")
        val signingKeyAlias = getProperty("signing.key.alias")
        val signingKeyPassword = getProperty("signing.key.password")

        if (keystoreFile != null && keystorePassword != null && signingKeyAlias != null && signingKeyPassword != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    dependenciesInfo {
        includeInBundle = false
        includeInApk = false
    }

    packaging {
        jniLibs {
            // Bundled by both libmpv and the libass (ass-kt) native libraries
            pickFirsts += "**/libc++_shared.so"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")

            ndk {
                abiFilters += listOf("arm64-v8a") //
            }
        }

        getByName("debug") {
            applicationIdSuffix = ".debug"
            isDebuggable = true

            ndk {
                abiFilters += listOf("x86_64", "arm64-v8a")
            }
        }
    }

    flavorDimensions += "variant"
    productFlavors {
        register("libre") {
            dimension = "variant"
            buildConfigField("boolean", "IS_PROPRIETARY", "false")
        }
        register("proprietary") {
            dimension = "variant"
            buildConfigField("boolean", "IS_PROPRIETARY", "true")
            isDefault = true
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        lintConfig = file("$rootDir/android-lint.xml")
        abortOnError = false
        sarifReport = true
        checkDependencies = true
    }

    room {
        schemaDirectory("$projectDir/schemas")
    }
}

base.archivesName.set("jellyfin-android-v${project.getVersionName()}")

dependencies {
    val proprietaryImplementation by configurations

    // Kotlin
    implementation(libs.bundles.coroutines)
    implementation(libs.kotlin.serialization.json)

    // Core
    implementation(libs.bundles.koin)
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work.runtime)
    coreLibraryDesugaring(libs.androiddesugarlibs)

    // Lifecycle
    implementation(libs.bundles.androidx.lifecycle)

    // UI
    implementation(libs.google.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.webkit)

    // Jetpack Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    // Network
    val sdkVersion = findProperty("sdk.version")?.toString()
    implementation(libs.jellyfin.sdk) {
        // Change version if desired
        when (sdkVersion) {
            "local" -> version { strictly(JellyfinSdk.LOCAL) }
            "snapshot" -> version { strictly(JellyfinSdk.SNAPSHOT) }
            "unstable-snapshot" -> version { strictly(JellyfinSdk.SNAPSHOT_UNSTABLE) }
        }
    }
    implementation(libs.bundles.coil)

    // Media
    implementation(libs.androidx.media)
    implementation(libs.androidx.mediarouter)
    implementation(libs.bundles.androidx.media3)
    proprietaryImplementation(libs.androidx.media3.cast)
    proprietaryImplementation(libs.bundles.playservices)
    implementation(libs.libass.media)

    //#libmpv-android
    implementation(libs.libmpv.android)

    // Room
    implementation(libs.bundles.androidx.room)
    ksp(libs.androidx.room.compiler)

    // Monitoring
    implementation(libs.slf4j.timber)
    implementation(libs.timber)

    // Testing
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.bundles.androidx.test)

    // Formatting rules for detekt
    detektPlugins(libs.detekt.formatting)
}

// OkHttp 5.x 与 kotlin-logging 7.x 以 Kotlin 多平台（KMP）方式发布，同时提供
// `-jvm`（JVM JAR）与 `-android`（Android AAR）两套制品，两者类名完全相同。
//
// Jellyfin SDK 1.8.x 新增的 `jellyfin-api-okhttp` 只有 JVM 变体（无 Android 变体），
// 其变体属性为 `org.gradle.jvm.environment=standard-jvm`，因此从它传递下来的
// `com.squareup.okhttp3:okhttp` 与 `io.github.oshai:kotlin-logging` 会解析到 `-jvm` 制品；
// 而 coil / media3 / 其他依赖走 Android 变体解析到对应的 `-android` AAR。
// 两者同时进入 APK 后，AGP 的 checkDuplicateClasses 任务会因 `okhttp3.*` 与
// `io.github.oshai.kotlinlogging.*` 重复类而失败（见构建 cnb-c3u-1k4l8td40）。
//
// 处理：把 `-jvm` 依赖请求改写成其基础模块（在 Android 上会解析到 `-android` 变体），
// 并显式排除 `-jvm` 制品，确保 APK 中只保留 Android AAR。
// 注意：`jellyfin-api-okhttp-jvm-*.jar` 本身没有 Android 替代品，仍需保留，不在排除之列。
//
// 【重要】排除必须只作用于「打包进 APK 的依赖类路径」，不能对所有 configuration 生效。
// 曾经写成 `configurations.configureEach { exclude(...) }`，把 `okhttp-jvm` 从 KSP 的
// 处理类路径里也一并剔除，导致 KSP 解析不到 `jellyfin-api-okhttp` 依赖的 okhttp 类型，
// 报 `[ksp] [MissingType]: Element 'org.jellyfin.mobile.data.JellyfinDatabase' references
// a type that is not present` 并中断构建（见构建 cnb-8ic-1k4lc3nu6）。
// KSP 的处理类路径必须保留完整依赖图，因此排除只作用于依赖类路径，
// 显式放过注解处理器与构建工具类路径（ksp / kapt / detekt）。
val toolClasspathPrefixes = listOf("ksp", "kapt", "detekt")

// 请求改写是「换名字」而非「删制品」，对 KSP 类路径无害，保持全局生效。
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.squareup.okhttp3" && requested.name == "okhttp-jvm") {
            useTarget("${requested.group}:okhttp:${requested.version}")
        }
        if (requested.group == "io.github.oshai" && requested.name == "kotlin-logging-jvm") {
            useTarget("${requested.group}:kotlin-logging:${requested.version}")
        }
    }
}

// 排除 `-jvm` 制品：exclude 会沿 extendsFrom 链传递到真正可解析的编译/运行时类路径
// （如 libreReleaseCompileClasspath），从而只清理打进 APK 的产物，不影响 KSP。
configurations
    .matching { cfg -> toolClasspathPrefixes.none { cfg.name.startsWith(it) } }
    .configureEach {
        exclude(group = "com.squareup.okhttp3", module = "okhttp-jvm")
        exclude(group = "io.github.oshai", module = "kotlin-logging-jvm")
    }

tasks {
    withType<Detekt> {
        reports {
            sarif.required.set(true)
        }
    }

    // Testing
    withType<Test> {
        useJUnit()
        testLogging {
            events(
                org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
                org.gradle.api.tasks.testing.logging.TestLogEvent.STANDARD_ERROR,
                org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
            )
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
    }

    register("versionTxt") {
        doLast {
            val path = layout.buildDirectory.file("version.txt").get().asFile

            val versionString = "v${android.defaultConfig.versionName}=${android.defaultConfig.versionCode}"
            println("Writing [$versionString] to $path")
            path.writeText("$versionString\n")
        }
    }
}
