#!/usr/bin/env sh
# Builds Shiori with the official Kotlin compiler only (no Gradle, no downloaded library).
#   KOTLINC=/path/to/kotlinc ./build.sh        build/shiori.jar, then run the tests
set -eu
KOTLINC="${KOTLINC:-kotlinc}"
cd "$(dirname "$0")"
mkdir -p build
"$KOTLINC" src/main/kotlin -jvm-target 21 -Werror -include-runtime -d build/shiori.jar
"$KOTLINC" src/main/kotlin src/test/kotlin -jvm-target 21 -Werror -include-runtime -d build/shiori-tests.jar
java -cp build/shiori-tests.jar shiori.CheckKt
