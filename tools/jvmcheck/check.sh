#!/usr/bin/env bash
#
# Compiles and runs the pure-JVM half of savant, plus its unit tests, without gradle and without an
# Android SDK. What it covers:
#
#   app/src/main/java/dev/savant/{domain,parsers,codecs,ai}   pure kotlin, no android imports
#   app/src/main/java/dev/savant/data/history/EntryCodec.kt   the transcript codec, also pure
#   app/src/test/java/**                                      every unit test
#
# The android half (room, data store, compose) is not compiled here — `.github/workflows/build-apk.yml`
# is the compiler of record for that. Run `tools/jvmcheck/setup.sh` once first.
#
# Usage: tools/jvmcheck/check.sh
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TC="${SAVANT_TOOLCHAIN:-$ROOT/tools/jvmcheck/.toolchain}"
OUT="$ROOT/tools/jvmcheck/build"
KOTLINC="$TC/kotlinc/bin/kotlinc"
JAVA="$TC/jdk/bin/java"

if [ ! -x "$KOTLINC" ] || [ ! -x "$JAVA" ]; then
  echo "toolchain missing — run tools/jvmcheck/setup.sh first" >&2
  exit 2
fi

CP="$TC/kotlinc/lib/kotlin-stdlib.jar:$TC/kotlinc/lib/kotlinx-coroutines-core-jvm.jar"

main_sources() {
  find "$ROOT/app/src/main/java/dev/savant/domain" \
       "$ROOT/app/src/main/java/dev/savant/parsers" \
       "$ROOT/app/src/main/java/dev/savant/codecs" \
       "$ROOT/app/src/main/java/dev/savant/ai" -name '*.kt'
  echo "$ROOT/app/src/main/java/dev/savant/data/history/EntryCodec.kt"
  echo "$ROOT/app/src/main/java/dev/savant/data/settings/SavantSettings.kt"
}

rm -rf "$OUT/classes"
mkdir -p "$OUT/classes"

echo "== compiling $(main_sources | wc -l) sources, $(find "$ROOT/app/src/test" -name '*.kt' | wc -l) test files"
JAVA_HOME="$TC/jdk" "$KOTLINC" \
  -jvm-target 17 \
  -nowarn \
  -cp "$CP" \
  -d "$OUT/classes" \
  $(main_sources) \
  $(find "$ROOT/app/src/test/java" -name '*.kt') \
  $(find "$ROOT/tools/jvmcheck/junit-stub/src" -name '*.kt')

echo "== running"
"$JAVA" -cp "$OUT/classes:$CP" dev.savant.jvmcheck.TestRunner "$OUT/classes"
