import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
val yoBackendUrl =
    providers.gradleProperty("yoBackendUrl").orNull
        ?: localProperties.getProperty("yoBackendUrl")
        ?: "http://10.0.2.2:8790"
// NOTE: there is deliberately no yoBackendKey any more. A single shared key baked into every
// APK was gap G3 - extracting it granted full API access as any user. Callers now authenticate
// with a per-account bearer token obtained at sign-in, which never ships in the binary.
// Where "invite a contact" points people. Overridable per build so a fork or a local test can send
// invites somewhere else; the default is the install page served by the backend.
val yoInviteUrl =
    providers.gradleProperty("yoInviteUrl").orNull
        ?: localProperties.getProperty("yoInviteUrl")
        ?: "https://yo.the-shop.io/install"
// The OAuth *web* client id (type 3) of the Google Cloud project, which is what an Android app
// must send as the server client id so the backend can pin the token's audience to it. Blank by
// default and blank is a supported state: the app hides its Google band rather than showing a
// button that cannot work. Unlike the old yoBackendKey this is not a secret - a client id is
// public by design, and possessing it grants nothing without the account it names.
val yoGoogleClientId =
    providers.gradleProperty("yoGoogleClientId").orNull
        ?: localProperties.getProperty("yoGoogleClientId")
        ?: ""
val escapedYoBackendUrl = yoBackendUrl.replace("\\", "\\\\").replace("\"", "\\\"")
val escapedYoInviteUrl = yoInviteUrl.replace("\\", "\\\\").replace("\"", "\\\"")
val escapedYoGoogleClientId = yoGoogleClientId.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.example.yo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.yo"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "YO_BACKEND_URL", "\"$escapedYoBackendUrl\"")
        buildConfigField("String", "YO_INVITE_URL", "\"$escapedYoInviteUrl\"")
        buildConfigField("String", "YO_GOOGLE_CLIENT_ID", "\"$escapedYoGoogleClientId\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kapt {
    correctErrorTypes = true
    javacOptions {
        option("--add-opens", "jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED")
        option("--add-opens", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED")
        option("--add-opens", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED")
        option("--add-opens", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED")
        option("--add-opens", "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED")
        option("--add-opens", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED")
        option("--add-opens", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED")
        option("--add-opens", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED")
        option("--add-opens", "jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED")
        option("--add-opens", "jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED")
        option("--add-opens", "jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    // Credential Manager rather than the deprecated GoogleSignInClient. It is also what puts the
    // device account picker on screen, which is the whole point of the feature.
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.0")

    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-compiler:2.48")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("com.google.android.gms:play-services-location:21.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val servicesJson = file("google-services.json")
if (servicesJson.exists() && servicesJson.readText().isNotBlank()) {
    apply(plugin = "com.google.gms.google-services")
}
