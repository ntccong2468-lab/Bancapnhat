#!/usr/bin/env bash
set -euo pipefail

PACKAGE_TYPE="${1:-app-image}"
case "$(uname -s)" in
  Darwin)
    [[ "$PACKAGE_TYPE" =~ ^(app-image|dmg|pkg)$ ]] || {
      echo "Usage on macOS: ./build.sh [app-image|dmg|pkg]" >&2
      exit 1
    }
    ;;
  Linux)
    [[ "$PACKAGE_TYPE" =~ ^(app-image|deb|rpm)$ ]] || {
      echo "Usage on Linux: ./build.sh [app-image|deb|rpm]" >&2
      exit 1
    }
    ;;
  *)
    echo "Unsupported host. Use build.bat on Windows." >&2
    exit 1
    ;;
esac

APP_NAME="WCode"
APP_VERSION="$(./mvnw help:evaluate -Dexpression=app.version -q -DforceStdout)"
APP_VENDOR="$(./mvnw help:evaluate -Dexpression=app.vendor -q -DforceStdout)"
MAIN_JAR="FBSBarcode-${APP_VERSION}.jar"
MAIN_CLASS="com.tuandev.fbsbarcode.Launcher"
JPACKAGE_INPUT="target/jpackage-input"

echo "Building JavaFX WCode ${APP_VERSION} with Maven..."
./mvnw -q clean verify
test -s "target/${MAIN_JAR}" || {
  echo "Missing application JAR: target/${MAIN_JAR}" >&2
  exit 1
}

rm -rf -- "$JPACKAGE_INPUT" out
mkdir -p "$JPACKAGE_INPUT/lib" out
cp "target/${MAIN_JAR}" "$JPACKAGE_INPUT/${MAIN_JAR}"
cp -R target/lib/. "$JPACKAGE_INPUT/lib/"

JPACKAGE_OPTIONS=(
  --type "$PACKAGE_TYPE"
  --name "$APP_NAME"
  --input "$JPACKAGE_INPUT"
  --main-jar "$MAIN_JAR"
  --main-class "$MAIN_CLASS"
  --dest out
  --app-version "$APP_VERSION"
  --vendor "$APP_VENDOR"
  --java-options "--enable-native-access=ALL-UNNAMED"
  --jlink-options "--strip-native-commands --strip-debug --no-man-pages --no-header-files --bind-services"
)

if [[ "$(uname -s)" == "Linux" ]]; then
  JPACKAGE_OPTIONS+=(--icon src/main/resources/com/tuandev/fbsbarcode/assets/images/logo.png)
fi

echo "Packaging JavaFX application as ${PACKAGE_TYPE}..."
jpackage "${JPACKAGE_OPTIONS[@]}"
echo "Done. Output is in out/."
