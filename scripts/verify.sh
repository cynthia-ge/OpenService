#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
./mvnw -B clean verify
npm ci --prefix tools/browser-coverage --ignore-scripts --no-audit --no-fund
node tools/browser-coverage/check.cjs
