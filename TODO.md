# KBuild TODO

Items from project review (2026-01-01). Prioritized by impact.

---

## Critical Issues

These block the core value proposition of the project.

- [x] **Fix DirectoryWatch tests** - FIXED
  - Root cause: Java's `PathMatcher` glob `**/*.kt` doesn't match files in root directory
  - Solution: Added `normalizeGlobPattern()` to convert `**/*.ext` to `{,**/}*.ext`
  - All 478 tests now pass

- [x] **Create reactive build demo** - DONE
  - Created `samples/reactive-build-demo/` with:
    - `Build.kt` - reactive build script with watch mode
    - `src/Main.kt` - sample source
    - `src/MainTest.kt` - sample tests
    - `README.md` - documentation
  - Demonstrates: DirectoryWatch + reactiveScope + auto-rebuild

- [x] **Document CI/CD usage** - DONE
  - Created `docs/ci-cd.md` with:
    - GitHub Actions and GitLab CI examples
    - Installation methods (JAR, source, Docker)
    - Caching strategies
    - Environment variables and exit codes
    - Sample workflows (release, PR validation)
  - Created `.github/workflows/build.yml` for kbuild itself

---

## Improvements

These would significantly improve adoption and usability.

- [x] **Bootstrap (self-hosting)** - DONE
  - Created `Build.kt` for kbuild at project root
  - Includes: compile, test, jar, sourcesJar, publishLocal, clean, build
  - Uses: kotlinJvmCompileBlocking, junitRunBlocking, jarBuild, MavenDeploy
  - Matches all dependencies from build.gradle.kts

- [ ] **Publish to Maven Central**
  - Currently only available via `mavenLocal`
  - Blocks external adoption
  - Need: GPG signing setup, Sonatype credentials, release workflow

- [ ] **Native IDE support** - Remove Gradle bridge dependency
  - Currently uses `GradleIdeBuild` to generate IDE files
  - Goal: Native `.idea/` and `.iml` generation
  - `IntelliJProjectBuild` and `IntelliJModuleBuild` exist but not integrated with KMP

- [x] **Hot reload demo** - DONE
  - Created `samples/hot-reload-demo/` with:
    - `Build.kt` - hot reload build with dev() function
    - `src/Server.kt` - simple HTTP server
    - `README.md` - documentation with comparisons
  - Demonstrates: DirectoryWatch + reactiveScope + server restart

- [x] **Performance benchmarks** - DONE
  - Created `local/benchmarks/benchmark.sh` script
  - Measures: Clean build, incremental, and test times
  - Compares Gradle vs KBuild
  - Results saved with timestamps to `local/benchmarks/`

- [ ] **Tutorial documentation**
  - Create: "Getting Started" guide for new projects
  - Create: Migration guide from Gradle
  - Create: API reference (KDoc → published docs)

---

## Code Quality

Lower priority but improve maintainability.

- [ ] **Improve error messages** - Some compilation errors lack context
  - Add: Source file path in error output
  - Add: Suggestion for common fixes (missing dependency, wrong target, etc.)

- [ ] **Add configurable logging** - No way to debug builds
  - Add: Log level configuration (DEBUG, INFO, WARN, ERROR)
  - Add: Verbose mode for compilation diagnostics
  - Consider: Structured logging for CI parsing

- [ ] **Clean up K2 compiler workarounds** - Document expiration
  - ByteBuddy patches in `KotlinJsCompile.kt` work around K2 bugs
  - Add: Kotlin version checks to disable patches when fixed
  - Add: Comments linking to upstream issues

- [ ] **Parallelize target compilation** - KMP builds are sequential
  - JVM, JS, Native targets could build in parallel
  - Use: Kotlin coroutines for concurrent compilation
  - Measure: Impact on build time

---

## Future Considerations

Not blocking, but worth tracking.

- [ ] **Remote build cache** - Share compilation results across machines
- [ ] **Gradle plugin interop** - Use Gradle plugins from kbuild (escape hatch)
- [ ] **Build visualization** - Show dependency graph, timing
- [ ] **Watch mode CLI** - `kbuild watch Build.compile` for continuous mode
