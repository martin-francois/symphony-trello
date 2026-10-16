FROM gcr.io/oss-fuzz-base/clusterfuzzlite-run-fuzzers:v1@sha256:e06ea97fcf9489c34cde688c2827d2e08fc160eef05d538f35c680a04d10b5a0

ARG JACOCO_VERSION

# ClusterFuzzLite's v1 runner bundles JaCoCo 0.8.7, which cannot instrument Java 25 bytecode.
COPY org.jacoco.agent-*-runtime.jar /opt/jacoco-agent.jar
COPY org.jacoco.cli-*-nodeps.jar /opt/jacoco-cli.jar

RUN test -n "$JACOCO_VERSION" \
    && java -jar /opt/jacoco-cli.jar version | grep -Eq "^${JACOCO_VERSION}(\\.|$)"
