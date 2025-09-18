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
    minSdk = (project.findProperty("minSdkMobile") as String).toInt()
    targetSdk = (project.findProperty("targetSdkMobile") as String).toInt()
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

  buildFeatures {
    compose = true
  }
  composeOptions {
    kotlinCompilerExtensionVersion = (project.findProperty("compose.compiler.version") as String)
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }

  splits {
    abi {
      isEnable = true
      reset()
      include("arm64-v8a", "x86_64")
      isUniversalApk = false
    }
  }
}

dependencies {
  implementation(project(":engine"))

  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation(platform("androidx.compose:compose-bom:2024.06.00"))
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  debugImplementation("androidx.compose.ui:ui-tooling")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.navigation:navigation-compose:2.8.0")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
  implementation("androidx.datastore:datastore-preferences:1.1.1")

  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${project.findProperty("coroutines.version")}")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:${project.findProperty("coroutines.version")}")
  implementation("com.google.android.gms:play-services-wearable:${project.findProperty("play.services.wearable.version")}")
  implementation("com.google.mlkit:translate:17.0.2")
  implementation("com.google.mlkit:language-id:17.0.0")
  implementation("com.jakewharton.timber:timber:5.0.1")

  testImplementation("junit:junit:4.13.2")
  androidTestImplementation("androidx.test.ext:junit:1.1.5")
  androidTestImplementation("androidx.test:runner:1.5.2")
  androidTestImplementation("androidx.test:rules:1.5.0")
  androidTestImplementation("androidx.test:core:1.5.0")
  androidTestImplementation("androidx.test:core-ktx:1.5.0")
  androidTestImplementation("com.google.truth:truth:1.1.5")
}
