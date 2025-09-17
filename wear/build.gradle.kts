plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "com.globespeak"
  compileSdk = (project.findProperty("compileSdk") as String).toInt()

  defaultConfig {
    applicationId = "com.globespeak"
    minSdk = (project.findProperty("minSdkWear") as String).toInt()
    targetSdk = (project.findProperty("targetSdkWear") as String).toInt()
    versionCode = 1
    versionName = "0.1.0"
  }

  buildFeatures {
    compose = true
  }
  composeOptions {
    kotlinCompilerExtensionVersion = (project.findProperty("compose.compiler.version") as String)
  }

  buildTypes {
    debug {
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"
    }
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
}

dependencies {
  implementation(project(":engine"))
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.fragment:fragment-ktx:1.7.1")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

  // Wear Compose
  implementation("androidx.wear.compose:compose-material:${project.findProperty("wear.compose.version")}")
  implementation("androidx.wear.compose:compose-foundation:${project.findProperty("wear.compose.version")}")

  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${project.findProperty("coroutines.version")}")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:${project.findProperty("coroutines.version")}")
  implementation("com.google.android.gms:play-services-wearable:${project.findProperty("play.services.wearable.version")}")

  testImplementation("junit:junit:4.13.2")
}
