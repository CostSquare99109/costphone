#!/data/data/com.termux/files/usr/bin/bash
# build-costphone.sh — builds CostPhone APK in Termux
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$PROJ/build"
ANDROID_JAR=$(ls ~/android-sdk/platforms/android-*/android.jar 2>/dev/null | tail -1)
[[ -z "$ANDROID_JAR" ]] && { echo "✗ android.jar not found in ~/android-sdk/platforms/"; exit 1; }
SDK_BT=$(ls -d ~/android-sdk/build-tools/36.0.0 2>/dev/null || ls -d ~/android-sdk/build-tools/3[4-9]* 2>/dev/null | tail -1)
[[ -z "$SDK_BT" ]] && { echo "✗ build-tools not found"; exit 1; }
D8="bash $SDK_BT/d8"
APKSIGNER="java -jar $SDK_BT/lib/apksigner.jar"
# Keystore: variables de entorno (CI) tienen prioridad. Si no existen, se usa
# el keystore de debug local y se crea si no está (compatibilidad con el
# flujo anterior en Termux).
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
aapt2 compile --dir res -o "$OUT/res.zip" 2>&1
echo "     ✓ $OUT/res.zip"

# 2. Link resources → generates R.java AND builds resources into base APK
echo "[2/7] Link resources + generate R.java (aapt2 link)..."
aapt2 link \
    -o "$OUT/base.apk" \
    --manifest AndroidManifest.xml \
    -I "$ANDROID_JAR" \
    --java "$OUT/r_java" \
    "$OUT/res.zip" \
    2>&1
# aapt2 link puts R.java under <folder>/<package/path>/R.java
RJAVADIR=$(find "$OUT/r_java" -name R.java 2>/dev/null | head -1)
echo "     ✓ R.java → $RJAVADIR"

# 3. Shizuku: los .aar de libs/ son volúmenes zip y, sin Gradle, no hay quien
#    los resuelva por nosotros; y la interfaz del UserService es AIDL, que
#    javac no compila. Las dos cosas se hacen aquí a mano.
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
    FULLCP="$ANDROID_JAR"   # build sin Shizuku, igual que siempre
fi

AIDLFILES=()
if [[ -d "$PROJ/src/aidl" ]]; then
    AIDLFILES=($(find "$PROJ/src/aidl" -name '*.aidl' | sort))
fi
if [[ ${#AIDLFILES[@]} -gt 0 ]]; then
    [[ -x "$SDK_BT/aidl" ]] || {
        echo "✗ aidl no encontrado en $SDK_BT (hace falta para el UserService de Shizuku)"
        exit 1
    }
    for f in "${AIDLFILES[@]}"; do
        "$SDK_BT/aidl" -I"$PROJ/src/aidl" -o"$OUT/aidl_java" "$f"
    done
    echo "     ✓ AIDL → $OUT/aidl_java"
fi
AIDLJAVA=($(find "$OUT/aidl_java" -name '*.java' 2>/dev/null | sort))

# 4. Compile Java + R.java
echo "[4/7] Compile Java sources..."
mkdir -p "$OUT/classes"
SRCS=($(find "$PROJ" -maxdepth 2 -name '*.java' | sort))
javac -encoding UTF-8 \
    -source 1.8 -target 1.8 \
    -bootclasspath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    -classpath "$FULLCP" \
    "${SRCS[@]}" "${RJAVADIR}" ${AIDLJAVA[@]+"${AIDLJAVA[@]}"} 2>&1 | tail -30
echo "     ✓ compiled classes"
find "$OUT/classes" -name '*.class' | wc -l | xargs echo "     classes:"

# 5. Convert → classes.dex
echo "[5/7] Create DEX (d8)..."
mkdir -p "$OUT/dex"
$D8 --output "$OUT/dex" \
    --lib "$ANDROID_JAR" \
    --min-api 24 \
    $(find "$OUT/classes" -name '*.class') ${LIBJARS[@]+"${LIBJARS[@]}"} 2>&1 | tail -5
echo "     ✓ $OUT/dex/classes.dex"

# 6. Embed classes.dex into base.apk
echo "[6/7] Embed classes.dex into APK..."
cp "$OUT/base.apk" "$OUT/unsigned.apk"
# Use zip to add the dex (base.apk already has resources)
cd "$OUT/dex"
zip -j "$OUT/unsigned.apk" classes.dex 2>&1 | tail -3
cd "$PROJ"
echo "     ✓ unsigned.apk (resources + dex)"

# 7. Sign (zipalign skipped – SDK binary isn't aarch64-compatible here)
echo "[7/7] Sign APK..."
if [[ ! -f "$KEYSTORE" ]]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 \
        -validity 10000 \
        -storepass "$KEYSTORE_PASSWORD" -keypass "$KEY_PASSWORD" \
        -dname "CN=Android Debug,O=Android,C=US" 2>&1 | tail -3
fi
$APKSIGNER sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$KEYSTORE_PASSWORD" \
    --key-pass "pass:$KEY_PASSWORD" \
    --ks-key-alias "$KEY_ALIAS" \
    --out "$OUT/CostPhone.apk" \
    "$OUT/unsigned.apk" 2>&1
echo "     ✓ signed"

# Verify
echo ""
echo "────────────────────────────────────────────"
$APKSIGNER verify -v "$OUT/CostPhone.apk" 2>&1 | head -10
echo "────────────────────────────────────────────"
aapt2 dump badging "$OUT/CostPhone.apk" 2>&1 | grep -E 'package:|application-label:|sdkVersion:|uses-permission:|launchable-activity:' | head -12
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
echo ""
echo "Secret: termux-accessdroid-2025"
