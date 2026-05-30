#!/bin/sh

if [[ -z "${JAVA_OPTS}" ]];then
    export JAVA_OPTS="-Xmx64m -Xms64m"
fi

if [ x"${JAVA_ENABLE_DEBUG}" != x ] && [ "${JAVA_ENABLE_DEBUG}" != "false" ]; then
    java_debug_args="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=${JAVA_DEBUG_PORT:-5005}"
fi

# Java 17 compatibility flags for Spring Boot 1.x
java_17_compat="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED"

exec java ${java_debug_args} ${java_17_compat} ${JAVA_OPTS} ${JAVA_ARGS}  -jar /opt/egov/*.jar