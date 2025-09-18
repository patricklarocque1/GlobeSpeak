plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.globespeak.engine"
  compileSdk = (project.findProperty("compileSdk") as String).toInt()

  defaultConfig {
    minSdk = (project.findProperty("minSdkMobile") as String).toInt()

    externalNativeBuild {
      cmake {
        // Whisper streaming is only supported on 64-bit targets for performance.
        arguments += listOf("-DANDROID_STL=c++_shared")
      }
    }

    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    debug {}
    release {
      isMinifyEnabled = false
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

  externalNativeBuild {
    cmake {
      path = file("src/main/native/CMakeLists.txt")
    }
  }
}

dependencies {
  implementation("org.jetbrains.kotlin:kotlin-stdlib")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${project.findProperty("coroutines.version")}")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:${project.findProperty("coroutines.version")}")
  implementation("com.google.mlkit:translate:17.0.2")
  implementation("com.google.mlkit:language-id:17.0.0")
  implementation("androidx.datastore:datastore-preferences:1.1.1")
  implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")
  implementation("com.microsoft.onnxruntime:onnxruntime-extensions-android:0.13.0")

  testImplementation("junit:junit:4.13.2")
  androidTestImplementation("androidx.test:runner:1.5.2")
  androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
