#!/usr/bin/env sh
# Lightweight Gradle bootstrapper for this source bundle.
# It downloads a pinned Gradle distribution into the user's cache, then runs it.
set -eu

GRADLE_VERSION="8.14.3"
DIST_NAME="gradle-${GRADLE_VERSION}-bin.zip"
DIST_URL="https://services.gradle.org/distributions/${DIST_NAME}"
DIST_SHA256="bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531"
CACHE_ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/bootstrap/${GRADLE_VERSION}"
GRADLE_HOME="${CACHE_ROOT}/gradle-${GRADLE_VERSION}"
ZIP_PATH="${CACHE_ROOT}/${DIST_NAME}"

if [ ! -x "${GRADLE_HOME}/bin/gradle" ]; then
  mkdir -p "${CACHE_ROOT}"
  if [ ! -f "${ZIP_PATH}" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl --fail --location --retry 3 --output "${ZIP_PATH}" "${DIST_URL}"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "${ZIP_PATH}" "${DIST_URL}"
    else
      echo "curl or wget is required to download Gradle ${GRADLE_VERSION}." >&2
      exit 1
    fi
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s  %s\n' "${DIST_SHA256}" "${ZIP_PATH}" | sha256sum -c -
  elif command -v shasum >/dev/null 2>&1; then
    printf '%s  %s\n' "${DIST_SHA256}" "${ZIP_PATH}" | shasum -a 256 -c -
  else
    echo "sha256sum or shasum is required to verify Gradle." >&2
    exit 1
  fi
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "${ZIP_PATH}" -d "${CACHE_ROOT}"
  else
    echo "unzip is required to install Gradle ${GRADLE_VERSION}." >&2
    exit 1
  fi
fi

exec "${GRADLE_HOME}/bin/gradle" "$@"
