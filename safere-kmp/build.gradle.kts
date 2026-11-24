import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
  kotlin("multiplatform") version "2.2.20"
}

group = "org.safere"
version = "1.2.0-SNAPSHOT"

/**
 * Paths relative to safere/src/main/java excluded from commonMain.
 * JVM kernels / regex façade stay on jvmMain (or KMP overlays) so Maven's
 * public API sources are unchanged.
 */
val jvmOnlyRelativePaths = listOf(
  // Incubator Vector kernels (SWAR lives in commonMain via ByteArrayLongView)
  "org/safere/ByteVectorScan.kt",
  "org/safere/ShortVectorScan.kt",
  "org/safere/TeddyVectorScan.kt",
  "org/safere/IncubatorVectorScanProvider.kt",
  "org/safere/VectorScanProviderFactory.kt",
  "org/safere/VectorScanProviders.kt", // expect/actual overlays in safere-kmp
  "org/safere/ByteArrayLongView.kt",
  "org/safere/ByteArrayRegions.kt",
  // java.util.regex-shaped public façade (JVM public API preserved in Maven + jvmMain)
  "org/safere/Pattern.kt",
  "org/safere/Matcher.kt",
  "org/safere/Utf8Matcher.kt",
  // Pattern-typed diagnostics overloads (core SafeReDiagnostics is common)
  "org/safere/SafeReDiagnosticsPattern.kt",
  // Maven JVM helpers replaced by KMP expect/actual overlays
  "org/safere/ThreadLocalRef.kt",
  "org/safere/SystemProperties.kt",
  "org/safere/UnicodeJdkAliases.kt",
  "org/safere/PatternSyntaxExceptions.kt",
  "org/safere/UnicodeCharacterNames.kt",
  "org/safere/MatchDiagnosticsAccess.kt",
  // JVM-only ByteBuffer sink extension (Utf8Sink itself is common)
  "org/safere/Utf8SinkBuffers.kt",
  // Class-reflection Matcher inventory (stays JVM)
  "org/safere/MatcherTransitionInventory.kt",
  "module-info.java",
)

/** Shared files replaced by KMP expect/actual overlays (not compiled from shared on any target). */
val kmpOverlayRelativePaths =
  setOf(
    "org/safere/VectorScanProviders.kt",
    "org/safere/ByteArrayLongView.kt",
    "org/safere/ByteArrayRegions.kt",
    "org/safere/ThreadLocalRef.kt",
    "org/safere/SystemProperties.kt",
    "org/safere/UnicodeJdkAliases.kt",
    "org/safere/PatternSyntaxExceptions.kt",
    "org/safere/UnicodeCharacterNames.kt",
    "org/safere/MatchDiagnosticsAccess.kt",
  )

/** Shared JVM-only .kt files compiled into Gradle jvmMain (not replaced by overlays). */
val jvmMainSharedIncludes =
  jvmOnlyRelativePaths.filter {
    it.endsWith(".kt") && it !in kmpOverlayRelativePaths
  }

val sharedMain = file("../safere/src/main/java")

kotlin {
  applyDefaultHierarchyTemplate()

  compilerOptions {
    freeCompilerArgs.add("-Xexpect-actual-classes")
  }

  jvm {
    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
      }
    }
  }

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
      kotlin {
        srcDir(sharedMain)
        jvmOnlyRelativePaths.forEach { exclude(it) }
        exclude("module-info.java")
      }
    }
    commonTest {
      dependencies {
        implementation(kotlin("test"))
      }
    }
    jvmMain {
      kotlin {
        srcDir(sharedMain)
        // include() applies to every srcDir; keep shared JVM-only sources and the
        // sidecar expect/actual overlays under src/jvmMain/kotlin.
        include(*jvmMainSharedIncludes.toTypedArray())
        include("org/safere/*.jvm.kt")
      }
    }
  }
}
