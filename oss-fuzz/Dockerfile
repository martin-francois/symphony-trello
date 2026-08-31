# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

FROM eclipse-temurin:25.0.4_7-jdk@sha256:e787e08ef76f4c16866108cd7f9fcd96a68eef3ac6cc76866897d4d02d5a2262 AS jdk

FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:6d8d75febebc2fe98d2ecb426eba503afdd9e310bdb249c37470ce4950c0d83b

ARG SYMPHONY_TRELLO_REPO=https://github.com/martin-francois/symphony-trello.git
ARG SYMPHONY_TRELLO_REF=main

ENV JAVA_HOME=/opt/jdk-25
ENV PATH="$JAVA_HOME/bin:$PATH"

RUN apt-get update && apt-get install -y ca-certificates git && rm -rf /var/lib/apt/lists/*
COPY --from=jdk /opt/java/openjdk/ "$JAVA_HOME/"
RUN java -version

RUN git clone --depth=1 --branch "$SYMPHONY_TRELLO_REF" "$SYMPHONY_TRELLO_REPO" "$SRC/symphony-trello"
WORKDIR $SRC/symphony-trello
COPY build.sh $SRC/
