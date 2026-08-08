# Versionless Dependency-Management Entries Can Have Maven Semantics

## Upstream target

- Repository: [`openrewrite/rewrite`](https://github.com/openrewrite/rewrite)
- Module: `rewrite-maven`
- Published artifact used by Symphony Trello:
  `org.openrewrite:rewrite-maven:8.87.0`
- Pinned tag and commit:
  [`v8.87.0` at `2304703c678e9febf855d22adf18aeb32f44b7aa`](https://github.com/openrewrite/rewrite/tree/2304703c678e9febf855d22adf18aeb32f44b7aa)
- Pinned implementation:
  [`DependencyManagementDependencyRequiresVersion.java`](https://github.com/openrewrite/rewrite/blob/2304703c678e9febf855d22adf18aeb32f44b7aa/rewrite-maven/src/main/java/org/openrewrite/maven/cleanup/DependencyManagementDependencyRequiresVersion.java)
- Pinned tests: no class named
  `DependencyManagementDependencyRequiresVersionTest` exists at the pinned
  commit. The upstream fix MUST add focused tests.
- Proposed focused test path:
  `rewrite-maven/src/test/java/org/openrewrite/maven/cleanup/DependencyManagementDependencyRequiresVersionTest.java`
- Current-main comparison: the implementation at
  [`openrewrite/rewrite@6fe43db184cb1e38777d8e0ec3c981e94cb0b6ce`](https://github.com/openrewrite/rewrite/blob/6fe43db184cb1e38777d8e0ec3c981e94cb0b6ce/rewrite-maven/src/main/java/org/openrewrite/maven/cleanup/DependencyManagementDependencyRequiresVersion.java)
  was byte-for-byte identical to the pinned implementation when inspected on
  2026-07-19.

## Recipe and decision context

The exact recipe ID is:

`org.openrewrite.maven.cleanup.DependencyManagementDependencyRequiresVersion`

Symphony Trello records it as `Rejected` with zero current findings in
[`docs/openrewrite-zero-result-decisions.md`](../openrewrite-zero-result-decisions.md).
It is not a recurrence guard because the recipe can delete effective scope and
exclusion management.

The implementation is also reachable through
`org.openrewrite.maven.BestPractices`. Do not duplicate this report for that
composite.

The pinned display description asserts that a dependency-management entry
without a raw `<version>` cannot affect dependency resolution and can be
safely removed. The executed Maven model below disproves that assertion.

## Reproduction

The control used:

- OpenRewrite Maven plugin `6.44.0`;
- `org.openrewrite:rewrite-maven:8.87.0`;
- recipe
  `org.openrewrite.maven.cleanup.DependencyManagementDependencyRequiresVersion`;
- a parent and child Maven project;
- Guava `33.4.8-jre`, resolved from Maven Central; and
- Java 25.0.3.

Commands:

```shell
./mvnw -q -f child/pom.xml help:effective-pom \
  -Doutput=effective-before.xml
./mvnw -q rewrite:run
./mvnw -q -f child/pom.xml help:effective-pom \
  -Doutput=effective-after.xml
```

The follow-up correction also executed the same source and POM transformation in a focused
`DependencyManagementDependencyRequiresVersionProbeTest` on the pinned commit and current upstream
`main` at `b3008cc4a1f0c43f562da16e5933a2a56d9bc568`. Both runs produced the documented deletion.

Before rewriting, the normal dependency's effective scope is `runtime` and its
effective exclusions contain `com.google.code.findbugs:jsr305`. After
rewriting, its effective scope is `compile` and it has no exclusions.

An earlier hypothesis was that the versionless child entry inherits its
version field from a matching parent entry or imported BOM. Maven effective
model controls did not support that narrower hypothesis: the
dependency-management entry itself remained versionless. Do not use inherited
or imported version alone as claimed evidence. The proven defect is that a
normal dependency can declare its own version while the versionless
dependency-management entry manages other fields.

## Failure cases

### 1. A versioned dependency loses managed scope and exclusions

**Evidence status:** Reproduced in an isolated multi-module Maven project with
the pinned artifact. The actual XML and both effective models were generated,
not predicted.

#### Before

Parent `pom.xml`:

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>parent</artifactId>
  <version>1</version>
  <packaging>pom</packaging>
  <modules>
    <module>child</module>
  </modules>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>com.google.guava</groupId>
        <artifactId>guava</artifactId>
        <version>33.4.8-jre</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

Child `pom.xml`:

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.example</groupId>
    <artifactId>parent</artifactId>
    <version>1</version>
  </parent>
  <artifactId>child</artifactId>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>com.google.guava</groupId>
        <artifactId>guava</artifactId>
        <scope>runtime</scope>
        <exclusions>
          <exclusion>
            <groupId>com.google.code.findbugs</groupId>
            <artifactId>jsr305</artifactId>
          </exclusion>
        </exclusions>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.google.guava</groupId>
      <artifactId>guava</artifactId>
      <version>33.4.8-jre</version>
    </dependency>
  </dependencies>
</project>
```

Relevant effective dependency before rewriting:

```xml
<dependency>
  <groupId>com.google.guava</groupId>
  <artifactId>guava</artifactId>
  <version>33.4.8-jre</version>
  <scope>runtime</scope>
  <exclusions>
    <exclusion>
      <groupId>com.google.code.findbugs</groupId>
      <artifactId>jsr305</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

#### Actual pinned output

The recipe removes the versionless dependency. Cleanup then removes the empty
`dependencies` and `dependencyManagement` containers:

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.example</groupId>
    <artifactId>parent</artifactId>
    <version>1</version>
  </parent>
  <artifactId>child</artifactId>
  <dependencies>
    <dependency>
      <groupId>com.google.guava</groupId>
      <artifactId>guava</artifactId>
      <version>33.4.8-jre</version>
    </dependency>
  </dependencies>
</project>
```

Relevant effective dependency after rewriting:

```xml
<dependency>
  <groupId>com.google.guava</groupId>
  <artifactId>guava</artifactId>
  <version>33.4.8-jre</version>
  <scope>compile</scope>
</dependency>
```

#### Expected corrected output

The child POM MUST remain unchanged because the versionless declaration has
effective scope and exclusion semantics.

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.example</groupId>
    <artifactId>parent</artifactId>
    <version>1</version>
  </parent>
  <artifactId>child</artifactId>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>com.google.guava</groupId>
        <artifactId>guava</artifactId>
        <scope>runtime</scope>
        <exclusions>
          <exclusion>
            <groupId>com.google.code.findbugs</groupId>
            <artifactId>jsr305</artifactId>
          </exclusion>
        </exclusions>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.google.guava</groupId>
      <artifactId>guava</artifactId>
      <version>33.4.8-jre</version>
    </dependency>
  </dependencies>
</project>
```

This one fixture covers two independently observable effects: scope changes
from `runtime` to `compile`, and an exclusion disappears. Upstream SHOULD split
them into separate focused unit tests in addition to retaining the combined
regression.

## Root cause

The visitor checks only:

```java
isManagedDependencyTag()
    && tag.getChildValue("version").orElse(null) == null
```

It does not ask whether the entry manages scope, exclusions, type, classifier,
or another field for a dependency whose version is supplied elsewhere. It
therefore equates absence of one XML child with absence of all effective-model
semantics.

`RemoveContentVisitor` also removes now-empty parent containers. That cleanup
is truthful only after the dependency itself is proven inert; it magnifies the
visible diff but is not the primary defect.

## Required fix contract

- The recipe MUST preserve every dependency-management declaration that
  changes the effective model of any normal or managed dependency.
- A missing raw `<version>` MUST NOT be sufficient evidence for removal.
- Effective scope, exclusions, type, classifier, optionality, and other Maven
  model fields MUST be considered where Maven supports their management.
- Unresolved, partial, property-based, inherited, or imported models MUST
  select no change unless inertness is established.
- Cleanup MAY remove containers only when every removed declaration is proven
  inert.
- The recipe MAY use `MavenResolutionResult`, a model comparison, or a narrower
  syntactic proof. The contract does not prescribe one implementation.
- If no useful declaration can be proven inert safely, deprecating the recipe
  or narrowing it to diagnostics is acceptable.
- The fixed recipe MUST remain idempotent: a second cycle MUST produce no
  change after a safe removal or a conservative no-change decision.

## Regression test plan

Create
`DependencyManagementDependencyRequiresVersionTest implements RewriteTest`.
The matrix MUST include:

1. The executed combined scope-and-exclusion no-change fixture above.
2. A no-change versionless entry that manages only scope.
3. A no-change versionless entry that manages only exclusions.
4. A normal dependency with an explicit literal version.
5. A normal dependency with a property version.
6. Parent, imported-BOM, and same-POM model arrangements. These are controls;
   tests MUST assert observed Maven behavior instead of assuming version-field
   inheritance.
7. A positive removal case whose before and after effective models are
   demonstrably identical.
8. Multiple entries where only one is inert. The semantic entry and its
   containers MUST remain.
9. Missing, malformed, unresolved, and partial coordinates. They MUST remain
   unless removal safety is proved.
10. Duplicate coordinates, classifier/type variants, and property-based
    coordinates.
11. Effective-model assertions before and after each positive rewrite.
12. Run the recipe for a second cycle after every safe removal and assert no
    second-cycle diff; run both cycles on semantic no-change fixtures and
    assert that they remain byte-stable.
13. XML parsing and Maven model validation. Java compilation is not applicable
    because the defect is in the POM; a small compiling consumer MAY be added
    to prove that dependency scope/classpath remains unchanged.
14. The recipe has no options at `8.87.0`. If an option is added, test every
    value and retain a conservative default.

Targeted validation:

```shell
./gradlew :rewrite-maven:test \
  --tests org.openrewrite.maven.cleanup.DependencyManagementDependencyRequiresVersionTest
```

Module validation:

```shell
./gradlew :rewrite-maven:check
```

Use the checked-in Gradle wrapper and the Java toolchains requested by the
upstream build.

## Upstream contribution workflow

Treat this handoff as one contribution. Do not combine it with another recipe
fix.

Run:

```shell
bash .tessl/plugins/tessl-labs/good-oss-citizen/skills/recon/scripts/bash/github.sh \
  repo-scan openrewrite/rewrite
bash .tessl/plugins/tessl-labs/good-oss-citizen/skills/recon/scripts/bash/github.sh \
  issues-open openrewrite/rewrite
bash .tessl/plugins/tessl-labs/good-oss-citizen/skills/recon/scripts/bash/github.sh \
  issues-closed openrewrite/rewrite
```

Those helper commands apply from the Symphony checkout. If the helper is
unavailable, perform equivalent current repository-policy, issue,
closed-issue/PR, template, duplicate, and claim searches with the available
GitHub UI or GitHub tooling before coding.

Search for the exact recipe ID, class name, `dependencyManagement`,
`versionless`, `scope`, and `exclusions`. If an issue exists, read all comments
and related pull requests:

```shell
bash .tessl/plugins/tessl-labs/good-oss-citizen/skills/recon/scripts/bash/github.sh \
  issue-comments openrewrite/rewrite ISSUE_NUMBER
bash .tessl/plugins/tessl-labs/good-oss-citizen/skills/recon/scripts/bash/github.sh \
  related-prs openrewrite/rewrite ISSUE_NUMBER
```

Do not compete with a claimed issue. If no issue exists, file the executed
effective-model reproduction and obtain maintainer agreement before coding.
Fetch and follow the current issue and pull-request templates.

Open an early draft pull request after adding the minimal failing test, per the
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

Symphony Trello can activate the leaf only when:

- upstream merges a fix with effective-model regression tests;
- a released `rewrite-maven` artifact contains the fix;
- Symphony updates its pinned version through the normal dependency lane;
- the executed scope-and-exclusion fixture remains unchanged;
- a genuinely inert positive case still rewrites;
- the ordered OpenRewrite-and-Spotless run leaves no worktree change; and
- the full Symphony gate passes.

Fixing this leaf does not automatically accept
`org.openrewrite.maven.BestPractices`.

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
