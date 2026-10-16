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

FROM eclipse-temurin:25.0.4_7-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2 AS jdk

FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:1a11b25c776255e7992e75ea1d5f233c1d1c8e8411c8b13cd792aa597a07916a

ENV JAVA_HOME=/opt/jdk-25
ENV PATH="$JAVA_HOME/bin:$PATH"

RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=jdk /opt/java/openjdk/ "$JAVA_HOME/"
RUN java -version

COPY . /src/symphony-trello
COPY .clusterfuzzlite/build.sh /src/build.sh
WORKDIR /src/symphony-trello
