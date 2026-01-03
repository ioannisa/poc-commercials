import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
}

composeCompiler {
    stabilityConfigurationFile.set(project.file("compose_compiler_config.conf"))
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    
    jvm()
    
    js {
        browser()
        binaries.executable()
    }
    
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.ui.text.google.fonts)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            // Extra Icons
            implementation(libs.material.icons.extended)

            // DateTime
            implementation(libs.kotlinx.datetime)

            // Immutable Collections
            implementation(libs.kotlinx.collections.immutable)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)

            // JasperReports 7.x for PDF report generation (modular)
            implementation(libs.jasperreports)
            implementation(libs.jasperreports.pdf)
            implementation(libs.jasperreports.fonts)
            implementation(libs.jasperreports.jdt)
        }

        // Browser-based platforms: Ktor client for server-side report generation
        val webMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.js)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        jsMain.get().dependsOn(webMain)
        wasmJsMain.get().dependsOn(webMain)
    }
}

android {
    namespace = "eu.anifantakis.poc.ctv"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "eu.anifantakis.poc.ctv"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "eu.anifantakis.poc.ctv.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "eu.anifantakis.poc.ctv"
            packageVersion = "1.0.0"
        }
    }
}

// Ensure jvmMain resources are on the classpath for Compose Desktop run tasks
afterEvaluate {
    tasks.withType<JavaExec>().configureEach {
        if (name == "run" || name.startsWith("hotRun")) {
            classpath += files("src/jvmMain/resources")
        }
    }
}

