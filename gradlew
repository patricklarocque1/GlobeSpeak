#!/usr/bin/env bash

# Minimal Gradle wrapper bootstrapper (no gradle-wrapper.jar committed)
# Downloads Gradle if missing and delegates to it. CI-safe.

set -euo pipefail

GRADLE_VERSION="8.7"
DIST_NAME="gradle-${GRADLE_VERSION}"
DISTS_DIR="${HOME}/.gradle/wrapper/dists"
INSTALL_DIR="${DISTS_DIR}/${DIST_NAME}"
ZIP_PATH="${INSTALL_DIR}/${DIST_NAME}-bin.zip"
GRADLE_BIN="${INSTALL_DIR}/${DIST_NAME}/bin/gradle"

download_gradle() {
  mkdir -p "${INSTALL_DIR}"
  if [[ ! -f "${ZIP_PATH}" ]]; then
    echo "Downloading Gradle ${GRADLE_VERSION}..." >&2
    curl -fL "https://services.gradle.org/distributions/${DIST_NAME}-bin.zip" -o "${ZIP_PATH}"
  fi
  if [[ ! -x "${GRADLE_BIN}" ]]; then
    echo "Unpacking Gradle..." >&2
    unzip -q -o "${ZIP_PATH}" -d "${INSTALL_DIR}"
    chmod +x "${GRADLE_BIN}"
  fi
}

download_gradle
exec "${GRADLE_BIN}" -p "$(dirname "$0")" "$@"

