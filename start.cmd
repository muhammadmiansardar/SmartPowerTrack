@echo off
cd /d "%~dp0"
java -version >nul 2>&1
if errorlevel 1 (
  echo Install Java 17 or newer, then run this file again.
  pause
  exit /b 1
)
if exist app\smart-power-track.jar (
  set "POWERTRACK_JAR=app\smart-power-track.jar"
) else if exist target\smart-power-track-0.1.0.jar (
  set "POWERTRACK_JAR=target\smart-power-track-0.1.0.jar"
) else (
  echo Build the project with Maven 3.9+ using: mvn package
  echo Then run this file again.
  pause
  exit /b 1
)
echo SMART Power-Track: http://localhost:8080
echo Demo login: demo@powertrack.local / PowerTrack123!
java -jar "%POWERTRACK_JAR%"
pause
