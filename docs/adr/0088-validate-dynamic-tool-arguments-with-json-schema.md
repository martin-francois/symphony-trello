---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #584](https://github.com/martin-francois/symphony-trello/issues/584)"
  - "[GitHub issue #563](https://github.com/martin-francois/symphony-trello/issues/563)"
  - "[ADR 0003](0003-scoped-trello-handoff-tools.md)"
  - "[networknt json-schema-validator 2.0.7 README](https://github.com/networknt/json-schema-validator/blob/2.0.7/README.md)"
  - "[Jackson advisory GHSA-5jmj-h7xm-6q6v](https://github.com/advisories/GHSA-5jmj-h7xm-6q6v)"
informed: [Future maintainers, Contributors]
---

# Validate Dynamic-Tool Arguments With JSON Schema

## Context and Problem Statement

Symphony advertises six Trello dynamic tools to Codex. Each tool has a JSON `inputSchema` that sets
`required`, the property `type`, `minLength: 1`, an `enum` for the blocker-recheck status, and
`additionalProperties: false`. Until this decision, the runtime handler did not read that schema.
Local helpers (`requiredText`, `requiredBoolean`, `text`) and one status branch enforced a second,
hand-written copy of the rules.

The two copies had already drifted. Since the tools were added in May 2026, the advertised schemas
said `minLength: 1` and `additionalProperties: false`, but the handler did the following:

* accepted an empty string for the optional `name`, `list_name`, and `list_id` arguments;
* treated `null` as an absent optional argument instead of a wrong type;
* ignored undeclared properties such as `card_id`, which several tool descriptions tell Codex not to
  send.

How should the handler enforce the argument shape so the advertised schema and the runtime rules
cannot drift apart again?

## Decision Drivers

* One schema object should drive both the advertisement and the runtime validation.
* Trello permissions and domain policy stay in Symphony code.
* Failure results must stay structured and must not echo raw argument values.
* No second Jackson stack, no remote schema loading, and no optional native or JavaScript regex
  engines.
* The change should not grow the handler much more than the checks it removes.

## Considered Options

* Validate with `com.networknt:json-schema-validator` 2.0.x against the advertised schema node.
* Keep the local checks and add a schema/handler conformance test.
* Generate local validation code from a shared internal model.
* Use `dev.harrel:json-schema` or `erosb/json-sKema`.
* Use `com.networknt:json-schema-validator` 3.x.

## Decision Outcome

Chosen option: "Validate with `com.networknt:json-schema-validator` 2.0.x against the advertised
schema node", because it removes the second copy of the rules and adds one point of PMD cognitive
complexity in total.

The handler builds each tool definition once, in a static constant. The `inputSchema` node in that
definition is the node the validator compiles. `toolSpecs` advertises a deep copy, so a caller
cannot edit the node after compilation. `handle` validates `arguments` after the unsupported-tool,
`trello_tools.enabled`, `allow_writes`, and current-card checks and before per-tool policy or any
Trello request. The registry uses draft 2020-12, fail-fast mode, English messages, JSON-path
locations, and the JDK regular-expression engine. The schemas contain no `$ref` or `$schema`, and
the library's default loader does not fetch remote resources.

Behavior that this decision sets:

* A schema violation fails with error code `invalid_tool_arguments` and the message
  `Invalid <tool> arguments: <location>: <rule>`, for example
  `Invalid trello_add_comment arguments: $.text: integer found, string expected`. The library
  message names the argument and the rule, and for an undeclared property it repeats that
  property's name, which Codex chose. It does not contain the supplied value. The message goes back
  to Codex only; Symphony does not log it.
* Unknown properties are rejected. The advertised schemas have said `additionalProperties: false`
  since the tools were introduced, and the tool descriptions already tell Codex not to send a card
  id. Codex sees the failure in the same turn and can retry with the declared arguments.
* `null` for any argument is a wrong type, and an empty string fails `minLength`, for optional
  arguments too.
* A required string that is only whitespace fails with the same error code. This rule stays in
  Symphony because JSON Schema has no portable way to express it: `\S` matches different characters
  in ECMA-262 and in the JDK engine.
* Absent `arguments` fail as a non-object.
* Before this decision, invalid arguments failed with `trello_tool_failed` and messages such as
  `Missing required argument: text`, and an invalid recheck status failed with
  `invalid_blocker_recheck_status`. Neither code was documented in `SPEC.md`, workflows, or skills.

Measured on the handler (`TrelloHandoffToolHandler.java`), from `main` at `0d135edf` to this change:

| Measure                                    | Before | After |
| ------------------------------------------ | -----: | ----: |
| Physical lines                             |  1,220 | 1,270 |
| PMD 7 cognitive complexity, file total     |    198 |   199 |
| `handle`                                   |      7 |     7 |
| `toolSpecs`                                |      5 |     5 |
| `updateBlockerRecheckStatus`               |      7 |     5 |
| `requiredText` + `requiredBoolean` + `text`|      3 |     - |
| `argumentViolation` (new)                  |      - |     5 |
| `addToolSpecs` (new)                       |      - |     1 |

The change deletes 28 lines of local shape checks: the three helpers (19 lines), the status branch
(3 lines), and the six-way unsupported-tool condition (6 lines). The registry configuration, the
`argumentViolation` record method, and building the definitions once in a static constant add the
rest, for a net gain of 50 lines, or 4% of the file. The registry configuration and its two key
constants take 14 of them, and the `ToolDefinition` record with `argumentViolation` takes 20.
Together with the one PMD point, this is not a material expansion for removing a second rule set
that had already drifted in three ways. Workpad, blocker-recheck, checklist,
attachment, and move algorithms did not change, and the table claims no reduction for them.

The dependency adds `json-schema-validator` 2.0.7 (580,450 bytes) and `com.ethlo.time:itu` 1.14.0
(57,633 bytes), both Apache-2.0 and pure Java. Version 2.0.8 was published on 2026-10-02 and was
still inside the repository's seven-day release-age window.

### Consequences

* Good, because `required`, `type`, `minLength`, `enum`, and `additionalProperties` now come from
  the advertised schema only. Editing a schema changes the runtime check in the same edit.
* Good, because the three drift cases listed above are fixed.
* Good, because the error code is specific: Codex and humans can tell an argument mistake from a
  Trello failure.
* Bad, because failure messages now come from the library. A library update can change their
  wording; the handler tests pin the exact text, so such a change fails the build and gets reviewed.
* Bad, because a tool call that sends an undeclared property now fails instead of succeeding. For
  `trello_update_blocker_recheck_status`, the workflow stops the attempt on any failure, so a model
  that keeps sending extra properties would stop that attempt too.
* Bad, because the build has one more runtime dependency of about 638 KB.

### Confirmation

* `TrelloHandoffToolHandlerTest.rejectsArgumentsOutsideTheAdvertisedSchemaBeforeTrelloIo` covers
  every tool with missing, `null`, wrong-type, empty-string, whitespace-only, unknown-property, and
  invalid-enum cases. Each case asserts the exact failure and that no Trello request happened.
* `validatesConcurrentCallsAgainstTheSharedCompiledSchemas` runs eight threads against one handler
  and checks every result.
* `omitsRawArgumentValuesFromValidationFailures` checks that a supplied value never appears in a
  failure.
* `./mvnw -q dependency:tree -Dincludes='com.fasterxml.jackson*,tools.jackson*'` shows only Jackson
  2.22.2 artifacts (annotations `2.22`) and no `tools.jackson` artifact. The validator itself
  declares Jackson 2.22.1, and the Quarkus BOM raises it.
* The row for `json-schema-validator` in
  [Dependency upgrade confidence](../testing/dependency-upgrade-confidence.md) names the tests that
  catch a bad update.

## Pros and Cons of the Options

### Validate with `com.networknt:json-schema-validator` 2.0.x against the advertised schema node

Compile each advertised `inputSchema` node once with the networknt 2.0.x validator, the line that
uses Jackson 2, and validate `arguments` against it before dispatch. Domain rules stay in the
handler.

* Good, because there is one source of truth for argument shape.
* Good, because `Schema` instances are documented as thread-safe once built, so the handler can
  share them across Codex sessions.
* Good, because the library needs no remote fetch, no Joni, and no GraalJS for these schemas.
* Neutral, because the PMD total moves from 198 to 199, within the 0 to 3 points the issue
  expected.
* Bad, because messages follow the library's wording.

### Keep the local checks and add a schema/handler conformance test

Keep `requiredText`, `requiredBoolean`, `text`, and the status branch, and add a test that compares
them with the advertised schemas.

* Good, because it adds no dependency.
* Bad, because the test would have to restate each rule a third time to compare the other two.
* Bad, because it would not have caught the drift cases above unless someone thought to list them.

### Generate local validation code from a shared internal model

Describe the arguments in a Symphony-owned model and derive both the JSON schema and the checks from
it.

* Good, because it adds no dependency.
* Bad, because it builds and maintains a partial schema engine inside Symphony.

### Use `dev.harrel:json-schema` or `erosb/json-sKema`

Use one of two smaller, active JSON Schema libraries.

* Good, because both are maintained and licensed for this use.
* Bad, because each was below the 100-star adoption bar from
  [GitHub issue #563](https://github.com/martin-francois/symphony-trello/issues/563) (34 and 81 stars
  when that audit ran).

### Use `com.networknt:json-schema-validator` 3.x

Use the newest networknt line.

* Good, because it is the newest release line.
* Bad, because it requires Jackson 3 and would add a second Jackson stack beside the one Quarkus
  manages.

## More Information

The prerequisite from [GitHub issue #584](https://github.com/martin-francois/symphony-trello/issues/584)
was a patched Jackson for
[GHSA-5jmj-h7xm-6q6v](https://github.com/advisories/GHSA-5jmj-h7xm-6q6v). The Quarkus 3.39.5 BOM
provides Jackson 2.22.2, so the validator did not need its own Jackson override.

Revisit this decision if Codex starts sending properties outside the advertised schema often enough
to stop runs, if networknt ends the 2.x line, or if Quarkus moves to Jackson 3. In the last case,
evaluate the 3.x line again.
