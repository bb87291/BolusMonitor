#!/bin/sh

APP_HOME=$( cd "${APP_HOME:-./}" && pwd -P ) || exit

exec java -cp "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
