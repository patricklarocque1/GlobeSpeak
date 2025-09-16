plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
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
  implementation("androidx.appcompat:appcompat:1.7.0")

  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${project.findProperty("coroutines.version")}")
  implementation("com.google.android.gms:play-services-wearable:${project.findProperty("play.services.wearable.version")}")

  testImplementation("junit:junit:4.13.2")
}

