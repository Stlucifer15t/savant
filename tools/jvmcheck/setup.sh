#!/usr/bin/env bash
#
# Provisions the tools `tools/jvmcheck/check.sh` needs, into $SAVANT_TOOLCHAIN
# (default ~/.cache/savant/toolchain — it is ~300 mb and belongs to the machine, not the repository).
#
# The dev sandbox has no Android SDK, no JDK and no route to maven central or google maven, so
# everything here comes from the two hosts it can reach:
#
#   * the jdk        — pypi `jdk4py` (a temurin 17 runtime in a python wheel)
#   * kotlinc 2.0.21 — the npm package `kotlin-compiler`, the official distribution tarball
#   * coroutines     — from inside that same kotlinc distribution (lib/kotlinx-coroutines-core-jvm.jar)
#
# Versions are pinned to the ones gradle builds with (gradle/libs.versions.toml), so a green run
# here means something. Idempotent: an existing toolchain is reused.
set -euo pipefail

TC="${SAVANT_TOOLCHAIN:-${XDG_CACHE_HOME:-$HOME/.cache}/savant/toolchain}"
KOTLIN_VERSION="2.0.21"
JDK4PY_VERSION="17.0.9.2"

mkdir -p "$TC"
cd "$TC"

if [ ! -x "$TC/kotlinc/bin/kotlinc" ]; then
  echo "== kotlin compiler $KOTLIN_VERSION (npm kotlin-compiler)"
  curl -sSL -o kotlin-compiler.tgz \
    "https://registry.npmjs.org/kotlin-compiler/-/kotlin-compiler-${KOTLIN_VERSION}.tgz"
  tar xzf kotlin-compiler.tgz
  mv package kotlinc
  rm -f kotlin-compiler.tgz
fi

if [ ! -x "$TC/jdk/bin/java" ]; then
  echo "== jdk $JDK4PY_VERSION (pypi jdk4py)"
  WHEEL=$(curl -sS "https://pypi.org/pypi/jdk4py/${JDK4PY_VERSION}/json" | python3 -c '
import json, sys
release = json.load(sys.stdin)
for url in release["urls"]:
    if "x86_64" in url["filename"] and "linux" in url["filename"]:
        print(url["url"])
        break
')
  [ -n "$WHEEL" ] || { echo "no linux x86_64 jdk4py wheel for $JDK4PY_VERSION"; exit 1; }
  curl -sSL -o jdk.whl "$WHEEL"
  mkdir -p jdk-unpack
  unzip -q -o jdk.whl -d jdk-unpack
  mv jdk-unpack/jdk4py/java-runtime jdk
  rm -rf jdk.whl jdk-unpack
  chmod +x jdk/bin/*
fi

echo "== java: $("$TC/jdk/bin/java" -version 2>&1 | head -1)"
echo "== kotlinc: $(JAVA_HOME="$TC/jdk" "$TC/kotlinc/bin/kotlinc" -version 2>&1 | tail -1)"
echo "ready: $TC"
