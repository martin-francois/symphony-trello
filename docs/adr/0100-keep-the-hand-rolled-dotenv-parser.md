---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #385](https://github.com/martin-francois/symphony-trello/issues/385)"
  - "[dotenv-java](https://github.com/cdimascio/dotenv-java)"
  - "[Quarkus .env file support](https://quarkus.io/guides/config-reference#env-file)"
  - "[SmallRye Config DotEnvConfigSourceProvider](https://github.com/smallrye/smallrye-config/blob/main/implementation/src/main/java/io/smallrye/config/DotEnvConfigSourceProvider.java)"
  - "[GitHub PR #801](https://github.com/martin-francois/symphony-trello/pull/801)"
informed: [Future maintainers, Contributors]
---

# Keep the hand-rolled dotenv parser

## Context and Problem Statement

`ch.fmartin.symphony.trello.config.LocalEnvironment` does two things. It parses dotenv files, and it
resolves settings from the process environment first and from a dotenv file second.

The parser reads one line at a time. It skips blank lines and `#` comments, removes an `export `
prefix, and accepts a key only when it starts with a letter or `_` and continues with letters,
digits, or `_`. A double-quoted value decodes `\"`, `\\`, `\b`, `\f`, `\n`, `\r`, and `\t` and keeps
other backslashes as written, so Windows paths work. A single-quoted value is literal. A `# comment`
after a closing quote is dropped. In an unquoted value, `#` starts a comment only after whitespace,
so `abc#def` stays whole. When text other than a comment follows the closing quote, the parser keeps
the whole value and only removes matching outer quotes. A value never continues on the next line.
Exactly one leading UTF-8 byte order mark is ignored. A file that cannot be read gives an empty map.

Most of these rules come from real user files. The trailing-comment and byte-order-mark handling
were fixes for
[GitHub issue #240](https://github.com/martin-francois/symphony-trello/issues/240). The setup flow
writes credentials with `TrelloCredentialStore.dotenvValue`, and those values must read back
unchanged. Callers load arbitrary files such as `.env.NAME` for each board, and nothing may change
the process environment.

The parser is about 125 lines of owned code with no dependency. Should a maintained dotenv library
replace it, or should the class be split?

## Decision Drivers

* Existing `.env` files must resolve to the same values. Any change in which lines count or how a
  value is unquoted or unescaped is a silent change for installed users.
* Values written by the setup flow must read back unchanged.
* Process environment values keep winning over dotenv values. The parser must load a file at an
  explicit path and must not change the process environment or system properties.
* An unreadable or missing file keeps giving an empty map without an error.
* A new dependency must be updatable by Renovate, respect the seven-day release-age cooldown, and
  remove more owned code than it adds.

## Considered Options

* Keep the hand-rolled parser.
* Use dotenv-java as it is.
* Parse values with dotenv-java plus glue code.
* Read the file through the SmallRye Config dotenv source.
* Split the parser into its own class without a new dependency.

## Decision Outcome

Chosen option: "Keep the hand-rolled parser", because no library reads existing files the same way,
and the glue that brings dotenv-java close to the contract owns most of the rules again while it
still disagrees on values with `#`.

A differential spike compared each option with a copy of the current parser:

| Option | Owned lines | Exhaustive files that differ | Random files that differ | Written values that do not read back |
| --- | --- | --- | --- | --- |
| Current parser | 125 | (baseline) | (baseline) | 0 |
| dotenv-java as it is | 0 | 188,538 | 236,641 | 320,650 |
| dotenv-java plus glue | 77 | 21,891 | 111,493 | 7,448 |
| SmallRye Config dotenv source as it is | 0 | 4,839,891 | 818,881 | 906,812 |

Owned lines count non-blank, non-comment Java lines. The glue count includes the three helpers of
the current parser that the glue still needs: the byte order mark check, the key check, and the
double-quote unescaping. The spike used three inputs:

* every sequence of up to 6 symbols over the 13 symbols `A`, `=`, `"`, `'`, `#`, `\`, space, line
  feed, carriage return, byte order mark, `n`, `-`, and `export `, read as file content (5,229,043
  files, 164,450 of which give at least one entry);
* 1,000,000 seeded random files of one to three lines, built from keys, quotes, escapes, comments,
  `export` prefixes, `${...}`, line endings, byte order marks, and random Unicode characters;
* 1,000,000 seeded random values written as `TRELLO_API_KEY=<dotenvValue(value)>` and read back.
  The 162 values with a line break were skipped, because the writer rejects them.

The spike also read the hand-picked cases through `LocalEnvironment.load` and through the real
dotenv-java file loader, to confirm that its in-memory copies behave like the real code.

`LocalEnvironmentTest` now also pins the line shapes where the candidates differ: the remaining
escapes, single-quoted backslashes, an escaped quote before `#`, the whole-value fallback, no line
continuation for an open quote or a trailing backslash, Windows line endings, literal `${...}`,
dotted and `KEY:value` lines, and an invalid UTF-8 file. `TrelloCredentialStoreTest` checks that
values written by the setup flow read back unchanged.

### Consequences

* Good, because every existing `.env` file resolves exactly as before.
* Good, because the runtime classpath does not change.
* Good, because the format now has focused tests that any later replacement must pass, including a
  round trip with the setup writer.
* Bad, because the project keeps owning about 125 lines of parser code and its tests.
* Neutral, because `LocalEnvironment` keeps both jobs in one class of about 220 lines.

### Confirmation

* `LocalEnvironment` keeps its own parsing and imports no dotenv library.
* `pom.xml` declares no `io.github.cdimascio:dotenv-java` dependency, and no code reads `.env`
  files through `DotEnvConfigSourceProvider`.
* `LocalEnvironmentTest` and `TrelloCredentialStoreTest.writtenDotenvValuesReadBackUnchanged` pass
  in `./mvnw -q spotless:check verify`.

## Pros and Cons of the Options

### Keep the hand-rolled parser

`LocalEnvironment.load` reads the file with `Files.readAllLines` and parses each line with the rules
in the context section. This is the current implementation.

* Good, because the code states each rule directly, and each rule has a test.
* Good, because the reader and the setup writer agree on every value the spike wrote.
* Bad, because the repository maintains the parser.

### Use dotenv-java as it is

Add `io.github.cdimascio:dotenv-java` 3.2.0 and load a file with
`Dotenv.configure().directory(dir).filename(name).ignoreIfMissing().ignoreIfMalformed().load()`,
then read only the entries declared in the file. Version 3.2.0 was released in February 2025, has
no runtime dependencies, and is 19 KB. Renovate's Maven manager would update it, and the cooldown
does not block it.

* Good, because it is the most used Java dotenv library and the call is short.
* Bad, because it does not know the `export ` prefix. `export TRELLO_API_KEY=key` is dropped.
* Bad, because it keeps a leading byte order mark in the first key, so the first entry is lost.
  This is the bug that [GitHub issue #240](https://github.com/martin-francois/symphony-trello/issues/240)
  fixed.
* Bad, because it does not decode escapes in double quotes. `"a\"b"` reads as `a\"b`, so 320,650 of
  the values the setup flow wrote did not read back unchanged.
* Bad, because it keeps single quotes as part of the value. `'token'` reads as `'token'`.
* Bad, because `#` starts a comment anywhere in an unquoted value. `abc#def` reads as `abc`.
* Bad, because its key pattern accepts `.`, `-`, and a leading digit. `1INVALID` and `a.b` become
  keys, and `ÄPFEL` is dropped.
* Bad, because an open double quote joins the following lines into one value, so a single broken
  line can hide the keys after it.
* Bad, because an unreadable file or invalid UTF-8 throws `DotenvException` even with
  `ignoreIfMissing()`, instead of giving an empty map.
* Bad, because `Dotenv.get` always reads `System.getenv()` first, with no way to pass the
  environment. Tests and the `firstPresent` lookup need an injected environment map, so
  `LocalEnvironment` would keep its own precedence code anyway.

### Parse values with dotenv-java plus glue code

Keep the owned byte order mark check, `export ` removal, key check, and one-line-at-a-time reading.
Pass each `KEY=value` line alone to dotenv-java's `DotenvParser`. Decode escapes when the value
started with `"`, remove single quotes, and keep the raw value when the library rejects the line.

* Good, because it fixes most differences of the plain library.
* Bad, because it still differs on 21,891 exhaustive files. All but 65 of them contain `#`: the
  library cuts an unquoted value at any `#` (`A=A#` reads as `A`) and ends a double-quoted value at
  `\"` followed by `#`. The other 65 are lines such as `A="""`.
* Bad, because 7,448 written values with both `\"` and `#` do not read back unchanged.
* Bad, because the glue needs `DotenvParser` and `DotenvReader` from the library's `internal`
  package. The public API only reads files and merges in `System.getenv()`.
* Bad, because the result is 77 owned lines plus a new dependency. Fixing the remaining
  differences needs the owned quote scanner, the owned `#` rule, and the whole-value fallback,
  which leaves the library nothing to parse.

### Read the file through the SmallRye Config dotenv source

Load a file with `DotEnvConfigSourceProvider.dotEnvSources(location, classLoader)` from SmallRye
Config 3.17.2, which Quarkus already puts on the classpath, and read values from the resulting
config source or from a `SmallRyeConfig` built on it.

* Good, because it needs no new dependency.
* Good, because it can load a file at an explicit path outside the Quarkus runtime. The spike built
  a `SmallRyeConfig` on it with `SmallRyeConfigBuilder` and no Quarkus application.
* Bad, because the source reads the file with `java.util.Properties`, which is a different format.
  Quotes stay in the value, a trailing `# comment` stays in the value, `export TRELLO_API_KEY=key`
  becomes the key `export`, `KEY:value` and lines without `=` become entries, `\u00e9` is decoded,
  backslashes are removed from `C:\Users` (it reads `C:Users`), and a trailing `\` joins the next
  line.
* Bad, because a `SmallRyeConfig` expands `${...}` in every value it serves. In the spike,
  `TRELLO_API_KEY=${TRELLO_API_KEY:-x}` failed with `IllegalArgumentException`, and
  `abc${HOME}def` was served as no value at all.
* Neutral, because precedence comes from config source ordinals. The dotenv source has ordinal 295,
  below environment variables (300), so the process environment still wins with the default
  sources. But system properties (400) then also win over the file, which `LocalEnvironment` never
  reads, and a `config_ordinal` entry in the file changes the file's own rank.
* Bad, because the source maps names for config lookups, so `TRELLO_API_KEY` also answers
  `trello.api-key`. That is the Quarkus convention, not the plain name lookup that callers use.
* Bad, because 906,812 of the written values did not read back unchanged.

### Split the parser into its own class without a new dependency

Move the line and value parsing into a package-private class and keep `get`, `firstPresent`, `load`,
`defaultDotenv`, and `configuredDotenv` in `LocalEnvironment`.

* Good, because each class would have one job.
* Bad, because it moves code without removing any. The parser already has one entry point,
  `load(Path)`, and its tests already use it directly.
* Bad, because it does not touch the real duplication: `TrelloCredentialStore.definesEnvKey` matches
  keys with its own code in the setup package.

## More Information

The spike copied the current parser and the setup writer into a standalone Java program and ran it
on Java 25 with dotenv-java 3.2.0 and SmallRye Config 3.17.2 from the Quarkus 3.39.5 BOM. The
description of the pull request that closes
[GitHub issue #385](https://github.com/martin-francois/symphony-trello/issues/385) contains the spike
source and its output. The method follows the differential spike in
[GitHub PR #801](https://github.com/martin-francois/symphony-trello/pull/801), which keeps the
hand-rolled environment reference classifier.

Revisit this decision if a maintained dotenv library offers an explicit file path, an injected
environment, the `export` prefix, double-quote escapes, literal single quotes, and the current `#`
rule. Then rerun the spike against `LocalEnvironmentTest` and the writer round trip.

The spike found two current behaviors that are probably not intended. Both are separate issues and
this decision does not change them:

* [GitHub issue #806](https://github.com/martin-francois/symphony-trello/issues/806):
  the setup writer does not recognize a key on a first line that starts with a byte order mark, so
  saving a new credential leaves the old line in the file.
* [GitHub issue #807](https://github.com/martin-francois/symphony-trello/issues/807):
  `KEY= # comment` reads as the value `# comment` instead of an empty value.

[GitHub issue #386](https://github.com/martin-francois/symphony-trello/issues/386) evaluates the
`CredentialValue` resolution, which reads credential files through `LocalEnvironment.load`.
