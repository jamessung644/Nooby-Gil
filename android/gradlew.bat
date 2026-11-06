@echo off
setlocal
set PROJECT_DIR=%~dp0
if not defined GRADLE_USER_HOME set GRADLE_USER_HOME=%PROJECT_DIR%.gradle-home
if defined JAVA_HOME (set JAVA_BIN=%JAVA_HOME%\bin\java.exe) else (set JAVA_BIN=java.exe)
"%JAVA_BIN%" -classpath "%PROJECT_DIR%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
endlocal
