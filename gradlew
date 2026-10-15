#!/bin/sh

#
# AURA-Agent Gradle bootstrap.
# If the official wrapper jar is missing, falls back to system `gradle`.
#

set -e

APP_HOME=$(cd "$(dirname "$0")" && pwd)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_PROPS="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

download_wrapper() {
  if [ -f "$WRAPPER_JAR" ]; then
    return 0
  fi
  echo "gradle-wrapper.jar missing — trying to fetch Gradle distribution via system gradle..."
  if command -v gradle >/dev/null 2>&1; then
    (cd "$APP_HOME" && gradle wrapper --gradle-version 8.7) || true
  fi
}

download_wrapper

if [ -f "$WRAPPER_JAR" ]; then
  # Prefer official wrapper when jar exists
  JAVACMD=${JAVA_HOME:+$JAVA_HOME/bin/java}
  JAVACMD=${JAVACMD:-java}
  exec "$JAVACMD" \
    -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
fi

if command -v gradle >/dev/null 2>&1; then
  echo "Using system gradle (wrapper jar not present)."
  exec gradle "$@"
fi

echo "ERROR: Neither gradle-wrapper.jar nor system gradle found."
echo "Install JDK 17 + Gradle 8.7, or run: gradle wrapper --gradle-version 8.7"
exit 1
