import javax.inject.Inject

plugins { id("com.android.application") version "9.5.0" }

// One-way dependency: the production project never includes this build.
abstract class StagePresentation : DefaultTask() {
    @get:InputDirectory abstract val applicationSource: DirectoryProperty
    @get:OutputDirectory abstract val kotlinOutput: DirectoryProperty
    @get:OutputDirectory abstract val resourceOutput: DirectoryProperty
    @get:Inject abstract val files: FileSystemOperations

    @TaskAction fun stage() {
        files.sync {
            from(applicationSource.dir("java/com/alwayside/android")) {
                include("AssistantPresentation.kt", "Ui.kt", "PrototypeIcons.kt",
                    "AppAppearance.kt", "AppearanceMode.kt", "ThemeScheme.kt", "TextActions.kt")
            }
            into(kotlinOutput.dir("com/alwayside/android"))
        }
        files.sync {
            from(applicationSource.dir("res")) {
                include("drawable/*.xml", "values/styles.xml", "values-night/styles.xml")
            }
            into(resourceOutput)
        }
    }
}
val stagePresentation = tasks.register<StagePresentation>("stagePresentation") {
    applicationSource.set(layout.projectDirectory.dir("../../../apps/android/app/src/main"))
    kotlinOutput.set(layout.buildDirectory.dir("generated/presentation/kotlin"))
    resourceOutput.set(layout.buildDirectory.dir("generated/presentation/res"))
}

android {
    // Keep R and Kotlin internal references in their original namespace.
    namespace = "com.alwayside.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.alwayside.docmedia"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.named("main") {
        assets.directories.add(file("../scenarios").absolutePath)
    }
    // An optional existing key avoids creating a global debug keystore on managed machines.
    providers.gradleProperty("docMediaKeystore").orNull?.let { key ->
        signingConfigs.getByName("debug") {
            storeFile = file(key)
            storePassword = "android"
            keyAlias = "alwayside-test"
            keyPassword = "android"
        }
    }
    lint { abortOnError = true }
}

androidComponents.onVariants { variant ->
    variant.sources.kotlin!!.addGeneratedSourceDirectory(stagePresentation, StagePresentation::kotlinOutput)
    variant.sources.res!!.addGeneratedSourceDirectory(stagePresentation, StagePresentation::resourceOutput)
}
dependencies { implementation("com.google.android.material:material:1.14.0") }
