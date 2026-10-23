FROM gcr.io/oss-fuzz-base/clusterfuzzlite-run-fuzzers:v1@sha256:1b78cfcf1647c8cad96682ce7c8a3f8ea74dbc5b26af5b9a3f87bfb9e4a16d6f

ARG JACOCO_VERSION

# ClusterFuzzLite's v1 runner bundles JaCoCo 0.8.7, which cannot instrument Java 25 bytecode.
COPY org.jacoco.agent-*-runtime.jar /opt/jacoco-agent.jar
COPY org.jacoco.cli-*-nodeps.jar /opt/jacoco-cli.jar

RUN test -n "$JACOCO_VERSION" \
    && java -jar /opt/jacoco-cli.jar version | grep -Eq "^${JACOCO_VERSION}(\\.|$)"
