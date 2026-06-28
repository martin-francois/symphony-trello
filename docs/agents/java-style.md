# Java style & design preferences

## Scope

How to write and shape Java code in this repository: formatting, language-feature use, complexity
and reuse tradeoffs, and exception handling. Static-analysis tooling has its own page; see
References.

## Formatting and language

- Let Spotless handle formatting and import cleanup. Use `spotless:apply` before verifying when
  formatting changed.
- Use imports instead of inline fully qualified type names. Write `Arrays.stream(...)`, not
  `java.util.Arrays.stream(...)`. PMD enforces this with the narrow `UnnecessaryFullyQualifiedName`
  rule.
- Use Java 25 LTS language/runtime features where they make the code clearer, but do not be clever
  for its own sake.
- Keep code ASCII unless an existing file or domain requirement clearly needs Unicode.
- Avoid unrelated metadata churn and broad rewrites.

## Streams and Optional

- Prefer streams for real collection transformations and lookups when they make the code clearer
  than manual loop state. Do not replace a readable collection stream with a loop just to avoid an
  `Optional`, especially when the loop needs labeled `continue`, mutable sentinel flags beyond the
  natural state of the algorithm, or duplicated branch flow.
- When refactoring Java code to make better use of streams, use the repo-local
  `java-streams-eval-capture` Codex skill. Capture the before, prompt, intermediate when present,
  and final code, then create or update the corresponding eval issue in
  `martinfrancois/java-streams-skill`.
- Avoid Optional-as-null-control-flow. Do not use `optional.isPresent()` followed by `optional.get()`,
  `optional.orElseThrow()`, or equivalent value reads, and do not convert an `Optional` to nullable
  state with `optional.orElse(null)` just to branch on `value != null`. Do not convert an `Optional`
  to a one-element collection with `optional.stream().toList()` just to enter a loop or return early.
  This does not forbid streaming real collections and ending with an Optional-returning terminal
  operation when the source may contain many values. Use `findFirst()` only when encounter order is
  part of the required behavior; use `findAny()` when any matching value is equivalent. Before adding
  or keeping `findFirst()`, temporarily switch that specific lookup to `findAny()` and run the narrow
  relevant test. If the test does not fail but encounter order is still semantically required, add or
  update a realistic test that asserts the first-match contract, then keep `findFirst()`. If no
  realistic order-dependent scenario exists, use `findAny()` instead. In those cases, still keep the
  resulting `Optional` as the control-flow boundary with whichever `Optional` API expresses the
  intent most clearly, such as `map`, `flatMap`, `filter`, `or`, `orElse`, `orElseGet`,
  `orElseThrow`, `ifPresent`, or `ifPresentOrElse`; this is guidance, not a closed list. If the
  absent branch throws a checked exception or needs prompting, plain branching at that boundary is
  acceptable because the checked exception is part of the method's honest contract. Keep this
  exception narrow: use it only to choose between a present value and a checked-IO/prompting
  fallback, not as a general Optional style. Do not add a dependency or project-specific functional
  helper only to avoid this explicit branch unless the pattern becomes common enough to justify an
  ADR-level style decision. Keep `isPresent()` only when the boolean presence check is itself the
  clearest logic and the value is not immediately read.

## HTTP status codes

- Use named HTTP status constants or focused helper methods for production HTTP status handling.
  Avoid raw status-code literals such as `200`, `404`, or `429` in production code when a named
  constant or helper makes the intent clearer. Tests and fake servers may use literals when the
  status itself is the scenario under test.

## Exception handling

- When a catch block intentionally ignores an exception or falls back, make that policy explicit.
  Prefer a small helper whose name states the fallback, a narrow debug log when the failure is useful
  diagnostic context, or tests/spec wording that prove the quiet fallback is intentional. When the
  quiet fallback stays inline, add a short comment at the catch site stating why ignoring the
  exception is correct there, for example because another validation path already reports it. Do not
  leave comment-only or empty catch bodies in production code, and do not add noisy logs for expected
  optional data, spec-defined parse skips, non-POSIX filesystem behavior, or best-effort diagnostics.

## Comments

- Add comments only for non-obvious decisions, surprising constraints, or tradeoffs that would slow
  down a future maintainer.
- Prefer making unclear code self-documenting before adding prose. Extract a variable, constant,
  method, type, or helper with a precise name when that name can explain what a value means or why a
  branch exists. Use a short comment or Javadoc only when the rationale cannot be expressed cleanly
  in the code structure. If the explanation is a durable design tradeoff rather than a local
  implementation detail, also add or update an ADR.

## Complexity, reuse, and centralization

- Keep complexity to the minimum that satisfies the spec and current use cases.
- Optimize changes for the quality, maintainability, and readability of the resulting code, not for
  the least intrusive patch. Minimum complexity (KISS) means the complexity of the resulting code,
  not the size of the diff. When a smaller patch and a larger change both address an issue and the
  larger change is more correct - it fixes the owning boundary or mechanism instead of patching each
  symptom site - prefer the larger change, even when it requires a large refactor. Do not choose a
  temporary or partial fix only because it is smaller. A required refactor goes in its own commit,
  separate from the behavior-change commit, on the same branch; the combined work must still be one
  cohesive change per the scoping rule. When an external constraint such as review scope, release
  timing, or a dependency forces the smaller fix now, record the preferred end state in a GitHub
  issue in the same change and link it from the PR or code, so the temporary fix cannot silently
  become permanent.
- Prefer existing project patterns, standard Quarkus/JDK APIs, and established maintained libraries
  over new abstractions and hand-rolled reimplementations. Do not write code the project then has to
  maintain when an established feature already provides the behavior; choosing between a premade
  solution and a custom implementation stays ADR-worthy per the specification section.
- Centralize connected constants and configuration values. Do not duplicate magic values with the
  same meaning in multiple places. More generally, when separate code, docs, test data, generated
  templates, constants, literals, or other artifacts represent one concept and a maintainer would
  expect them to change together, centralize or derive them from one shared source in the narrowest
  sensible scope. Strings and numbers are common examples, but the rule is about coupled change, not
  literal type or textual equality. Do not centralize independent values only because they happen to
  look the same today; leave them separate when they represent different concepts that could
  reasonably change independently. When duplicated logic would need to change together at multiple
  call sites, extract a shared helper or abstraction so the coupling is explicit and future
  contributors only have one place to update. Treat making the same edit, or copying the same
  pattern, in two or more places during one change as that signal: stop and ask whether the
  duplicated shape should be refactored before continuing. If those places would likely have to
  change together again, centralize them into a shared helper or constant before finishing instead of
  leaving parallel copies. Apply the same standard to review feedback: when a reviewer points out
  duplicated edits or says two places must change together, treat that as an immediate refactoring
  requirement in the current scope rather than leaving another parallel copy for a later pass.
- Apply the same centralization rule to test scenario data. When a repeated literal appears across
  rows of one parameterized test, scenario factory, fake fixture, or state-machine table, assume it
  is one coupled concept unless the test names or scenario fields make it clear that each occurrence
  is intentionally independent and may be changed separately.
- Treat an unexplained numeric literal as a magic number when its meaning is not obvious from the
  immediate expression and surrounding API. Numeric literals other than `0` and `1` usually deserve
  a name; `0` and `1` are only exempt when they are ordinary counts, indexes, or boolean-adjacent
  values, and should still be named when they represent a sentinel, limit, exit code, protocol
  value, status code, timeout, retry count, size, or another domain contract. Extract such values to
  a named constant at the narrowest sensible scope. When fixing one magic number, search for the same
  concept nearby and update matching occurrences; create or suggest a follow-up issue for broader
  unrelated magic-number cleanup.

## JDK APIs and platform

- Treat JDK module exports, not package name prefixes, as the API support boundary. Packages that a
  JDK module exports are supported API even under `com.sun`; for example, the Compiler Tree API
  `com.sun.source.*` from the `jdk.compiler` module is documented and fine to use through the
  standard `javax.tools.JavaCompiler` entry point. Do not use non-exported internals such as
  `com.sun.tools.javac.*`, `sun.*`, or `jdk.internal.*`, and do not add `--add-exports` or
  `--add-opens` flags to project code to reach them. Toolchain dependencies such as Error Prone that
  need those flags manage their own compiler arguments; that is not a precedent for application or
  test code.
- For project-owned hashes, prefer SHA3-family algorithms. Use `HmacSHA3-256` for tokens that
  represent private context and may appear in diagnostics, public issue reports, logs, or
  user-facing troubleshooting output. Use `SHA3-256` for deterministic project-owned hashes that do
  not need a key. Use `SHA-256` only when an external tool, API, file format, dependency, or
  compatibility contract requires it; make that reason clear nearby when it is not obvious.
- Use the SDKMAN-managed Azul Zulu Java 25 LTS default for local work. Do not hardcode temporary JDK
  paths or prefix Maven commands with custom `JAVA_HOME`/`PATH` assignments; `java`, `javac`, and
  `./mvnw` should resolve through the shell environment.
- Use `ch.fmartin.symphony.trello` as the Java package root and Maven group ID. Do not use
  `com.openai...` for implementation namespaces because this project is an adapted variant, not an
  official OpenAI implementation.

## Helper-language and tooling preferences

- Prefer Java/JVM-based maintained project tooling over Python or other helper languages when the
  task can be handled cleanly in Java. Short shell snippets in documentation are fine for command
  orchestration, but committed reusable helpers should fit the repository's Java-first maintenance
  model unless there is a concrete reason not to.
- Do not use Perl/Python/Ruby/Node one-liners as the documented implementation of a reproducible
  project workflow when a small Java helper would keep the workflow easier to maintain in this repo.
- Prefer pnpm for Node-based tooling in this repository. Use Corepack-pinned pnpm in CI instead of
  adding a `package.json` solely to run a JavaScript CLI in this Java project.
- When configuring build-tool integrations such as Java agents, prefer the official documented Maven
  pattern over hand-built local repository paths or other brittle shortcuts.

## References

- [Static analysis policy](static-analysis.md)
- [Specification & ADR discipline](specification-and-adr-discipline.md)
- [Testing](testing.md)
