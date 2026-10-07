#!/usr/bin/env bash
# Builds the app and the root module zip (Magisk, KernelSU, APatch) into dist/.
set -e
cd "$(dirname "$0")"
./gradlew --console=plain -q :app:assembleRelease
rm -rf dist && mkdir -p dist/module
cp app/build/outputs/apk/release/app-release.apk dist/HilightLink.apk
cp -r module/. dist/module/
cp dist/HilightLink.apk dist/module/
python -c "import shutil; shutil.make_archive('dist/HilightLink-module', 'zip', 'dist/module')"
rm -rf dist/module
ls -la dist
