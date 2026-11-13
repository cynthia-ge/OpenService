#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
exec ./mvnw -B test-compile exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.classpathScope=test -Dexec.args="install chromium"
