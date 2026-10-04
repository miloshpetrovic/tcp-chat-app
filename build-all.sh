#!/bin/sh
# Builds one self-contained JAR per supported OS/CPU into dist/. Needs JDK 21+ and Maven; works from any OS.
set -e
rm -rf dist && mkdir dist
for p in win linux linux-aarch64 mac mac-aarch64; do
  mvn -B -q -ntp clean package -DskipTests -Djavafx.platform=$p
  cp target/tcp-chat-app-$p.jar dist/
done
ls -l dist
