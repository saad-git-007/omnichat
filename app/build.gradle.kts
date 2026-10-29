plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
  testOptions { unitTests.isReturnDefaultValues = true }
  namespace = "com.saad.omnichat"
  compileSdk = 37
  defaultConfig {
    applicationId = "com.saad.omnichat"
    minSdk = 33
    targetSdk = 37
    versionCode = 28
    versionName = "1.27"

    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    externalNativeBuild {
      cmake {
        arguments += "-DCMAKE_BUILD_TYPE=Release"
        // shared STL: C++ exceptions (e.g. a Vulkan driver error) must unwind across libggml-*.so into libomni
        arguments += "-DANDROID_STL=c++_shared"
        arguments += "-DOMNI_VULKAN=ON"
        // OpenCL needs an ICD loader to link against; see third_party/opencl-android
        val openclLoader = rootProject.file("../third_party/opencl-android/lib/libOpenCL.so")
        arguments += "-DOMNI_OPENCL=" + (if (openclLoader.exists()) "ON" else "OFF")
        // Hexagon NPU needs the Hexagon SDK; see third_party/hexagon
        arguments += "-DOMNI_HEXAGON=" + (if (rootProject.file("../third_party/hexagon/6.7.0.0").isDirectory) "ON" else "OFF")
      }
    }
  }
  ndkVersion = "30.0.16248370"
  externalNativeBuild {
    cmake {
      path("src/main/cpp/CMakeLists.txt")
      version = "4.1.2"
    }
  }

  buildTypes {
    release {
      // sideload build: signed with the local debug key, no Play upload
      signingConfig = signingConfigs.getByName("debug")
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    jniLibs {
      // ggml_backend_load_all_from_path() scans nativeLibraryDir, so libs must be extracted
      useLegacyPackaging = true
      // the device's own vendor libOpenCL.so is used at run time
      excludes += "**/libOpenCL.so"
      // Hexagon DSP code: the NDK's strip doesn't understand it
      keepDebugSymbols += "**/libggml-htp-*.so"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation("org.json:json:20240303")
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.coil.compose)
  // PDF text extraction for document attachments
  implementation(libs.pdfbox.android)
}

tasks.withType<Test>().configureEach { systemProperty("live", System.getProperty("live") ?: "false") }
