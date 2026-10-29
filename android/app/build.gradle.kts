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

    // A chave de release NÃO fica no repositório: vem de variáveis de ambiente
    // (GitHub Secrets no CI, ou o seu shell localmente). Sem elas, assina com a chave de debug.
    val releaseKeystore = System.getenv("DHARMA_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("DHARMA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DHARMA_KEY_ALIAS") ?: "dharma"
                keyPassword = System.getenv("DHARMA_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
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
