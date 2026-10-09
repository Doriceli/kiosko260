plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kiosko260.videoqr"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kiosko260.videoqr"
        // Android 10+: necesario para leer RELATIVE_PATH de MediaStore (carpeta de Quick Share).
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    androidResources {
        // Las canciones ya vienen comprimidas (mp3/m4a); no recomprimirlas en el APK.
        noCompress += listOf("mp3", "m4a", "aac")
    }
}

dependencies {
    val media3 = "1.5.1"

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Edición de video: marco, logo, cámara lenta y música.
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("com.google.guava:guava:33.3.1-android")

    // Servidor web local para que el celular del cliente descargue el video.
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Generación del código QR.
    implementation("com.google.zxing:core:3.5.3")
}
