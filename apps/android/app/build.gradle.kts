plugins { id("com.android.application") }

val releaseVersionName = providers.gradleProperty("releaseVersionName")
val releaseVersionCode = providers.gradleProperty("releaseVersionCode")
val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH")

android {
    namespace = "com.alwayside.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.alwayside.android"
        minSdk = 29
        targetSdk = 36
        versionCode = releaseVersionCode.orNull?.toInt() ?: 13
        versionName = releaseVersionName.orNull ?: "0.6.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        if (releaseKeystorePath.isPresent) {
            create("release") {
                storeFile = file(releaseKeystorePath.get())
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
        getByName("debug") {
            storeFile = rootProject.file("../../.local/android-build/alwayside-test.jks")
            storePassword = "android"
            keyAlias = "alwayside-test"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseKeystorePath.isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    lint { abortOnError = true }
}
dependencies {
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
}
