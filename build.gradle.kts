plugins {
  // Versions provided via gradle.properties
  id("com.android.application") version System.getProperty("agp.version") ?: providers.gradleProperty("agp.version").get() apply false
  id("com.android.library") version System.getProperty("agp.version") ?: providers.gradleProperty("agp.version").get() apply false
  id("org.jetbrains.kotlin.android") version System.getProperty("kotlin.version") ?: providers.gradleProperty("kotlin.version").get() apply false
}

tasks.register("clean", Delete::class) {
  delete(layout.buildDirectory)
}

