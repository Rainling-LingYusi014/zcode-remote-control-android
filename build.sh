#!/usr/bin/env bash
# 构建 Zcode RC APK（JDK + Android SDK 的 aapt2/d8，无 Gradle；第三方库仅 zxing-core 二维码解码）
#
# 依赖（默认取仓库同级目录 ../toolchain，可用环境变量 ZREMOTE_TOOLCHAIN 覆盖；见 README）：
#   build-tools r34 (aapt2.exe / lib/d8.jar / zipalign.exe / lib/apksigner.jar)
#   platforms;android-35 (android.jar)
#   zxing-core-3.5.3.jar（缺失时脚本自动从镜像下载）
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

# 二维码识别库（扫码配对用，Apache-2.0，约 600KB）；SHA-256 固定为官方发行值，
# 每次构建校验，防镜像劫持/缓存投毒产出带毒 APK（审查 #15：SHA-1 → SHA-256）
ZXING_JAR="$TC/zxing-core-3.5.3.jar"
ZXING_SHA256="8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82"
if [ ! -f "$ZXING_JAR" ]; then
  echo "== 0/6 下载 zxing-core-3.5.3.jar"
  curl -fL --retry 2 -o "$ZXING_JAR" \
    "https://maven.aliyun.com/repository/public/com/google/zxing/core/3.5.3/core-3.5.3.jar" ||
    curl -fL --retry 2 -o "$ZXING_JAR" \
      "https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar"
fi
[ -f "$ZXING_JAR" ] || { echo "缺少依赖库：$ZXING_JAR" >&2; exit 1; }
echo "$ZXING_SHA256  $ZXING_JAR" | sha256sum -c - >/dev/null 2>&1 ||
  { echo "zxing-core-3.5.3.jar 校验失败：与官方 SHA-256 不符，请删除后重新下载" >&2; exit 1; }

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
# classpath 拼两条以上时 MSYS 不再自动转 Windows 路径，必须显式归一化（javac 是原生 exe）
JAVAC_CP="$(cygpath -m "$ANDROID_JAR");$(cygpath -m "$ZXING_JAR")"
javac -encoding UTF-8 --release 8 -nowarn \
  -cp "$JAVAC_CP" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== 3/6 d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
# r34 自带的 d8 8.2.2-dev 有内部类 NPE bug，改用 toolchain 目录下的新版 r8 jar（r8-8.5.35.jar）
R8_JAR="$TC/r8-8.5.35.jar"
[ -f "$R8_JAR" ] || R8_JAR="$D8_JAR"
java -cp "$R8_JAR" com.android.tools.r8.D8 \
  --min-api 29 --lib "$ANDROID_JAR" --output "$OUT/dex" \
  "$ZXING_JAR" @"$OUT/classes.txt"

echo "== 4/6 注入 classes.dex"
mkdir -p tools/build
javac -d tools/build tools/ZipAppend.java
java -cp tools/build ZipAppend "$OUT/base.apk" "$OUT/dex/classes.dex" classes.dex

echo "== 5/6 zipalign"
"$ZIPALIGN" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== 6/6 签名"
# 签名密码不入库：优先环境变量 ZREMOTE_KS_PASS，其次本地密码文件（已 gitignore），首次构建随机生成
if [ "$LOCAL" = "1" ]; then
  KS_FILE="keystore.jks"; KS_ALIAS="zremote"; KS_PASS_FILE="$HERE/.keystore-pass.local"
  KS_DNAME="CN=ZCode RC, O=DSHA, C=CN"
  OUT_APK="$OUT/zremote-local.apk"
else
  KS_FILE="keystore-release.jks"; KS_ALIAS="zremote-release"; KS_PASS_FILE="$HERE/.keystore-pass"
  KS_DNAME="CN=ZRemote, O=DSHA, C=CN"
  OUT_APK="$OUT/zremote-release.apk"
fi
KS_PASS="${ZREMOTE_KS_PASS:-$(cat "$KS_PASS_FILE" 2>/dev/null || true)}"
# 密码文件只给自己可读（审查 #15；Git Bash 下的 chmod 尽力而为）
[ -f "$KS_PASS_FILE" ] && chmod 600 "$KS_PASS_FILE" 2>/dev/null || true
if [ ! -f "$KS_FILE" ]; then
  if [ -z "$KS_PASS" ]; then
    KS_PASS="$(openssl rand -hex 16 2>/dev/null || head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    printf '%s' "$KS_PASS" > "$KS_PASS_FILE"
    chmod 600 "$KS_PASS_FILE" 2>/dev/null || true
  fi
  "$KEYTOOL" -genkeypair -keystore "$KS_FILE" -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "$KS_DNAME" >/dev/null 2>&1
  [ "$LOCAL" = "1" ] || echo "  首次构建：生成发行签名 $KS_FILE（密码已存 $KS_PASS_FILE，务必冷备份，发布后不可更换）"
else
  [ -n "$KS_PASS" ] || { echo "缺少签名密码：设环境变量 ZREMOTE_KS_PASS 或写入 $KS_PASS_FILE" >&2; exit 1; }
fi
java -jar "$APKSIGNER_JAR" sign \
  --ks "$KS_FILE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$OUT_APK" "$OUT/aligned.apk"
java -jar "$APKSIGNER_JAR" verify --print-certs "$OUT_APK" | head -3
ls -lh "$OUT_APK"
echo "BUILD_OK"
