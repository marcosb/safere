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
  // Vector / VarHandle SWAR stack
  "org/safere/ByteVectorScan.kt",
  "org/safere/ShortVectorScan.kt",
  "org/safere/TeddyVectorScan.kt",
  "org/safere/ByteSwarScan.kt",
  "org/safere/ShortSwarScan.kt",
  "org/safere/IncubatorVectorScanProvider.kt",
  "org/safere/VectorScanProviderFactory.kt",
  "org/safere/VectorScanProviders.kt", // expect/actual overlays in safere-kmp
  "org/safere/Utf8InputScanner.kt",
  "org/safere/Utf8StartAccelerator.kt",
  "org/safere/LiteralAlternation.kt",
  "org/safere/ArrayUtf8Input.kt",
  // java.util.regex-shaped public façade (JVM public API preserved in Maven + jvmMain)
  "org/safere/Pattern.kt",
  "org/safere/Matcher.kt",
  "org/safere/Utf8Matcher.kt",
  "org/safere/Parser.kt",
  "org/safere/PatternSet.kt",
  "org/safere/RegexLexer.kt",
  // Tightly coupled to Pattern/Matcher/Character.getType
  "org/safere/RejectPrefilter.kt",
  "org/safere/SafeReDiagnostics.kt",
  "org/safere/StringStartAccelerator.kt",
  "org/safere/MultiAnchorCompiler.kt",
  "org/safere/MultiAnchorDescriptor.kt",
  "org/safere/MatchDescriptor.kt",
  "org/safere/StringRegionInputScanner.kt",
  "org/safere/StringInputScanner.kt",
  "org/safere/CharClassScanInfo.kt",
  "org/safere/UnicodeCaseFolding.kt",
  "org/safere/Nfa.kt",
  "org/safere/Dfa.kt",
  "org/safere/BitState.kt",
  "org/safere/OnePass.kt",
  "org/safere/ShiftDfa.kt",
  "org/safere/InnerLiteralSearch.kt",
  "org/safere/GraphemeSupport.kt",
  "org/safere/JavaCharacterClasses.kt",
  // Remaining JDK-tied surface parked for M1 native green
  "org/safere/WorkCounter.kt",
  "org/safere/MatcherTransitionInventory.kt",
  "org/safere/RegionClassSearch.kt",
  "org/safere/EnginePathContract.kt",
  "org/safere/EnginePathOptions.kt",
  "org/safere/EngineContext.kt",
  "org/safere/ByteEngineContext.kt",
  "org/safere/DiagnosticAccumulator.kt",
  "org/safere/OperationDiagnostics.kt",
  "org/safere/ScanAudit.kt",
  "org/safere/Utf8Input.kt",
  "org/safere/Utf8Sink.kt",
  "org/safere/TeddyModel.kt",
  "org/safere/Inst.kt",
  "org/safere/Simplifier.kt",
  "org/safere/Regexp.kt",
  "org/safere/Prog.kt",
  "org/safere/Compiler.kt",
  "org/safere/Walker.kt",
  "org/safere/UnicodeTables.kt",
  "org/safere/UnicodeProperties.kt",
  "org/safere/CharClass.kt",
  "org/safere/CharClassBuilder.kt",
  "org/safere/CharClassExpander.kt",
  "org/safere/ClassHashChain.kt",
  "org/safere/AstAnalysis.kt",
  "org/safere/InputScanner.kt",
  "org/safere/PatternCompiledEvent.kt",
  "org/safere/PatternDescriptor.kt",
  "org/safere/StrategyDecision.kt",
  "org/safere/StrategyParticipation.kt",
  "org/safere/MultiLiteralInfo.kt",
  "org/safere/RarityOracle.kt",
  "org/safere/WorkLimit.kt",
  "org/safere/AsciiBitmap.kt",
  "org/safere/IntArrayList.kt",
  "org/safere/Utf16.kt",
  "org/safere/StateAccelerator.kt",
  "org/safere/StringLiteralSearch.kt",
  "org/safere/AdaptiveBackoff.kt",
  "org/safere/CaptureRune.kt",
  "org/safere/LazyRuneLoop.kt",
  "org/safere/LoopEntry.kt",
  "org/safere/SafeReMatchDiagnostics.kt",
  "org/safere/Ascii.kt",
  "org/safere/AcceleratorPolicy.kt",
  "org/safere/Utils.kt",
  "module-info.java",
)

/** Shared JVM-only .kt files compiled into Gradle jvmMain (not replaced by overlays). */
val jvmMainSharedIncludes =
  jvmOnlyRelativePaths.filter {
    it.endsWith(".kt") && it != "org/safere/VectorScanProviders.kt"
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
    jvmMain {
      kotlin {
        srcDir(sharedMain)
        include(*jvmMainSharedIncludes.toTypedArray())
      }
    }
  }
}
