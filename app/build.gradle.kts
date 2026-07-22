plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.resourcefork.rccontrol"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.resourcefork.rccontrol"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.google.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // ViewModel + Compose integration
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // USB serial – communication with Arduino Nano (CH340 USB-to-serial)
    implementation(libs.usb.serial.android)

    // USB (UVC) cameras – phones like the Razr lack the external-camera HAL, so OTG webcams
    // (e.g. Arducam) are driven directly over libusb/libuvc instead of Camera2/CameraX.
    implementation(libs.uvcandroid)

    // CameraX – live preview surface + frame capture for the VLM
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // Networking – cloud VLM API calls with streaming (SSE)
    implementation(libs.okhttp)

    // Chrome Custom Tabs – open HF license/token pages in-app and return cleanly
    implementation(libs.androidx.browser)

    // On-device / offline VLM inference (MediaPipe LiteRT LLM Inference API).
    // tasks-core provides the com.google.mediapipe.framework.image.MPImage container
    // that LlmInferenceSession.addImage() requires for multimodal (vision) prompts.
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.mediapipe.tasks.core)

    // LiteRT interpreter (classic org.tensorflow.lite API) – runs the monocular depth
    // model (MiDaS small .tflite) that powers the geometry reflex layer.
    implementation(libs.litert)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // JSON serialization – VLM request / response parsing
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
