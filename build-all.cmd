@echo off
rem Builds one self-contained JAR per supported OS/CPU into dist\. Needs JDK 21+ and Maven.
if exist dist rmdir /s /q dist
mkdir dist
for %%p in (win linux linux-aarch64 mac mac-aarch64) do (
  call mvn -B -q -ntp clean package -DskipTests -Djavafx.platform=%%p || exit /b 1
  copy target\tcp-chat-app-%%p.jar dist\ >nul
)
dir dist
