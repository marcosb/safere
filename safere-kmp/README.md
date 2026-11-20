# safere-kmp (optional)

Gradle Kotlin Multiplatform sidecar for porting SafeRE off JVM-only APIs.
Maven remains the shipping build; this module is not in the reactor.

Sources: reuses `../safere/src/main/java` as `commonMain` (with
`jvmOnlyRelativePaths` parked on `jvmMain`), plus a `WorkCounterConfig`
stub (Maven generates the real JVM class). Track promotion work in
`kotlin_conversion_helpers/KMP_CLEANUP.md`.

```bash
# From the repository root (task name follows the host native target).
./tools/machine-run --label "safere-kmp native" -- \
  ./gradlew :safere-kmp:compileKotlinLinuxX64 --continue
```
