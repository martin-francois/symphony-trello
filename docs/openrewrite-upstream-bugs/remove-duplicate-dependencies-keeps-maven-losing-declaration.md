# `RemoveDuplicateDependencies` Keeps the Maven-Losing Declaration

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite>
- Module: `rewrite-maven`
- Artifact: `org.openrewrite:rewrite-maven:8.87.0`
- Audited source: tag `v8.87.0`, commit
  `2304703c678e9febf855d22adf18aeb32f44b7aa`
- Implementation:
  `rewrite-maven/src/main/java/org/openrewrite/maven/RemoveDuplicateDependencies.java`
- Tests:
  `rewrite-maven/src/test/java/org/openrewrite/maven/RemoveDuplicateDependenciesTest.java`

The implementation and tests were byte-identical on upstream `main` at
`6fe43db184cb1e38777d8e0ec3c981e94cb0b6ce`. The existing upstream test
`removeDependencyWithDifferentVersion` currently encodes the unsafe
first-declaration result. No exact open or closed issue or active PR claim was
found on 2026-07-19. Related closed issue
[openrewrite/rewrite#8235](https://github.com/openrewrite/rewrite/issues/8235)
concerns the different
`org.openrewrite.java.dependencies.RemoveRedundantDependencies` recipe in
`rewrite-java-dependencies`; its fix does not cover this recipe. Related closed
issue
[openrewrite/rewrite#4868](https://github.com/openrewrite/rewrite/issues/4868)
fixed classifier identity. The current classifier controls pass and do not
cover the version, optionality, exclusions, or managed-scope defects documented
here.

## Recipe and decision context

Maven requires dependency uniqueness by
`groupId:artifactId:type:classifier`, but when duplicates occur its effective
model uses the later declaration's semantic fields. The recipe records the
first matching declaration with `putIfAbsent` and deletes later matches even
when version, optionality, exclusions, or managed scope differ.

The cleanup therefore preserves the declaration Maven was not using and
deletes the one that controlled the effective model. Symphony keeps the
recipe inactive pending an upstream fix.

## Reproduction

All displayed outputs were executed in a pinned
`RemoveDuplicateDependenciesProbeTest` against `v8.87.0`. The selected
upstream scope/type/classifier tests were also executed as negative controls.

## Failure cases

### 1. Direct dependencies: version, optionality, and exclusions

**Evidence status:** execution-proven pinned output for all three differing
fields in `/project/dependencies`. Maven selects the later value for each
duplicate; the recipe deletes that declaration and keeps the earlier value.

**Before**

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>example</artifactId>
  <version>1</version>
  <dependencies>
    <dependency>
      <groupId>junit</groupId>
      <artifactId>junit</artifactId>
      <version>4.13.1</version>
    </dependency>
    <dependency>
      <groupId>junit</groupId>
      <artifactId>junit</artifactId>
      <version>4.13.2</version>
    </dependency>

    <dependency>
      <groupId>com.google.guava</groupId>
      <artifactId>guava</artifactId>
      <version>29.0-jre</version>
      <optional>false</optional>
    </dependency>
    <dependency>
      <groupId>com.google.guava</groupId>
      <artifactId>guava</artifactId>
      <version>29.0-jre</version>
      <optional>true</optional>
    </dependency>

    <dependency>
      <groupId>org.apache.httpcomponents</groupId>
      <artifactId>httpclient</artifactId>
      <version>4.5.13</version>
    </dependency>
    <dependency>
      <groupId>org.apache.httpcomponents</groupId>
      <artifactId>httpclient</artifactId>
      <version>4.5.13</version>
      <exclusions>
        <exclusion>
          <groupId>commons-codec</groupId>
          <artifactId>commons-codec</artifactId>
        </exclusion>
      </exclusions>
    </dependency>
  </dependencies>
</project>
```

**Actual output**

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>example</artifactId>
  <version>1</version>
  <dependencies>
    <dependency>
      <groupId>junit</groupId>
      <artifactId>junit</artifactId>
      <version>4.13.1</version>
    </dependency>

    <dependency>
      <groupId>com.google.guava</groupId>
      <artifactId>guava</artifactId>
      <version>29.0-jre</version>
      <optional>false</optional>
    </dependency>

    <dependency>
      <groupId>org.apache.httpcomponents</groupId>
      <artifactId>httpclient</artifactId>
      <version>4.5.13</version>
    </dependency>
  </dependencies>
</project>
```

**Expected output**

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>example</artifactId>
  <version>1</version>
  <dependencies>
    <dependency>
      <groupId>junit</groupId>
      <artifactId>junit</artifactId>
      <version>4.13.2</version>
    </dependency>

    <dependency>
      <groupId>com.google.guava</groupId>
      <artifactId>guava</artifactId>
      <version>29.0-jre</version>
      <optional>true</optional>
    </dependency>

    <dependency>
      <groupId>org.apache.httpcomponents</groupId>
      <artifactId>httpclient</artifactId>
      <version>4.5.13</version>
      <exclusions>
        <exclusion>
          <groupId>commons-codec</groupId>
          <artifactId>commons-codec</artifactId>
        </exclusion>
      </exclusions>
    </dependency>
  </dependencies>
</project>
```

Leaving the differing duplicates unchanged is also safe. If the recipe
removes one, it MUST retain Maven's effective later declaration.

### 2. Managed dependencies: version, scope, optionality, and exclusions

**Evidence status:** execution-proven pinned output for all four fields in
`/project/dependencyManagement/dependencies`. The non-import managed key
additionally hard-codes `Scope.Compile`, so a later managed runtime scope is
deleted.

**Before**

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>example</artifactId>
  <version>1</version>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>com.example</groupId>
        <artifactId>versioned</artifactId>
        <version>1</version>
      </dependency>
      <dependency>
        <groupId>com.example</groupId>
        <artifactId>versioned</artifactId>
        <version>2</version>
      </dependency>

      <dependency>
        <groupId>com.example</groupId>
        <artifactId>scoped</artifactId>
        <version>1</version>
        <scope>compile</scope>
      </dependency>
      <dependency>
        <groupId>com.example</groupId>
        <artifactId>scoped</artifactId>
        <version>1</version>
        <scope>runtime</scope>
      </dependency>

      <dependency>
        <groupId>com.example</groupId>
        <artifactId>optional</artifactId>
        <version>1</version>
        <optional>false</optional>
      </dependency>
      <dependency>
        <groupId>com.example</groupId>
        <artifactId>optional</artifactId>
        <version>1</version>
        <optional>true</optional>
      </dependency>

      <dependency>
        <groupId>com.example</groupId>
        <artifactId>excluded</artifactId>
        <version>1</version>
      </dependency>
      <dependency>
        <groupId>com.example</groupId>
        <artifactId>excluded</artifactId>
        <version>1</version>
        <exclusions>
          <exclusion>
            <groupId>com.example</groupId>
            <artifactId>transitive</artifactId>
          </exclusion>
        </exclusions>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

**Actual output**

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>example</artifactId>
  <version>1</version>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>com.example</groupId>
        <artifactId>versioned</artifactId>
        <version>1</version>
      </dependency>

      <dependency>
        <groupId>com.example</groupId>
        <artifactId>scoped</artifactId>
        <version>1</version>
        <scope>compile</scope>
      </dependency>

      <dependency>
        <groupId>com.example</groupId>
        <artifactId>optional</artifactId>
        <version>1</version>
        <optional>false</optional>
      </dependency>

      <dependency>
        <groupId>com.example</groupId>
        <artifactId>excluded</artifactId>
        <version>1</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

**Expected output**

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>example</artifactId>
  <version>1</version>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>com.example</groupId>
        <artifactId>versioned</artifactId>
        <version>2</version>
      </dependency>

      <dependency>
        <groupId>com.example</groupId>
        <artifactId>scoped</artifactId>
        <version>1</version>
        <scope>runtime</scope>
      </dependency>

      <dependency>
        <groupId>com.example</groupId>
        <artifactId>optional</artifactId>
        <version>1</version>
        <optional>true</optional>
      </dependency>

      <dependency>
        <groupId>com.example</groupId>
        <artifactId>excluded</artifactId>
        <version>1</version>
        <exclusions>
          <exclusion>
            <groupId>com.example</groupId>
            <artifactId>transitive</artifactId>
          </exclusion>
        </exclusions>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

### 3. Imported BOM version

**Evidence status:** execution-proven pinned output for the special
`scope=import` key path. The key includes import scope and POM type but omits
version, so it deletes the later BOM version Maven imports.

**Before**

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>example</artifactId>
  <version>1</version>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.apache.logging.log4j</groupId>
        <artifactId>log4j-bom</artifactId>
        <version>2.24.0</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.apache.logging.log4j</groupId>
        <artifactId>log4j-bom</artifactId>
        <version>2.24.1</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

**Actual output**

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>example</artifactId>
  <version>1</version>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.apache.logging.log4j</groupId>
        <artifactId>log4j-bom</artifactId>
        <version>2.24.0</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

**Expected output**

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>example</artifactId>
  <version>1</version>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.apache.logging.log4j</groupId>
        <artifactId>log4j-bom</artifactId>
        <version>2.24.1</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

## Execution-proven negative controls

These are not failures:

- direct dependencies with differing scope remained unchanged;
- direct dependencies with differing non-default type remained unchanged;
- direct dependencies with differing classifier remained unchanged;
- managed dependencies with differing type remained unchanged; and
- managed dependencies with differing classifier remained unchanged.

The selected pinned upstream tests for all five controls passed. Type and
classifier are part of the key or an additional managed-dependency guard.
Direct scope is part of the direct-dependency key. These safe branches MUST
remain covered while fixing the omitted fields.

## Root cause

`DependencyKey` contains only group, artifact, type, classifier, and scope.
It omits version, optionality, and exclusions. The non-import managed factory
also always stores `Scope.Compile`. Each container keeps the first key with
`putIfAbsent`; a later match is returned as `null` from `visitTag` and removed.
That algorithm conflicts with Maven's later-declaration effective-model
selection.

## Required fix contract

The fix MUST:

- preserve Maven's effective direct and managed dependency model;
- cover normal dependencies, non-import managed dependencies, and imported
  BOMs;
- account for version, scope, optionality, type, classifier, exclusions,
  system path, and every other semantic field Maven accepts in that context;
- retain the later declaration when differing duplicates are consolidated, or
  conservatively leave them unchanged;
- continue removing truly identical duplicates;
- preserve properties, interpolation, inherited/BOM-resolved values,
  comments, and declaration order;
- keep the existing safe scope/type/classifier distinctions;
- select no change for unresolved or partial models; and
- remain idempotent: a second cycle MUST produce no POM change.

## Regression test plan

The three displayed fixtures are execution-proven. Treat additional matrix
items below as unexecuted regression or negative-control requirements until
their tests are run:

1. split each displayed differing field into a focused direct, managed, and
   import test wherever Maven supports it;
2. test the reverse value order for version, scope, optionality, and
   exclusions;
3. preserve type/classifier/direct-scope controls;
4. combine multiple differing fields and multiple duplicate groups;
5. use literal, property, parent, and imported-BOM versions;
6. test default versus explicit values;
7. verify comments on the removed and retained declarations;
8. compare effective POMs and dependency graphs before and after;
9. cover unresolved, malformed, partial, and duplicate property definitions;
10. compile a small consumer where classpath scope/exclusions are relevant;
11. avoid assuming the host local Maven repository by pinning fixtures; and
12. run a second recipe cycle after every positive and no-change case.

Targeted validation:

```text
./gradlew :rewrite-maven:test \
  --tests org.openrewrite.maven.RemoveDuplicateDependenciesTest \
  --no-daemon
./gradlew :rewrite-maven:check --no-daemon
```

## Upstream contribution workflow

Search current issues and PRs for `RemoveDuplicateDependencies`, `duplicate
dependency`, `later declaration`, `version`, `optional`, and `exclusions`.
Inspect closed attempts, comments, and claims. If Symphony's Good OSS helper
is unavailable, perform equivalent current policy, template, duplicate,
issue/PR, comment, and claim searches through GitHub tooling.

The existing different-version expectation MUST be corrected as part of this
fix. A human MUST review Maven model precedence and the complete semantic
field matrix.

AI-assisted work MUST include:

> This change was prepared with AI assistance. I reviewed the implementation,
> tests, generated before/after examples, and contribution text.

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

## Symphony acceptance criteria

Symphony can reconsider the recipe when every removal preserves Maven's
effective model, all direct/managed/import paths and controls are covered, and
the released recipe produces no change on a second ordered cycle.

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
