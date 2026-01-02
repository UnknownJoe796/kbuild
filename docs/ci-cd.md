# Using KBuild in CI/CD Pipelines

This guide explains how to integrate KBuild into continuous integration and deployment pipelines.

## Quick Start

### GitHub Actions

```yaml
name: Build and Test

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Cache Maven dependencies
        uses: actions/cache@v4
        with:
          path: ~/.maven-cache
          key: ${{ runner.os }}-maven-${{ hashFiles('Build.kt') }}
          restore-keys: ${{ runner.os }}-maven-

      - name: Cache KBuild
        uses: actions/cache@v4
        with:
          path: ~/.kbuild
          key: ${{ runner.os }}-kbuild-${{ hashFiles('Build.kt') }}

      - name: Install KBuild
        run: |
          # Option 1: Download pre-built JAR
          curl -L https://github.com/user/kbuild/releases/latest/download/kbuild.jar -o ~/.kbuild/kbuild.jar
          echo 'alias kbuild="java -jar ~/.kbuild/kbuild.jar"' >> ~/.bashrc

          # Option 2: Build from source (if not published)
          # git clone https://github.com/user/kbuild.git ~/.kbuild-src
          # cd ~/.kbuild-src && ./gradlew installDist
          # export PATH="$PATH:~/.kbuild-src/build/install/kbuild/bin"

      - name: Build
        run: kbuild Build.build

      - name: Test
        run: kbuild Build.test

      - name: Package
        run: kbuild Build.package
```

### GitLab CI

```yaml
stages:
  - build
  - test

variables:
  MAVEN_CACHE: "$CI_PROJECT_DIR/.maven-cache"

cache:
  paths:
    - .maven-cache/
    - .kbuild/

build:
  stage: build
  image: eclipse-temurin:17
  script:
    - ./scripts/install-kbuild.sh
    - kbuild Build.compile
  artifacts:
    paths:
      - build/

test:
  stage: test
  image: eclipse-temurin:17
  script:
    - ./scripts/install-kbuild.sh
    - kbuild Build.test
  dependencies:
    - build
```

## Installation Methods

### Method 1: Pre-built JAR (Recommended)

Download the all-in-one JAR from releases:

```bash
mkdir -p ~/.kbuild
curl -L https://github.com/user/kbuild/releases/latest/download/kbuild.jar -o ~/.kbuild/kbuild.jar

# Create wrapper script
cat > /usr/local/bin/kbuild << 'EOF'
#!/bin/bash
java -jar ~/.kbuild/kbuild.jar "$@"
EOF
chmod +x /usr/local/bin/kbuild
```

### Method 2: Build from Source

```bash
git clone https://github.com/user/kbuild.git ~/.kbuild-src
cd ~/.kbuild-src
./gradlew installDist
export PATH="$PATH:$HOME/.kbuild-src/build/install/kbuild/bin"
```

### Method 3: Docker Image

```dockerfile
FROM eclipse-temurin:17-jdk

# Install kbuild
RUN mkdir -p /opt/kbuild && \
    curl -L https://github.com/user/kbuild/releases/latest/download/kbuild.jar -o /opt/kbuild/kbuild.jar

# Create wrapper
RUN echo '#!/bin/bash\njava -jar /opt/kbuild/kbuild.jar "$@"' > /usr/local/bin/kbuild && \
    chmod +x /usr/local/bin/kbuild

WORKDIR /app
ENTRYPOINT ["kbuild"]
```

Use in CI:

```yaml
build:
  image: your-registry/kbuild:latest
  script:
    - kbuild Build.build
```

## Build.kt for CI/CD

Structure your Build.kt with CI-friendly targets:

```kotlin
object Build {
    // Standard targets
    fun compile() { /* ... */ }
    fun test() { /* ... */ }
    fun package() { /* ... */ }
    fun publish() { /* ... */ }

    // CI-specific targets
    fun ci() {
        clean()
        compile()
        test()
        package()
    }

    fun ciPublish() {
        ci()
        publish()
    }

    // Report generation for CI
    fun generateReports() {
        // JUnit XML reports for test results
        // Coverage reports
        // Dependency reports
    }
}
```

## Caching Strategies

KBuild uses several cache directories that should be preserved across builds:

| Directory | Purpose | Cache Key |
|-----------|---------|-----------|
| `~/.maven-cache` | Downloaded Maven dependencies | `hashFiles('Build.kt')` |
| `build/cache` | Incremental compilation cache | `hashFiles('src/**/*.kt')` |
| `~/.kbuild` | KBuild installation | Static |

### GitHub Actions Caching

```yaml
- name: Cache dependencies
  uses: actions/cache@v4
  with:
    path: |
      ~/.maven-cache
      build/cache
    key: ${{ runner.os }}-kbuild-${{ hashFiles('Build.kt', 'src/**/*.kt') }}
    restore-keys: |
      ${{ runner.os }}-kbuild-${{ hashFiles('Build.kt') }}
      ${{ runner.os }}-kbuild-
```

## Environment Variables

KBuild respects these environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `KBUILD_VERBOSE` | Enable verbose output | `false` |
| `KBUILD_CACHE_DIR` | Cache directory location | `build/cache` |
| `MAVEN_CACHE_DIR` | Maven cache location | `~/.maven-cache` |
| `JAVA_HOME` | JDK location | System default |

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Compilation error |
| 2 | Test failure |
| 3 | Configuration error |
| 4 | Dependency resolution error |

## Parallel Builds

For multi-module projects, parallelize at the CI level:

```yaml
jobs:
  build-jvm:
    runs-on: ubuntu-latest
    steps:
      - run: kbuild Build.compileJvm

  build-js:
    runs-on: ubuntu-latest
    steps:
      - run: kbuild Build.compileJs

  build-native:
    runs-on: macos-latest
    steps:
      - run: kbuild Build.compileNative

  test:
    needs: [build-jvm, build-js, build-native]
    runs-on: ubuntu-latest
    steps:
      - run: kbuild Build.testAll
```

## Comparison with Gradle

| Aspect | Gradle | KBuild |
|--------|--------|--------|
| Install size | ~100MB | ~50MB JAR |
| Cold start | 5-10s | 1-2s |
| Incremental | Up-to-date checks | Reactive + compiler IC |
| Configuration | Groovy/Kotlin DSL | Plain Kotlin |
| Cache restore | Gradle cache action | Standard file cache |

## Troubleshooting

### Build fails with "class not found"

Ensure Build.kt is in the project root and properly structured:

```kotlin
// Build.kt - must be at project root
object Build {
    // ...
}
```

### Dependency resolution fails

Check network access and cache:

```bash
# Clear cache and retry
rm -rf ~/.maven-cache
kbuild Build.compile
```

### Incremental compilation not working

Clear the build cache:

```bash
rm -rf build/cache
kbuild Build.compile
```

## Sample Workflows

### Release Workflow

```yaml
name: Release

on:
  push:
    tags:
      - 'v*'

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Build release
        run: |
          kbuild Build.clean
          kbuild Build.build
          kbuild Build.package

      - name: Publish to Maven Central
        env:
          MAVEN_USERNAME: ${{ secrets.MAVEN_USERNAME }}
          MAVEN_PASSWORD: ${{ secrets.MAVEN_PASSWORD }}
          GPG_PRIVATE_KEY: ${{ secrets.GPG_PRIVATE_KEY }}
          GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
        run: kbuild Build.publishToMavenCentral
```

### PR Validation

```yaml
name: PR Check

on: pull_request

jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Build and test
        run: |
          kbuild Build.compile
          kbuild Build.test

      - name: Check formatting
        run: kbuild Build.checkFormat
```
