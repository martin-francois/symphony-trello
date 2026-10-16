FROM gcr.io/oss-fuzz-base/clusterfuzzlite-run-fuzzers:v1@sha256:6a6fae291bec879f7b9ce926cda0b784cff21211e59d30a17e851bf5b3c5c1f6

ARG JACOCO_VERSION

# ClusterFuzzLite's v1 runner bundles JaCoCo 0.8.7, which cannot instrument Java 25 bytecode.
COPY org.jacoco.agent-*-runtime.jar /opt/jacoco-agent.jar
COPY org.jacoco.cli-*-nodeps.jar /opt/jacoco-cli.jar

RUN test -n "$JACOCO_VERSION" \
    && java -jar /opt/jacoco-cli.jar version | grep -Eq "^${JACOCO_VERSION}(\\.|$)"
