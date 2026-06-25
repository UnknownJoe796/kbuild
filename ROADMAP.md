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
- ✅ Full test suite green (junitRun parity with Gradle)

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
| Parallel target compilation | ⬜ | Targets build sequentially today |

## 2. Dependency resolution & KMP ecosystem compatibility

> **This is the headline gap.** KBuild can *produce* ecosystem-compatible artifacts but
> cannot yet *consume* arbitrary published KMP libraries correctly.

| Capability | Status | Notes |
|---|---|---|
| Maven dependency resolution (Aether) | ✅ | Transitive, scope-aware, cached |
| **Generate** Gradle Module Metadata (`.module`) | ✅ | `KmpPublish` writes variants + `available-at`; our libs are Gradle-consumable |
| **Read/parse** Gradle Module Metadata for resolution | ⬜ | **Critical.** Resolution today guesses artifacts by convention (`-jvm`, `-js.klib`, `-{target}.klib`) instead of reading the published `.module` |
| Variant-aware resolution (attributes → artifact) | ⬜ | Must match on `org.jetbrains.kotlin.platform.type`, native target, usage/category attributes |
| `available-at` redirects | ⬜ | Real KMP libs point a root module to per-target modules at *different* coordinates; convention-guessing can't follow these |
| KLIB resolution (JS/Native) | 🟡 | Works for conventionally-named artifacts; should flow from module metadata |
| Version catalogs / BOM / platform alignment | ⬜ | Needed for realistic dependency graphs |
| Local dependency substitution (dev builds) | ⬜ | Override a published dep with a local build |

### What "read their format" concretely requires

To be a first-class KMP consumer, dependency resolution must:

1. Fetch the root `.module` (Gradle Module Metadata v1.1) alongside the POM.
2. Select the correct **variant** for the requested target by matching Gradle attributes
   (platform type, native target, usage, category, and `org.gradle.jvm.environment`).
3. Follow **`available-at`** to the real per-target module coordinate and resolve *its*
   metadata recursively.
4. Read each variant's `files` and `dependencies` (including `dependencyConstraints`)
   rather than inferring artifact names.
5. Fall back gracefully to POM-only resolution for non-KMP libraries.

Until this lands, KBuild can only reliably consume KMP libraries that happen to follow
naming conventions — which excludes much of the real ecosystem (kotlinx, Compose, Ktor,
many Lightning Kite libraries).

## 3. Library authoring & publishing

| Capability | Status | Notes |
|---|---|---|
| Maven / S3 publishing | ✅ | `MavenDeploy`, `S3MavenPublish` |
| Gradle Module Metadata generation | ✅ | See §2 |
| POM generation (scope-aware) | ✅ | `PomBuild` |
| GPG signing | ✅ | `GpgSigner`, keychain-backed credentials |
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
| Self-host bootstrap | ✅ | From-source, no Gradle; S3 fast-path |
| Output/build cache by input hash | ⬜ | Make clean builds as fast as incremental |
| Parallel target compilation | ⬜ | Coroutine-based fan-out (the reactive model supports it) |
| Compiler/daemon warm-up | ⬜ | Hide first-build init cost |
| Remote build cache | 🧭 | Share results across machines |

## 8. Quality, testing & maintenance

| Capability | Status | Notes |
|---|---|---|
| JUnit5 execution (`junitRun`) | ✅ | Parity with Gradle |
| Browser / Node JS test execution | ✅ | `BrowserTestRunner`, `NodeJsTestRunner` |
| Native test execution | ✅ | `KotlinNativeTestRunner` |
| CI that dogfoods kbuild | ⬜ | Self-host build lane + unit/integration split + Kotlin-version matrix |
| junitRun test-classpath isolation | ⬜ | Run tests from an isolated copy so a test can't corrupt the live classpath |
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

---

## Near-term priorities (recommended order)

1. **Read Gradle Module Metadata** for variant-aware KMP resolution (§2) — unblocks consuming
   the real ecosystem; the single highest-leverage gap.
2. **Compose Multiplatform** compiler plugin (§5) — required by most production UI apps/libs.
3. **Kotlin/Wasm** target (§1) — production web.
4. Phase 3 release + Phase 4 CI — make it consumable and continuously verified.
5. Single source of truth for dependencies (§8) — remove the 3-way drift risk.
