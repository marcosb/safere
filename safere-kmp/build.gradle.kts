import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
  kotlin("multiplatform") version "2.2.20"
}

group = "org.safere"
version = "1.2.0-SNAPSHOT"

kotlin {
  jvm {
    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
      }
    }
  }

  // One host-native target so local `compileKotlin*` drives JVM→KMP API fixes.
  // Declared targets are not wired into the Maven reactor; this module is opt-in.
  when {
    HostManager.hostIsMingw -> mingwX64()
    HostManager.hostIsLinux -> linuxX64()
    HostManager.hostIsMac -> {
      if (System.getProperty("os.arch") == "aarch64") {
        macosArm64()
      } else {
        macosX64()
      }
    }
    else ->
      throw GradleException(
        "Unsupported host for safere-kmp native target: ${HostManager.hostName}"
      )
  }

  sourceSets {
    commonMain {
      dependencies {
        implementation(kotlin("stdlib"))
      }
      // Existing Maven Kotlin sources (still under src/main/java). Expected to fail
      // on kotlin/native and common metadata until java.* usage is replaced.
      kotlin.srcDir("../safere/src/main/java")
    }
  }
}
