FROM gcr.io/oss-fuzz-base/clusterfuzzlite-run-fuzzers:v1@sha256:66a14553ab8b8b9dba9d38ad70de2d6cd4fae36d90ddbf21e7fe757a7392804c

ARG JACOCO_VERSION

# ClusterFuzzLite's v1 runner bundles JaCoCo 0.8.7, which cannot instrument Java 25 bytecode.
COPY org.jacoco.agent-*-runtime.jar /opt/jacoco-agent.jar
COPY org.jacoco.cli-*-nodeps.jar /opt/jacoco-cli.jar

RUN test -n "$JACOCO_VERSION" \
    && java -jar /opt/jacoco-cli.jar version | grep -Eq "^${JACOCO_VERSION}(\\.|$)"
