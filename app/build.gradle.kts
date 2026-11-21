import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
}
val keystoreProperties =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

fun gitOutput(vararg args: String): String =
    runCatching {
        providers.exec {
            commandLine("git", *args)
            workingDir = rootProject.projectDir
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrDefault("").ifEmpty { "unknown" }

val gitHash = gitOutput("rev-parse", "--short", "HEAD")
val gitDate = gitOutput("log", "-1", "--format=%cs")

android {
    namespace = "fr.gildasledrogoff.tokenusage"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "fr.gildasledrogoff.tokenusage"
        minSdk = 29
        targetSdk = 37
        versionCode = 13
        versionName = "0.2.0"
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        buildConfigField("String", "GIT_DATE", "\"$gitDate\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(
                    keystoreProperties.getProperty("storeFile").removePrefix("../")
                )
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            enableAndroidTestCoverage = true
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
            buildConfigField("Boolean", "IS_DEBUG", "true")
        }
        release {

            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            ndk { abiFilters += listOf("arm64-v8a") }
            vcsInfo { include = false }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")

            buildConfigField("Boolean", "IS_DEBUG", "false")
        }


    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }


    sourceSets {
        getByName("debug") {
            java.directories.add("src/demoData/java")
            kotlin.directories.add("src/demoData/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    androidResources {
        localeFilters += listOf("en", "fr", "zh")
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
            // Compress native libs in the APK (smaller download; extracted at install).
            useLegacyPackaging = true
        }
        dex {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += setOf("DebugProbesKt.bin", "META-INF/*.version")
        }
    }

    @Suppress("UnstableApiUsage")
    bundle {
        storeArchive {
            enable = true
        }
    }
}
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}



kover {
    reports {
        filters {
            excludes {
                classes(
                    "*_Factory",
                    "*_Factory\$*",
                    "*_HiltModules*",
                    "*_HiltComponents*",
                    "*_MembersInjector",
                    "Hilt_*",
                    "*.Hilt_*",
                    "dagger.hilt.internal.*",
                    "hilt_aggregated_deps.*",
                    "*_GeneratedInjector",
                    "*_Impl",
                    "*_Impl\$*",
                    "*.BuildConfig",
                    "*.R",
                    "*.R\$*",
                    "*ComposableSingletons*",
                    "*.ui.theme.ThemeKt",
                    "*.ui.theme.DimensKt",
                )
                annotatedBy(
                    "androidx.compose.ui.tooling.preview.Preview",
                    "androidx.compose.ui.tooling.preview.PreviewLightDark",
                )
            }
        }
    }
}
dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.startup.runtime)
    implementation(libs.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    debugImplementation(libs.okhttp.logging)
    implementation(libs.retrofit.kotlinx.serialization)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.work.runtime.ktx)



    implementation(libs.tink.android)
    implementation(libs.androidx.biometric)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    implementation(libs.accompanist.permissions)

    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    implementation(libs.sqlcipher.android)
    implementation(libs.sqlite.ktx)
    testImplementation(libs.okhttp.logging)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.mockito.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(composeBom)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
}
