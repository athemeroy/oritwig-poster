#!/usr/bin/env bash
# Linux x86_64 only. Installs an official, checksum-pinned Eclipse Temurin JDK.
set -euo pipefail
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'Use a JDK 21 for your platform; this helper supports Linux x86_64.' >&2; exit 2; }
target=${1:?Usage: tools/setup-jdk.sh ABSOLUTE_DIRECTORY}
[[ $target == /* ]] || { echo 'Specify an absolute directory.' >&2; exit 2; }
[[ ! -e "$target/release" ]] || { echo 'Destination already contains a JDK; choose an empty directory.' >&2; exit 2; }
archive=$(mktemp)
trap 'rm -f "$archive"' EXIT
curl -fsSL --retry 3 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz' -o "$archive"
printf '%s  %s\n' ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94 "$archive" | sha256sum -c - >&2
mkdir -p "$target"
tar -xzf "$archive" --strip-components=1 -C "$target"
"$target/bin/javac" -version >&2
printf '%s\n' "$target"
