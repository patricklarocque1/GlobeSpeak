plugins {
  id("com.android.application") version "8.6.1" apply false
  id("com.android.library") version "8.6.1" apply false
  id("org.jetbrains.kotlin.android") version "2.0.20" apply false
  id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}

tasks.register("clean", Delete::class) {
  delete(layout.buildDirectory)
}
