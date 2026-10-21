#!/usr/bin/env bash
# Project-local Maven launcher. Distribution is checked against Maven Central SHA-512.
set -euo pipefail
cd "$(dirname "$0")"
if command -v mvn >/dev/null 2>&1; then exec mvn "$@"; fi
powertrack_maven_version=3.9.11
powertrack_maven_dir=".tools/apache-maven-$powertrack_maven_version"
if [ ! -x "$powertrack_maven_dir/bin/mvn" ]; then
  mkdir -p .tools
  powertrack_maven_url="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$powertrack_maven_version/apache-maven-$powertrack_maven_version-bin.tar.gz"
  curl -fSL "$powertrack_maven_url" -o .tools/maven.tar.gz
  curl -fsSL "$powertrack_maven_url.sha512" -o .tools/maven.sha512
  powertrack_expected="$(awk '{print $1}' .tools/maven.sha512)"
  powertrack_actual="$(shasum -a 512 .tools/maven.tar.gz | awk '{print $1}')"
  if [ "$powertrack_actual" != "$powertrack_expected" ]; then echo "Maven checksum mismatch."; exit 1; fi
  tar -xzf .tools/maven.tar.gz -C .tools
fi
exec "$powertrack_maven_dir/bin/mvn" -Dmaven.repo.local=.tools/m2 "$@"
