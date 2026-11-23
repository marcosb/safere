# safere-kmp (optional)

Gradle Kotlin Multiplatform sidecar for porting SafeRE off JVM-only APIs.
Maven remains the shipping build; this module is not in the reactor.

Sources: reuses `../safere/src/main/java` as `commonMain` (with
`jvmOnlyRelativePaths` parked on `jvmMain`), plus a `WorkCounterConfig`
stub (Maven generates the real JVM class). Track promotion work in
`kotlin_conversion_helpers/KMP_CLEANUP.md`.

## Portable façade

`PlatformPattern` / `PlatformMatcher` / `PlatformUtf8Matcher` are the
multiplatform entry points:

- **JVM actual** delegates to the shipping Maven `Pattern` / `Matcher` /
  `Utf8Matcher`.
- **Native actual** compiles and matches through common engines
  (`PlatformRegexEngine`: Parser → Compiler → OnePass / BitState / DFA /
  start+reject accelerators / Nfa).

The JDK-shaped `Pattern` / `Matcher` / `Utf8Matcher` types and incubator Vector
kernels stay on `jvmMain`. Native Unicode overlays use generated case maps and
ICU4J-derived `\N{...}` tables; JDK Block/Script alias fallback is intentionally
JVM-only (see `INTENTIONAL_DIVERGENCES.md`).

```bash
# From the repository root (task name follows the host native target).
./tools/machine-run --label "safere-kmp native" -- \
  ./gradlew :safere-kmp:compileKotlinLinuxX64 --continue

# Host-native PlatformPattern tests (linuxX64 in WSL/Linux).
./tools/machine-run --label "safere-kmp nativeTest" -- \
  ./gradlew :safere-kmp:linuxX64Test --continue
```
