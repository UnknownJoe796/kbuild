# KBuild Project Review

**Date:** 2026-01-01
**Reviewer:** Claude (automated evaluation)
**Scope:** Project goals, real-world usage in Reactive library, areas for improvement

---

## Goals vs Reality

| Goal | Status | Evidence |
|------|--------|----------|
| **Library, not framework** | ✅ Achieved | Reactive's Build.kt is 530 lines of imperative Kotlin with direct function calls |
| **Explicit over implicit** | ✅ Achieved | Every compilation step is Ctrl+Clickable Kotlin code |
| **Reactive by default** | ⚠️ Partial | Infrastructure exists (`DirectoryWatch`, reactive APIs), but Build.kt uses blocking variants |
| **Thin wrappers** | ✅ Achieved | Direct K2 compiler, Aether, javac access with documented workarounds |
| **No plugins** | ✅ Achieved | Just import and call - no plugin system |

## Target Use Cases

| Use Case | Status |
|----------|--------|
| Kotlin JVM server binaries with hot reload | ⚠️ Infrastructure ready, not demonstrated |
| Continuous builds with file watching | ⚠️ `DirectoryWatch` exists but has failing tests |
| Integration testing (server + tests together) | ✅ `ServerProcess` + `junitRun` integrated |
| Fine-grained control | ✅ Full control demonstrated in Reactive build |

---

## What's Working Well

1. **Comprehensive compilation support** - JVM, JS (K2 two-phase), Native all working with incremental builds

2. **Real validation** - Reactive library (a production KMP lib on Maven Central) successfully builds with KBuild

3. **Test suite maturity** - 476/478 tests passing (99.6%)

4. **Pragmatic engineering** - ByteBuddy patches for K2 compiler bugs with full documentation in `local/kotlin-js-incremental-npe-bug.md`

5. **KMP coordination** - `KmpProject`, `SourceSetHierarchy`, multi-target builds work correctly

6. **Publishing** - Maven Local/Central publishing with Gradle Module Metadata generation

7. **Modern Kotlin** - Uses context parameters (Kotlin 2.2.0+), sealed classes, coroutines throughout

8. **CLI maturity** - Interactive REPL, daemon mode, expression parser with dot notation

---

## What's Not Working

### Critical Issues

1. **DirectoryWatch reliability** - 2 failing tests on file watching
   - `scans initial files correctly()` - FAILED
   - `detects file changes when listener is active()` - FAILED
   - This is the core reactive feature - must be reliable

2. **Reactive usage not demonstrated** - Build.kt uses `*Blocking()` variants everywhere
   - The "reactive by default" goal isn't proven in real-world usage
   - No example of continuous builds reacting to file changes

3. **CI/CD story missing** - Reactive still uses `./gradlew` in GitHub Actions
   - No documented path for using kbuild in CI pipelines

### Improvements Needed

4. **Bootstrap (self-hosting)** - Build KBuild with KBuild
   - Would prove the system is complete
   - Would dogfood the tool daily

5. **Maven Central publication** - Currently only `mavenLocal`
   - Blocks adoption by others

6. **IDE support without Gradle** - Currently uses `GradleIdeBuild` bridge
   - Native IntelliJ support would complete the Gradle-free story

7. **Hot reload demo** - The "server binaries with hot reload" use case needs a working example
   - `ServerProcess` exists but no end-to-end demo

8. **Performance benchmarks** - Claims of being better than Gradle need data
   - Before/after metrics, especially for incremental builds

9. **Documentation gaps**
   - No tutorial for new projects
   - No migration guide from Gradle
   - No published API docs

### Code Quality

10. **Error messages** - Some compilation errors could be clearer about root cause

11. **Logging verbosity** - No configurable log levels for debugging builds

---

## Real-World Usage Analysis (Reactive Library)

**Build.kt Statistics:**
- 530 lines of Kotlin
- 11 major build functions
- 5 publishing strategies (JVM, JS, native variants, metadata)
- 4 compilation targets (JVM + 3 iOS native)

**Features Actually Used:**
- ✅ Multiplatform project configuration
- ✅ Incremental compilation with caching
- ✅ Kotlin compilation with context parameters
- ✅ K2 JS two-phase compilation (Sources → KLIB → JS)
- ✅ Kotlin/Native compilation
- ✅ JUnit5 programmatic test execution
- ✅ JAR building with manifests
- ✅ Maven dependency resolution
- ✅ Maven publishing with subartifacts
- ✅ Gradle IDE file generation

**Not Used in Practice:**
- ❌ Reactive/continuous build mode (uses blocking APIs)
- ❌ File watching (`DirectoryWatch`)
- ❌ Hot reload

---

## Comparison with Gradle

| Aspect | Gradle | KBuild |
|--------|--------|--------|
| Task dependencies | Explicit `dependsOn` | Implicit from data flow |
| Incremental builds | Complex up-to-date checks | Reactive file watching + compiler IC |
| Plugin behavior | Opaque black boxes | Functions you call |
| IDE support | Native | Requires Gradle bridge |
| Configuration | DSL → model | Plain Kotlin objects |
| Debugging | Read plugin source | Ctrl+Click your code |
| CI/CD | Standard support | Not documented |
| Adoption | Industry standard | Single-user |

---

## Summary

**Has kbuild achieved its goals?** Largely yes for the technical foundation:
- ✅ Library model works beautifully
- ✅ Explicit compilation is genuinely better than Gradle's magic
- ✅ Thin wrappers provide real control
- ⚠️ Reactive builds: infrastructure exists but not proven in practice

**What's holding it back?**
1. The reactive/continuous build story (the unique selling point) has reliability issues and no real demo
2. No self-hosting yet
3. CI/CD requires Gradle
4. Not published for others to use

**Recommendation:** Fix the DirectoryWatch tests and create a compelling "continuous build with hot reload" demo. That's the differentiating feature that would make this worth adopting over Gradle.

---

## Test Results Summary

```
Total: 478 tests
Passing: 478 (100%)
Failing: 0
```

**All tests pass** after fixing the DirectoryWatch glob pattern normalization issue.

---

## Progress Update (2026-01-01)

### Completed Items

1. **DirectoryWatch tests fixed** - Root cause was Java's PathMatcher glob behavior
2. **Reactive build demo** - Created `samples/reactive-build-demo/`
3. **CI/CD documentation** - Created `docs/ci-cd.md` and `.github/workflows/build.yml`
4. **Bootstrap (self-hosting)** - Created `Build.kt` at project root
5. **Hot reload demo** - Created `samples/hot-reload-demo/`
6. **Performance benchmarks** - Created `local/benchmarks/benchmark.sh`

### Remaining

- **Maven Central publication** - Requires credentials and Sonatype setup
