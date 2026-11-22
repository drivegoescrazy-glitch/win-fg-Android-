#!/usr/bin/env bash
# Build and sign the Win-FG launcher APK (arm64-v8a engine + Java app).
# Uses the Android SDK build tools directly, so no Gradle/network is required
# beyond the SDK/NDK toolchain itself.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
APP="$ANDROID_DIR/app"
APP_INFO="$APP/src/com/winfg/launcher/AppInfo.java"

VERSION="$(sed -n 's/.*VERSION_NAME = "\([^"]*\)".*/\1/p' "$APP_INFO")"
VERSION_CODE="$(sed -n 's/.*VERSION_CODE = \([0-9][0-9]*\).*/\1/p' "$APP_INFO")"
[ -n "$VERSION" ] && [ -n "$VERSION_CODE" ] || { echo "cannot read app version"; exit 1; }

# ── toolchain discovery ─────────────────────────────────────────────────────
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  :
elif [ -x "$ROOT/.toolchain/jdk/bin/javac" ]; then
  JAVA_HOME="$ROOT/.toolchain/jdk"
else
  echo "JAVA_HOME not set and .toolchain/jdk is missing"; exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$ROOT/.toolchain/sdk}}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-$ROOT/.toolchain/android-ndk-r27d}}"
CMAKE="${CMAKE:-$SDK/cmake/3.22.1/bin/cmake}"
[ -x "$CMAKE" ] || CMAKE="$(command -v cmake)"
[ -x "$CMAKE" ] || { echo "cmake not found"; exit 1; }
export PATH="$(dirname "$CMAKE"):$PATH"

BUILD_TOOLS="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
[ -x "$BUILD_TOOLS/aapt2" ] || { echo "build-tools 35.0.0 missing in $SDK"; exit 1; }
[ -f "$ANDROID_JAR" ] || { echo "platform android-35 missing in $SDK"; exit 1; }
[ -f "$NDK/build/cmake/android.toolchain.cmake" ] || { echo "NDK missing: $NDK"; exit 1; }

VULKAN_INCLUDE="${VULKAN_INCLUDE:-$ROOT/.toolchain/vk-headers/include}"
if [ ! -d "$VULKAN_INCLUDE/vulkan" ]; then
  echo "[apk] fetching Vulkan-Headers"
  mkdir -p "$ROOT/.toolchain"
  git clone --depth 1 https://github.com/KhronosGroup/Vulkan-Headers "$ROOT/.toolchain/vk-headers"
  VULKAN_INCLUDE="$ROOT/.toolchain/vk-headers/include"
fi

# ── native win-fg layer ─────────────────────────────────────────────────────
NATIVE_BUILD="$ROOT/build-android"
DESC="$(git -C "$ROOT" describe --tags --always --dirty --long 2>/dev/null || echo "v$VERSION-nogit")"
echo "[apk] building native layer ($DESC)"
"$CMAKE" -S "$ROOT" -B "$NATIVE_BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release \
  -DWINFG_GIT_DESCRIBE="$DESC" \
  -DVULKAN_INCLUDE="$VULKAN_INCLUDE"
"$CMAKE" --build "$NATIVE_BUILD" --target win_fg
[ -f "$NATIVE_BUILD/libwin_fg.so" ] || { echo "libwin_fg.so not produced"; exit 1; }

ASSETS="$APP/assets/win-fg"
mkdir -p "$ASSETS"
cp "$NATIVE_BUILD/libwin_fg.so" "$ASSETS/libwin_fg.so"
cp "$ROOT/manifest/VkLayer_win_framegen.json" "$ASSETS/VkLayer_win_framegen.json"
printf 'engine %s\nlauncher %s (build %s)\n' "$(cat "$ROOT/VERSION")" "$VERSION" "$DESC" \
  > "$ASSETS/VERSION"

# ── resources + Java + dex ──────────────────────────────────────────────────
BUILD="$ANDROID_DIR/build"
DIST="$ANDROID_DIR/dist"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$DIST"

echo "[apk] compiling resources"
"$BUILD_TOOLS/aapt2" compile --dir "$APP/res" -o "$BUILD/res.zip"
"$BUILD_TOOLS/aapt2" link \
  -o "$BUILD/unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  -R "$BUILD/res.zip" \
  --java "$BUILD/gen" \
  -A "$APP/assets" \
  --auto-add-overlay

echo "[apk] compiling Java"
find "$APP/src" -name '*.java' > "$BUILD/sources.txt"
find "$BUILD/gen" -name '*.java' >> "$BUILD/sources.txt"
"$JAVA_HOME/bin/javac" -source 11 -target 11 -encoding UTF-8 -Xlint:-options \
  -classpath "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "[apk] dexing"
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class' -print)
cp "$BUILD/dex/classes.dex" "$BUILD/classes.dex"
( cd "$BUILD" && zip -q -X unsigned.apk classes.dex )

# ── align + sign with the persistent update key ─────────────────────────────
KEYSTORE="$ANDROID_DIR/keys/winfg-release.jks"
KEY_PROPS="$ANDROID_DIR/keys/keystore.properties"
[ -f "$KEYSTORE" ] || { echo "release keystore missing: $KEYSTORE"; exit 1; }
STORE_PASS="$(sed -n 's/^storePassword=//p' "$KEY_PROPS")"
KEY_PASS="$(sed -n 's/^keyPassword=//p' "$KEY_PROPS")"
KEY_ALIAS="$(sed -n 's/^keyAlias=//p' "$KEY_PROPS")"

"$BUILD_TOOLS/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
OUT="$DIST/win-fg-v$VERSION.apk"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
  --out "$OUT" "$BUILD/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --print-certs "$OUT" > "$BUILD/cert.txt"
cat "$BUILD/cert.txt"

echo "[apk] built $OUT (versionCode $VERSION_CODE)"
