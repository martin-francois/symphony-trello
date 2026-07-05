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

FROM gcr.io/oss-fuzz-base/base-builder-jvm

ARG SYMPHONY_TRELLO_REPO=https://github.com/martin-francois/symphony-trello.git
ARG SYMPHONY_TRELLO_REF=main

ENV JAVA_HOME=/opt/jdk-25
ENV PATH="$JAVA_HOME/bin:$PATH"

RUN apt-get update && apt-get install -y ca-certificates curl git && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL \
      https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse \
      -o /tmp/jdk25.tar.gz \
    && mkdir -p "$JAVA_HOME" \
    && tar -xzf /tmp/jdk25.tar.gz --strip-components=1 -C "$JAVA_HOME" \
    && rm /tmp/jdk25.tar.gz \
    && java -version

RUN git clone --depth=1 --branch "$SYMPHONY_TRELLO_REF" "$SYMPHONY_TRELLO_REPO" "$SRC/symphony-trello"
WORKDIR $SRC/symphony-trello
COPY build.sh $SRC/
