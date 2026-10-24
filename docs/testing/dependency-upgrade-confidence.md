# Dependency Upgrade Confidence

Every direct dependency and build plugin must have a required check that exercises its main failure
surface. The script test fails when `pom.xml` introduces an artifact that no table row names, and
when the row naming that artifact leaves its primary failure surface or required detection empty.

| Dependencies and plugins                                                                                                  | Primary failure surface                                                                 | Required detection                                                   |
| ------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| `quarkus-arc`, `quarkus-rest-jackson`, `quarkus-scheduler`, `quarkus-maven-plugin`                                        | CDI wiring, HTTP JSON contracts, scheduling, packaging                                  | Maven unit and integration tests plus package startup checks         |
| `quarkus-junit`, `rest-assured`                                                                                           | Quarkus test boot and HTTP assertions                                                   | Normal Maven test and integration-test phases                        |
| `picocli`                                                                                                                 | CLI parsing, help, exit codes, and command dispatch                                     | CLI unit and process-boundary tests                                  |
| `jackson-dataformat-yaml`, `pebble`                                                                                       | Configuration parsing and generated workflow rendering                                  | Parser, fixture, snapshot, and generated-workflow tests              |
| `guava`, `jspecify`                                                                                                       | Collection semantics and nullness boundaries                                            | Java compilation, unit tests, Error Prone, and nullness review       |
| `json-schema-validator`                                                                                                   | Dynamic-tool argument validation against the advertised input schemas                   | Handler schema-case and concurrent-validation unit tests             |
| `opentelemetry-bom`                                                                                                       | Trace API compatibility and runtime instrumentation                                     | Maven dependency resolution, compilation, and diagnostics tests      |
| `assertj-core`, `mockito-core`, `mockito-junit-jupiter`                                                                   | Test compilation, mocks, and assertion behavior                                         | Complete Maven test suite                                            |
| `archunit-junit5`                                                                                                         | Architectural dependency rules                                                          | ArchUnit tests in normal Maven verification                          |
| `approvaltests`                                                                                                           | Terminal-output snapshot storage, comparison, and approval                              | Terminal snapshot tests in normal Maven verification                 |
| `jazzer-junit`                                                                                                            | Fuzz-test discovery and regression corpus execution                                     | Normal fuzz regression tests and the focused fuzzing profile         |
| `error_prone_core`, `error-prone-contrib`, `refaster-runner`                                                              | Compiler checks and refactoring rules                                                   | Blocking compiler analysis and curated OpenRewrite verification      |
| `error_prone_annotations` | Test fixture immutability declarations and compiler annotation compatibility | Test compilation with the matching Error Prone version and the installer signature tests |
| `findsecbugs-plugin`, `spotbugs-maven-plugin`                                                                             | Bytecode correctness and security findings                                              | Blocking SpotBugs and FindSecBugs verification                       |
| `maven-pmd-plugin`                                                                                                        | Source correctness, maintainability, and duplication rules                              | Blocking PMD and CPD verification                                    |
| `spotless-maven-plugin`                                                                                                   | Formatting and import ordering                                                          | Blocking Spotless check                                              |
| `jacoco-maven-plugin`                                                                                                     | Coverage instrumentation and thresholds                                                 | Complete Maven verification                                          |
| `maven-compiler-plugin`, `maven-dependency-plugin`, `maven-surefire-plugin`, `maven-failsafe-plugin`, `exec-maven-plugin` | Compilation, dependency resolution, unit tests, integration tests, and helper execution | Complete Maven verification and package workflow                     |
| `maven-source-plugin`, `maven-javadoc-plugin`                                                                             | Sources and Javadoc jars that Maven Central requires                                    | Complete Maven verification, which builds both jars on `package`                                                                           |
| `maven-deploy-plugin`, `jreleaser-maven-plugin`                                                                           | Maven Central staging, signing, POM rules, bundle, and upload                           | `MavenCentralPublicationTest` and the JReleaser dry run from `CONTRIBUTING.md`, run by a maintainer because Renovate never automerges them |
| `rewrite-maven-plugin`, `rewrite-maven`, `rewrite-migrate-java`, `rewrite-static-analysis`, `rewrite-testing-frameworks`  | Curated source transformations and fixed-point output                                   | OpenRewrite Renovate automation tests and full generated-change gate |

The repository also pins and tests Node script tooling, GitHub Actions, and tool-container images.
Their manifests, custom Renovate managers, config validation, and script tests are the confidence
map for those non-Maven dependencies.
Tool images can only be tested by running them, so each has a required check that runs the
image a pull request pins: `pinned-betterleaks-image` for BetterLeaks
([ADR 0124](../adr/0124-look-up-betterleaks-through-the-github-package-feed.md)) and
`pinned-powershell-image` for the .NET SDK image behind `scripts/pwsh-docker.sh`
([ADR 0127](../adr/0127-run-powershell-tests-on-the-pinned-dotnet-sdk-image.md)), and
`pinned-maven-snapshot-image` for the Maven image behind `scripts/snapshot-tests-docker.sh`
([ADR 0092](../adr/0092-approvaltests-terminal-output-snapshots.md)). The snapshot check runs the
wrapper on each pull request, including Renovate image updates. It fails if no snapshot tests run.

HyperFrames, the README demo renderer, changes the committed video and poster. Its updates never
merge automatically, and the README demo freshness script test blocks them until a maintainer
commits media rendered with the new version.
