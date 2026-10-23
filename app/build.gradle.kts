plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sonycam.monitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sonycam.monitor"
        minSdk = 26
        // 34, no 36. Subirlo dejaba a AGP 8.5.2 fuera de su matriz probada
        // ("tested up to compileSdk = 34"), y targetSdk además cambia
        // comportamientos en tiempo de ejecución —el edge-to-edge forzado desde
        // 35 toca justo el pantalla-completa de esta app— que no se han
        // probado. Subirlo como efecto secundario de una revisión de seguridad
        // fue un error. Para publicar en Play hará falta, pero como pieza de
        // trabajo propia: AGP + Gradle + Kotlin juntos y una pasada en cámara.
        targetSdk = 34
        versionCode = 46
        versionName = "0.46"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // El log PTP por transacción y el volcado de descriptores son la
            // herramienta de diagnóstico de este proyecto; en un build de
            // release sólo serían ruido en logcat con datos del dispositivo.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
}
