#!/usr/bin/env bash
# build-costphone.sh — builds CostPhone APK
# Optimized for GitHub Actions + Termux compatibility
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$PROJ/build"
SDK_ROOT=""
ANDROID_JAR=""
SDK_BT=""

# --- SDK Root Detection ---
# Priority 1: ANDROID_HOME (set by github-actions/setup-android)
# Priority 2: ANDROID_SDK_ROOT
# Priority 3: Termux local SDK (~/.android-sdk or $HOME/android-sdk)
if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
    SDK_ROOT="$ANDROID_HOME"
    echo "SDK_ROOT from ANDROID_HOME: $SDK_ROOT"
elif [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT" ]]; then
    SDK_ROOT="$ANDROID_SDK_ROOT"
    echo "SDK_ROOT from ANDROID_SDK_ROOT: $SDK_ROOT"
elif [[ -d "$HOME/android-sdk" ]]; then
    SDK_ROOT="$HOME/android-sdk"
    echo "SDK_ROOT from Termux local: $SDK_ROOT"
else
    echo "✗ Android SDK no encontrado. Define ANDROID_HOME o ANDROID_SDK_ROOT."
    exit 1
fi

# --- Android JDK Platform (android.jar) ---
# Look for platform-34 first, fallback to highest installed
ANDROID_JAR="$SDK_ROOT/platforms/android-34/android.jar"
if [[ ! -f "$ANDROID_JAR" ]]; then
    ANDROID_JAR=$(ls "$SDK_ROOT"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)
fi
if [[ -z "$ANDROID_JAR" || ! -f "$ANDROID_JAR" ]]; then
    echo "✗ android.jar not found in $SDK_ROOT/platforms/"
    echo "Installed platforms:"
    ls "$SDK_ROOT"/platforms/ 2>/dev/null || echo "  (none found)"
    exit 1
fi
echo "✓ Using android.jar: $ANDROID_JAR"

# --- Build-Tools Detection ---
# If BUILD_TOOLS_VERSION is set, use it; otherwise find newest
if [[ -n "${BUILD_TOOLS_VERSION:-}" ]]; then
    BT_DIR="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
    if [[ -d "$BT_DIR" ]]; then
        SDK_BT="$BT_DIR"
        echo "Using Build Tools $BUILD_TOOLS_VERSION"
    else
        echo "✗ Build Tools $BUILD_TOOLS_VERSION not found in $SDK_ROOT/build-tools/"
        ls "$SDK_ROOT"/build-tools/ 2>/dev/null || echo "  (no build-tools dir)"
        exit 1
    fi
else
    # Find newest build-tools directory
    BT_CANDIDATES=$(ls -d "$SDK_ROOT"/build-tools/* 2>/dev/null | sort -V)
    if [[ -z "$BT_CANDIDATES" ]]; then
        echo "✗ No build-tools found in $SDK_ROOT/build-tools/"
        exit 1
    fi
    SDK_BT=$(echo "$BT_CANDIDATES" | tail -1)
    echo "Using newest Build Tools: $(basename $SDK_BT)"
fi

# --- Tool Validation ---
# Verify aapt2 exists
if [[ ! -x "$SDK_BT/aapt2" ]]; then
    echo "✗ aapt2 not found in $SDK_BT"
    # Try to find it
    FOUND_AAPT2=$(find "$SDK_ROOT" -name aapt2 -type f 2>/dev/null | head -1)
    if [[ -n "$FOUND_AAPT2" ]]; then
        echo "Found aapt2 at: $FOUND_AAPT2"
        SDK_BT=$(dirname "$FOUND_AAPT2")
    else
        exit 1
    fi
fi

# Verify d8 exists
if [[ ! -f "$SDK_BT/d8" ]]; then
    echo "✗ d8 not found in $SDK_BT"
    FOUND_D8=$(find "$SDK_ROOT" -name d8 -type f 2>/dev/null | head -1)
    if [[ -n "$FOUND_D8" ]]; then
        echo "Found d8 at: $FOUND_D8"
        SDK_BT=$(dirname "$FOUND_D8")
    else
        exit 1
    fi
fi

# Verify apksigner exists
if [[ ! -f "$SDK_BT/lib/apksigner.jar" ]]; then
    echo "✗ apksigner.jar not found in $SDK_BT"
    FOUND_SIGNER=$(find "$SDK_ROOT" -name apksigner -type f 2>/dev/null | head -1)
    if [[ -n "$FOUND_SIGNER" ]]; then
        echo "Found apksigner at: $FOUND_SIGNER"
    else
        exit 1
    fi
fi

echo "✓ All tools validated in $SDK_BT"

# --- Safe command runners ---
run_d8() { bash "$SDK_BT/d8" "$@"; }
run_apksigner() { java -jar "$SDK_BT/lib/apksigner.jar" "$@"; }
run_aapt2() { "$SDK_BT/aapt2" "$@"; }

# --- Keystore ---
if [[ -n "${KEYSTORE_PATH:-}" ]]; then
    KEYSTORE="$KEYSTORE_PATH"
else
    KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
fi
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-android}"
KEY_PASSWORD="${KEY_PASSWORD:-android}"

rm -rf "$OUT"
mkdir -p "$OUT"
cd "$PROJ"

echo "────────────────────────────────────────────"
echo " CostPhone APK builder"
echo "────────────────────────────────────────────"

# 1. Compile resources → binary resource zip
echo "[1/7] Compile resources (aapt2 compile)..."
run_aapt2 compile --dir res -o "$OUT/res.zip" 2>&1
echo "     ✓ $OUT/res.zip"

# 2. Link resources → generates R.java AND builds resources into base APK
echo "[2/7] Link resources + generate R.java (aapt2 link)..."
run_aapt2 link     -o "$OUT/base.apk"     --manifest AndroidManifest.xml     -I "$ANDROID_JAR"     --java "$OUT/r_java"     "$OUT/res.zip"     2>&1
# aapt2 link puts R.java under <folder>/<package/path>/R.java
RJAVADIR=$(find "$OUT/r_java" -name R.java 2>/dev/null | head -1)
echo "     ✓ R.java → $RJAVADIR"

# 3. Shizuku: AAR → classpath, AIDL → Java
echo "[3/7] Shizuku (AAR → classpath, AIDL → Java)..."
rm -rf "$OUT/libs" "$OUT/aidl_java"
mkdir -p "$OUT/libs" "$OUT/aidl_java"
shopt -s nullglob
for aar in "$PROJ"/libs/*.aar; do
    name=$(basename "$aar" .aar)
    mkdir -p "$OUT/aar/$name"
    unzip -o -q "$aar" classes.jar -d "$OUT/aar/$name"
    cp "$OUT/aar/$name/classes.jar" "$OUT/libs/$name.jar"
    echo "     ✓ libs/$name.jar"
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
        echo "     ✓ AIDL → $OUT/aidl_java"
    else
        echo "✗ aidl not executable in $SDK_BT"
        exit 1
    fi
else
    echo "     (no AIDL files to process)"
fi
AIDLJAVA=($(find "$OUT/aidl_java" -name '*.java' 2>/dev/null | sort))

# 4. Compile Java + R.java
echo "[4/7] Compile Java sources..."
mkdir -p "$OUT/classes"
SRCS=($(find "$PROJ" -maxdepth 2 -name '*.java' | sort))
javac -encoding UTF-8     -source 1.8 -target 1.8     -bootclasspath "$ANDROID_JAR"     -d "$OUT/classes"     -classpath "$FULLCP"     "${SRCS[@]}" "${RJAVADIR}" ${AIDLJAVA[@]+"${AIDLJAVA[@]}"} 2>&1 | tail -30
echo "     ✓ compiled classes"
find "$OUT/classes" -name '*.class' | wc -l | xargs echo "     classes:"

# 5. Convert → classes.dex
echo "[5/7] Create DEX (d8)..."
mkdir -p "$OUT/dex"
run_d8 --output "$OUT/dex"     --lib "$ANDROID_JAR"     --min-api 24     $(find "$OUT/classes" -name '*.class') ${LIBJARS[@]+"${LIBJARS[@]}"} 2>&1 | tail -5
echo "     ✓ $OUT/dex/classes.dex"

# 6. Embed classes.dex into base.apk
echo "[6/7] Embed classes.dex into APK..."
cp "$OUT/base.apk" "$OUT/unsigned.apk"
cd "$OUT/dex"
zip -j "$OUT/unsigned.apk" classes.dex 2>&1 | tail -3
cd "$PROJ"
echo "     ✓ unsigned.apk (resources + dex)"

# 7. Sign APK
echo "[7/7] Sign APK..."
if [[ ! -f "$KEYSTORE" ]]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -v         -keystore "$KEYSTORE"         -alias "$KEY_ALIAS"         -keyalg RSA -keysize 2048         -validity 10000         -storepass "$KEYSTORE_PASSWORD" -keypass "$KEY_PASSWORD"         -dname "CN=Android Debug,O=Android,C=US" 2>&1 | tail -3
fi
run_apksigner sign     --ks "$KEYSTORE"     --ks-pass "pass:$KEYSTORE_PASSWORD"     --key-pass "pass:$KEY_PASSWORD"     --out "$OUT/CostPhone.apk"     "$OUT/unsigned.apk" 2>&1
echo "     ✓ signed"

# Verify
echo ""
echo "────────────────────────────────────────────"
run_apksigner verify -v "$OUT/CostPhone.apk" 2>&1 | head -10
echo "────────────────────────────────────────────"
echo ""
echo "✅ APK: $OUT/CostPhone.apk"
echo ""
echo "Install:"
echo "  1. Copy APK: termux-open $OUT/CostPhone.apk"
echo "     (opens Android package installer)"
echo "  2. Or: adb install -r $OUT/CostPhone.apk"
echo ""
echo "Then enable:"
echo "  Settings → Accessibility → Downloaded services"
echo "  → CostPhone → activar"
echo "  → confirm in dialog"
