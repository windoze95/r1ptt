plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Tagged distributions must opt in to a stable, explicitly supplied signing identity.
// Ordinary local builds retain their existing version and local debug signing behavior.
val releaseTag = providers.gradleProperty("releaseTag").orNull
val releaseSigning = providers.gradleProperty("releaseSigning").orNull == "true"
val releaseCode = releaseTag?.let { tag ->
    val parts = Regex("v(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})").matchEntire(tag)
        ?: error("releaseTag must be vMAJOR.MINOR.PATCH")
    val (major, minor, patch) = parts.destructured
    require(major.toInt() <= 2099) { "Release major version is out of range" }
    (major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt()).also {
        require(it in 2..2_099_999_999) { "Release version code is out of range" }
    }
}
require((releaseTag != null) == releaseSigning) { "Tagged builds require releaseSigning=true; release signing requires a tag" }
fun signingEnv(name: String) = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
    ?: error("Missing release signing input: $name")

android {
    namespace = "dev.r1ptt"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.r1ptt"
        // The R1 runs Android 13 (stock) or 14 (LineageOS 21 GSI).
        minSdk = 33
        targetSdk = 34
        versionCode = releaseCode ?: 1
        versionName = releaseTag?.drop(1) ?: "0.1.0"
    }

    if (releaseSigning) signingConfigs.create("distribution") {
        storeFile = file(signingEnv("ROBOTOS_KEYSTORE_PATH"))
        storePassword = signingEnv("ROBOTOS_STORE_PASSWORD")
        keyAlias = signingEnv("ROBOTOS_KEY_ALIAS")
        keyPassword = signingEnv("ROBOTOS_KEY_PASSWORD")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Tagged distributions use the approved stable key; local builds keep their existing identity.
            signingConfig = signingConfigs.getByName(if (releaseSigning) "distribution" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (android.jar only ships stubs).
    testImplementation("org.json:json:20240303")
}
