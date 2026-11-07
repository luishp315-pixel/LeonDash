plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.luis.leondash"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.luis.leondash"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // El almacén de claves se genera en GitHub Actions en cada compilación (no se sube al repo)
    val ksPath = System.getenv("LEONDASH_KEYSTORE")
    val ksPass = System.getenv("LEONDASH_KEYSTORE_PASS")
    if (ksPath != null && ksPass != null) {
        signingConfigs {
            create("ci") {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = "leondash"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksPath != null && ksPass != null) signingConfigs.getByName("ci")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
