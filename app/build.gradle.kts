import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

// Apply Firebase Google Services automatically when app/google-services.json is present.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

val geminiApiKey: String = (
    localProps.getProperty("GEMINI_API_KEY")
        ?: localProps.getProperty("geminiApiKey")
        ?: System.getenv("GEMINI_API_KEY")
        ?: ""
    ).trim()

val emailWebhookUrl: String = (
    localProps.getProperty("EMAIL_WEBHOOK_URL")
        ?: localProps.getProperty("emailWebhookUrl")
        ?: System.getenv("EMAIL_WEBHOOK_URL")
        ?: ""
    ).trim()

val emailWebhookSecret: String = (
    localProps.getProperty("EMAIL_WEBHOOK_SECRET")
        ?: localProps.getProperty("emailWebhookSecret")
        ?: System.getenv("EMAIL_WEBHOOK_SECRET")
        ?: ""
    ).trim()

val verifyWebUrl: String = (
    localProps.getProperty("VERIFY_WEB_URL")
        ?: localProps.getProperty("verifyWebUrl")
        ?: System.getenv("VERIFY_WEB_URL")
        ?: ""
    ).trim()

android {
    namespace = "com.nevergrowold.holdhightheshop"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nevergrowold.holdhightheshop"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GEMINI_API_KEY", "\"${geminiApiKey.replace("\"", "\\\"")}\"")
        buildConfigField("String", "EMAIL_WEBHOOK_URL", "\"${emailWebhookUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "EMAIL_WEBHOOK_SECRET", "\"${emailWebhookSecret.replace("\"", "\\\"")}\"")
        buildConfigField("String", "VERIFY_WEB_URL", "\"${verifyWebUrl.replace("\"", "\\\"")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty(
                "robolectric.dependency.repo.url",
                "https://maven-central.storage-download.googleapis.com/maven2",
            )
            it.systemProperty("robolectric.dependency.repo.id", "gcs-maven-central")
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.material)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.asm)
    testImplementation(libs.asm.commons)
    testImplementation(libs.asm.tree)
    testImplementation(libs.asm.util)

    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}