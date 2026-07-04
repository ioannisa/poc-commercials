import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Base KMP library: ALL of this app's targets (android, both iOS arm64
 * flavors, desktop JVM, browser js AND wasmJs - the desktop and web apps are
 * first-class here, unlike the skill's android+ios default). Android
 * namespace derives from the module path so feature submodules never
 * collide.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    android {
        namespace = "eu.anifantakis.commercials." +
            project.path.removePrefix(":").replace(Regex("[:\\-]"), "_")
        compileSdk = catalog.findVersion("android-compileSdk").get().requiredVersion.toInt()
        minSdk = catalog.findVersion("android-minSdk").get().requiredVersion.toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}
