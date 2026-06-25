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

## Performance Improvements

These would make KBuild competitive with Gradle's build times.

**Current state (building KBuild itself, ~90 files):**
| Scenario | Gradle | KBuild Daemon |
|----------|--------|---------------|
| Clean build | 6-7s | 9-10s |
| No changes | ~0s | 2-3ms |
| 1 file changed | ~0.6s | **0.3-0.5s** |

- [x] **Classpath snapshotting** - Enable true incremental compilation - DONE
  - Implemented `ClasspathSnapshotManager` using Kotlin BuildTools API
  - Per-JAR snapshots cached by path and modification time
  - Uses `ClasspathSnapshotEnabled.IncrementalRun.ToBeComputedByIncrementalCompiler`
  - Result: 1 file changed now compiles in ~300-500ms (was 5s with full rebuild)

- [x] **Source file change tracking** - DONE
  - Implemented `SourceFileTracker` that tracks file modification times
  - Passes `ChangedFiles.Known` to compiler with modified/removed files
  - Combined with classpath snapshotting, enables true incremental compilation

- [ ] **Compiler warm-up at daemon startup** - Pre-initialize Kotlin compiler
  - Currently the first compilation pays JVM + compiler initialization cost
  - Idea: Do a no-op compilation at daemon startup to warm the compiler
  - Impact: Would reduce first build time by ~2-3s

- [ ] **Parallel source scanning** - File discovery is currently sequential
  - `sourceRoots.asSequence().flatMap { it.walkTopDown() }` is single-threaded
  - Use: Coroutines to scan multiple source roots in parallel
  - Impact: Small (~100-500ms) but helps for large codebases

- [ ] **Build cache** - Cache compilation outputs by input hash
  - Like Gradle's build cache but simpler
  - Hash: (source files + classpath + compiler args) → output classes
  - Impact: Makes clean builds as fast as incremental for unchanged code

---

## Deployment Helper Tools

Port useful tools from `lk-gradle-helpers` to KBuild.

### High Priority

- [x] **Git-Based Versioning** (`git/`) - DONE
  - `Git.kt` - Git command wrapper (ProcessBuilder)
  - `GitVersion.kt` - Automatic semantic versioning from git state
  - Parse git tags as semantic versions
  - Branch detection: `master`/`main`/`dev` → release, `version-X` → pre-release
  - Dirty working tree → append `-local` suffix
  - CI detection (GitHub Actions, etc.) → treat as clean

- [ ] **Local Dependencies** (`dev/`)
  - `LocalProperties.kt` - Properties file handling
  - `LocalDependencies.kt` - Override published deps with local builds
  - Auto-clone from Git URL if project not present locally
  - Build and use local version instead of published
  - Track local version in `local.version.txt`

- [ ] **Documentation Publishing** (`docs/`)
  - `DokkaGenerate.kt` - Run Dokka on project sources
  - `DocsPublish.kt` - Upload docs to S3
  - GitHub source link generation
  - Version-aware URL structure: `{group}/{name}/{version}/docs/`
  - HTML redirect at root to latest version

### Medium Priority

- [ ] **Maven Central Publishing** (`maven/`)
  - `MavenCentralPublish.kt` - Full staging workflow
  - Sonatype OSSRH staging API integration
  - Automatic close and release of staging repositories
  - Integrate with existing `GpgSigning.kt`
  - S3 fallback when Central unavailable

- [ ] **Utilities** (`util/`)
  - `StringCasing.kt` - camelCase, pascalCase, snakeCase, kabobCase, etc.
  - `LatestVersion.kt` - Query Maven metadata for latest matching version
  - `OfflineMode.kt` - Check properties/environment for offline flag

- [ ] **Standard Library Setup** (`standard/`)
  - `StandardLibrary.kt` - One-call setup for LK library projects
  - Combines git versioning + publishing + documentation + local deps

### Deferred

- [ ] **Version Catalog** (`catalog/`)
  - `VersionCatalog.kt` - Parse/modify `libs.versions.toml` files

### Design Notes

- Functions over classes (match KBuild's functional style)
- Suspend + Blocking variants (both reactive and imperative use)
- ProcessBuilder for Git (same pattern as existing GPG/Konan)
- Keychain integration (use existing `Keychain` for credentials)
- Leverage: `GpgSigning.kt`, `S3MavenPublish.kt`, `MavenAether`, `Version`

---

## Future Considerations

Not blocking, but worth tracking.

- [ ] **Remote build cache** - Share compilation results across machines
- [ ] **Gradle plugin interop** - Use Gradle plugins from kbuild (escape hatch)
- [ ] **Build visualization** - Show dependency graph, timing
- [ ] **Watch mode CLI** - `kbuild watch Build.compile` for continuous mode
