# Micrometer-to-JSpecify Migration Uses the Wrong Dependency Guard

## Upstream target

- Repository:
  [`openrewrite/rewrite-migrate-java`](https://github.com/openrewrite/rewrite-migrate-java)
- Module: repository root recipe module
- Published artifact:
  `org.openrewrite.recipe:rewrite-migrate-java:3.40.0`
- Pinned tag and commit:
  [`v3.40.0` at `658481254a6ee678f5f162e51d8d49ee01c75877`](https://github.com/openrewrite/rewrite-migrate-java/tree/658481254a6ee678f5f162e51d8d49ee01c75877)
- Pinned declarative implementation:
  [`src/main/resources/META-INF/rewrite/jspecify.yml`](https://github.com/openrewrite/rewrite-migrate-java/blob/658481254a6ee678f5f162e51d8d49ee01c75877/src/main/resources/META-INF/rewrite/jspecify.yml#L137-L161)
- Closest pinned tests:
  [`JSpecifyBestPracticesTest.java`](https://github.com/openrewrite/rewrite-migrate-java/blob/658481254a6ee678f5f162e51d8d49ee01c75877/src/test/java/org/openrewrite/java/migrate/jspecify/JSpecifyBestPracticesTest.java)
  tests other source annotation ecosystems but has no Micrometer dependency
  regression.
- Current-main comparison: the affected YAML at
  [`openrewrite/rewrite-migrate-java@a5b7ddcf37df2aaa9c71799cabe387eb7fb9dd20`](https://github.com/openrewrite/rewrite-migrate-java/blob/a5b7ddcf37df2aaa9c71799cabe387eb7fb9dd20/src/main/resources/META-INF/rewrite/jspecify.yml#L137-L161)
  was byte-for-byte identical to the pinned implementation when inspected on
  2026-07-19.

## Recipe and decision context

The exact defective leaf is:

`org.openrewrite.java.jspecify.MigrateFromMicrometerAnnotations`

The wrapper `org.openrewrite.java.jspecify.MigrateToJSpecify` includes this
leaf. Do not file a duplicate wrapper report; cross-reference this root defect.

Symphony Trello records the leaf as `Rejected` with zero current findings in
[`docs/openrewrite-zero-result-decisions.md`](../openrewrite-zero-result-decisions.md).
Micrometer annotations are absent today, but the recipe cannot be a recurrence
guard because it creates uncompilable output when they appear without an
existing JSpecify dependency.

## Reproduction

The declarative recipe first configures `AddDependency` for
`org.jspecify:jspecify:1.0.0`, then changes Micrometer `Nullable` and `NonNull`
types. The dependency step incorrectly uses:

```yaml
onlyIfUsing: org.springframework.lang.*ull*
```

The type-changing steps correctly target:

```yaml
io.micrometer.core.lang.*ull*
```

An isolated Maven project ran:

```shell
mvn -q rewrite:run
mvn -q compile
```

with OpenRewrite Maven plugin `6.44.0`,
`rewrite-migrate-java:3.40.0`, Micrometer Core `1.15.1`, Java 17 source, and
only the Micrometer dependency. The rewrite command exited `0`; compilation
exited `1`.

A pinned-tag `RewriteTest` probe covering the same source and POM also passed
when its expected result asserted changed imports and an unchanged POM.

## Failure cases

### 1. Both Micrometer nullness annotations migrate without JSpecify

**Evidence status:** Reproduced with the published artifact in an isolated
Maven project and with a pinned-tag `RewriteTest`. The actual output and
compiler errors were executed.

#### Before

`pom.xml`:

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>micrometer-jspecify-probe</artifactId>
  <version>1</version>
  <dependencies>
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-core</artifactId>
      <version>1.15.1</version>
    </dependency>
  </dependencies>
</project>
```

`Example.java`:

```java
package com.example;

import io.micrometer.core.lang.NonNull;
import io.micrometer.core.lang.Nullable;

final class Example {
    @NonNull
    String required(@Nullable String input) {
        return input == null ? "" : input;
    }
}
```

#### Actual pinned output

The POM is unchanged:

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>micrometer-jspecify-probe</artifactId>
  <version>1</version>
  <dependencies>
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-core</artifactId>
      <version>1.15.1</version>
    </dependency>
  </dependencies>
</project>
```

The source is changed:

```java
package com.example;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

final class Example {
    @NonNull
    String required(@Nullable String input) {
        return input == null ? "" : input;
    }
}
```

Compilation reports:

```text
package org.jspecify.annotations does not exist
cannot find symbol: class Nullable
cannot find symbol: class NonNull
```

#### Expected corrected output

The source change is desirable, and the POM MUST receive JSpecify:

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>micrometer-jspecify-probe</artifactId>
  <version>1</version>
  <dependencies>
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-core</artifactId>
      <version>1.15.1</version>
    </dependency>
    <dependency>
      <groupId>org.jspecify</groupId>
      <artifactId>jspecify</artifactId>
      <version>1.0.0</version>
    </dependency>
  </dependencies>
</project>
```

```java
package com.example;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

final class Example {
    @NonNull
    String required(@Nullable String input) {
        return input == null ? "" : input;
    }
}
```

The combined fixture exercises every type-changing leaf in this recipe:
Micrometer `Nullable` and Micrometer `NonNull`. The wrong guard is shared, so
separate focused tests SHOULD accompany this combined regression.

## Root cause

The `onlyIfUsing` value was copied from the neighboring Spring migration and
was not changed to the Micrometer package. `AddDependency` therefore runs only
when Spring nullness annotations happen to be present. The later `ChangeType`
steps have no corresponding Spring precondition and still rewrite Micrometer
types.

When Spring and Micrometer annotations coexist, the unrelated Spring usage can
accidentally mask the defect by causing JSpecify to be added. That mixed-source
case is a required control, not a fix.

## Required fix contract

- Micrometer `Nullable` or `NonNull` usage MUST cause
  `org.jspecify:jspecify:1.0.0` to be available before changed source is
  compiled.
- The dependency guard MUST inspect
  `io.micrometer.core.lang.*ull*`, not Spring.
- A direct or accepted transitive JSpecify dependency MUST NOT be duplicated.
- No dependency MUST be added when no Micrometer nullness annotation is used.
- Both Maven and Gradle project dependency mutation MUST follow the existing
  `AddDependency` contract.
- The existing annotation placement and type changes MUST remain intact.
- `MigrateToJSpecify` MUST inherit the corrected leaf without a second
  implementation.
- The fixed leaf and owning composite MUST remain idempotent: a second cycle
  MUST produce no source or dependency change.

Changing the one declarative guard is the smallest apparent fix, but the
contract permits another implementation that produces the same dependency and
source result.

## Regression test plan

Extend `JSpecifyBestPracticesTest` or add a focused Micrometer class:

1. `Nullable` only: source changes and JSpecify is added.
2. `NonNull` only: source changes and JSpecify is added.
3. Both annotations, matching the executed fixture.
4. Field, parameter, return, nested type, and array annotation placements
   already supported by the composite.
5. Maven and Gradle build files.
6. Existing direct JSpecify dependency, with no duplicate.
7. Existing accepted transitive JSpecify dependency, with no duplicate.
8. No Micrometer annotation usage, with no source or build change.
9. Spring-only usage, proving this leaf does not activate.
10. Mixed Spring and Micrometer usage, proving the Micrometer guard owns the
    dependency result.
11. Micrometer dependency present but no annotation usage, with no JSpecify
    addition.
12. Compilation after every positive rewrite.
13. Run the leaf and the owning composite for a second cycle on every positive
    output and assert that neither produces a second-cycle change; no-change
    fixtures MUST remain byte-stable across both cycles.
14. Micrometer versions whose annotation package exists and a no-change
    control for a version where it does not. Do not assume the host
    classpath; pin parser fixtures.
15. The recipe has no options. If options are introduced, test every value and
    preserve the safe default.

Targeted validation:

```shell
./gradlew test \
  --tests org.openrewrite.java.migrate.jspecify.JSpecifyBestPracticesTest
```

Full repository validation:

```shell
./gradlew check
```

## Upstream contribution workflow

Handle only this defect in the contribution. Before coding from the Symphony
checkout, run:

```shell
bash .tessl/plugins/tessl-labs/good-oss-citizen/skills/recon/scripts/bash/github.sh \
  repo-scan openrewrite/rewrite-migrate-java
bash .tessl/plugins/tessl-labs/good-oss-citizen/skills/recon/scripts/bash/github.sh \
  issues-open openrewrite/rewrite-migrate-java
bash .tessl/plugins/tessl-labs/good-oss-citizen/skills/recon/scripts/bash/github.sh \
  issues-closed openrewrite/rewrite-migrate-java
```

If the helper is unavailable, perform equivalent current policy, template,
open/closed issue and PR, duplicate, and claim searches through the available
GitHub UI or tooling.

Search for the exact leaf and wrapper IDs, `Micrometer`, `JSpecify`,
`onlyIfUsing`, and `org.springframework.lang`. For a matching issue, run
`issue-comments` and `related-prs` with the helper, or inspect all comments and
prior PRs equivalently. Stop if claimed. If no issue exists, file this executed
reproduction and obtain maintainer agreement.

Open an early draft pull request with the failing test, following the
[OpenRewrite contribution guide](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md).
Human review is mandatory before submission.

### Mandatory upstream contribution checklist

Before preparing public upstream work, the contributor MUST:

1. Read the current contributing and automation instructions for the target repository.
2. Search current open and closed issues and pull requests for every exact recipe ID and every
   failure mechanism documented in this packet, and confirm that no contributor has claimed the
   same work.
3. Reproduce every listed failure case and negative control against the current upstream default
   branch. If that branch already fixes a case, record the fixing commit and remove the case from
   the proposed patch.
4. Add the smallest failing `RewriteTest` cases before changing the implementation.
5. Run the focused commands documented in this packet and the full gate for the owning repository.
6. Obtain human review of the implementation, tests, public issue, and pull-request text before
   publication.

When AI assistance was used, the public issue or pull request MUST include a normal-prose
AI-disclosure statement, for example:

> This change was prepared with AI assistance. I reviewed the implementation, tests, generated
> before/after examples, and contribution text.

## Symphony acceptance criteria

Symphony can activate this leaf after:

- upstream merges the guard fix and all dependency/source tests;
- a released `rewrite-migrate-java` artifact contains it;
- Symphony updates its pin normally;
- the isolated fixture compiles after rewrite;
- no JSpecify dependency is duplicated;
- the OpenRewrite-and-Spotless maintenance run is clean; and
- the complete Symphony gate passes.

Do not activate the broader `MigrateToJSpecify` wrapper without separately
reviewing its other leaves.

### Mandatory Symphony reactivation checklist

After upstream releases the fix, Symphony MUST:

1. Update the pinned artifact through the normal dependency process for the repository.
2. Run the fixed leaf alone against every positive and negative control in this packet.
3. Run the complete ordered `OpenRewrite -> Spotless` maintenance lane.
4. Run the complete repository gate.
5. Run the complete ordered maintenance lane a second time and confirm that the worktree remains
   unchanged.
6. Update the recipe decision record and the selected defect tracker with the upstream issue, pull request, release,
   and local outcome.

Parent composites MUST remain independently reviewed. Fixing one leaf MUST NOT activate unreviewed
siblings.
