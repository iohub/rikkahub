#!/bin/bash
set -o pipefail
export https_proxy=http://127.0.0.1:7897
export http_proxy=http://127.0.0.1:7897
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_SDK_ROOT=$ANDROID_HOME
cd /Users/ydl/iohub/rikkahub
echo "=== BUILD START $(date) ==="
echo "JAVA_HOME=$JAVA_HOME"
java -version 2>&1
chmod +x ./gradlew
./gradlew :app:assembleGithubDebug \
  --no-daemon \
  --stacktrace \
  2>&1
echo "=== BUILD EXIT CODE: $? @ $(date) ==="
