plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jonathanfdr.dharma"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jonathanfdr.dharma"
        minSdk = 26
        targetSdk = 34
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"
    }

    signingConfigs {
        // Chave pessoal só para instalar o APK no seu celular (sideload).
        create("personal") {
            storeFile = file("../dharma.keystore")
            storePassword = "dharma123"
            keyAlias = "dharma"
            keyPassword = "dharma123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    // os .wav já são pequenos o bastante e não devem ser re-comprimidos/alterados
    androidResources { noCompress += "wav" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
