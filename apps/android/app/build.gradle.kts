plugins { id("com.android.application") }

android {
    namespace = "com.alwayside.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.alwayside.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.3.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("../../.local/android-build/alwayside-test.jks")
            storePassword = "android"
            keyAlias = "alwayside-test"
            keyPassword = "android"
        }
    }
    lint { abortOnError = true }
}
dependencies { testImplementation("junit:junit:4.13.2") }
