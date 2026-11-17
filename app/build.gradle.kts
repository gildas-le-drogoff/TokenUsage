import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
val keystoreProperties =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
android {
    namespace = "fr.gildasledrogoff.tokenusage"
    compileSdk = 37

    defaultConfig {
        applicationId = "fr.gildasledrogoff.tokenusage"
        minSdk = 29
        targetSdk = 37
        versionCode = 12
        versionName = "0.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile").removePrefix("../"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // x86_64 est réservé aux builds de test (émulateurs).
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
            buildConfigField("Boolean", "IS_DEBUG", "true")
        }
        release {
            // Never ship a debuggable build: blocks JDWP, run-as and heap dumps.
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // Un seul APK universel ARM : F-Droid attend un APK par versionCode.
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
            // Build reproductible : pas de métadonnées git dans l'APK.
            vcsInfo { include = false }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")

            buildConfigField("Boolean", "IS_DEBUG", "false")
        }

        // Staging : performances release (R8 + shrinkResources, non
        // debuggable, clé release) mais données d'exemple du debug (source
        // set partagé src/demoData) et relaxations du debug via IS_DEBUG :
        create("staging") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            buildConfigField("Boolean", "IS_DEBUG", "true")
            ndk { abiFilters += "x86_64" }
        }
    }

    // F-Droid refuse le bloc de métadonnées de dépendances (chiffré avec une
    // clé Google) qu'AGP ajoute par défaut au bloc de signature.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Données d'exemple embarquées uniquement dans les build types debug et
    sourceSets {
        getByName("debug") {
            java.srcDir("src/demoData/java")
            kotlin.srcDir("src/demoData/java")
        }
        getByName("staging") {
            java.srcDir("src/demoData/java")
            kotlin.srcDir("src/demoData/java")
        }
        getByName("testStaging") {
            java.srcDir("src/testDebug/java")
            kotlin.srcDir("src/testDebug/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("en", "fr", "zh")
    }

    packaging {
        // Build reproductible : sans cela, AGP strippe les .so quand un NDK est
        // installé et les laisse intacts sinon (cas du serveur F-Droid), ce qui
        // donne deux APK différents.
        jniLibs {
            keepDebugSymbols += "**/*.so"
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
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit.kotlinx.serialization)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.work.runtime.ktx)

    // Security: Tink primitives, keysets wrapped by an AndroidKeyStore AES-GCM key
    // (replaces the deprecated androidx.security:security-crypto).
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
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.mockito.core)

    // Captures fastlane (generate-screenshots.sh) : test instrumenté Compose.
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    // ui-test-junit4 tire un Espresso trop ancien pour les API récentes
    // (InputManager.getInstance supprimé) : version forcée.
    androidTestImplementation(libs.espresso.core)
}
