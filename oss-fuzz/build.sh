#!/bin/bash
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

set -euo pipefail

cd "$SRC/symphony-trello"

rm -rf target/oss-fuzz-deps
./mvnw -q -DskipTests package test-compile dependency:copy-dependencies \
  -DincludeScope=runtime \
  -DoutputDirectory=target/oss-fuzz-deps

fuzzer_class_dir=target/test-classes/ch/fmartin/symphony/trello/fuzz
fuzzer_helper_dir=target/test-classes/ch/fmartin/symphony/trello/tracker

mapfile -t fuzzer_class_files < <(find "$fuzzer_class_dir" -name '*Fuzzer.class' ! -name '*$*' -print | sort)
if ((${#fuzzer_class_files[@]} == 0)); then
  echo "No OSS-Fuzz fuzzer classes found under $fuzzer_class_dir" >&2
  exit 1
fi

mkdir -p "$OUT/classes" "$OUT/test-classes" "$OUT/lib"
cp -R target/classes/. "$OUT/classes/"

mkdir -p "$OUT/test-classes/ch/fmartin/symphony/trello"
cp -R "$fuzzer_class_dir" "$OUT/test-classes/ch/fmartin/symphony/trello/"

shopt -s nullglob
fuzzer_helper_classes=("$fuzzer_helper_dir"/TrelloReferenceFuzzInvariants*.class)
if ((${#fuzzer_helper_classes[@]} == 0)); then
  echo "No TrelloReferenceFuzzInvariants helper classes found under $fuzzer_helper_dir" >&2
  exit 1
fi
mkdir -p "$OUT/test-classes/ch/fmartin/symphony/trello/tracker"
cp "${fuzzer_helper_classes[@]}" "$OUT/test-classes/ch/fmartin/symphony/trello/tracker/"

runtime_jars=(target/oss-fuzz-deps/*.jar)
if ((${#runtime_jars[@]} > 0)); then
  cp "${runtime_jars[@]}" "$OUT/lib/"
fi
shopt -u nullglob

disallowed_runtime_jars=()
for jar in "$OUT"/lib/*.jar; do
  [[ -e "$jar" ]] || continue
  jar_name="$(basename "$jar")"
  case "$jar_name" in
    *jazzer*.jar|*junit*.jar|*mockito*.jar|*assertj*.jar|*surefire*.jar|*hamcrest*.jar|\
      *byte-buddy*.jar|*objenesis*.jar|*opentest4j*.jar|*apiguardian*.jar|*archunit*.jar|\
      *rest-assured*.jar)
      disallowed_runtime_jars+=("$jar_name")
      ;;
  esac
done
if ((${#disallowed_runtime_jars[@]} > 0)); then
  echo "OSS-Fuzz runtime classpath contains test-runner-only jars:" >&2
  printf '  %s\n' "${disallowed_runtime_jars[@]}" >&2
  exit 1
fi

echo "OSS-Fuzz runtime jars in \$OUT/lib:"
runtime_jar_count=0
for jar in "$OUT"/lib/*.jar; do
  [[ -e "$jar" ]] || continue
  runtime_jar_count=$((runtime_jar_count + 1))
  printf '  %s\n' "$(basename "$jar")"
done
if ((runtime_jar_count == 0)); then
  echo "  (none)"
fi

rm -rf "$OUT/open-jdk-25"
cp -a "$JAVA_HOME" "$OUT/open-jdk-25"

generated_fuzzers=()
for class_file in "${fuzzer_class_files[@]}"; do
  target_class="${class_file#target/test-classes/}"
  target_class="${target_class%.class}"
  target_class="${target_class//\//.}"
  fuzzer_name="$(basename -s .class "$class_file")"
  generated_fuzzers+=("$fuzzer_name")
  cat >"$OUT/$fuzzer_name" <<EOF
#!/bin/bash
# LLVMFuzzerTestOneInput
this_dir=\$(dirname "\$0")
runtime_java_home="\$this_dir/open-jdk-25"
runtime_ld_library_path="\$runtime_java_home/lib/server:\${JVM_LD_LIBRARY_PATH:-}:\$this_dir"
if [[ -n "\${LD_LIBRARY_PATH:-}" ]]; then
  runtime_ld_library_path="\$runtime_ld_library_path:\$LD_LIBRARY_PATH"
fi
runtime_classpath="\$this_dir/classes:\$this_dir/test-classes:\$this_dir/jazzer_agent_deploy.jar"
for jar in "\$this_dir"/lib/*.jar; do
  [[ -e "\$jar" ]] || continue
  runtime_classpath="\$runtime_classpath:\$jar"
done
if [[ " \$* " =~ " -runs=" ]]; then
  mem_settings='-Xmx1900m:-Xss900k'
else
  mem_settings='-Xmx2048m:-Xss1024k'
fi
JAVA_HOME="\$runtime_java_home" \\
PATH="\$runtime_java_home/bin:\$PATH" \\
LD_LIBRARY_PATH="\$runtime_ld_library_path" \\
  "\$this_dir/jazzer_driver" \\
  --agent_path="\$this_dir/jazzer_agent_deploy.jar" \\
  --cp="\$runtime_classpath" \\
  --target_class="$target_class" \\
  --jvm_args="\$mem_settings:-Djava.awt.headless=true" \\
  "\$@"
EOF
  chmod +x "$OUT/$fuzzer_name"
done

echo "Generated OSS-Fuzz wrappers:"
printf '  %s\n' "${generated_fuzzers[@]}"
