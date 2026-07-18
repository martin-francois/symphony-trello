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
- Prefer the Java file APIs' specified UTF-8 defaults for `Files.readString`, `Files.readAllLines`,
  and `Files.writeString`. Keep an explicit charset for APIs that otherwise use the platform
  default and for protocol conversions whose byte encoding is part of the contract.
- Use Java 25 LTS language/runtime features where they make the code clearer, but do not be clever
  for its own sake.
- Keep code ASCII unless an existing file or domain requirement clearly needs Unicode.
- Give Java Unicode escapes used as character data a domain name before use, including escapes in
  literals, regular expressions, fixtures, and similar data. Bind `\\uXXXX` syntax to a constant
  whose name identifies the character or intentional character group, and use that constant at call
  sites and in test scenario tables. Search for the same code point before adding a constant so
  coupled validation and rendering rules share one definition instead of accumulating parallel
  escapes. This rule does not apply to escapes shown only in identifiers or explanatory comments.
- Avoid unrelated metadata churn and broad rewrites.

## Reuse and dependency selection

- Before writing or retaining hand-made utility logic, search the Java 25 API and the APIs of
  dependencies declared directly in `pom.xml`. Start with the operation's owning type rather than
  a generic helper: for example, check `Process` and `Thread` for `Duration` overloads, `String` for
  bounded searches, `Files.walkFileTree` for recursive file lifecycle work, sequenced collections
  for first/last or reversed views, and the compiler tree API before scanning Java syntax. In tests,
  also check the exact AssertJ API before maintaining custom assertion loops. Prefer these APIs when
  they state the same contract directly and remove conversion, restoration, parser, or accumulator
  machinery.
- Resolve the version actually used by Maven and consult that version's official API documentation
  or Javadocs before adopting an overload or dependency method whose behavior is subtle. Verify
  null rejection, empty-input behavior, encounter order, defensive-copy and mutability guarantees,
  end-exclusive bounds, timeout precision, interruption, exception cleanup, symlink traversal, and
  short-circuiting as applicable. Add a focused regression at that boundary. A similarly named API
  is not a replacement when it changes one of those properties; retain explicit local code and
  record the concrete mismatch instead.
- Prefer a well-maintained dependency over hand-written infrastructure when it materially reduces
  code or complexity and the resulting API remains readable. Do not reject a library merely because
  it adds a dependency; compare the whole implementation, operational, security, and maintenance
  cost against keeping the custom code.
- A new library candidate must have an upstream release within the previous 12 months, an
  unarchived repository, no deprecation or unmaintained notice, an open-source license compatible
  with this project, and at least 100 GitHub stars. Verify each condition from current primary
  sources when making the recommendation rather than relying on remembered metadata.
- Also evaluate Java compatibility, security history, API stability, transitive dependency size,
  platform coverage, and native/runtime requirements. A candidate that meets the minimum activity,
  license, and popularity bar may still be rejected when it increases operational complexity or
  does not replace enough custom behavior; record that concrete trade-off in the issue or review.
- Before adding a dependency, inspect existing declared dependencies and the Java standard library
  for the same capability. Do not couple source code to an undeclared transitive dependency. Treat a
  new runtime or build dependency as an architecture decision when its lifecycle or platform impact
  is non-trivial, and update the owning ADR or add one when required by the ADR policy.
- Guava is a directly declared dependency. Before writing or retaining small character-boundary
  loops, delimiter-only regex splitting, null-to-empty String adapters, immutable collection
  collectors, or manually capped FIFO queues, check the matching Guava API (`CharMatcher`,
  `Splitter`, `Strings`, immutable collectors, or `EvictingQueue`) against the exact resolved Guava
  version. Use it when it removes custom mechanism and makes the intended semantics more obvious.
  Preserve the original contract explicitly: `Splitter` keeps empty fields unless configured not to;
  `CharMatcher` operates on UTF-16 characters and must not replace a code-point or domain-specific
  Unicode parser; `Strings.nullToEmpty` is for immediate String normalization, not for erasing a
  meaningful nullable state; and immutable or bounded collections must preserve order, duplicate,
  null, and mutation behavior. Prefer the JDK or focused custom code when the Guava form is longer,
  less domain-specific, or semantically different; do not perform broad mechanical collection churn.

## Streams and Optional

- Prefer immutable collection results by default. Use `List.of`, `Set.of`, `Map.of`,
  `Stream.toList()`, immutable collectors, and `List.copyOf`/`Set.copyOf`/`Map.copyOf` at API and
  record boundaries when callers should not mutate the result. Use mutable collections when mutation
  is part of the method's honest work or when a small builder makes the code easier to read than a
  stream or immutable-builder expression; copy to an immutable result before returning unless the
  contract requires mutability.
- Prefer streams for real collection transformations and lookups when they make the code clearer
  than manual loop state. Do not replace a readable collection stream with a loop just to avoid an
  `Optional`, especially when the loop needs labeled `continue`, mutable sentinel flags beyond the
  natural state of the algorithm, or duplicated branch flow.
- Before using `collect(Collectors.toCollection(...))`, first check whether a simpler collector such
  as `toList()`, a Guava immutable collector, or `Collectors.toMap(...)` states the result clearly
  enough. Keep `toCollection(...)` only when the chosen collection type matters, and explain that
  choice with the same non-default collection rule below. Do not use ambiguous JDK collectors such as
  `Collectors.toSet()` because it does not specify the result type or mutability, and the repository
  enforces Picnic's `CollectorMutability` check.
- Whenever code chooses a concrete data structure other than an array, `ArrayList`, `HashMap`,
  `HashSet`, or an immutable counterpart, explain the required semantics at the allocation site.
  This includes `LinkedHashMap`, `LinkedHashSet`, `TreeMap`, `TreeSet`, `ArrayDeque`, bounded queues,
  sequenced collections, and `Collectors.toCollection(...)`. A precise surrounding name may carry
  the explanation; otherwise add a short comment that leads with the domain or resource reason for
  the choice, then names the required encounter order, sorted order, eviction, deque semantics,
  mutability, duplicate handling, or membership performance. Describing only what the structure
  does or how it works is insufficient when it does not explain why that behavior is needed. If the
  code avoids an obvious API such as `toSet()`, state the specific reason that obvious API is not
  used.
- Treat stream and Optional refactors as behavior-preserving by default. Before accepting a skill
  suggestion, verify mutability, encounter order, duplicate handling, parser splitting, laziness,
  exception behavior, nullability, prompt ordering, and side-effect boundaries. If a better-looking
  refactor changes one of those properties, either keep the behavior-preserving shape or make the
  behavior change explicit with the reason it is safe. When the context is insufficient, ask or leave
  the behavior unchanged.
- Whenever a Java change or review presents a reusable lesson about choosing or preserving a stream,
  collector, direct result-producing collection transformation, or character-predicate traversal,
  use the repo-local `java-streams-eval-capture` Codex skill, even when the preferred final code
  contains no stream. For broad Java or API audits, enumerate every implemented transformation that
  presents a distinct reusable equivalence boundary, not only the most visibly stream-shaped change.
- Whenever a Java change or review presents a reusable Optional or absence/fallback
  strategy-selection lesson, use the repo-local `java-optionals-eval-capture` Codex skill. This also
  applies to adjacent owning APIs such as `ScopedValue.orElse(...)` when the reusable lesson is the
  absence or fallback strategy rather than the container migration; routine null checks, defaults,
  and collection-only work inside `Optional.map(...)` do not trigger it.
- Avoid Optional-as-null-control-flow. Do not use `optional.isPresent()` followed by `optional.get()`,
  `optional.orElseThrow()`, or equivalent value reads, and do not convert an `Optional` to nullable
  state with `optional.orElse(null)` just to branch on `value != null`. Do not convert an `Optional`
  to a one-element collection with `optional.stream().toList()` just to enter a loop or return early.
  This does not forbid streaming real collections and ending with an Optional-returning terminal
  operation when the source may contain many values. Use `findFirst()` only when encounter order is
  part of the required behavior; use `findAny()` when any matching value is equivalent. Before adding
  or keeping `findFirst()`, temporarily switch that specific lookup to `findAny()` and run the narrow
  relevant test. If the test does not fail but encounter order is still semantically required, add or
  update a realistic test that asserts the first-match contract, then keep `findFirst()`. A sequential
  stream may still return the first element from `findAny()`, so the minimum durable proof is an
  executable first-match scenario with inputs where a later matching element would be wrong. If the
  order contract is real but cannot be fully reproduced by a unit test, still preserve semantics with
  `findFirst()` and make the reason clear in the owning test, method name, or nearby code. If no
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

## Temporary Skill Overrides

These rules override the installed Java Streams and Optional skills until the linked eval issues are
closed. At most once per turn before applying one of these overrides, check the linked issue state on
GitHub. If all linked issues for an override are closed, remove that override in the same change
instead of following it. When using the Java Streams or Optional skills, do not treat reading this
section as enough; compare the specific skill recommendation or refactor against the applicable
still-open overrides before editing, and follow the override when it narrows or contradicts the
skill's advice.

- Streams issue
  [#40](https://github.com/martinfrancois/java-streams-skill/issues/40): when a stream must emit
  zero, one, or several values per input element, consider `mapMulti(...)` or a result-producing
  collector before using a stream `forEach(...)` that mutates an external collection.
- Streams issues [#42](https://github.com/martinfrancois/java-streams-skill/issues/42) and
  [#48](https://github.com/martinfrancois/java-streams-skill/issues/48): do not treat
  `toList()` plus `addAll(...)` as an automatic improvement over an explicit append loop. Prefer
  collecting directly to the final result when that preserves behavior; otherwise keep the explicit
  loop when it avoids hidden side effects or extra per-batch allocation.
- Streams issue
  [#44](https://github.com/martinfrancois/java-streams-skill/issues/44): use
  `Function.identity()` for identity value mappers in collectors instead of writing lambdas such as
  `value -> value`.
- Streams issue
  [#45](https://github.com/martinfrancois/java-streams-skill/issues/45): when manually editing a
  multi-operation stream chain, prefer keeping `.stream()` with the source expression and placing
  following operations on separate lines when Spotless preserves it. Do not add custom formatting
  tooling for this preference unless the formatter decision ADR changes.
- Streams issue
  [#46](https://github.com/martinfrancois/java-streams-skill/issues/46): every remaining
  `Collectors.toCollection(...)` must have an obvious reason for the concrete collection type or a
  nearby comment that explains it.
- Streams issue
  [#47](https://github.com/martinfrancois/java-streams-skill/issues/47): stream refactors must
  preserve parser splitting semantics. Do not replace regex `\R` line splitting with
  `String.lines()` unless the narrower line-break set is intentional and tested.
- Streams issue
  [#49](https://github.com/martinfrancois/java-streams-skill/issues/49): pure predicate loops that
  only answer whether any element matches should usually become `anyMatch(...)`, with any terminal
  side effect such as adding one warning kept outside the stream. Keep the loop when the body
  performs IO, writes per-element output, mutates state for later steps, depends on an index, scans
  characters with state or code-point-sensitive logic, probes ports, or otherwise has side effects
  that are the point of the method. A pure UTF-16 code-unit predicate scan may instead use
  `String.chars().anyMatch(...)` or `allMatch(...)`; with `allMatch(...)`, add an explicit non-empty
  guard when empty input must be rejected. Use `codePoints()` only when Unicode code-point semantics
  are intended.
- Streams issue
  [#50](https://github.com/martinfrancois/java-streams-skill/issues/50): when code builds a mutable
  append buffer only to pass it directly into an immutable-copying boundary, prefer a
  result-producing immutable stream shape if it stays readable and preserves encounter order. Keep a
  mutable builder when mutation is the method's honest work, the result must stay mutable, or
  conditional construction is clearer that way.
- Streams issue
  [#56](https://github.com/martinfrancois/java-streams-skill/issues/56): when a parser extracts a
  nested collection into a canonical value-object boundary that already owns normalization and
  ordered duplicate handling, keep the parser focused on extraction instead of repeating the same
  collector or map. Before removing parser-local deduplication, verify encounter order, first-wins
  semantics, blank handling, and immutability. Check this issue's state at most once per turn and
  remove this override when the issue is closed.
- Streams issue
  [#57](https://github.com/martinfrancois/java-streams-skill/issues/57): when an `Optional` or
  collector lambda starts a nested stream pipeline that continues across lines, extract the
  collection work into a domain-named helper and keep the outer lambda as short glue. Preserve
  encounter order, equivalent-match semantics, null handling, and result mutability. Check this
  issue's state at most once per turn before applying this override, and remove the override when the
  issue is closed.
- Streams issue
  [#58](https://github.com/martinfrancois/java-streams-skill/issues/58): when reviewing or changing a
  constructor-owned immutable snapshot, including during a broad audit, capture the reusable choice
  between repeating nested mapping at construction and access versus taking the deep/nested snapshot
  once in the constructor while retaining any required shallow `List.copyOf(field)` accessor
  boundary. Verify the constructor invariant, nested mutability, null rejection, and accessor
  contract.
- Streams issue
  [#59](https://github.com/martinfrancois/java-streams-skill/issues/59): when reviewing or changing
  adapter-plus-copy collection work inside another fluent mapping, including during a broad audit,
  capture the direct immutable mapping even when the final code has no stream. Verify varargs target
  typing, snapshot behavior, iteration order, and null rejection.
- Streams issues [#60](https://github.com/martinfrancois/java-streams-skill/issues/60) and
  [#62](https://github.com/martinfrancois/java-streams-skill/issues/62): during broad audits,
  enumerate conditional delimiter reductions and identity concatenation reductions as separate
  `joining(...)` equivalence boundaries. For `.reduce("", String::concat)`, verify null elements,
  empty-source and empty-element behavior, and encounter order. For conditional delimiter reducers,
  additionally verify delimiter placement and sentinel collisions.
- Streams issue
  [#61](https://github.com/martinfrancois/java-streams-skill/issues/61): when reviewing or changing a
  pure character-predicate loop, including during a broad audit, capture the primitive match-stream
  choice. Verify empty-input and vacuous-truth behavior plus the intended UTF-16 code-unit versus
  Unicode code-point semantics.
- For Streams issues #58 through #62, check each linked issue state at most once per turn before
  applying its override, and remove a closed link with its corresponding rule.
- Streams issue
  [#51](https://github.com/martinfrancois/java-streams-skill/issues/51): use `findAny()` for
  equivalent matches, but keep `findFirst()` when encounter order is part of the product contract.
  When keeping `findFirst()`, make the first-match scenario executable in the owning test when the
  behavior can be reproduced. If a sequential-stream test cannot force `findAny()` to choose a later
  element, still preserve the ordered operation and document the first-match contract in the closest
  realistic test, method name, or code comment.
- Streams issue
  [#53](https://github.com/martinfrancois/java-streams-skill/issues/53): when lookup code must
  return missing, exactly one match, or a duplicate/conflict error, do not replace it with
  `findFirst()` unless duplicates are equivalent. Prefer a bounded shape such as
  `filter(...).limit(2).toList()` with an explicit zero/one/ambiguous branch when that preserves the
  existing conflict behavior and avoids collecting every duplicate.
- Optional issues [#69](https://github.com/martinfrancois/java-optionals-skill/issues/69) and
  [#70](https://github.com/martinfrancois/java-optionals-skill/issues/70): side-effecting
  boundaries such as checked prompting, checked IO, Trello writes, or comment upserts may use an
  explicit empty guard and one local value read. Do not force those branches into Optional lambdas or
  generic checked-Optional helpers.
- Optional issue
  [#67](https://github.com/martinfrancois/java-optionals-skill/issues/67): when Optional presence
  gates a simple unchecked validation or other side effect, prefer `ifPresent(...)` when it keeps the
  operation readable and preserves the exact value being validated. Keep `isPresent()` when presence
  itself is the clearest boolean domain predicate and no value is read. Check this issue state at most
  once per turn before applying this override, and remove the override when the issue is closed.
- Optional issue
  [#71](https://github.com/martinfrancois/java-optionals-skill/issues/71): when an Optional fallback
  performs non-trivial work, preserve lazy fallback behavior and capture missed-trigger cases where
  the installed Optional skill should have activated during the original implementation prompt.
- Optional issue
  [#72](https://github.com/martinfrancois/java-optionals-skill/issues/72): choose `findFirst()` or
  `findAny()` by the encounter-order contract, not by habit. Use `findAny()` for equivalent matches,
  and keep `findFirst()` when first-match behavior is meaningful even if a sequential-stream test
  cannot mechanically force `findAny()` to return a later element.
- Optional issue
  [#74](https://github.com/martinfrancois/java-optionals-skill/issues/74): when an Optional's
  presence only selects between two simple values, prefer `optional.map(...).orElse(...)` over
  `optional.isPresent() ? presentValue : absentValue` if the present branch has no checked exception
  or side effect. Use `orElseGet(...)` instead when the absent branch performs non-trivial work.
- Optional issue
  [#76](https://github.com/martinfrancois/java-optionals-skill/issues/76): when an implementation
  adds validation backed by an Optional catalog lookup, bind the present value once with `map(...)`
  or another direct Optional operation and express the absent case as the fallback. Do not use an
  empty/present check followed by one or more `get()` calls for ordinary value flow. Check this issue
  state at most once per turn before applying this override, and remove the override when the issue
  is closed.
- Optional issue
  [#77](https://github.com/martinfrancois/java-optionals-skill/issues/77): apply eval capture to
  nullable, absent, fallback, and default strategy selection on adjacent owning APIs such as
  `ScopedValue.orElse(...)`, even without `java.util.Optional`. Prefer selecting the prebuilt
  strategy and invoking it once; do not generalize a `ThreadLocal`-to-`ScopedValue` migration into
  an Optional rule. Check this issue state at most once per turn before applying this override, and
  remove this override when the issue is closed.
- Optional issue
  [#78](https://github.com/martinfrancois/java-optionals-skill/issues/78): before a present Optional
  value suppresses non-trivial fallback work, validate that the value is semantically usable by the
  present branch. Use `filter(...)` at the value-producing boundary when an invalid parsed value
  should behave as absence, and keep the fallback lazy with `orElseGet(...)`. Check this issue state
  at most once per turn before applying this override, and remove this override when the issue is
  closed.

## Nullness

- Use JSpecify annotations for Java nullness contracts at reviewed API and integration boundaries.
  Prefer `@NullMarked` at package level only after the whole package has been audited; otherwise use
  type-level `@NullMarked` for a focused model, parser, configuration, or payload boundary.
- Mark intentional null contracts with `@Nullable` on the type use that is nullable, including record
  components, constructor parameters, method parameters, and return values. Do not rely on prose
  comments alone when an annotated boundary accepts or returns `null`.
- Do not add broad mechanical nullness churn or a blocking nullness checker unless an issue and ADR
  define the baseline and expected noise level.

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
- Write comments in plain, specific language. Explain the user-visible or domain reason when there
  is one, and avoid shorthand that only names an internal implementation detail.
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
  templates, constants, literals, path fragments, command names, environment-variable names, or other
  artifacts represent one concept and a maintainer would expect them to change together, centralize
  or derive them from one shared source in the narrowest sensible scope. Strings and numbers are
  common examples, but the rule is about coupled change, not literal type or textual equality. Do not
  centralize independent values only because they happen to look the same today; leave them separate
  when they represent different concepts that could reasonably change independently. When duplicated
  logic would need to change together at multiple call sites, extract a shared helper or abstraction
  so the coupling is explicit and future contributors only have one place to update. Treat making the
  same edit, or copying the same pattern, in two or more places during one change as that signal:
  stop and ask whether the duplicated shape should be refactored before continuing. If those places
  would likely have to change together again, centralize them into a shared helper or constant before
  finishing instead of leaving parallel copies. Apply the same standard to review feedback: when a
  reviewer points out duplicated edits or says two places must change together, treat that as an
  immediate refactoring requirement in the current scope rather than leaving another parallel copy
  for a later pass.
- Before declaring a `CharMatcher`, `Splitter`, or similar reusable parser constant, search
  production and test code for the same construction and semantic family. When the behavior would
  need to change together, centralize it under a domain-named matcher or parsing operation; do not
  leave identical package-local constants or ambiguous names such as `CSV` for a simple
  comma-separated value list.
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
- For Java/JVM project-owned hashes, use SHA3-family algorithms. Use `HmacSHA3-256` for
  tokens that represent private context and may appear in diagnostics, public issue reports, logs,
  or user-facing troubleshooting output. Use `SHA3-256` for deterministic project-owned hashes that
  do not need a key. Use SHA-2 only when an external tool, API, file format, dependency, or
  compatibility contract requires it; make that reason clear nearby when it is not obvious. Use
  SHA-1 only for external interoperability such as existing object identifiers or protocol fields,
  never as a new project-owned security hash.
- Do not introduce broken or unsuitable hash algorithms for new Java/JVM project code. This includes
  MD2, MD4, MD5, SHA-0, SHA-1 for collision-resistant use, original 128-bit RIPEMD, HAVAL,
  GOST R 34.11-94, PANAMA, Snefru/Snefru-n, Tiger, Streebog-512, and the full ECRYPT broken-hash
  catalog. If an external standard or jurisdiction strictly requires one of these algorithms, keep
  that use isolated to the compatibility boundary and document the reason at the call site.
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
