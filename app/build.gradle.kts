plugins {
    // Con AGP 9 el soporte de Kotlin viene integrado: no hace falta el plugin kotlin-android.
    id("com.android.application")
}

android {
    namespace = "com.kiosko260.videoqr"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kiosko260.videoqr"
        // Android 10+: necesario para leer RELATIVE_PATH de MediaStore (carpeta de Quick Share).
        minSdk = 29
        // Se mantiene en 35 para no cambiar el comportamiento de la app en la tablet.
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
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
    buildFeatures {
        viewBinding = true
    }
    androidResources {
        // Las canciones ya vienen comprimidas (mp3/m4a); no recomprimirlas en el APK.
        noCompress += listOf("mp3", "m4a", "aac")
    }
}

dependencies {
    val media3 = "1.11.1"

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Edición de video: marco, logo, cámara lenta y música.
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("com.google.guava:guava:33.8.0-android")

    // Servidor web local para que el celular del cliente descargue el video (2.3.1 es la última versión).
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Generación del código QR.
    implementation("com.google.zxing:core:3.5.4")
}
