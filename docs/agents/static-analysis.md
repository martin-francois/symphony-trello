# Static analysis policy

## Scope

Which static analyzers this repository uses, how to triage and suppress findings, and how
automation config (Renovate, pinned tool versions, GitHub Actions) should be kept. Java code style
itself lives in [Java style & design preferences](java-style.md).

## Tool selection

- Prefer ArchUnit for architecture rules that can be checked from compiled classes. Do not add
  Checkstyle for this repository. Use PMD as a curated source-level analyzer for correctness,
  security, performance, duplication, and maintainability rules that complement Spotless, ArchUnit,
  tests, and other static analyzers. Do not import broad PMD categories or third-party PMD rulesets
  into the blocking gate without first measuring findings against this repository.

## Feedback loop and triage

- Use static analysis as a local, deterministic agent feedback loop. Fix findings when reasonable,
  rerun the analyzer, rerun the relevant build or test command, and keep changes scoped to the
  current issue. New static-analysis rules should start in report-only, candidate, or otherwise
  non-blocking mode until baseline findings are understood and useful.
- Apply the same triage policy to every static-analysis tool, including PMD, CPD, SpotBugs,
  FindSecBugs, Error Prone, Picnic Error Prone Support, Semgrep, CodeQL, linters, and dependency
  analyzers. Do not call a rule noisy only because it reports many findings. A finding is justified
  when fixing it would make the code meaningfully better, cleaner, safer, faster, or more
  maintainable. Code compiling successfully does not by itself make a supplementary static-analysis
  finding unjustified. A rule is noisy only when representative findings are false positives or
  already cleaner to leave as they are. A large diff is acceptable when the resulting code is
  meaningfully better. High counts of justified findings should become staged cleanup work or a
  candidate profile, not a noisy-rule classification.
- For source-rewrite tools whose output is normalized by Spotless, evaluate the ordered final state.
  Run OpenRewrite first and `spotless:apply` second. A recipe MUST NOT be rejected only because its
  raw output uses different formatting or because its justified final diff is large. The accepted
  CI invariant is that this ordered pipeline leaves the committed repository state unchanged; a
  raw `rewrite:dryRun` result or a second unformatted OpenRewrite run is not a substitute.
- Give every evaluated leaf recipe an individual status and evidence-based rationale in the
  repository recipe decision record. A parent-composite decision MUST NOT stand in for its
  children. Zero current results MUST NOT exclude a recipe. Select a compatible, generally
  applicable leaf as a recurrence guard when it makes code meaningfully better or enforces a useful
  invariant for Java, Maven, or an ecosystem already used by the repository. Compatible means no
  previously supported, working use stops working. A correction to invalid or already-broken
  behavior remains compatible when the generated behavior is genuinely better; an observable
  behavior change alone does not make the repair breaking. Record a preferable transformation that
  stops supported, working use as an inactive breaking-release candidate. Reject unsafe,
  context-dependent, defective, or worse output. Mark a leaf Contingent only when its language,
  build tool, library, framework, capability, or required migration target is absent from the
  recurring lane. Quarkus target-BOM migrations remain owned by the selected `quarkus:update`
  migration rather than the recurring composite.
- Do not treat a report-only or candidate static-analysis profile as finished while it still
  contains known justified findings. If the current branch cannot fix every finding, make the
  remaining work explicit in GitHub issues before finishing and link every follow-up issue from the
  relevant meta or tracking issue. Keep that meta issue complete and current until the final end
  state is reached: every useful non-noisy rule is enforced by `./mvnw -q spotless:check verify`,
  every justified finding is fixed, every true false positive has a targeted suppression with a
  reason, and deferred or rejected rules have documented rationale. Implementing the meta issue
  should be sufficient to reach the stated static-analysis end state without rediscovering hidden
  follow-up work.
- Handle static-analysis findings in this order: fix justified findings; tune the rule if it is
  valid but too broad; suppress false positives with the narrowest possible scope; include a reason
  for every suppression. Do not disable a whole analyzer, package, source tree, or rule category only
  to make a check pass. When describing false-positive evidence, tie the evidence to the rule's
  semantics. For example, successful compilation is relevant evidence for a type-resolution rule that
  reports an unresolved type, but it is not relevant evidence against most supplementary
  maintainability, correctness, security, performance, or style findings. Exclude generated code only
  when the affected path is actually generated, vendored, or otherwise outside the intended analysis
  scope. Hosted dashboards may add signal, but they must not replace local checks that an agent can
  run, fix, and rerun.
- If the same finding family needs recurring suppressions across multiple analyzers, reconsider the
  rule or code boundary instead of copying suppressions into every tool. Decide whether the rule is
  the wrong fit for this repository, whether a narrower custom rule should replace it, or whether the
  code should expose a clearer reviewed boundary that analyzers can understand.

## Per-tool rules

- For PMD, prefer fixing or rule tuning. Use `@SuppressWarnings("PMD.RuleName")` for code-local
  suppressions, `// NOPMD - reason` only for truly line-local cases, and ruleset-level suppression
  only when a repeated false positive can be described precisely. Consider PMD's unnecessary
  suppression checks when practical so stale suppressions are caught.
- For SpotBugs and FindSecBugs, prefer fixing findings. Use `config/spotbugs/exclude.xml` for
  project-level false positives and `@SuppressFBWarnings(value = "...", justification = "...")` only
  when the exception belongs next to the code. Keep filter entries precise by bug pattern, class,
  method, or field, and do not suppress broad packages or all security findings.
- For Error Prone and Picnic Error Prone Support, start new rules in a non-blocking profile until
  build compatibility and baseline findings are understood. Prefer generated or in-place patches for
  mechanical fixes, promote checks from warning to error only after baseline cleanup, and use stable
  `-Xep:<CheckName>:OFF|WARN|ERROR` flags for rule control. The current selected Error Prone, Picnic
  bug-check, JUnit, Mockito, and Refaster rule families run as blocking production and test-source
  compiler checks in `./mvnw -q spotless:check verify`; keep any future rewrite/fix exploration
  explicit so normal verification does not unexpectedly modify source files.
- For Semgrep, use custom rules for cross-language guardrails and security patterns that are not
  already covered by specialized linters. Prefer fixing findings, use rule-specific `nosemgrep`
  comments only with a reason, use `.semgrepignore` only for generated, vendored, or irrelevant
  paths, and keep local Semgrep checks metrics-disabled. The accepted repository Semgrep rules live
  in `config/semgrep` and run in the dedicated `Semgrep` workflow. Before adding or promoting
  another Semgrep rule, measure the baseline locally with metrics disabled, fix justified findings,
  and document any rejected rule in the relevant issue or ADR.
- Treat CodeQL as a hosted repository code-scanning layer. Keep local Maven-based checks as the
  primary agent feedback loop, and do not require CodeQL as part of normal local `verify`.

## Automation config

- Keep automation config minimal. Do not restate inherited defaults or duplicate global Renovate
  policy in package rules unless the narrower rule changes behavior.
- When pinning tool versions outside their native manifest files, ensure Renovate can update them
  through an existing manager or an explicit custom manager.
- Pin GitHub Actions to full commit SHAs with the tracked version tag in a comment. Renovate may
  automerge non-major GitHub Actions updates after the configured release-age delay, but major action
  updates still need human review.

## References

- [Java style & design preferences](java-style.md)
- [Specification & ADR discipline](specification-and-adr-discipline.md)
