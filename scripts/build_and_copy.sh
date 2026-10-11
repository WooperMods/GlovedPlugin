#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOWNLOADS_DIR="${HOME}/Downloads"
PLUGIN_DIR="/home/crafty/crafty-4/servers/9db363c7-b2ff-47b6-b334-c647675017c9/plugins"

echo "[GlovedPlugin] Building project in ${PROJECT_DIR}"
cd "${PROJECT_DIR}"

# Use Java 17 to run Gradle, toolchain will compile with Java 25
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export PATH="${JAVA_HOME}/bin:${PATH}"

./gradlew clean build --no-daemon

JAR_PATH=$(find "${PROJECT_DIR}/build/libs" -maxdepth 1 -type f -name 'GlovedPlugin-*.jar' | head -n1)

if [[ -z "${JAR_PATH}" ]]; then
  echo "Error: Build jar not found"
  exit 1
fi

echo "[GlovedPlugin] Copying ${JAR_PATH} to ${DOWNLOADS_DIR}"
cp -v "${JAR_PATH}" "${DOWNLOADS_DIR}/"

mkdir -p "${PLUGIN_DIR}"
echo "[GlovedPlugin] Copying ${JAR_PATH} to ${PLUGIN_DIR}"
cp -v "${JAR_PATH}" "${PLUGIN_DIR}/"

echo "[GlovedPlugin] Done."
echo "  Downloads: ${DOWNLOADS_DIR}/$(basename "${JAR_PATH}")"
echo "  Plugins:   ${PLUGIN_DIR}/$(basename "${JAR_PATH}")"
