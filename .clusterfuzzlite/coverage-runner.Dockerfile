FROM gcr.io/oss-fuzz-base/clusterfuzzlite-run-fuzzers:v1@sha256:e032621b6c96b6c8251fb26fd1cbb9a85a7084dd8070e4c41d95b86aee7bedbd

ARG JACOCO_VERSION

# ClusterFuzzLite's v1 runner bundles JaCoCo 0.8.7, which cannot instrument Java 25 bytecode.
COPY org.jacoco.agent-*-runtime.jar /opt/jacoco-agent.jar
COPY org.jacoco.cli-*-nodeps.jar /opt/jacoco-cli.jar

RUN test -n "$JACOCO_VERSION" \
    && java -jar /opt/jacoco-cli.jar version | grep -Eq "^${JACOCO_VERSION}(\\.|$)"
