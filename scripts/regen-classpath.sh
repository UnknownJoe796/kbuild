#!/usr/bin/env bash
# Regenerate bootstrap/classpath.txt (the flat transitive dependency manifest) from
# bootstrap/dependencies.txt via Gradle.
#
# Why this exists: the from-source bootstrap (bootstrap/bootstrap.sh) compiles kbuild against a
# flat list of jars with no resolver. That list must be regenerated whenever direct dependencies
# (bootstrap/dependencies.txt) change. Gradle does the transitive resolution; this script maps each
# resolved jar in the Gradle cache back to a "group:artifact:version[:classifier] repo-url" line.
#
# Repo mapping heuristic: com.lightningkite artifacts come from the LK S3 repo, everything else from
# Maven Central. sisu-guice keeps its required 'no_aop' classifier (derived from the jar filename).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT="$PROJECT_DIR/bootstrap/classpath.txt"
CENTRAL="https://repo1.maven.org/maven2"
LK_S3="https://lightningkite-maven.s3.us-west-2.amazonaws.com"

cd "$PROJECT_DIR"
echo "Resolving runtime classpath via Gradle..." >&2
CP="$(./gradlew -q printClasspath | tail -1)"

{
  cat <<'HEADER'
# KBuild transitive dependency manifest — GENERATED, do not hand-edit dependency choices here.
# Direct dependencies are declared in bootstrap/dependencies.txt (the single source of truth);
# this flat transitive list is derived from them via scripts/regen-classpath.sh (uses Gradle).
# Format: group:artifact:version[:classifier] repo-base-url
# Generated from Gradle's runtimeClasspath resolved configuration.
# URL pattern: {repo}/{group/with/slashes}/{artifact}/{version}/{artifact}-{version}[-{classifier}].jar
# Repos: Maven Central = https://repo1.maven.org/maven2
#        LK S3         = https://lightningkite-maven.s3.us-west-2.amazonaws.com
# NOTE: sisu-guice carries the 'no_aop' classifier — omitting it fetches the wrong jar and Aether fails.
HEADER
  echo ""

  IFS=':' read -ra JARS <<< "$CP"
  for jar in "${JARS[@]}"; do
    [ -f "$jar" ] || continue
    # Gradle cache layout: .../files-2.1/<group>/<artifact>/<version>/<hash>/<file>.jar
    if [[ "$jar" == *"/files-2.1/"* ]]; then
      rest="${jar#*/files-2.1/}"
      group="${rest%%/*}"; rest="${rest#*/}"
      artifact="${rest%%/*}"; rest="${rest#*/}"
      version="${rest%%/*}"
      file="$(basename "$jar" .jar)"
      # classifier = trailing suffix after "<artifact>-<version>-"
      classifier=""
      prefix="${artifact}-${version}-"
      [[ "$file" == "$prefix"* ]] && classifier="${file#"$prefix"}"
      coord="$group:$artifact:$version"
      [ -n "$classifier" ] && coord="$coord:$classifier"
    else
      # mavenLocal (~/.m2) layout: .../repository/<group/dirs>/<artifact>/<version>/<file>.jar
      rel="${jar#*/repository/}"
      version="$(basename "$(dirname "$jar")")"
      artifact="$(basename "$(dirname "$(dirname "$jar")")")"
      group="$(dirname "$(dirname "$(dirname "$rel")")")"; group="${group//\//.}"
      coord="$group:$artifact:$version"
    fi
    case "$group" in
      com.lightningkite) repo="$LK_S3" ;;
      *) repo="$CENTRAL" ;;
    esac
    echo "$coord $repo"
  done
} > "$OUT"

echo "Wrote $(grep -c ':' "$OUT") entries to $OUT" >&2
