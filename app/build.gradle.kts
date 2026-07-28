import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.kapt")
}

val localProperties =
    Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use(::load)
        }
    }

// Configuration has no defaults on purpose. Every value below used to fall back to something
// plausible - the emulator loopback for the backend, an empty string for the client id - and a
// plausible default is precisely what lets a misconfigured build reach a user looking healthy.
// A release pointing at 10.0.2.2 installs and launches fine; it just never reaches a server.
// Missing values are therefore not substituted here. They are reported by `verifyYoConfiguration`
// below, which fails only the tasks that actually produce an installable artifact, so unit tests
// still run in a bare checkout with no local.properties.
fun optionalSetting(
    gradleProperty: String,
    environmentVariable: String,
): String? =
    (
        providers.gradleProperty(gradleProperty).orNull
            ?: localProperties.getProperty(gradleProperty)
            ?: System.getenv(environmentVariable)
    )?.takeIf { it.isNotBlank() }

val yoBackendUrl = optionalSetting("yoBackendUrl", "YO_BACKEND_URL")

// Where "invite a contact" points people: the backend's public install page.
val yoInviteUrl = optionalSetting("yoInviteUrl", "YO_INVITE_URL")

// The OAuth *web* client id (type 3) of the Google Cloud project. An Android app must send this
// as the server client id so the backend can pin the token's audience to it. Not a secret - a
// client id is public by design and grants nothing without the account it names.
val yoGoogleClientId = optionalSetting("yoGoogleClientId", "YO_GOOGLE_CLIENT_ID")

// Google Play requires the privacy policy to be reachable from inside the app as well as from
// the store listing. Served by the backend at /privacy so it cannot drift from the code.
val yoPrivacyUrl = optionalSetting("yoPrivacyUrl", "YO_PRIVACY_URL")

fun escapeForBuildConfig(value: String?): String =
    (value ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

val keystoreFile = optionalSetting("yoKeystoreFile", "YO_KEYSTORE_FILE")
val keystorePassword = optionalSetting("yoKeystorePassword", "YO_KEYSTORE_PASSWORD")
val uploadKeyAlias = optionalSetting("yoKeyAlias", "YO_KEY_ALIAS")
val uploadKeyPassword = optionalSetting("yoKeyPassword", "YO_KEY_PASSWORD")
val hasSigningMaterial =
    keystoreFile != null &&
        keystorePassword != null &&
        uploadKeyAlias != null &&
        uploadKeyPassword != null

android {
    namespace = "hr.theshop.yo"
    compileSdk = 36

    defaultConfig {
        applicationId = "hr.theshop.yo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "YO_BACKEND_URL", "\"${escapeForBuildConfig(yoBackendUrl)}\"")
        buildConfigField("String", "YO_INVITE_URL", "\"${escapeForBuildConfig(yoInviteUrl)}\"")
        buildConfigField(
            "String",
            "YO_GOOGLE_CLIENT_ID",
            "\"${escapeForBuildConfig(yoGoogleClientId)}\"",
        )
        buildConfigField("String", "YO_PRIVACY_URL", "\"${escapeForBuildConfig(yoPrivacyUrl)}\"")
    }

    signingConfigs {
        // Declared only when the material is actually present. Declaring an empty config instead
        // would let `assembleRelease` produce an unsigned APK that still looks like a release.
        if (hasSigningMaterial) {
            create("release") {
                storeFile = file(keystoreFile!!)
                storePassword = keystorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigningMaterial) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Robolectric refuses to build a sandbox for Android SDK 36 on anything below Java 21
// ("Android SDK 36 requires Java 21"), so the unit tests run on a 21 toolchain. The app itself
// still compiles to 17 bytecode - this is about the JVM that hosts the tests, not the app's
// target. Gradle finds the JDK itself, which keeps the machine-specific path out of the repo;
// see README.md if auto-detection does not find one.
tasks.withType<Test>().configureEach {
    javaLauncher =
        javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(21)
        }
}

// Fails the build when an artifact would be produced from incomplete configuration. Scoped to the
// task graph rather than to configuration time so that `testDebugUnitTest` still runs in a fresh
// clone with no local.properties, which is what CI does.
val verifyYoConfiguration =
    tasks.register("verifyYoConfiguration") {
        doLast {
            val missing = mutableListOf<String>()
            if (yoBackendUrl == null) missing += "yoBackendUrl (or YO_BACKEND_URL)"
            if (yoInviteUrl == null) missing += "yoInviteUrl (or YO_INVITE_URL)"
            if (yoGoogleClientId == null) missing += "yoGoogleClientId (or YO_GOOGLE_CLIENT_ID)"
            if (yoPrivacyUrl == null) missing += "yoPrivacyUrl (or YO_PRIVACY_URL)"
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Cannot build an app artifact without: ${missing.joinToString(", ")}. " +
                        "Set them in local.properties, as -P gradle properties, or in the " +
                        "environment. See README.md, 'Configuring a build'.",
                )
            }
        }
    }

val verifyYoReleaseConfiguration =
    tasks.register("verifyYoReleaseConfiguration") {
        dependsOn(verifyYoConfiguration)
        doLast {
            if (!hasSigningMaterial) {
                throw GradleException(
                    "A release build must be signed with the upload key. Set yoKeystoreFile, " +
                        "yoKeystorePassword, yoKeyAlias and yoKeyPassword (or the YO_KEYSTORE_* " +
                        "/ YO_KEY_* environment variables). See docs/RELEASE.md.",
                )
            }
            // Cleartext is blocked outside debug builds, so an http:// release URL yields an app
            // that installs and then fails every request at runtime. Catch it at build time.
            if (yoBackendUrl?.startsWith("https://") != true) {
                throw GradleException(
                    "A release build needs an https:// backend URL; got '$yoBackendUrl'.",
                )
            }
            if (!file("google-services.json").exists()) {
                throw GradleException(
                    "app/google-services.json is missing, so this release would ship without " +
                        "push - the entire point of the app. See docs/RELEASE.md.",
                )
            }
        }
    }

androidComponents {
    onVariants { variant ->
        val capitalized = variant.name.replaceFirstChar(Char::uppercase)
        val gate =
            if (variant.buildType == "release") {
                verifyYoReleaseConfiguration
            } else {
                verifyYoConfiguration
            }
        listOf("assemble$capitalized", "bundle$capitalized").forEach { name ->
            tasks.matching { it.name == name }.configureEach { dependsOn(gate) }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

kapt {
    correctErrorTypes = true
    arguments {
        // Export the Room schema so a future version bump can be given a real, testable
        // Migration. The database is deliberately built without fallbackToDestructiveMigration:
        // that would silently erase a user's Yo history on the next schema change. Without an
        // exported schema there is nothing to migrate *from*, so this is what keeps writing one
        // possible. Committed under app/schemas.
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.activity:activity-compose:1.12.4")

    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    // Credential Manager rather than the deprecated GoogleSignInClient. It is also what puts the
    // device account picker on screen, which is the whole point of the feature.
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")

    implementation("com.google.dagger:hilt-android:2.58")
    kapt("com.google.dagger:hilt-compiler:2.58")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")

    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    // The separate -ktx artifact was folded into the main one and removed in BOM 34.x. Nothing
    // here used the KTX extensions anyway - the code calls FirebaseMessaging.getInstance().
    implementation("com.google.firebase:firebase-messaging")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val servicesJson = file("google-services.json")
if (servicesJson.exists() && servicesJson.readText().isNotBlank()) {
    apply(plugin = "com.google.gms.google-services")
}
