---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted: [Mockito documentation, Maven Wrapper behavior, Java 25 runtime behavior]
informed: [Future maintainers]
---

# Use Java 25 with Maven 3 and Official Maven Patterns for Test Agents

## Context and Problem Statement

The project targets Java 25 LTS while using Maven 3 after deciding not to continue with Maven 4. Java
25 emits warnings for some older library behavior, including Maven's embedded Guice stack and
Mockito's inline mock maker when it self-attaches at test runtime.

How should the build stay on Maven 3, keep warning output useful, and avoid brittle JVM or local
repository hacks?

## Decision Drivers

* Use the latest LTS JDK configured through SDKMAN.
* Stay on the Maven 3 line unless the project deliberately revisits Maven 4.
* Keep the Maven wrapper generated from the official Maven Wrapper plugin.
* Avoid noisy warnings that hide real build failures.
* Use Mockito's documented Java-agent setup instead of runtime self-attachment.
* Avoid hand-built local Maven repository paths in Surefire configuration.

## Considered Options

* Maven 3 wrapper, `.mvn/jvm.config`, and Mockito's documented Maven Java-agent pattern.
* Maven 4 to avoid Maven 3 dependency warnings.
* Ignore all Java 25 warnings.
* Hardcode the Mockito jar path from `${settings.localRepository}`.

## Decision Outcome

Chosen option: "Maven 3 wrapper, `.mvn/jvm.config`, and Mockito's documented Maven Java-agent
pattern", because it preserves the Maven 3 choice while keeping test warning handling maintainable.

### Consequences

* Good, because Maven starts through a current generated wrapper script.
* Good, because Maven's Java 25 Unsafe warning is handled once in `.mvn/jvm.config`.
* Good, because Mockito uses a startup `-javaagent` instead of dynamic self-attachment.
* Good, because `maven-dependency-plugin:properties` resolves the Mockito jar path without a
  brittle local repository expression.
* Bad, because the build has additional Surefire and dependency-plugin configuration.
* Bad, because `.mvn/jvm.config` applies to all Maven invocations and should remain minimal.

### Confirmation

Run `./mvnw -q -v`, `./mvnw -q -Dtest=LocalAgentRunnerTest test`, and
`./mvnw -q spotless:check verify`. These commands should not emit the old Maven Jansi warning,
Maven/Guice Unsafe warning, Mockito self-attachment warning, dynamic agent loading warning, or CDS
warning.

## Pros and Cons of the Options

### Maven 3 wrapper, `.mvn/jvm.config`, and Mockito's documented Maven Java-agent pattern

Use Maven Wrapper `only-script`, configure Maven's Java 25 runtime flag in `.mvn/jvm.config`, and
configure Surefire with `maven-dependency-plugin:properties` plus
`-javaagent:${org.mockito:mockito-core:jar}`.

* Good, because it follows maintained Maven and Mockito patterns.
* Good, because it keeps Java 25 warning output actionable.
* Good, because it does not require developers to prefix commands with custom `JAVA_HOME` or
  `MAVEN_OPTS`.
* Neutral, because the build file includes one extra plugin for agent path resolution.
* Bad, because future Maven or Mockito changes may let us remove some configuration.

### Maven 4 to avoid Maven 3 dependency warnings

Move the wrapper and plugins back to the Maven 4 line.

* Good, because Maven 4 may use a newer dependency stack.
* Bad, because the project deliberately returned to Maven 3.
* Bad, because Maven 4 can require plugin compatibility work.

### Ignore all Java 25 warnings

Leave Maven and Mockito warning output as-is.

* Good, because no build configuration is needed.
* Bad, because warning noise makes failures harder to spot.
* Bad, because Mockito's self-attachment warning describes behavior that future JDKs may block.

### Hardcode the Mockito jar path from `${settings.localRepository}`

Point Surefire directly at the Mockito jar in the local Maven repository.

* Good, because it is short and works in common Maven layouts.
* Bad, because it is more brittle than Mockito's documented Maven pattern.
* Bad, because it bypasses Maven's dependency property support.

## More Information

The relevant Mockito guidance is the Java 21+ section in Mockito's documentation. The current build
keeps this logic in `pom.xml` so CI and local test runs behave the same way.
