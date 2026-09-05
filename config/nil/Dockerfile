FROM eclipse-temurin:21-jdk-jammy@sha256:9d8dcf999b0bce2453e913823595a5ff2a4e8e9e5d5241b45280d0ff069818ec AS build

# renovate: NIL ref=master
ARG NIL_COMMIT=967bb983890bf2c4145d2155dfe0e88c02480ad6
ARG NIL_SOURCE_SHA256=24e22b80323d9af55a229cabf26e5e4554f0480a7a498e459448e03ee950a869

RUN apt-get update \
    && apt-get install -y --no-install-recommends patch \
    && rm -rf /var/lib/apt/lists/*

ADD https://github.com/kusumotolab/NIL/archive/${NIL_COMMIT}.tar.gz /tmp/nil.tar.gz
COPY nil-jls25.patch /tmp/nil.patch
COPY verification-metadata.xml /tmp/verification-metadata.xml

RUN echo "${NIL_SOURCE_SHA256}  /tmp/nil.tar.gz" | sha256sum -c - \
    && mkdir /src \
    && tar -xzf /tmp/nil.tar.gz --strip-components=1 -C /src \
    && cd /src \
    && patch -p1 < /tmp/nil.patch \
    && cp /tmp/verification-metadata.xml gradle/verification-metadata.xml \
    && ./gradlew --no-daemon --max-workers=2 --dependency-verification strict shadowJar \
    && echo "45f6a03c70baf95934764d205cf4ed392fe980b08f6cdd6e06ac6b103e71a8e0  build/libs/NIL-all.jar" \
        | sha256sum -c -

FROM eclipse-temurin:21-jre-jammy@sha256:d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13

COPY --from=build /src/build/libs/NIL-all.jar /opt/nil/NIL-all.jar

USER 65532:65532
ENTRYPOINT ["java", "-Xms128m", "-Xmx1024m", "-XX:ActiveProcessorCount=2", "-jar", "/opt/nil/NIL-all.jar"]
