import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Пароль и алиас берём из local.properties, как это делает NekoBox:
// пароль не светится в коде и в build.gradle.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "ru.autoclicker"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.autoclicker"
        // 26, а не 24: приложение использует startForegroundService,
        // Notification.Builder(context, channelId) и VibrationEffect —
        // всё это появилось в Android 8.0. На 24-25 был бы краш.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.keystore")
            storePassword = localProps.getProperty("KEYSTORE_PASS")
            keyAlias = localProps.getProperty("ALIAS_NAME")
            keyPassword = localProps.getProperty("ALIAS_PASS")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        warningsAsErrors = false
        checkReleaseBuilds = false
        abortOnError = false
    }

    // один универсальный APK
    splits {
        abi {
            isEnable = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
}
