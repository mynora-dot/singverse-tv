
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val productVersion = rootProject.file("VERSION").readText().trim()
check(productVersion.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) { "VERSION must be M.m.p" }
val versionParts = productVersion.split('.').map { it.toLong() }
check(versionParts[0] <= 2100 && versionParts[1] <= 999 && versionParts[2] <= 999) { "VERSION minor/patch must be <= 999" }
val productVersionCode = versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2]
check(productVersionCode in 1..2_100_000_000L) { "VERSION produces an invalid Android versionCode" }

android {
    namespace = "com.mynora.singverse.tv"
    compileSdk = 35
    // Platform-only UI instrumentation: no additional test framework downloads.
    useLibrary("android.test.runner", false)
    useLibrary("android.test.base", false)
    defaultConfig {
        applicationId = "com.mynora.singverse.tv"
        minSdk = 23
        targetSdk = 35
        testInstrumentationRunner = "com.mynora.singverse.tv.TvUiTestRunner"
        versionCode = productVersionCode.toInt()
        versionName = productVersion
    }
    if (providers.gradleProperty("singverseSignRelease").orNull == "true") {
        val signingValues = listOf("SINGVERSE_TV_KEYSTORE", "SINGVERSE_TV_STORE_PASSWORD", "SINGVERSE_TV_KEY_ALIAS", "SINGVERSE_TV_KEY_PASSWORD").associateWith { System.getenv(it) ?: error("Missing release signing environment: $it") }
        signingConfigs.create("production") {
            storeFile = file(signingValues.getValue("SINGVERSE_TV_KEYSTORE"))
            storePassword = signingValues.getValue("SINGVERSE_TV_STORE_PASSWORD")
            keyAlias = signingValues.getValue("SINGVERSE_TV_KEY_ALIAS")
            keyPassword = signingValues.getValue("SINGVERSE_TV_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("production")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    testImplementation("junit:junit:4.13.2")
    implementation("com.google.zxing:core:3.5.3")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.tv:tv-material:1.0.0")
}

// Production packages always use the durable release key from the release environment.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.name in listOf("assembleRelease", "prepareTvRelease") }) {
        check(providers.gradleProperty("singverseSignRelease").orNull == "true") { "Configure release signing explicitly; debug keys cannot publish production updates" }
    }
}
tasks.register<Exec>("prepareTvRelease") {
    dependsOn("assembleRelease")
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/prepare-release.py", "--apk", layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile.absolutePath,
        "--platform", "tv", "--output", layout.buildDirectory.dir("tv-release").get().asFile.absolutePath)
}
