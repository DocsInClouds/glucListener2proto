@echo off

echo generating java class file for Android project...
protoc.exe --java_out="./app/src/main/java" ./glucose.proto

echo done!

pause