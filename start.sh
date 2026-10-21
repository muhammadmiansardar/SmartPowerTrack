#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if ! java -version >/dev/null 2>&1; then
  echo "Install Java 17 or newer, then run this file again."
  exit 1
fi
if [ -f app/smart-power-track.jar ]; then
  jar_path=app/smart-power-track.jar
elif [ -f target/smart-power-track-0.1.0.jar ]; then
  jar_path=target/smart-power-track-0.1.0.jar
else
  echo "Building SMART Power-Track for the first time..."
  ./mvnw -q -DskipTests package
  jar_path=target/smart-power-track-0.1.0.jar
fi
echo "SMART Power-Track: http://localhost:${PORT:-8080}"
echo "Demo email: demo@powertrack.local | Password: PowerTrack123!"
echo "Keep this window open. Press Ctrl+C to stop."
exec java -jar "$jar_path"
