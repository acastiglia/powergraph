plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

/**
 * The app version: the `versionName` Gradle property when given, as scripts/release.sh passes
 * from the tag it builds; otherwise the tag on the checked-out commit; otherwise a dev version.
 * A leading "v" on the tag is dropped.
 */
val appVersionName: String = (
    providers.gradleProperty("versionName").orNull
        ?: providers.exec {
            commandLine("git", "describe", "--tags", "--exact-match")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    ).removePrefix("v").ifEmpty { "0.0.0-dev" }

/**
 * [versionName] as major.minor.patch packed into MMmmpp -- 0.2.0 is 200 -- so each release
 * installs as an upgrade over the one before. 1 for anything that isn't a plain release version.
 */
fun versionCodeOf(versionName: String): Int {
    val match = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(versionName) ?: return 1
    val (major, minor, patch) = match.destructured
    return major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}

/**
 * The release keystore's path, from ~/.gradle/gradle.properties alongside its alias and
 * passwords, never from the repository. Null where it isn't set up, which only fails a build
 * that packages a release APK.
 */
val releaseKeystore: String? = providers.gradleProperty("POWERGRAPH_KEYSTORE").orNull

android {
    namespace = "com.anthonycastiglia.karoo.powergraph"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anthonycastiglia.karoo.powergraph"
        minSdk = 23
        targetSdk = 34
        versionCode = versionCodeOf(appVersionName)
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.gradleProperty("POWERGRAPH_KEYSTORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("POWERGRAPH_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("POWERGRAPH_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
}

gradle.taskGraph.whenReady {
    if (releaseKeystore == null && allTasks.any { it.path == ":app:packageRelease" }) {
        throw GradleException(
            "A release APK must be signed: set POWERGRAPH_KEYSTORE, POWERGRAPH_KEYSTORE_PASSWORD, " +
                "POWERGRAPH_KEY_ALIAS and POWERGRAPH_KEY_PASSWORD in ~/.gradle/gradle.properties.",
        )
    }
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.androidx.lifeycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)

    testImplementation(libs.junit)
}
