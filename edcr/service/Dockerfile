FROM egovio/alpine-maven-builder-jdk-8:gcp AS build
ARG WORK_DIR
WORKDIR /app

# Import corporate Zscaler root CA so Maven can reach all repos over HTTPS
COPY zscaler-root-ca.crt /tmp/zscaler-root-ca.crt
RUN keytool -import -trustcacerts -keystore "$JAVA_HOME/lib/security/cacerts" \
    -storepass changeit -noprompt -alias zscaler-root-ca \
    -file /tmp/zscaler-root-ca.crt

# Install local jars that are unavailable from online repositories
COPY local-deps /tmp/local-deps
RUN mvn install:install-file -Dfile=/tmp/local-deps/aspose-cad-20.10.jar              -DgroupId=com.aspose           -DartifactId=aspose-cad              -Dversion=20.10        -Dpackaging=jar -q && \
    mvn install:install-file -Dfile=/tmp/local-deps/javaxt-core-1.7.8.jar             -DgroupId=javaxt               -DartifactId=javaxt-core             -Dversion=1.7.8        -Dpackaging=jar -q && \
    mvn install:install-file -Dfile=/tmp/local-deps/batik-all-1.0.jar                 -DgroupId=org.edcr             -DartifactId=batik-all               -Dversion=1.0          -Dpackaging=jar -q && \
    mvn install:install-file -Dfile=/tmp/local-deps/kabeja-svg-0.4.jar                -DgroupId=org.edcr             -DartifactId=kabeja-svg              -Dversion=0.4          -Dpackaging=jar -q && \
    mvn install:install-file -Dfile=/tmp/local-deps/pdf-transcoder-1.1.jar            -DgroupId=org.edcr             -DartifactId=pdf-transcoder          -Dversion=1.1          -Dpackaging=jar -q && \
    mvn install:install-file -Dfile=/tmp/local-deps/xercesImpl-2.11.0.SP5.jar         -DgroupId=xerces               -DartifactId=xercesImpl              -Dversion=2.11.0.SP5   -Dpackaging=jar -q && \
    mvn install:install-file -Dfile=/tmp/local-deps/taglibs-standard-compat-1.2.6-RC1.jar  -DgroupId=org.apache.taglibs  -DartifactId=taglibs-standard-compat  -Dversion=1.2.6-RC1   -Dpackaging=jar -q && \
    mvn install:install-file -Dfile=/tmp/local-deps/taglibs-standard-spec-1.2.6-RC1.jar    -DgroupId=org.apache.taglibs  -DartifactId=taglibs-standard-spec    -Dversion=1.2.6-RC1   -Dpackaging=jar -q && \
    mvn install:install-file -Dfile=/tmp/local-deps/taglibs-standard-impl-1.2.6-RC1.jar    -DgroupId=org.apache.taglibs  -DartifactId=taglibs-standard-impl    -Dversion=1.2.6-RC1   -Dpackaging=jar -q && \
    mvn install:install-file -Dfile=/tmp/local-deps/log4j-core-2.17.1.jar                 -DgroupId=org.apache.logging.log4j  -DartifactId=log4j-core          -Dversion=2.17.1       -Dpackaging=jar -q

# Compile org.kabeja:kabeja:0.4 from bundled source (not available in any live repo)
COPY local-kabeja-src /tmp/local-kabeja-src
RUN mkdir -p /tmp/kabeja-classes && \
    find /tmp/local-kabeja-src/org -name "*.java" > /tmp/kabeja-sources.txt && \
    javac -cp "/tmp/local-kabeja-src/miethxml-ui.jar" \
          -d /tmp/kabeja-classes \
          @/tmp/kabeja-sources.txt && \
    jar cf /tmp/kabeja-0.4.jar -C /tmp/kabeja-classes . && \
    mvn install:install-file -Dfile=/tmp/kabeja-0.4.jar \
        -DgroupId=org.kabeja -DartifactId=kabeja -Dversion=0.4 -Dpackaging=jar -q

# copy the project files

COPY ${WORK_DIR} ./${WORK_DIR}
RUN --mount=type=cache,target=/root/.m2 \
    cd ${WORK_DIR} \
    && MAVEN_OPTS="-Xmx2048m -XX:MaxPermSize=512m" mvn clean package -DskipTests
                                 

# Create runtime image

#FROM egovio/wildfly:1-helm-fin-e6312078
FROM upyogio/wildfly:1-ed
COPY --from=build /app/egov/egov-ear/target/*.ear /opt/jboss/wildfly/standalone/deployments/
USER jboss

CMD ["/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0", "-bmanagement", "0.0.0.0", "-Ddb.migration.enabled=false"]
