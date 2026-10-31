---
status: accepted
date: 2026-10-31
decision-makers: [François Martin]
consulted:
  - "[GitHub issue #786](https://github.com/martin-francois/symphony-trello/issues/786)"
  - "[GitHub changelog: Actions YAML anchors and non-public workflow templates](https://github.blog/changelog/2025-09-18-actions-yaml-anchors-and-non-public-workflow-templates/)"
  - "[actions/runner issue #1182: merge keys not supported by the new parser](https://github.com/actions/runner/issues/1182#issuecomment-2810242069)"
  - "[YAML 1.2.2 specification](https://yaml.org/spec/1.2.2/)"
  - "[Hugo v0.152.0 release](https://github.com/gohugoio/hugo/releases/tag/v0.152.0)"
  - "[Kubernetes 1.27: server side field validation GA](https://kubernetes.io/blog/2023/04/24/openapi-v3-field-validation-ga/)"
  - "[Spring Boot OriginTrackedYamlLoader](https://github.com/spring-projects/spring-boot/blob/v3.5.0/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/env/OriginTrackedYamlLoader.java)"
  - "[Ruby Psych safe_load](https://github.com/ruby/psych/blob/master/lib/psych.rb)"
  - "[FasterXML/jackson-dataformats-text issue #98](https://github.com/FasterXML/jackson-dataformats-text/issues/98)"
informed: [Operators, Contributors, Future maintainers]
---

# Resolve YAML aliases and reject merge keys and repeated keys in workflow front matter

## Context and Problem Statement

`WorkflowLoader`, the startup `server.port` read and the setup editor read `WORKFLOW.md` front
matter with Jackson's YAML reader (`jackson-dataformat-yaml` 2.21 on SnakeYAML 2.6). That reader
returns an alias as its anchor name and does not apply merge keys
([FasterXML/jackson-dataformats-text issue #98](https://github.com/FasterXML/jackson-dataformats-text/issues/98)).
The setup editor built its reader with `YAMLFactory.builder()`, which leaves out Jackson's default
`EMPTY_STRING_AS_NULL` feature, so it read a key without a value as `""` where the loader and the
startup read gave `null`.
[GitHub issue #786](https://github.com/martin-francois/symphony-trello/issues/786) lists what an
operator got before this decision:

| Front matter | Result before |
| --- | --- |
| `a: &x [1, 2]` and `b: *x` | `b` was the string `x` |
| `base: &d {t: 5}` and `c: {<<: *d}` | `c` was `{"<<": "d"}` |
| `a: 1` followed by `a: 2` | `{a: 2}`, no warning |
| nothing or only a comment between the `---` lines | `workflow_parse_error` |
| `null` between the `---` lines | empty config |

The first three load a config the YAML does not describe, without an error. `SPEC.md` section 5.2
only said that the front matter must decode to a map, and the upstream Symphony specification says
nothing more. The Symphony reference implementation (`openai/symphony` at `be10a1b`, Elixir,
`yaml_elixir` 2.12.0) has the same gaps: when run, it resolves block aliases but gives `b: 1` for a
flow-list anchor, turns `<<` into a literal `"<<4"` key, keeps the first of two repeated keys, and
returns an empty map for empty, comment-only and `null` front matter.

How should front matter treat aliases, merge keys, repeated keys and empty content, and how should
the Java code implement it?

## Decision Drivers

* A workflow loads the config its YAML describes, or fails with a `workflow_parse_error` that names
  the problem and the key.
* Front matter that loaded before and uses none of the four cases loads to exactly the same values
  and Java types: `yes` and `on` stay booleans, `0o17` stays a string, `1_000` stays an integer,
  timestamps stay strings, and keys stay strings.
* A small file cannot expand into a huge config through nested aliases (billion laughs).
* Follow what widely used YAML consumers do, and YAML 1.2.
* The loader, the startup port read and the setup editor read front matter the same way.
* Prefer an established library or a documented extension point over hand-written parsing.

## Considered Options

Rules:

* Resolve aliases with a limit, reject merge keys, reject repeated keys, empty front matter is an
  empty config.
* Also resolve merge keys.
* Reject aliases.
* Keep the first or the last of repeated keys.

Implementation of the chosen rules:

* Rewrite SnakeYAML events in Jackson's `YAMLParser.getEvent()` hook and check keys in
  `nextToken()`.
* Jackson's `YAMLAnchorReplayingFactory`.
* SnakeYAML's `Yaml.load` or SnakeYAML Engine with `LoadSettings`.
* SnakeYAML's composer for the node graph, then a hand-written conversion to Java values.

## Decision Outcome

Chosen rules, as `SPEC.md` section 5.2 states them:

1. An alias resolves to the anchored scalar, sequence or map, in block and flow style. An alias
   without an earlier anchor, or inside the node its own anchor marks, is a `workflow_parse_error`.
   All aliases of one front matter must not copy more than 10,000 YAML nodes or more than
   1,000,000 characters of scalars in total; [Alias limits](#alias-limits) explains both values. A
   copy keeps the value an inner alias had where the anchor was defined.
2. A merge key is a `workflow_parse_error` that names the key's path, anywhere in the document. A
   merge key is the plain scalar `<<` or a key tagged `!!merge`, the scalars YAML 1.1 resolves to
   the merge type. A quoted `"<<"`, or `! <<` with the non-specific tag, stays an ordinary string
   key, as it loaded before.
3. A key that appears twice in one map is a `workflow_parse_error` that names the key's path. Keys
   compare by their text, which is how the config map stores them, so `a` and `"a"`, or `1` and
   `"1"`, are the same key.
4. Empty front matter, and front matter with only blank lines, spaces and comments, is an empty
   config, like `null`. A tab that starts a line, alone or before a comment, stays a YAML syntax
   error, as it was before.

Chosen implementation: `WorkflowFrontMatterYaml` subclasses Jackson's `YAMLFactory` and
`YAMLParser`. Its parser overrides `getEvent()`, which Jackson documents as the hook "to influence
the events being consumed", for example to work around "the lack of anchor and alias support". It
keeps the events of anchored nodes in one list, in document order, with each alias inside an
anchored node kept as a reference to the node it named. An anchor is a range of that list, and an
alias hands Jackson the events of its range in place of the alias, so Jackson decodes every scalar
exactly as before. Memory grows with the size of the front matter, not with how deeply anchored
nodes nest. Its `nextToken()` override checks each key Jackson reads for the merge tag and for a
repeat in the same map, and names the key with Jackson's parsing context. Jackson's own
`YAMLParser.Feature.EMPTY_DOCUMENT_AS_EMPTY_OBJECT` handles empty front matter. The loader,
`WorkflowConfigIngestion.collectFrontMatter` and `WorkflowConfigEditor` all read through it. Setup
failure messages keep the rule violation's text, because it names a key path or an alias and never a
value: a key that an alias supplies appears as the alias, such as `*k`.

A setup write that rewrites the front matter, such as the Trello tool settings that setup keeps on
regeneration, stores each alias as a written-out copy of its value and drops the anchors. The
values stay the same; only the sharing is lost.

### Alias limits

`WorkflowFrontMatterYaml.MAX_ALIAS_EXPANDED_NODES` and
`WorkflowFrontMatterYaml.MAX_ALIAS_COPIED_CHARACTERS` hold the two limits. The tests read these
constants. `SPEC.md` section 5.2 repeats both values in prose, and `docs/fuzzing.md` repeats the
node limit.

* 10,000 nodes: the node limit stops nested aliases, where each level copies the level below
  several times (billion laughs). A real workflow copies a few hundred nodes at most; the whole
  front matter of `WORKFLOW.example.md` has 117 nodes, keys included.
* 1,000,000 characters: the node limit alone lets 9,999 aliases of one 200 KB scalar, a 224 KB
  file, load as about 2 billion characters, which a setup write would then store. The longest
  values in a real workflow are hook scripts of a few kilobytes, and the whole front matter of
  `WORKFLOW.example.md` is 2,231 characters, so a workflow could copy that front matter 448 times
  and stay below the limit. Every character of a copied scalar counts, keys included.

A copy also counts toward Jackson's nesting limit of 1,000 levels. Front matter that nests deeper
without aliases was already a parse error; a copy that goes past the limit now fails with a message
that names the alias instead of the generic invalid-YAML message.

Alternatives for the limits:

* Count aliases instead of copied nodes, as SnakeYAML's `maxAliasesForCollections` does. Rejected,
  because a few aliases per level still copy an exponential number of nodes.
* Bound the size of the front matter or of the written file instead. Rejected, because the loader
  and the startup read would still build the huge config in memory before any write.
* A single limit on copied characters. Rejected, because aliases of short scalars or empty
  collections copy many nodes with few characters, and each node costs memory of its own.

### Consequences

* Good, because anchors now copy values, the way GitHub Actions, Docker Compose, Hugo and Spring
  Boot read them.
* Good, because a merge key or a repeated key fails at load time with the key's path and the line,
  instead of running with values the author did not intend.
* Good, because every other front matter keeps its values and types in the loader and the startup
  read; a differential test compares the old and the new reader on the repository's workflows,
  fixtures and hand-made YAML. Setup differs only for a key without a value, as described below.
* Good, because one class owns front matter reading, so the startup port check and setup cannot
  disagree with the loader.
* Bad, because this is a breaking change. A workflow that loaded before now fails when it has a
  repeated key, a merge key (`<<` or a key tagged `!!merge`), an alias without an earlier anchor, an
  alias inside its own anchored node, aliases past either limit, or an alias whose copy nests the
  front matter deeper than 1,000 levels; the old reader loaded those aliases as their anchor names.
  The operator must remove the repeated key, write the merged keys out, and replace each failing
  alias with its value.
* Bad, because the replay and key checks are about 250 lines of code on Jackson 2.x internals
  (`getEvent()`, `_lastEvent`, `_yamlResolver`). A move to Jackson 3 must port them.
* Bad, because a setup write that rewrites the front matter turns aliases into copies.
* Neutral, because setup now reads a key without a value as `null`, as the loader always did.
  Regeneration writes such a Trello tool setting as `null` instead of `""`, and an empty
  `trello_tools:` section as `{}` where setup used to fail with `setup_workflow_invalid`.
* Neutral, because SnakeYAML 2.6 was already on the classpath through Jackson's YAML module. The
  reader imports SnakeYAML's event classes, so `pom.xml` now declares it directly, at the version
  the Quarkus BOM manages.

### Confirmation

* `WorkflowFrontMatterYamlRulesTest` covers each rule in block and flow style, nested and inside
  sequences, an alias to a merge-key mapping, keys that differ only in quoting, a repeated key that
  an alias supplies, an anchor redefined later and inside its own node, an undefined alias, and
  both alias limits at and one past the threshold.
* `WorkflowFrontMatterYamlCostTest` checks that anchors nested 200 deep allocate about what plain
  Jackson allocates for the same front matter. It also checks that a billion-laughs document, a
  long scalar copied by many aliases, and deep nesting fail within a bounded time as a
  `workflow_parse_error` in the loader and as no config in the startup read, not as an `Error`.
* `WorkflowFrontMatterYamlDifferentialTest` reads every front matter found in
  `WORKFLOW.example.md`, the fuzz seed corpus and the Java text blocks, plus hand-made scalars and
  structures, through plain Jackson and through `WorkflowFrontMatterYaml`, and requires the same
  values with the same Java types or a failure on both sides. SnakeYAML's own parser and composer
  pick out the inputs that use one of the four rules, so the filter does not depend on the code
  under test.
* `WorkflowLoaderInvariants` checks every fuzz input against SnakeYAML's composer: no content gives
  an empty config, a merge or repeated key fails, and an accepted config has the maps, lists and
  keys of the YAML with aliases resolved. `WorkflowConfigInvariants` checks that the startup port
  read agrees with the loader.

## Pros and Cons of the Options

### Resolve aliases with a limit, reject merge keys and repeated keys, empty is empty

Aliases copy values; merge keys and repeated keys fail; empty front matter loads.

* Good, because GitHub Actions resolves anchors and aliases since 2025-09-18 and rejects merge keys
  ([changelog](https://github.blog/changelog/2025-09-18-actions-yaml-anchors-and-non-public-workflow-templates/);
  a comment for the Actions team on
  [actions/runner issue #1182](https://github.com/actions/runner/issues/1182#issuecomment-2810242069)
  says its new parser does not support merge keys). Docker Compose resolves anchors. Correction to
  issue #786: the issue says GitHub rejects merge keys because they are not part of YAML 1.2, but
  the cited comment gives parser support as the reason.
* Good, because YAML 1.2 removed merge keys and requires unique keys in a map.
* Good, because Hugo 0.152.0 (goccy/go-yaml), Kubernetes 1.27 (server-side field validation, which
  `kubectl` runs in `Strict` mode) and Spring Boot (`setAllowDuplicateKeys(false)` in
  `OriginTrackedYamlLoader`) reject repeated keys.
* Bad, because a workflow that used either rejected form stops loading.

### Also resolve merge keys

Apply `<<` as YAML 1.1 and Docker Compose do.

* Good, because Docker Compose users know the form.
* Bad, because it is not part of YAML 1.2, and GitHub Actions rejects it.
* Bad, because precedence between merged and local keys, and lists of merged maps, add rules the
  operator has to learn. Writing the keys out is short for a workflow file.

### Reject aliases

Fail on any alias, as Ruby Psych's `safe_load` does by default (`aliases: false`).

* Good, because no expansion limit is needed.
* Bad, because GitHub Actions, Docker Compose, Hugo and Spring Boot accept aliases, and operators
  who use anchors to share settings would have to copy values.

### Keep the first or the last of repeated keys

Accept the map and pick one value, as the reference implementation (first) and the old Jackson
reader (last) did.

* Good, because no workflow breaks.
* Bad, because one of the values the author wrote is ignored without a message.

### Rewrite events in Jackson's getEvent hook (chosen)

Subclass `YAMLParser`, replace aliases with recorded events in `getEvent()`, and check keys in
`nextToken()`.

* Good, because Jackson keeps decoding scalars, so types match the old reader by construction.
* Good, because `getEvent()` is a documented hook for this purpose.
* Bad, because the replay, the node count and the key checks are code this project maintains.

### Jackson's YAMLAnchorReplayingFactory

Jackson 2.19 added a parser subclass that replays anchors.

* Good, because it is maintained in Jackson.
* Bad, because it applies merge keys instead of letting the loader reject them.
* Bad, because it treats every scalar `<<`, including a quoted value such as `a: "<<"`, as a merge
  key and fails on it, so front matter that loaded before would break.
* Bad, because in 2.21.7 an alias inside an anchored node is not recorded: `a: &x 1`,
  `b: &y [*x]`, `c: *y` gives `c: []`.
* Bad, because its limit counts events per replay buffer, not in total, so many aliases of a large
  anchor still multiply the config.

### SnakeYAML Yaml.load or SnakeYAML Engine

Construct Java values with SnakeYAML (`LoaderOptions`) or SnakeYAML Engine (`LoadSettings` with
`setAllowDuplicateKeys(false)` and `setMaxAliasesForCollections`).

* Good, because duplicate detection and an alias limit come built in.
* Bad, because the values change type. SnakeYAML resolves timestamps to `Date` and keeps
  non-string keys. SnakeYAML Engine follows YAML 1.2, which reads `yes` and `on` as strings where
  the old reader gave `true`.
* Bad, because SnakeYAML's constructor applies merge keys, and its duplicate check compares Java
  key objects, so `1` and `"1"` would both load and then collide in the string-keyed config map.
* Bad, because SnakeYAML Engine is a new dependency.
* Bad, because `maxAliasesForCollections` counts aliases, not copied nodes: 50 aliases nested two
  per level still copy 2^25 nodes.

### SnakeYAML composer plus a hand-written conversion

Compose the node graph with SnakeYAML, check it, then convert nodes to Java values.

* Good, because the composer resolves aliases and reports undefined ones.
* Bad, because the conversion must copy Jackson's scalar typing rule by rule, which is the part
  most likely to drift and change existing configs.

## More Information

* Jackson's `getEvent()` hook exists since 2.18; the `_lastEvent` and `_yamlResolver` fields the
  key check reads are protected fields of `YAMLParser` 2.x.
* Revisit this decision when the project moves to Jackson 3 (`tools.jackson.dataformat.yaml`), or
  when Jackson's YAML reader can resolve aliases without applying merge keys.
