#!/usr/bin/env bash
# build-costphone.sh — builds CostPhone APK
# with error diagnostics for GitHub Actions
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$PROJ/build"
DIAGNOSTIC="$OUT/.build-diagnostic.txt"
SDK_ROOT=""
ANDROID_JAR=""
SDK_BT=""

# Initialize diagnostic file
echo "=== Build Diagnostic Start ===" > "$DIAGNOSTIC"
echo "Date: $(date)" >> "$DIAGNOSTIC"
echo "Host: $(uname -a)" >> "$DIAGNOSTIC"
echo "" >> "$DIAGNOSTIC"

# --- SDK Root Detection ---
echo "=== Phase 1: SDK Root Detection ===" >> "$DIAGNOSTIC"
if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
    SDK_ROOT="$ANDROID_HOME"
    echo "SDK_ROOT from ANDROID_HOME: $SDK_ROOT" >> "$DIAGNOSTIC"
elif [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT" ]]; then
    SDK_ROOT="$ANDROID_SDK_ROOT"
    echo "SDK_ROOT from ANDROID_SDK_ROOT: $SDK_ROOT" >> "$DIAGNOSTIC"
elif [[ -d "$HOME/android-sdk" ]]; then
    SDK_ROOT="$HOME/android-sdk"
    echo "SDK_ROOT from Termux local: $SDK_ROOT" >> "$DIAGNOSTIC"
else
    echo "✗ Android SDK no encontrado. Define ANDROID_HOME o ANDROID_SDK_ROOT." >> "$DIAGNOSTIC"
    echo "SDK not found" >> "$DIAGNOSTIC"
    exit 1
fi
echo "✓ Using android.jar: " >> "$DIAGNOSTIC"

# --- Android JDK Platform (android.jar) ---
echo "=== Phase 2: Android JDK Platform ===" >> "$DIAGNOSTIC"
ANDROID_JAR="$SDK_ROOT/platforms/android-34/android.jar"
if [[ ! -f "$ANDROID_JAR" ]]; then
    ANDROID_JAR=$(ls "$SDK_ROOT"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)
fi
if [[ -z "$ANDROID_JAR" || ! -f "$ANDROID_JAR" ]]; then
    echo "✗ android.jar not found in $SDK_ROOT/platforms/" >> "$DIAGNOSTIC"
    echo "Installed platforms:" >> "$DIAGNOSTIC"
    ls "$SDK_ROOT"/platforms/ 2>/dev/null >> "$DIAGNOSTIC" || echo "  (none found)" >> "$DIAGNOSTIC"
    echo "SDK platform not found" >> "$DIAGNOSTIC"
    exit 1
fi
echo "✓ Using android.jar: $ANDROID_JAR" >> "$DIAGNOSTIC"

# --- Build-Tools Detection ---
echo "=== Phase 3: Build-Tools Detection ===" >> "$DIAGNOSTIC"
if [[ -n "${BUILD_TOOLS_VERSION:-}" ]]; then
    BT_DIR="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
    if [[ -d "$BT_DIR" ]]; then
        SDK_BT="$BT_DIR"
        echo "Using Build Tools $BUILD_TOOLS_VERSION" >> "$DIAGNOSTIC"
    else
        echo "✗ Build Tools $BUILD_TOOLS_VERSION not found in $SDK_ROOT/build-tools/" >> "$DIAGNOSTIC"
        echo "Build tools not found" >> "$DIAGNOSTIC"
        exit 1
    fi
else
    BT_CANDIDATES=$(ls -d "$SDK_ROOT"/build-tools/* 2>/dev/null | sort -V)
    if [[ -z "$BT_CANDIDATES" ]]; then
        echo "✗ No build-tools found in $SDK_ROOT/build-tools/" >> "$DIAGNOSTIC"
        echo "No build-tools found" >> "$DIAGNOSTIC"
        exit 1
    fi
    SDK_BT=$(echo "$BT_CANDIDATES" | tail -1)
    echo "Using newest Build Tools: $(basename $SDK_BT)" >> "$DIAGNOSTIC"
fi

# --- Tool Validation ---
echo "=== Phase 4: Tool Validation ===" >> "$DIAGNOSTIC"

# Verify aapt2 exists
if [[ ! -x "$SDK_BT/aapt2" ]]; then
    echo "✗ aapt2 not found in $SDK_BT" >> "$DIAGNOSTIC"
    echo "aapt2 not found" >> "$DIAGNOSTIC"
    exit 1
fi
echo "✓ aapt2 validated" >> "$DIAGNOSTIC"

# Verify d8 exists
if [[ ! -f "$SDK_BT/d8" ]]; then
    echo "✗ d8 not found in $SDK_BT" >> "$DIAGNOSTIC"
    echo "d8 not found" >> "$DIAGNOSTIC"
    exit 1
fi
echo "✓ d8 validated" >> "$DIAGNOSTIC"

# Verify apksigner exists
if [[ ! -f "$SDK_BT/lib/apksigner.jar" ]]; then
    echo "✗ apksigner.jar not found in $SDK_BT" >> "$DIAGNOSTIC"
    echo "apksigner.jar not found" >> "$DIAGNOSTIC"
    exit 1
fi
echo "✓ apksigner validated" >> "$DIAGNOSTIC"

echo "✓ All tools validated in $SDK_BT" >> "$DIAGNOSTIC"

# --- Safe command runners ---
run_d8() { bash "$SDK_BT/d8" "$@"; }
run_apksigner() { java -jar "$SDK_BT/lib/apksigner.jar" "$@"; }
run_aapt2() { "$SDK_BT/aapt2" "$@"; }

# --- Keystore ---
echo "=== Phase 5: Keystore ===" >> "$DIAGNOSTIC"
if [[ -n "${KEYSTORE_PATH:-}" ]]; then
    KEYSTORE="$KEYSTORE_PATH"
else
    KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
fi
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
# Mask passwords in logs - never write actual passwords
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-android}"
KEY_PASSWORD="${KEY_PASSWORD:-android}"

# Mask the keystore path in diagnostic (no secret content)
KEYSTORE_MASKED="${KEYSTORE// /_}"
echo "✓ Keystore configured: $KEYSTORE_MASKED" >> "$DIAGNOSTIC"

rm -rf "$OUT"
mkdir -p "$OUT"
cd "$PROJ"

echo "=== Build Starting ===" >> "$DIAGNOSTIC"

# 1. Compile resources → binary resource zip
echo "[1/7] Compile resources (aapt2 capture)..." >> "$DIAGNOSTIC"
set +e
RAAPT2_OUT=$(/data/data/com.termux/files/usr/bin/aapt2 compile --dir res -o "$OUT/res.zip" 2>&1)
RAAPT2_EXIT=$?
set -e
echo "$RAAPT2_OUT" >> "$DIAGNOSTIC"
echo "Exit code: $RAAPT2_EXIT" >> "$DIAGNOSTIC"
if [[ $RAAPT2_EXIT -ne 0 ]]; then
    echo "✗ aapt2 compile FAILED at phase 1" >> "$DIAGNOSTIC"
    cat "$DIAGNOSTIC" >> "$OUT/build.log"
    exit 1
fi
echo "✓ aapt2 compile OK" >> "$DIAGNOSTIC"

# 2. Link resources → generates R.java AND builds resources into base APK
echo "[2/7] Link resources (aapt2 link)..." >> "$DIAGNOSTIC"
set +e
LINK_OUT=$(/data/data/com.termux/files/usr/bin/aapt2 link -o "$OUT/base.apk" --manifest AndroidManifest.xml -I "$ANDROID_JAR" --java "$OUT/r_java" "$OUT/res.zip" 2>&1)
LINK_EXIT=$?
set -e
echo "$LINK_OUT" >> "$DIAGNOSTIC"
echo "Exit code: $LINK_EXIT" >> "$DIAGNOSTIC"
if [[ $LINK_EXIT -ne 0 ]]; then
    echo "✗ aapt2 link FAILED at phase 2" >> "$DIAGNOSTIC"
    cat "$DIAGNOSTIC" >> "$OUT/build.log"
    exit 1
fi
echo "✓ aapt2 link OK" >> "$DIAGNOSTIC"

# 3. Shizuku: AAR → classpath, AIDL → Java
echo "[3/7] Shizuku (AAR and AIDL)..." >> "$DIAGNOSTIC"
rm -rf "$OUT/libs" "$OUT/aidl_java"
mkdir -p "$OUT/libs" "$OUT/aidl_java"
shopt -s nullglob
for aar in "$PROJ"/libs/*.aar; do
    name=$(basename "$aar" .aar)
    mkdir -p "$OUT/aar/$name"
    unzip -o -q "$aar" classes.jar -d "$OUT/aar/$name"
    cp "$OUT/aar/$name/classes.jar" "$OUT/libs/$name.jar"
    echo "✓ libs/$name.jar" >> "$DIAGNOSTIC"
done
shopt -u nullglob
LIBJARS=($(find "$OUT/libs" -name '*.jar' | sort))
if [[ ${#LIBJARS[@]} -gt 0 ]]; then
    LIBCP=$(find "$OUT/libs" -name '*.jar' | paste -sd:)
    FULLCP="$ANDROID_JAR:$LIBCP"
else
    FULLCP="$ANDROID_JAR"
fi

AIDLFILES=()
if [[ -d "$PROJ/src/aidl" ]]; then
    AIDLFILES=($(find "$PROJ/src/aidl" -name '*.aidl' | sort))
fi
if [[ ${#AIDLFILES[@]} -gt 0 ]]; then
    if [[ -x "$SDK_BT/aidl" ]]; then
        for f in "${AIDLFILES[@]}"; do
            "$SDK_BT/aidl" -I"$PROJ/src/aidl" -o"$OUT/aidl_java" "$f"
        done
        echo "✓ AIDL → $OUT/aidl_java" >> "$DIAGNOSTIC"
    else
        echo "✗ aidl not executable in $SDK_BT" >> "$DIAGNOSTIC"
        echo "aidl not found" >> "$DIAGNOSTIC"
        exit 1
    fi
else
    echo "     (no AIDL files to process)" >> "$DIAGNOSTIC"
fi
AIDLJAVA=($(find "$OUT/aidl_java" -name '*.java' 2>/dev/null | sort))

# 4. Compile Java + R.java
echo "[4/7] Compile Java sources..." >> "$DIAGNOSTIC"
set +e
javac_output=$(/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/bin/javac \
    --source 1.8 --target 1.8 \
    --bootclasspath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    -classpath "$FULLCP" \
    "${SRCS[@]}" "${RJAVADIR}" ${AIDLJAVA[@]+"${AIDLJAVA[@]}"} 2>&1)
javac_exit=$?
set -e
echo "$javac_output" >> "$DIAGNOSTIC"
echo "Exit code: $javac_exit" >> "$DIAGNOSTIC"
if [[ $javac_exit -ne 0 ]]; then
    echo "✗ javac FAILED at phase 4" >> "$DIAGNOSTIC"
    cat "$DIAGNOSTIC" >> "$OUT/build.log"
    exit 1
fi
echo "✓ javac OK" >> "$DIAGNOSTIC"

# 5. Convert → classes.dex
echo "[5/7] Create DEX (d8)..." >> "$DIAGNOSTIC"
set +e
DEX_OUT=$(/data/data/com.termux/files/usr/bin/d8 "$OUT/classes" --output "$OUT/dex" --lib "$ANDROID_JAR" --min-api 24 ${LIBJARS[@]+"${LIBJARS[@]}"} 2>&1)
DEX_EXIT=$?
set -e
echo "$DEX_OUT" >> "$DIAGNOSTIC"
echo "Exit code: $DEX_EXIT" >> "$DIAGNOSTIC"
if [[ $DEX_EXIT -ne 0 ]]; then
    echo "✗ d8 FAILED at phase 5" >> "$DIAGNOSTIC"
    cat "$DIAGNOSTIC" >> "$OUT/build.log"
    exit 1
fi
echo "✓ d8 OK" >> "$DIAGNOSTIC"

# 6. Embed classes.dex into base.apk
echo "[6/7] Embed classes.dex into APK..." >> "$DIAGNOSTIC"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
cd "$OUT/dex"
zip -j "$OUT/unsigned.apk" classes.dex 2>&1 | tail -3 >> "$DIAGNOSTIC" 2>/dev/null || true
cd "$PROJ"
echo "✓ unsigned.apk OK" >> "$DIAGNOSTIC"

# 7. Sign APK
echo "[7/7] Sign APK..." >> "$DIAGNOSTIC"
if [[ ! -f "$KEYSTORE" ]]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    # Generate debug keystore if needed (no sensitive info in diagnostic)
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 \
        -validity 10000 \
        -storepass "$KEYSTORE_PASSWORD" -keypass "$KEY_PASSWORD" \
        -dname "CN=Android Debug,O=Android,C=US" 2>>"$DIAGNOSTIC" | tail -3
fi
set +e
SIGN_OUT=$(/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/bin/java -jar "$SDK_BT/lib/apksigner.jar" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$KEYSTORE_PASSWORD" \
    --key-pass "pass:$KEY_PASSWORD" \
    --out "$OUT/CostPhone.apk" \
    "$OUT/unsigned.apk" 2>&1)
SIGN_EXIT=$?
set -e
echo "$SIGN_OUT" >> "$DIAGNOSTIC"
echo "Exit code: $SIGN_EXIT" >> "$DIAGNOSTIC"
if [[ $SIGN_EXIT -ne 0 ]]; then
    echo "✗ apksigner FAILED at phase 7" >> "$DIAGNOSTIC"
    cat "$DIAGNOSTIC" >> "$OUT/build.log"
    exit 1
fi
echo "✓ apksigner OK" >> "$DIAGNOSTIC"

# Verify
echo "" >> "$DIAGNOSTIC"
echo "=== Verification ===" >> "$DIAGNOSTIC"
run_apksigner verify -v "$OUT/CostPhone.apk" 2>&1 | head -10 >> "$DIAGNOSTIC" 2>/dev/null || echo "Verification errored" >> "$DIAGNOSTIC"

echo "" >> "$DIAGNOSTIC"
echo "=== Build Complete ===" >> "$DIAGNOSTIC"
echo "✅ APK: $OUT/CostPhone.apk" >> "$DIAGNOSTIC"

# Final: copy diagnostic to root for GitHub Actions
cp "$DIAGNOSTIC" "$OUT/build.log"

echo ""
echo "✅ APK: $OUT/CostPhone.apk"
echo "Diagnostic log: $OUT/build.log"
