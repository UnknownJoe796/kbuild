# KBuild Roadmap

The single source of truth for where KBuild is and where it's going. Supersedes the
older feature-by-feature `ROADMAP.md`, `TODO.md`, and `REVIEW.md` notes.

## Vision

KBuild is a **reactive build library** for Kotlin — not a framework, not a plugin host.
The goal is to support **everything a production Kotlin app or library needs to build,
test, and ship**, while staying compatible with the existing Kotlin Multiplatform (KMP)
ecosystem, all expressed as plain, Ctrl-clickable Kotlin you call directly.

Two non-negotiables drive this roadmap:

1. **Production-complete** — every major capability a real app or library requires
   (all targets, publishing, packaging, codegen, dev loop) is a first-class, supported path.
2. **Ecosystem-compatible** — KBuild must consume and produce artifacts that interoperate
   with the wider KMP/Gradle ecosystem. We cannot be an island.

## Status legend

- ✅ **Done** — implemented, tested, supported
- 🟡 **Partial** — works for common cases; known gaps
- ⬜ **Planned** — not yet implemented
- 🧭 **Deferred** — intentionally out of near-term scope

---

## Current foundation (solid today)

Result of the production-readiness program (Phases 0–2 below):

- ✅ JVM compilation on the **public Kotlin Build Tools API** (no internal-compiler coupling), Kotlin **2.3.20** / KSP **2.3.9**
- ✅ All current targets compiling (JVM, JS, Native) with incremental builds
- ✅ **Self-hosting**: KBuild builds and tests itself with no Gradle, via a from-source bootstrap; Gradle retained only as an escape hatch
- ✅ Reactive **live recompile** (`--watch`) working through both the `reactive {}` and `reactiveSuspending {}`/CLI paths
- ✅ Full test suite green (junitRun parity with Gradle), tests run in an isolated **forked JVM**
- ✅ **Dogfooded on a real external KMP library** (`lightningkite/reactive`): kbuild compiles all 5 targets (JVM/JS/3×iOS), runs its tests (115/0, matching Gradle's 103 methods), and publishes to `~/.m2` (see Benchmarks). The clean multiplatform publish that was previously blocked by a transitive `kotlin-stdlib-js:2.1.0`/`atomicfu` (from `coroutines:1.10.2`) clashing with the pinned 2.3.20 stdlib now works end-to-end — fixed by version-conflict resolution (§2).

### Benchmarks

Clean `publishToMavenLocal` of the `reactive` library (all 5 targets → `~/.m2`; warm
dependency/konan caches; build outputs (`build/`) wiped each run, build-tool incremental caches
kept warm (kbuild `.kbuild`, Gradle daemon + `~/.gradle`); two runs each, `tmp/benchmark-publish.sh`):

| Tool | Time | Conditions |
|---|---|---|
| **kbuild** `ReactiveBuild.publish` | **~19.3s** | daemon-JVM + concurrent JS/native/metadata pipeline; unsigned; warm `.kbuild` |
| **Gradle** `publishToMavenLocal` (warm daemon) | **~22.7s** | signed; complete |

This is the real `reactive` number with the full-parallel pipeline (JVM via Kotlin daemon, JS/native/
metadata concurrent): **~19.3s vs Gradle's ~22.7s with a warm daemon** — kbuild is modestly faster
despite Gradle's parallel tasks and warm daemon. The published artifact set is otherwise Gradle
**artifact-for-artifact** (see §3). The one caveat: the benchmark run was **unsigned** — it used a
signing stub because real-key GPG can't run in this sandbox, so real signing (a fast per-file `gpg`
pass) would add a little time. The kbuild run keeps `.kbuild` incremental caches warm, mirroring
Gradle's warm daemon. The earlier "~21s (stale)" figure predated both the daemon/parallel work and
version-conflict resolution and is superseded.

---

## 1. Targets & compilation

| Capability | Status | Notes |
|---|---|---|
| Kotlin/JVM | ✅ | BTA-based, incremental, type-safe options |
| Kotlin/JS | 🟡 | Two-phase (KLIB→JS), incremental — still on internal compiler APIs + ByteBuddy patches; no public API exists for JS yet |
| Kotlin/Native | ✅ | All Konan targets, C-interop, test runner |
| Android | 🟡 | Manifest, APK builder, SDK handling — needs AAB + full resource pipeline |
| iOS | 🟡 | Swift compile, XCFramework, Xcode project, code signing — needs end-to-end `.app`/IPA + asset/entitlement coverage |
| **Kotlin/Wasm** | ⬜ | Not yet — increasingly required for production web; high priority |
| KMP source-set hierarchy | ✅ | `SourceSetHierarchy`, per-target compiler args |
| Parallel target compilation | ✅ | **All** targets compile concurrently: JVM via the **BTA daemon** (out-of-process), natives via `konanc` subprocesses, and the two in-process-capable compiles (JS + commonMain metadata) governed by a single in-process permit (`InProcessCompileLock`) — whichever is ready first runs in-process, the other runs in a **forked kbuild JVM** (`CompileFork`), so they overlap while exactly one in-process compile runs at any instant. `kmpBuildAllBlocking` / `KmpPublisher.publishAll` fan out under this invariant |

## 2. Dependency resolution & KMP ecosystem compatibility

> **Largely closed.** KBuild now *consumes* published KMP libraries via variant-aware Gradle
> Module Metadata reading (kotlinx/Ktor/Compose-style publications), following `available-at`
> redirects instead of guessing artifact names. Remaining gap: BOM/platform alignment and the
> `strictly`/`rejects` version algebra (deferred).

| Capability | Status | Notes |
|---|---|---|
| Maven dependency resolution (Aether) | ✅ | Transitive, scope-aware, cached |
| **Generate** Gradle Module Metadata (`.module`) | ✅ | `KmpPublish` writes variants + `available-at`; our libs are Gradle-consumable |
| **Read/parse** Gradle Module Metadata for resolution | ✅ | `MavenAether.fetchModuleMetadata` parses `.module` (`GradleModuleMetadata.kt`); returns null for non-GMM libs → POM/convention fallback. Covered by `GradleModuleMetadataTest` |
| Variant-aware resolution (attributes → artifact) | ✅ | `MavenAether.selectVariant` matches `org.gradle.category=library`, `org.jetbrains.kotlin.platform.type`, native target (`KonanTarget.targetName`), and usage (api/runtime); packaging (jar vs klib) read from the variant's `files[0]` extension |
| `available-at` redirects | ✅ | `resolveKmpForTarget` follows the root→per-target redirect once via `available-at` coords (not the back-referencing per-target component); verified resolving e.g. `kotlinx-coroutines-core` → `…-jvm-1.10.2.jar` / `…-iosarm64-1.10.2.klib` |
| KLIB resolution (JS/Native) | ✅ | Flows from module metadata: JS → `…-js.klib`, native → per-target klib via `available-at`. Convention path retained only as fallback for non-GMM libs |
| Version-conflict resolution | ✅ | Highest-version-wins per `group:artifact` across the resolved graph (Gradle's default), with **Kotlin first-party (`org.jetbrains.kotlin:kotlin-*`) pinned to `Kotlin.version`** so the stdlib/runtime stays ABI-compatible with the embedded compiler (mirrors KGP version alignment). Implemented in `KmpDependencyResolver.resolveForTarget` via `resolveVersionConflicts`; shared version comparator in `common/VersionComparison.kt`. Verified: reactive (JS/native no longer clash on stdlib `unique_name`) and KiteUI (`library:5.3.36` Js → exactly one `kotlin-stdlib-js`) |
| Version catalogs / BOM / platform alignment | 🟡 | Platform (`org.gradle.category=platform`/BOM) dependencies are **filtered out** during GMM resolution (TODO in `GmmVersionConstraint`); `strictly`/`rejects`/`prefers` algebra not yet implemented (uses declared `requires`). BOM-driven alignment still deferred — highest-wins + Kotlin pinning covers the common case; a BOM that pins a *lower* version than highest-wins would not be honored |
| Local dependency substitution (dev builds) | ⬜ | Override a published dep with a local build |

### What "read their format" concretely requires — and what landed

To be a first-class KMP consumer, dependency resolution must:

1. Fetch the root `.module` (Gradle Module Metadata v1.1) alongside the POM. — ✅
2. Select the correct **variant** for the requested target by matching Gradle attributes
   (platform type, native target, usage, category). — ✅ (`selectVariant`)
3. Follow **`available-at`** to the real per-target module coordinate and resolve *its*
   metadata. — ✅ (one redirect; per-target modules carry no further redirects)
4. Read each variant's `files` (for packaging) and `dependencies` rather than inferring
   artifact names. — ✅ (transitive deps recursed through GMM, convention fallback per dep)
5. Fall back gracefully to POM-only / convention resolution for non-KMP libraries. — ✅
   (a missing `.module` yields null, preserving prior behavior)

**Landed since:** version-conflict resolution — highest-version-wins per module with Kotlin
first-party pinned to the compiler's version (see the table row above). This is what unblocked the
clean reactive multiplatform publish.

**Still deferred:** `dependencyConstraints` and BOM/platform alignment (platform deps are
currently filtered, not aligned), and the `strictly`/`rejects`/`prefers` version algebra
(only `requires` is honored). These affect tightly version-pinned graphs but not the common
case of consuming kotlinx/Ktor/Compose at a chosen version.

## 3. Library authoring & publishing

| Capability | Status | Notes |
|---|---|---|
| Maven / S3 publishing | ✅ | `MavenDeploy`, `S3MavenPublish` |
| Gradle Module Metadata generation | ✅ | See §2 |
| POM generation (scope-aware) | ✅ | `PomBuild` |
| GPG signing | 🟡 | Wired into the KMP publish path (`MultiplatformLibrary` signs by default via `GpgSigner`); produces a `.asc` per artifact. Real-key signing is **untested in the sandbox** (`~/.gnupg` inaccessible) — confirm in a normal terminal |
| KMP publish completeness | ✅ | Publication matches Gradle **artifact-for-artifact** for the reactive library across all 6 coordinates (per-target `.module` w/ checksums, root commonMain metadata jar, `kotlin-tooling-metadata.json`, native `-metadata.jar`; no javadoc — Gradle's KMP publication emits none). The root jar's `kotlin-project-structure-metadata.json` now lists the **full declared shared hierarchy** (e.g. `appleMain`/`iosMain`/`nativeMain` even when empty), with per-source-set `dependsOn`, `moduleDependency` (derived from each dependency's own structure metadata), and `hostSpecific`/cinterop fields matching Gradle — verified equal to Gradle's output for reactive. Native per-target module structure and the structure-metadata source-set list are now guarded by `KmpPublishTest`. **Publish loop verified from Gradle's side:** a real Gradle KMP consumer resolves a kbuild-published reactive via the `.module` files — variant-aware across JVM/JS/native (`*ApiElements-published` selected, platform attributes + `available-at` redirects honored) — and compiles against it. This caught a real bug: POMs lacked the `do_not_remove: published-with-gradle-metadata` marker, so Gradle ignored every `.module` and fell back to plain-Maven resolution (root metadata jar instead of per-target artifacts); now injected by `KmpPublish.injectGradleMetadataMarker` and guarded by `KmpPublishTest`. **Caveat:** real-key GPG signing remains untested in the sandbox (see GPG row) |
| Sources / fat JARs | ✅ | `sourcesJar`, `Jar.fatJar()` |
| Git-based versioning | ✅ | `GitVersion` |
| Dokka / API docs publishing | ⬜ | Generate + publish versioned docs (to S3) |
| Maven Central (Sonatype) | 🧭 | Deferred — S3 distribution is the near-term channel |
| Binary-compatibility validation | ⬜ | BTA exposes `AbiValidationToolchain`; worth adopting |

## 4. Application packaging & distribution

| Capability | Status | Notes |
|---|---|---|
| Fat / executable JAR | ✅ | `Jar.fatJar()` |
| JVM app images / native installers | ⬜ | jpackage-style distribution |
| Android APK | 🟡 | Builder exists; needs signing config + AAB |
| iOS `.app` / IPA + signing | 🟡 | Pieces exist; needs end-to-end packaging |
| Web bundling | ✅ | Vite integration, dev server |
| Dead-code elimination / minification | ⬜ | R8/ProGuard (JVM/Android), JS DCE |
| Resource handling (multiplatform resources) | ⬜ | Bundled resources across targets |

## 5. Compiler plugins & code generation

| Capability | Status | Notes |
|---|---|---|
| KSP2 (JVM/JS/Native) | ✅ | Standalone-tool model |
| kotlinx.serialization | ✅ | Wired via BTA `COMPILER_PLUGINS` |
| **Compose Multiplatform** compiler plugin | ⬜ | Major gap for production apps/libs |
| Other plugins (allopen, noarg, etc.) | 🟡 | BTA `CompilerPlugin` API makes these straightforward to add on demand |

## 6. Developer experience

| Capability | Status | Notes |
|---|---|---|
| Reactive live recompile (`--watch`) | ✅ | mtime-aware change detection (content edits now retrigger) |
| Hot reload (server restart) | 🟡 | Demo exists; productionize the loop |
| Dev server | ✅ | Vite |
| CLI / REPL / daemon | ✅ | Expression dot-notation, watch mode |
| Native IDE project generation (JVM) | ✅ | KBuild generates its own `.idea`/`.iml` |
| Native IDE generation (KMP) | 🧭 | Deferred — still uses the Gradle bridge for multiplatform IDE import |
| Configurable logging | ⬜ | Levels + verbose diagnostics |
| Error-message quality | ⬜ | Source paths + actionable hints |

## 7. Build infrastructure & performance

| Capability | Status | Notes |
|---|---|---|
| Incremental compilation (JVM/JS) | ✅ | BTA snapshots (JVM); IC (JS) |
| Self-host bootstrap | ✅ | From-source, no Gradle; S3 fast-path. `bootstrap.sh` rebuilds `build/bootstrap/kbuild.jar` when it's missing **or any `src/main/kotlin/**.kt` / the dependency manifest is newer than it** (mtime check) — previously it only built when the jar was absent, so source edits silently ran stale code through `run-kbuild.sh` / `kbuild-on.sh`. Verified: rebuilds after a source touch, skips when unchanged |
| Output/build cache by input hash | ⬜ | Make clean builds as fast as incremental |
| Parallel target compilation (native) | ✅ | Native targets fan out across concurrent `konanc` subprocesses (`kmpBuildAllNativeBlocking`) |
| **Parallel compilation of ALL targets** | ✅ | **Done.** The embeddable compiler can't overlap with itself (process-global IntelliJ singletons: `ApplicationManager`, `Disposer`, extension registries), so JVM compilation moved **out-of-process** to the **BTA daemon execution strategy** (`DaemonJvmCompile` + `DaemonJvmCompileDriver`, loaded in an isolated `URLClassLoader` as the daemon classpath requires). The two in-process-capable compiles — Kotlin/JS and the commonMain metadata chain — share a single in-process permit (`InProcessCompileLock`): whichever is ready first runs in-process, the other runs in a **forked kbuild JVM** (`CompileFork`/`CompileForkMain`, modeled on `JUnitForkRunner`), so they overlap while honoring "exactly one in-process compile at any instant". Natives remain `konanc` subprocesses. JVM (daemon) + natives (konanc) + one in-process + one forked all overlap. `kmpBuildAllBlocking` and `KmpPublisher.publishAll` launch every target concurrently under this invariant (dependencies resolved up front, as the Aether session is not concurrency-safe). The CLI now `exitProcess`es after a one-shot build, since the Kotlin daemon client keeps non-daemon RMI threads alive. |
| Compiler/daemon warm-up | ⬜ | Hide first-build init cost (and amortize across the per-target compiler processes above) |
| Remote build cache | 🧭 | Share results across machines |

## 8. Quality, testing & maintenance

| Capability | Status | Notes |
|---|---|---|
| JUnit5 execution (`junitRun`) | ✅ | Parity with Gradle |
| Browser / Node JS test execution | ✅ | `BrowserTestRunner`, `NodeJsTestRunner` |
| Native test execution | ✅ | `KotlinNativeTestRunner` |
| CI that dogfoods kbuild | ⬜ | Self-host build lane + unit/integration split + Kotlin-version matrix |
| junitRun test-classpath isolation | ✅ | Tests run in a **forked JVM** with exactly the project's test classpath (`JUnitForkRunner`), like Gradle — kbuild's own bundled deps can't leak in |
| Single source of truth for dependencies | ⬜ | Today deps live in 3 places (`build.gradle.kts`, `Build.kt`, `bootstrap/classpath.txt`) — drift risk |
| Kotlin-version-bump runbook | ⬜ | KBuild is pinned 1:1 to a Kotlin version (JS/Native use `kotlin-compiler-embeddable`); every Kotlin release is a KBuild release event |

---

## Production-readiness program

The phased plan currently in flight to bring KBuild to a public-quality bar (distributed
via S3; public Maven Central deferred):

| Phase | Scope | Status |
|---|---|---|
| 0 | Stabilize baseline (green suite, clean commits, tag) | ✅ Done |
| 1 | Compiler story: BTA migration + Kotlin 2.3.20; remove dead Bintray; consolidate version | ✅ Done |
| 2 | Full self-host: from-source bootstrap, native JVM IDE gen, demote Gradle to a stub | ✅ Done |
| 3 | Release via S3: signed artifacts, GitVersion, documented consumer contract + version-pin rule, CHANGELOG | ⬜ Next |
| 4 | CI on KBuild itself: self-host lane, unit/integration split, Kotlin-version matrix, caches | ⬜ |
| 5 | Public polish & docs: reconcile README, runnable samples, getting-started + migration guides, Dokka | ⬜ |
| 6 | Maintenance process: this roadmap, Kotlin-bump runbook, logging + safe-error pass | 🟡 In progress |

## Compatibility & maintenance commitments

- **Kotlin version pinning.** Because JS/Native compile through `kotlin-compiler-embeddable`,
  a given KBuild release targets **exactly one** Kotlin version. The supported version is
  stated per release; the bump runbook (Phase 6) is run on every Kotlin release.
- **Consume what we produce.** Any publication format KBuild emits (Gradle Module Metadata,
  POM, KLIB layout) must also be resolvable by KBuild's own dependency resolver (§2).
- **Escape hatch.** Gradle remains buildable as a fallback and for regenerating the bootstrap
  dependency manifest until a native regenerator exists.
- **All targets compile in parallel (met).** Every enabled target — JVM, JS, and all native —
  builds concurrently. JVM compilation runs out-of-process via the **Build Tools API daemon
  execution strategy** (`DaemonJvmCompile`); natives are separate `konanc` subprocesses; the two
  in-process-capable compiles (Kotlin/JS and the commonMain metadata chain) share a single
  in-process permit (`InProcessCompileLock`) and the one that doesn't win it runs in a **forked
  kbuild JVM** (`CompileFork`), so they overlap while **exactly one** in-process compilation runs at
  any instant. The embeddable compiler's process-global state (`ApplicationManager`, `Disposer`,
  extension registries) is why only one may run in-process; everything else is its own process.

---

## Near-term priorities (recommended order)

1. **Close the publish loop** (§3) — 🟡 *Gradle-consumer half done.* A real Gradle KMP consumer now
   resolves a kbuild-published reactive end-to-end via the `.module` files (variant-aware, JVM/JS/native,
   compiles) — this caught and fixed the missing `published-with-gradle-metadata` POM marker. **Remaining:**
   confirm real-key GPG signing on a real machine (the benchmark's only caveat; the artifact set is
   Gradle-parity and now Gradle-resolvable).
2. **Compose Multiplatform** compiler plugin (§5) — required by most production UI apps/libs.
3. **Kotlin/Wasm** target (§1) — production web.
4. Phase 3 release + Phase 4 CI — make it consumable and continuously verified.
5. Single source of truth for dependencies (§8) — remove the 3-way drift risk.
6. BOM/platform alignment + `strictly`/`rejects` version algebra (§2) — beyond highest-wins+Kotlin-pinning.
7. De-flake the two occasionally-flaky self-tests (JS incremental-cache benchmark, Android keytool) —
   seen failing once then passing; flaky self-tests undermine confidence for a build tool.

_Done: version-conflict resolution (highest-wins + Kotlin first-party pinning), which unblocked the
clean reactive multiplatform publish; full-parallel target compilation (JVM via BTA daemon, JS +
metadata under a single in-process permit with the loser forking a kbuild JVM, natives via konanc);
parallel native target compilation; forked-JVM test isolation; CLI test summary + non-zero exit on
failure._
