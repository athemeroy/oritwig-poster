#!/usr/bin/env bash
# Official Gradle 8.11.1 wrapper JAR: https://gradle.org/release-checksums/
set -euo pipefail
cd "$(dirname "$0")/.."
printf '%s  %s\n' '2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046' 'gradle/wrapper/gradle-wrapper.jar' | sha256sum -c -
grep -Fxq 'distributionSha256Sum=f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6' gradle/wrapper/gradle-wrapper.properties
printf '%s  %s\n' 'a3648413b47ef77af21d5ebc36c687c7d103aaef3e17f33de7d4f080a6f300a3' gradlew '57931b17dd228e5c24dac90e815d0bf82477e831a4618dfab4136f5446b42a9f' gradlew.bat | sha256sum -c -
