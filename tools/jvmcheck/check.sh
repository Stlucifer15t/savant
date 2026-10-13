#!/usr/bin/env bash
#
# Compiles and runs the pure-JVM half of savant, plus its unit tests, without gradle and without an
# Android SDK. What it covers:
#
#   app/src/main/java/dev/savant/{domain,parsers,codecs,plugins,mcp,ai}   pure kotlin
#   app/src/main/java/dev/savant/data/history/EntryCodec.kt              the transcript codec
#   app/src/main/java/dev/savant/data/settings/{SavantSettings,SecretStore}.kt
#   app/src/test/java/**                                                 every unit test
#
# A file in one of those directories that imports android, androidx, hilt or javax.inject (today
# that is only `ai/SelectingAssistant.kt`, the hilt wiring) is skipped and named, so a new parser
# dropped into `parsers/` is picked up without editing this script, and an android import that
# creeps into the pure half is noticed instead of silently narrowing the check.
#
# The android half (room, data store, compose) is not compiled here — `.github/workflows/build-apk.yml`
# is the compiler of record for that. Run `tools/jvmcheck/setup.sh` once first.
#
# Usage: tools/jvmcheck/check.sh            compile + run every test
#        tools/jvmcheck/check.sh Json       only test classes whose name contains "Json"
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TC="${SAVANT_TOOLCHAIN:-${XDG_CACHE_HOME:-$HOME/.cache}/savant/toolchain}"
OUT="$ROOT/build/jvmcheck"
KOTLINC="$TC/kotlinc/bin/kotlinc"
JAVA="$TC/jdk/bin/java"
FILTER="${1:-}"

if [ ! -x "$KOTLINC" ] || [ ! -x "$JAVA" ]; then
  echo "toolchain missing at $TC — run tools/jvmcheck/setup.sh first" >&2
  exit 2
fi

CP="$TC/kotlinc/lib/kotlin-stdlib.jar:$TC/kotlinc/lib/kotlinx-coroutines-core-jvm.jar"
IMPURE='^import (android\.|androidx\.|dagger\.|javax\.inject\.|com\.google\.)'

pure_sources() {
  find "$ROOT/app/src/main/java/dev/savant/domain" \
       "$ROOT/app/src/main/java/dev/savant/parsers" \
       "$ROOT/app/src/main/java/dev/savant/codecs" \
       "$ROOT/app/src/main/java/dev/savant/plugins" \
       "$ROOT/app/src/main/java/dev/savant/mcp" \
       "$ROOT/app/src/main/java/dev/savant/ai" -name '*.kt' | sort
  echo "$ROOT/app/src/main/java/dev/savant/data/history/EntryCodec.kt"
  echo "$ROOT/app/src/main/java/dev/savant/data/settings/SavantSettings.kt"
  echo "$ROOT/app/src/main/java/dev/savant/data/settings/SecretStore.kt"
}

MAIN=()
SKIPPED=()
while IFS= read -r f; do
  if grep -qE "$IMPURE" "$f"; then SKIPPED+=("$f"); else MAIN+=("$f"); fi
done < <(pure_sources)

mapfile -t TESTS < <(find "$ROOT/app/src/test/java" -name '*.kt' | sort)
mapfile -t STUB < <(find "$ROOT/tools/jvmcheck/junit-stub/src" -name '*.kt' | sort)

rm -rf "$OUT/classes"
mkdir -p "$OUT/classes"

echo "== compiling ${#MAIN[@]} main sources + ${#TESTS[@]} test files"
for f in "${SKIPPED[@]:-}"; do
  [ -n "$f" ] && echo "   skipped (android imports): ${f#"$ROOT"/}"
done

JAVA_HOME="$TC/jdk" "$KOTLINC" \
  -jvm-target 17 \
  -nowarn \
  -cp "$CP" \
  -d "$OUT/classes" \
  "${MAIN[@]}" "${TESTS[@]}" "${STUB[@]}"

echo "== running${FILTER:+ (filter: $FILTER)}"
"$JAVA" -cp "$OUT/classes:$CP" dev.savant.jvmcheck.TestRunner "$OUT/classes" "$FILTER"
