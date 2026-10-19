#!/usr/bin/env bash
# 构建 Zcode RC APK（JDK + Android SDK 的 aapt2/d8，无 Gradle、无第三方依赖）
#
# 依赖（位于 E:\.ZCode\toolchain，见仓库根 README）：
#   build-tools r34 (aapt2.exe / lib/d8.jar / zipalign.exe / lib/apksigner.jar)
#   platforms;android-35 (android.jar)
#
# 用法：./build.sh   产物在 build/zremote.apk
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

# 构建模式：
#   默认        → 发行版 ZRemote（原创图标、keystore-release 签名）
#   LOCAL=1    → 本地自用版 ZCode RC（官方图标需 branding-local/，keystore.jks 签名）
# 工具链：默认取项目同级 ../toolchain，可用 ZREMOTE_TOOLCHAIN 环境变量覆盖
LOCAL="${LOCAL:-0}"
TC="${ZREMOTE_TOOLCHAIN:-$HERE/../toolchain}"
# build-tools 解压目录名可能是 android-14 / android-14.0.x 等，自动探测
BT="$(find "$TC" -maxdepth 2 -type d \( -name 'android-1[0-9]*' -o -name 'build-tools' \) | head -1)"
ANDROID_JAR="$(find "$TC" -maxdepth 3 -name 'android.jar' | head -1)"
AAPT2="$BT/aapt2.exe"
D8_JAR="$BT/lib/d8.jar"
ZIPALIGN="$BT/zipalign.exe"
APKSIGNER_JAR="$BT/lib/apksigner.jar"

for f in "$ANDROID_JAR" "$AAPT2" "$D8_JAR" "$ZIPALIGN" "$APKSIGNER_JAR"; do
  [ -f "$f" ] || { echo "缺少构建工具：$f" >&2; exit 1; }
done

OUT="${OUT:-build}"
# javac 诊断输出统一英文，避免 GBK 乱码
export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US ${JAVA_TOOL_OPTIONS:-}"
# PATH 里只有 java/javac 垫片，keytool 需从 java.home 解析
JAVA_HOME_DIR="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.home = //p')"
KEYTOOL="$(cygpath -u "$JAVA_HOME_DIR" 2>/dev/null || echo "$JAVA_HOME_DIR")/bin/keytool"
command -v keytool >/dev/null 2>&1 && KEYTOOL=keytool
[ -x "$KEYTOOL" ] || [ -f "$KEYTOOL.exe" ] || { echo "找不到 keytool：$KEYTOOL" >&2; exit 1; }
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "== 1/6 aapt2 compile+link"
RES_DIR="$HERE/res"
MANIFEST="$HERE/AndroidManifest.xml"
if [ "$LOCAL" = "1" ]; then
  MANIFEST="$OUT/AndroidManifest.local.xml"
  sed 's/android:label="ZRemote"/android:label="ZCode RC"/g' "$HERE/AndroidManifest.xml" > "$MANIFEST"
  if [ -d "$HERE/branding-local" ]; then
    RES_DIR="$OUT/res-local"
    rm -rf "$RES_DIR"; cp -r "$HERE/res" "$RES_DIR"
    for d in mipmap-mdpi mipmap-hdpi mipmap-xhdpi mipmap-xxhdpi mipmap-xxxhdpi; do
      cp -f "$HERE/branding-local/$d/"*.png "$RES_DIR/$d/" 2>/dev/null || true
    done
    [ -f "$HERE/branding-local/colors.xml" ] && \
      cp -f "$HERE/branding-local/colors.xml" "$RES_DIR/values/colors.xml"
  fi
fi
LINK_ARGS=(-o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest "$MANIFEST"
  --min-sdk-version 29 --target-sdk-version 33)
if [ -d "$RES_DIR" ]; then
  "$AAPT2" compile --dir "$RES_DIR" -o "$OUT/res.zip"
  LINK_ARGS+=("$OUT/res.zip" --java "$OUT/gen")
fi
if [ -d assets ]; then
  LINK_ARGS+=(-A assets)
fi
"$AAPT2" link "${LINK_ARGS[@]}"

echo "== 2/6 javac"
{ find src -name '*.java'
  [ -d "$OUT/gen" ] && find "$OUT/gen" -name '*.java'; } > "$OUT/sources.txt"
javac -encoding UTF-8 --release 8 -nowarn \
  -cp "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== 3/6 d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
# r34 自带的 d8 8.2.2-dev 有内部类 NPE bug，改用新版 r8 jar（E:\.ZCode\toolchain\r8-8.5.35.jar）
R8_JAR="$TC/r8-8.5.35.jar"
[ -f "$R8_JAR" ] || R8_JAR="$D8_JAR"
java -cp "$R8_JAR" com.android.tools.r8.D8 \
  --min-api 29 --lib "$ANDROID_JAR" --output "$OUT/dex" @"$OUT/classes.txt"

echo "== 4/6 注入 classes.dex"
mkdir -p tools/build
javac -d tools/build tools/ZipAppend.java
java -cp tools/build ZipAppend "$OUT/base.apk" "$OUT/dex/classes.dex" classes.dex

echo "== 5/6 zipalign"
"$ZIPALIGN" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== 6/6 签名"
if [ "$LOCAL" = "1" ]; then
  KS_FILE="keystore.jks"; KS_ALIAS="zremote"; KS_PASS="zrczrczrc"
  KS_DNAME="CN=ZCode RC, O=DSHA, C=CN"
  OUT_APK="$OUT/zremote-local.apk"
  if [ ! -f keystore.jks ]; then
    "$KEYTOOL" -genkeypair -keystore keystore.jks -alias zremote -keyalg RSA -keysize 2048 \
      -validity 10000 -storepass zrczrczrc -keypass zrczrczrc \
      -dname "$KS_DNAME" >/dev/null 2>&1
  fi
else
  KS_FILE="keystore-release.jks"; KS_ALIAS="zremote-release"; KS_PASS="ZRemote-rel-2026"
  KS_DNAME="CN=ZRemote, O=DSHA, C=CN"
  OUT_APK="$OUT/zremote-release.apk"
  if [ ! -f keystore-release.jks ]; then
    "$KEYTOOL" -genkeypair -keystore keystore-release.jks -alias zremote-release \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass "$KS_PASS" -keypass "$KS_PASS" \
      -dname "$KS_DNAME" >/dev/null 2>&1
    echo "  首次构建：生成发行签名 keystore-release.jks（务必备份，凭据见 凭据_签名.md）"
  fi
fi
java -jar "$APKSIGNER_JAR" sign \
  --ks "$KS_FILE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$OUT_APK" "$OUT/aligned.apk"
java -jar "$APKSIGNER_JAR" verify --print-certs "$OUT_APK" | head -3
ls -lh "$OUT_APK"
echo "BUILD_OK"
