---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #384](https://github.com/martin-francois/symphony-trello/issues/384)"
  - "[SmallRye Common expression module](https://github.com/smallrye/smallrye-common/tree/main/expression)"
  - "[SmallRye Config property expressions](https://smallrye.io/smallrye-config/Main/config/expressions/)"
  - "[Apache Commons Text StringSubstitutor](https://commons.apache.org/proper/commons-text/apidocs/org/apache/commons/text/StringSubstitutor.html)"
informed: [Future maintainers, Contributors]
---

# Keep the hand-rolled environment reference classifier

## Context and Problem Statement

`ch.fmartin.symphony.trello.config.EnvironmentReferences` decides whether a whole configured value
is an environment reference. It has one method, `referenceName(String)`. The method returns the
variable name for `$NAME` and `${NAME}`, after trimming surrounding whitespace. It returns empty for
every other value, which callers then use as literal text. A name starts with a letter or `_` and
continues with letters, digits, or `_`. "Letter" and "digit" follow `Character.isLetter` and
`Character.isDigit`, so `$ÄPFEL` is a reference and `$1A` is not. This is wider than strict POSIX
names, which allow only ASCII letters and digits. The decision keeps that wider rule, because
narrowing it would turn values that resolve today into literal text.

`SPEC.md` defines `tracker.api_key` and `tracker.api_token` as a literal, `$VAR` or `${VAR}`, or a
file reference. Shell default expansions such as `${VAR:-fallback}` are not references and stay
literal text. `ConfigResolver`, `WorkflowConfigIngestion`, `WorkflowConfigEditor`,
`LocalWorkerManager`, and `TrelloCredentialStore` call the method. The credential-file guard in
`TrelloCredentialStore` depends on the classification answer itself, not on an expanded value.
Path settings also accept `$NAME/suffix`. `ConfigResolver` handles that prefix form with its own
code: `repository.default_path` uses the classifier only to check the name, and `workspace.root`
does not use it at all.

The implementation is about 30 lines of owned code with no dependency. Should a maintained library
replace it?

## Decision Drivers

* Keep the accepted input set unchanged. A value that is a reference today must stay one, and a
  literal must stay literal, unless `SPEC.md` changes in the same pull request.
* The API must answer "is this whole value a reference, and to which name". Interpolation inside a
  longer string is a different feature.
* A new dependency must be updatable by Renovate, respect the seven-day release-age cooldown, and
  remove more owned code than it adds.
* Keep the parser easy to audit, because it decides whether a credential value reaches Trello as
  written.

## Considered Options

* Keep the hand-rolled classifier.
* Use SmallRye Config property expressions as they are.
* Classify with the SmallRye Common `Expression` parser plus glue code.
* Use Apache Commons Text `StringSubstitutor` as it is.
* Classify with Apache Commons Text `StringSubstitutor` plus glue code.
* Replace the hand-written loop with a JDK regular expression.

## Decision Outcome

Chosen option: "Keep the hand-rolled classifier", because no candidate library classifies whole
values and every library option either changes the accepted inputs or needs more owned code than it
replaces.

Both candidate libraries interpolate `${...}` inside text. Used as they
are, both change which values count as references (see the table below). Wrapped in glue code that
restores the contract, both still need the owned `$NAME` branch and the owned name check, so the
code grows instead of shrinking:

| Option | Owned lines | Differs from the current contract |
| --- | --- | --- |
| Current classifier | 29 | (baseline) |
| SmallRye Common `Expression` plus glue | 46 | No differences found |
| Commons Text `StringSubstitutor` plus glue | 37 | No differences found |
| JDK regular expression | 12 | Accepts names with letters or digits outside the Basic Multilingual Plane |
| SmallRye Config as it is | 0 | `$NAME` stays literal; `${NAME:-x}` and `${NAME:x}` resolve with a default; text around `${NAME}` is interpolated |
| `StringSubstitutor` as it is | 0 | `$NAME` stays literal; `${NAME:-x}` resolves with a default; text around `${NAME}` is interpolated |

Owned lines count non-blank, non-comment Java lines, including the name check that every glue
variant still needs. The differential spike ran each glue variant and the regular expression
against the current implementation:

* every string of length 0 to 6 over the 12 characters `$ { } : - A 1 _ space é / \`
  (3,257,437 inputs): no differences for any variant;
* 2,000,000 seeded random strings of length 0 to 24, with and without a `$` or `${...}` wrapper,
  mixing those characters, ASCII letters, random characters below U+D800, and supplementary
  characters from U+10000 to U+1FFFF: no differences for both glue variants, 4,760 differences for the
  regular expression. In every regular expression difference, the current classifier rejects and
  the regular expression accepts a name that contains a supplementary letter or digit, such as `$𝐀`.
  The current classifier checks UTF-16 `char` values and a surrogate is neither a letter nor a
  digit, while the regular expression matches whole code points.

`EnvironmentReferencesTest` now pins the contract as a table of accepted and literal shapes. It
includes `${NAME:-fallback}`, `${NAME:fallback}`, `${NAME-fallback}`, `${NAME:?message}`, escaped
`$$NAME`, text before or after a reference, dotted property names, and a name with a supplementary
letter, the one case that separates the regular expression from the current classifier.

### Consequences

* Good, because no value changes meaning. Existing workflows and credential files resolve exactly
  as before.
* Good, because the runtime classpath does not change. Commons Text would add `commons-text` and
  `commons-lang3`, which is test-only today.
* Good, because the contract now has a focused unit test that any later replacement must pass.
* Neutral, because the owned code stays at about 30 lines.
* Bad, because the project keeps owning a small parser. Shell features such as `${VAR:-fallback}`
  remain unsupported until `SPEC.md` chooses them.

### Confirmation

* `EnvironmentReferences.referenceName` keeps its own parsing and imports no interpolation library.
* `EnvironmentReferencesTest`, `ConfigResolverTest.keepsShellDefaultExpansionSyntaxAsLiteralText`,
  and `ConfigResolverTest.resolvesBraceStyleEnvironmentReferencesForCredentials` pass in
  `./mvnw -q spotless:check verify`.
* `pom.xml` declares no `commons-text` dependency for this purpose.

## Pros and Cons of the Options

### Keep the hand-rolled classifier

Trim the value, strip a leading `$` and optional surrounding braces, and accept the rest only when
it is a valid name. This is the current `EnvironmentReferences` implementation.

* Good, because the code states the contract directly and has no dependency.
* Good, because it answers the classification question that the credential-file guard needs.
* Bad, because the repository maintains the parser and its tests.

### Use SmallRye Config property expressions as they are

Read workflow values through SmallRye Config, which is already on the classpath through Quarkus,
and let its expression interceptor expand `${...}`.

* Good, because it needs no new dependency and no owned parsing.
* Bad, because `$NAME`, the form that setup writes into generated workflows, is not an expression
  and would stay literal.
* Bad, because the default delimiter is `:`. In the spike, `${TRELLO_API_KEY:-fallback}` resolved to
  `-fallback` when the variable was unset, and `${TRELLO_API_KEY:fallback}` resolved to `fallback`.
  Both are literal today.
* Bad, because it interpolates inside text, so `abc${TRELLO_API_KEY}def` would become a resolved
  value instead of a literal.
* Bad, because it returns an expanded value, not the referenced name, so the credential-file guard
  cannot use it.

### Classify with the SmallRye Common `Expression` parser plus glue code

Use `io.smallrye.common.expression.Expression`, the parser behind SmallRye Config expressions.
Compile the trimmed value with `NO_TRIM`, `NO_RECURSE_KEY`, `NO_RECURSE_DEFAULT`, `NO_SMART_BRACES`,
and `NO_$$`. Then evaluate it with a callback that records each key and whether it has a default,
and accept the value only when it expands to exactly one key without a default.

* Good, because the module is already on the runtime classpath through Quarkus, and the Quarkus BOM
  manages its version.
* Good, because the spike found no behavior difference from the current classifier.
* Bad, because the parser has no multi-character `$NAME` form (`MINI_EXPRS` covers `$X` only), so
  the owned `$NAME` branch stays.
* Bad, because keys are not validated, so the owned name check stays. `${a.b}` and `${ A }` are
  valid keys for the library.
* Bad, because the glue needs a sentinel value, a mutable key list, and a malformed-input catch.
  The result is 46 owned lines instead of 29, and readers must know the library flags to follow it.
* Bad, because the project would declare a direct dependency on a module it only gets transitively
  today.

### Use Apache Commons Text `StringSubstitutor` as it is

Add `org.apache.commons:commons-text` and replace values with
`new StringSubstitutor(environment).replace(value)`. The Quarkus BOM manages the version (1.15.0,
released in December 2025, outside the seven-day cooldown), so Renovate would update it with
Quarkus.

* Good, because it is a widely used, maintained library and the call is one line.
* Bad, because `$NAME` stays literal.
* Bad, because the default delimiter is `:-`. In the spike, `${TRELLO_API_KEY:-fallback}` resolved to
  `fallback` when the variable was unset. It is literal today.
* Bad, because it interpolates inside text and returns an expanded value, not the referenced name.
* Bad, because it adds two runtime jars, `commons-text` (265 KB) and `commons-lang3` (714 KB).

### Classify with Apache Commons Text `StringSubstitutor` plus glue code

Configure `StringSubstitutor` with a recording lookup, no value delimiter, and no nested
substitution, and accept the value only when it expands to exactly one recorded key.

* Good, because the spike found no behavior difference from the current classifier.
* Bad, because the owned `$NAME` branch and the owned name check stay.
* Bad, because the result is 37 owned lines plus two runtime jars, to replace 29 lines.

### Replace the hand-written loop with a JDK regular expression

Match the trimmed value against
`\$(?:\{([\p{L}_][\p{L}\p{Nd}_]*)\}|([\p{L}_][\p{L}\p{Nd}_]*))`.

* Good, because it is the shortest option (12 lines) and adds no dependency.
* Bad, because it is not a library replacement. The project still owns the grammar, now as a
  regular expression.
* Bad, because it accepts names with supplementary letters or digits that the current classifier
  rejects.
  The difference is unlikely to matter, but it is a contract change and the option offers no
  behavior gain to justify it.

## More Information

The spike copied the current classifier into a standalone Java program and compared every option
against it on the same inputs. It ran on Java 25 with SmallRye Common expression 2.19.0, SmallRye
Config 3.17.2 (both from the Quarkus 3.39.5 BOM), and Commons Text 1.15.0 with Commons Lang 3.20.0.
The description of the pull request that closes
[GitHub issue #384](https://github.com/martin-francois/symphony-trello/issues/384) contains the spike
source and its output.

Revisit this decision if `SPEC.md` adopts shell default expansion or interpolation inside values.
Then the interpolation libraries match the new contract, and `EnvironmentReferencesTest` must change
with it.

[GitHub issue #385](https://github.com/martin-francois/symphony-trello/issues/385) evaluates the
`LocalEnvironment` dotenv loader and
[GitHub issue #386](https://github.com/martin-francois/symphony-trello/issues/386) evaluates the
`CredentialValue` resolution. Both are separate decisions.
[GitHub issue #799](https://github.com/martin-francois/symphony-trello/issues/799) tracks that the
separate path prefix code misresolves `${NAME}/suffix`.
