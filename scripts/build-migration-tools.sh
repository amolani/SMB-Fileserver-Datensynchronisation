#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
./scripts/build.sh
mkdir -p build/migration-classes
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -cp build/classes \
    -d build/migration-classes tools/migration/org/example/MigrationCheckpoint.java
jar --create --file build/filesync-migration-tools.jar -C build/migration-classes .
