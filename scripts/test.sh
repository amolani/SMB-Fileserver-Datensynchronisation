#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
./scripts/build.sh
command -v rsync >/dev/null
mkdir -p build/test-classes
find src/test/java -name '*.java' -print | sort > build/test-sources.txt
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -cp build/classes -d build/test-classes @build/test-sources.txt
java -ea -cp build/classes:build/test-classes org.example.TestSuite
