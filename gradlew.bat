@echo off
set DIR=%~dp0
java %JAVA_OPTS% %GRADLE_OPTS% -Dorg.gradle.appname=gradlew -classpath "%DIR%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
