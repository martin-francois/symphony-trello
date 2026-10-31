# Changelog

## 2.0.0 (2026-10-31)

## Breaking Changes

* **workflow:** Generated or migrated workflows use the No PR label for branch-only handoff. Rename the label, configure github.no_pr_label to a different name, or disable it with an empty string to keep prior behavior. A top-level github value must now be an object with valid fields. Remove or rename unrelated values, or replace them with documented settings. ([7ec1cf0](https://github.com/martin-francois/symphony-trello/commit/7ec1cf05810bdeae1768e192dc52ec58eeb04d26))
* **installer:** Source updates reject staged or unstaged tracked edits. Commit edits on a separate branch, or save a patch and restore the files, before rerunning the installer. Untracked build output remains allowed. ([3dc25b7](https://github.com/martin-francois/symphony-trello/commit/3dc25b73f658f159dc22a067c55b287ff522a149))
* **installer:** Existing installs with unreadable versions and source updates with local build inputs now stop before replacement. Repair Java or the existing app classes, and move local build inputs outside the app checkout before retrying. Restore recorded Git casing for selected inputs after an unrecorded case-only rename. Source builds no longer read caller-directory configuration. Migration help failures stop setup and worker restart; repair the installed command before continuing. ([e2a3433](https://github.com/martin-francois/symphony-trello/commit/e2a3433d373f0c0a89783ae84bf9102960e87a33))
* **installer:** source updates now refuse any tracked path marked assume-unchanged or skip-worktree, including unchanged paths and sparse checkouts. Back up local edits, clear those flags, disable sparse checkout, and save or restore the resulting changes before retrying. ([3999abd](https://github.com/martin-francois/symphony-trello/commit/3999abdfb8b59489cb9e0c8c6bea67566746713d))
* **workflow:** Repository-changing cards now run up to 3 Codex review rounds before handoff by default, which takes longer and uses more tokens. Set `repository.codex_review_max_rounds: 0` to keep the earlier behavior. ([2831f14](https://github.com/martin-francois/symphony-trello/commit/2831f14732343da45c950425aee6337b10ca2dba))

## What's Changed
* test(concurrency): audit production guards by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/583
* test(orchestrator): synchronize command request publication by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/595
* test(setup): isolate managed-port selection by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/596
* fix(ci): rotate size-label reconciliation by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/599
* feat(release): credit contributors in changelog by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/598
* feat(build): add guarded OpenRewrite maintenance lane by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/592
* feat(build): automate OpenRewrite updates by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/604
* docs: explain queueing cards for future Codex capacity by @YashRaj0307 in https://github.com/martin-francois/symphony-trello/pull/594
* fix(ci): keep Error Prone updates compatible by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/607
* test(ci): isolate host-dependent fixtures by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/608
* chore(deps): update openrewrite toolchain by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/606
* test(ci): decouple guards from dependency pins by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/610
* chore(deps): update dependency org.openrewrite:rewrite-maven to v8.87.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/611
* fix(ci): handle SpotBugs 4.10 false positives by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/612
* fix(ci): bound parallel test resources by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/615
* docs: add demo video to README by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/605
* fix(deps): update all dependencies by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/609
* fix(deps): update all dependencies by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/617
* chore(deps): update dependency pnpm to v11.13.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/621
* chore(deps): update all dependencies by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/622
* chore(deps): update dependency pnpm to v11.14.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/623
* chore(deps): update dependency pnpm to v11.15.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/624
* feat(ci): track pull requests waiting for author action by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/619
* chore(deps): update dependency pnpm to v11.15.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/625
* chore(deps): update actions/checkout action to v7.0.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/626
* chore(deps): update github/codeql-action action to v4.37.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/627
* chore(deps): update semgrep/semgrep docker tag to v1.170.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/628
* chore(deps): update github/codeql-action action to v4.37.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/629
* chore(deps): update dependency pnpm to v11.16.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/630
* chore(deps): update semgrep/semgrep docker tag to v1.171.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/631
* fix(deps): update all dependencies to v3.37.4 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/632
* chore(deps): update all dependencies by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/633
* chore(deps): update dependency com.diffplug.spotless:spotless-maven-plugin to v3.9.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/635
* chore(deps): update semgrep/semgrep docker tag to v1.172.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/636
* chore(deps): update openrewrite toolchain by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/620
* fix(deps): update all dependencies by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/637
* chore(deps): update actions/attest action to v4.2.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/639
* build: pin tool images and share engineering guidance by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/634
* fix(renovate): separate major updates from the automergeable bundle by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/641
* chore(deps): update openrewrite toolchain by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/644
* fix(deps): update all non-major dependencies by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/643
* chore(deps): update actions/setup-java digest to b6effb0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/645
* chore(config): refresh lockfiles on a schedule so transitive advisories get fixed by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/646
* chore(deps): pin dependencies by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/647
* chore(deps): update github/codeql-action action to v4.37.5 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/648
* fix(deps): update all non-major dependencies by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/650
* chore(deps): update dependency pnpm to v11.21.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/651
* chore(deps): update all non-major dependencies to v21.2.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/652
* fix(deps): update all non-major dependencies by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/653
* chore(deps): update dependency pnpm to v11.22.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/654
* test: harden OpenRewrite workflow contract checks by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/656
* chore(deps): update openrewrite toolchain by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/657
* chore(deps): update dependency com.diffplug.spotless:spotless-maven-plugin to v3.10.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/658
* fix(deps): update dependency com.google.guava:guava to v33.7.1-jre by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/660
* docs(README): don't require knowledge of WORKFLOW.md when using insta… by @nw1n in https://github.com/martin-francois/symphony-trello/pull/662
* docs(README): explain api key and token sensitivity in logical order by @nw1n in https://github.com/martin-francois/symphony-trello/pull/663
* docs(README): explain that the token link is hidden inside a paragrap… by @nw1n in https://github.com/martin-francois/symphony-trello/pull/664
* chore(deps): update dependency org.openrewrite:rewrite-maven to v8.90.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/659
* fix(deps): update all non-major dependencies to v3.38.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/671
* chore(deps): update dependency com.github.spotbugs:spotbugs-maven-plugin to v4.10.4.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/673
* chore(deps): update OpenRewrite toolchain by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/672
* chore(deps): update semgrep/semgrep docker tag to v1.174.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/675
* chore(deps): update dependency org.openrewrite:rewrite-maven to v8.90.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/674
* chore(deps): update github/codeql-action action to v4.37.8 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/676
* docs(agents): require dated migration cleanup by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/677
* chore(deps): update dependency pnpm to v11.23.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/679
* docs: clarify repository defaults for generic Git repositories by @YashRaj0307 in https://github.com/martin-francois/symphony-trello/pull/613
* ci: add ClusterFuzzLite continuous fuzzing by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/512
* fix(ci): reclaim ClusterFuzzLite build output by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/680
* fix(ci): make fuzz storage failures visible by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/681
* fix(ci): support Java 25 fuzz coverage by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/682
* fix(ci): install Java 25 for fuzz coverage by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/683
* fix(ci): keep continuous fuzzing running by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/684
* chore(deps): update dependency pnpm to v11.24.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/685
* fix(ci): bootstrap continuous fuzzing deterministically by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/686
* fix(ci): schedule watchdog in established workflow by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/688
* fix(fuzzing): replace cron with a self-queued watchdog by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/689
* docs: clarify issue label application by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/694
* chore(deps): update actions/setup-java action to v6 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/687
* chore(deps): update dependency org.openrewrite:rewrite-maven to v8.90.4 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/695
* test(ci): make setup-java order guard version-independent by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/696
* fix(deps): update all non-major dependencies by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/697
* chore(deps): update dependency com.diffplug.spotless:spotless-maven-plugin to v3.10.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/698
* chore(deps): update dependency pnpm to v11.25.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/699
* chore(deps): update dependency org.apache.maven.plugins:maven-compiler-plugin to v3.16.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/701
* chore(deps): update all non-major dependencies to v3.6.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/702
* chore(deps): update semgrep/semgrep docker tag to v1.176.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/703
* fix(deps): update all non-major dependencies by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/704
* chore(deps): update dependency com.github.spotbugs:spotbugs-maven-plugin to v4.10.4.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/706
* chore(deps): update dependency pnpm to v11.26.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/707
* chore(deps): update advanced-security/maven-dependency-submission-action action to v6 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/705
* chore(deps): align Renovate with repository visibility by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/710
* chore(deps): update actions/setup-java action to v6.0.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/712
* chore(deps): update dependency @types/node to v24.13.4 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/713
* chore(deps): update dependency org.codehaus.mojo:exec-maven-plugin to v3.6.4 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/714
* ci: pin Renovate validator toolchain by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/708
* chore(deps): pin dependency java-jdk to 25.0.4+7.0.lts by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/711
* chore(deps): update dependency yaml to v2.9.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/715
* fix(deps): update quarkus.platform.version to v3.39.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/716
* chore(deps): update dependency renovate to v44.82.5 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/718
* chore(deps): update dependency pnpm to v11.27.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/717
* chore(deps): update semgrep/semgrep docker tag to v1.177.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/720
* chore(deps): update github/codeql-action action to v4.38.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/719
* chore(deps): update dependency renovate to v44.83.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/721
* fix(deps): update dependency io.opentelemetry:opentelemetry-bom to v1.66.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/722
* chore(deps): update dependency renovate to v44.83.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/723
* chore(deps): update dependency renovate to v44.85.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/724
* chore(deps): update dependency renovate to v44.86.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/725
* chore(deps): update dependency renovate to v44.90.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/726
* chore(deps): update dependency renovate to v44.90.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/727
* chore(deps): update dependency renovate to v44.91.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/728
* chore(deps): update dependency @types/node to v24.13.5 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/729
* chore(deps): update dependency renovate to v44.94.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/730
* chore(deps): update dependency renovate to v44.95.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/731
* chore(deps): update dependency pnpm to v12 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/700
* chore(deps): update dependency renovate to v44.97.4 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/733
* fix(deps): update quarkus.platform.version to v3.39.4 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/732
* chore(deps): update eclipse-temurin:25.0.4_7-jdk docker digest to 97014c4 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/734
* chore(deps): update github/codeql-action action to v4.38.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/735
* chore(deps): update dependency renovate to v44.101.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/736
* chore(deps): update dependency pnpm to v12.5.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/737
* chore(deps): update dependency renovate to v44.103.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/738
* chore(deps): update dependency pnpm to v12.5.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/739
* chore(deps): update dependency @types/node to v24.13.6 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/740
* chore(deps): update dependency renovate to v44.103.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/741
* chore(deps): update dependency renovate to v44.103.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/742
* chore(deps): update dependency renovate to v44.103.6 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/743
* chore(deps): update dependency renovate to v44.103.7 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/744
* chore(deps): update dependency renovate to v44.104.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/745
* chore(deps): update dependency renovate to v44.104.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/746
* chore(deps): update dependency renovate to v44.104.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/747
* chore(deps): update dependency renovate to v44.106.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/748
* chore(deps): update dependency renovate to v44.107.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/750
* chore(deps): update dependency pnpm to v12.6.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/749
* chore(deps): update dependency renovate to v44.108.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/751
* chore(deps): update dependency renovate to v44.108.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/752
* chore(deps): update dependency renovate to v44.111.4 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/754
* fix(deps): update quarkus.platform.version to v3.39.5 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/753
* chore(deps): update github/codeql-action action to v4.38.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/755
* chore(deps): update dependency renovate to v44.112.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/756
* chore(deps): update dependency renovate to v44.115.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/757
* chore(deps): update mockito monorepo to v5.24.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/758
* chore(deps): update dependency renovate to v44.115.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/759
* chore(deps): update semgrep/semgrep docker tag to v1.178.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/760
* chore(deps): update dependency com.tngtech.archunit:archunit-junit5 to v1.5.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/761
* chore(deps): update dependency renovate to v44.115.4 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/762
* chore(deps): update dependency pnpm to v12.7.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/763
* chore(deps): update dependency renovate to v44.115.6 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/764
* chore(deps): update dependency com.diffplug.spotless:spotless-maven-plugin to v3.10.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/765
* chore(deps): update dependency @types/node to v24.19.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/767
* chore(deps): update dependency renovate to v44.115.9 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/766
* chore(deps): update dependency renovate to v44.115.10 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/768
* chore(deps): update dependency renovate to v44.115.11 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/837
* chore(deps): update dependency renovate to v44.115.12 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/841
* chore(deps): update dependency renovate to v44.115.13 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/851
* chore(deps): update dependency maven to v3.10.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/854
* chore(deps): update dependency pnpm to v12.8.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/866
* chore(deps): update dependency renovate to v44.116.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/867
* fix(renovate): look up BetterLeaks through GitHub's package feed by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/864
* chore(deps): update dependency ghcr.io/betterleaks/betterleaks to v1.8.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/868
* chore(deps): update dependency pnpm to v12.8.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/869
* chore(deps): update dependency renovate to v44.117.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/871
* chore(deps): update dependency renovate to v44.117.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/873
* chore(deps): update dependency renovate to v44.118.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/874
* chore(deps): update dependency renovate to v44.119.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/875
* chore(deps): update dependency renovate to v44.119.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/876
* fix(renovate): read push times for the .NET SDK and OSS-Fuzz images by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/870
* fix(deps): update dependency com.google.guava:guava to v33.7.2-jre by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/877
* chore(renovate): align with the public repository baseline by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/872
* chore(deps): update dependency renovate to v44.120.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/878
* chore(deps): update dependency renovate to v44.121.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/879
* chore(deps): update dependency renovate to v44.121.4 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/880
* chore(deps): update dependency ghcr.io/betterleaks/betterleaks to v1.9.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/881
* chore(deps): update dependency pnpm to v12.8.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/882
* chore(deps): update dependency renovate to v44.124.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/883
* chore(deps): update dependency renovate to v44.124.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/884
* fix(deps): update quarkus.platform.version to v3.40.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/886
* chore(deps): update dependency renovate to v44.124.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/885
* chore(deps): update dependency renovate to v44.126.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/887
* chore(deps): update dependency renovate to v44.126.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/888
* chore(deps): update dependency renovate to v44.127.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/889
* chore(deps): update dependency renovate to v44.128.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/890
* chore(deps): update dependency renovate to v44.129.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/891
* chore(deps): update dependency renovate to v44.131.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/892
* chore(deps): update gcr.io/oss-fuzz-base/base-builder-jvm digest to 09b8394 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/893
* chore(deps): update gcr.io/oss-fuzz-base/clusterfuzzlite-run-fuzzers digest to b21609f by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/894
* chore(deps): update gcr.io/oss-fuzz-base/base-builder-jvm digest to ce90dc7 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/895
* chore(deps): update dependency @types/node to v24.19.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/896
* chore(deps): update semgrep/semgrep docker tag to v1.179.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/898
* chore(deps): update dependency renovate to v44.132.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/897
* chore(deps): update dependency renovate to v44.132.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/899
* chore(deps): update dependency pnpm to v12.9.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/900
* chore(deps): update dependency renovate to v44.132.4 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/902
* chore(deps): update dependency pnpm to v12.9.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/903
* chore(deps): update dependency renovate to v44.132.5 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/904
* chore(deps): update dependency renovate to v44.133.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/905
* chore(deps): update dependency mcr.microsoft.com/dotnet/sdk to v10 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/901
* ci: run PowerShell tests on the pinned .NET SDK image by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/906
* chore(deps): update dependency renovate to v44.133.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/907
* chore(deps): update dependency renovate to v44.134.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/909
* chore(renovate): refresh lockfiles on any Friday run by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/908
* chore(deps): update dependency renovate to v44.134.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/910
* refactor: fix the production-code Error Prone warnings by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/912
* chore(deps): update dependency renovate to v44.137.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/915
* fix(fuzzing): start the fuzzing JVM without Java 25 warnings by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/916
* chore(deps): update dependency renovate to v44.138.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/917
* chore(deps): update dependency renovate to v44.139.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/919
* chore(deps): update dependency pnpm to v12.10.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/918
* chore(deps): update dependency renovate to v44.140.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/920
* chore(deps): update dependency pnpm to v12.10.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/921
* chore(deps): update dependency renovate to v44.142.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/922
* chore(deps): update actions/upload-artifact action to v7.0.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/924
* chore(deps): update actions/download-artifact action to v8.0.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/923
* chore(deps): update dependency renovate to v44.144.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/925
* chore(deps): update dependency renovate to v44.145.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/926
* chore(deps): update actions/setup-node action to v7.1.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/927
* chore(deps): update semgrep/semgrep docker tag to v1.180.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/928
* chore(deps): update dependency renovate to v44.145.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/929
* chore(deps): update dependency renovate to v44.147.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/931
* chore(deps): update dependency renovate to v44.148.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/932
* chore(deps): update github/codeql-action action to v4.38.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/930
* chore(deps): update dependency com.diffplug.spotless:spotless-maven-plugin to v3.10.4 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/933
* chore(deps): update dependency renovate to v44.148.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/934
* chore(deps): update gcr.io/oss-fuzz-base/base-builder-jvm digest to 546368a by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/935
* chore(deps): update gcr.io/oss-fuzz-base/clusterfuzzlite-run-fuzzers digest to e06ea97 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/936
* chore(deps): update dependency renovate to v44.148.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/937
* chore(deps): update dependency renovate to v44.148.4 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/939
* chore(deps): update gcr.io/oss-fuzz-base/base-builder-jvm digest to 1a11b25 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/938
* chore(deps): update dependency pnpm to v12.11.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/940
* chore(deps): update gcr.io/oss-fuzz-base/clusterfuzzlite-run-fuzzers digest to 6a6fae2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/941
* chore(deps): update dependency pnpm to v12.11.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/942
* chore(deps): update dependency @types/node to v24.19.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/943
* chore(deps): update dependency renovate to v44.149.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/945
* chore(deps): update dependency pnpm to v12.11.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/944
* fix(deps): update dependency io.opentelemetry:opentelemetry-bom to v1.67.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/946
* chore(deps): update dependency renovate to v44.149.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/947
* chore(deps): update dependency pnpm to v12.12.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/948
* chore(deps): update dependency renovate to v44.149.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/949
* chore(deps): update dependency pnpm to v12.12.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/950
* chore(deps): update dependency renovate to v44.150.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/951
* chore(deps): update dependency renovate to v44.151.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/952
* chore(deps): update dependency renovate to v44.151.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/954
* chore(deps): update dependency com.github.spotbugs:spotbugs-maven-plugin to v4.10.5.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/953
* chore(deps): update ossf/scorecard-action action to v2.5.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/955
* chore(renovate): stop requesting review on dependency PRs by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/957
* chore(deps): update dependency renovate to v44.151.5 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/958
* chore(deps): update dependency pnpm to v12.13.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/961
* chore(deps): update actions/create-github-app-token action to v3.3.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/960
* chore(deps): update dependency renovate to v44.155.5 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/962
* chore(deps): update mcr.microsoft.com/dotnet/sdk digest to 0d99ebf by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/964
* chore(deps): update dependency renovate to v44.155.8 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/965
* chore(deps): update dependency renovate to v44.156.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/967
* chore(deps): update dependency renovate to v44.156.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/968
* chore(deps): update dependency renovate to v44.157.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/969
* chore(deps): update dependency renovate to v44.158.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/970
* chore(deps): update dependency renovate to v44.158.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/971
* chore(deps): update dependency renovate to v44.160.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/973
* chore(deps): update actions/setup-java action to v6.1.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/972
* chore(deps): update semgrep/semgrep docker tag to v1.181.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/974
* chore(deps): update dependency renovate to v44.161.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/975
* chore(deps): update dependency renovate to v44.161.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/976
* chore(deps): update github/codeql-action action to v4.39.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/978
* chore(deps): update dependency renovate to v44.163.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/977
* chore(deps): update dependency pnpm to v12.14.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/979
* chore(deps): update dependency renovate to v44.164.6 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/980
* chore(deps): update gcr.io/oss-fuzz-base/clusterfuzzlite-run-fuzzers digest to d22b8cc by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/982
* chore(deps): update gcr.io/oss-fuzz-base/base-builder-jvm digest to bd13a76 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/981
* chore(deps): update dependency renovate to v44.164.7 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/983
* chore(deps): update gcr.io/oss-fuzz-base/clusterfuzzlite-run-fuzzers digest to 8a49dc0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/984
* chore(deps): update dependency renovate to v44.164.10 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/985
* chore(deps): update gcr.io/oss-fuzz-base/clusterfuzzlite-run-fuzzers digest to 1b78cfc by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/986
* chore(deps): update dependency renovate to v44.165.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/987
* chore(deps): update dependency renovate to v44.165.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/988
* chore(deps): update dependency renovate to v44.165.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/989
* chore(deps): update dependency renovate to v44.166.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/990
* chore(deps): update dependency ubuntu to v26 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/963
* fix(renovate): skip .NET SDK previews behind the major.minor tag by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/991
* ci: give CodeQL's Maven dependency lookup the project's JDK by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/992
* refactor: replace deprecated picocli and AssertJ calls by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/993
* fix(config): read KEY= # comment in a dotenv file as an empty value by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/862
* docs(adr): keep the hand-rolled dotenv parser by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/808
* docs(adr): keep the hand-rolled environment reference classifier by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/801
* test(orchestrator): stop the workflow watcher before the late-usage probe by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1017
* test(setup): hold the diagnostics fake server port from bind to probe by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1015
* docs(README): send installer users to the terminal for Trello credentials by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/770
* docs(diagnostics): keep the local Markdown table helper over commonmark-java by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/849
* test(installer): stop timed-out process trees before terminating them by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1021
* chore(deps): update dependency renovate to v44.167.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1022
* test(agent): pick concurrent app-server fixture roles by workspace by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1020
* test(setup): assert the uninstall stop contract instead of TERM-handler timing by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1019
* fix(config): fail path settings whose environment variable is missing by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/830
* fix(config): expand braced environment prefixes in path settings by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/821
* chore(deps): update dependency renovate to v44.167.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1028
* test(fuzz): assert harness properties and verify fuzz target isolation by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/787
* ci(coderabbit): enforce the pull request template with pre-merge checks by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/781
* test(installer): reuse the account's image store for PowerShell tests by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/827
* feat(installer): color installer output with accessible blue accents by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/822
* feat(setup): add version ordering and atomic file replacement by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1036
* feat(installer): streamline installer progress and completion output by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/855
* test(cli): add snapshot coverage for terminal output by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/793
* chore(deps): update maven docker tag to v3.10.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1041
* test(fuzzing): check Trello handoff tool write scope by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/842
* feat(setup): record generated workflow provenance by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1043
* feat(setup): migrate generated workflow bodies by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1044
* fix(diagnostics): resolve path tokens from log lines and tool output by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/825
* feat(setup): pick the Codex model from the installed catalog by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/796
* feat(setup): support arrow-key editing in setup prompts by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/818
* feat(agent): validate dynamic-tool arguments against advertised schemas by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/784
* fix(deps): update dependency com.networknt:json-schema-validator to v2.0.8 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1047
* feat(trello): create follow-up cards for out-of-scope work by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/860
* fix(diagnostics): keep paths with spaces below option directories in one token by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/829
* chore(deps): update dependency renovate to v44.167.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1050
* fix(setup): start npm codex.cmd shims for setup Codex launches on Windows by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/831
* chore(deps): update dependency renovate to v44.167.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1052
* feat(setup): add a guided tutorial on a temporary Trello board by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/828
* docs(fuzzing): record the first-week baseline and next targets by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/774
* chore(deps): update dependency renovate to v44.167.4 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1053
* chore: let Renovate update HyperFrames without weakening render freshness by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/794
* test(fuzzing): fuzz Trello card payload mapping by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/836
* test(fuzz): resolve fuzzed workflow front matter into typed configuration by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/838
* chore(deps): update dependency renovate to v44.167.5 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1056
* fix(setup): name the underlying cause in every setup failure message by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/779
* docs(adr): keep the hand-rolled Trello retry backoff by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/797
* feat(installer): verify release signatures with GitHub CLI by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/811
* test(fuzz): generate structured repository source input by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/785
* refactor(repository): remove proven unreachable source checks by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1060
* feat(setup): assign linked GitHub issues before implementation by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/780
* fix(trello): close Merging cards with nothing to merge in Done by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/783
* test(installer): run PowerShell tests through the podman docker shim by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/845
* test(setup): retain delayed health re-probes after measuring stalls by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/850
* refactor: null-mark the first audited packages by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/861
* feat(orchestrator): slow Trello polling down under rate limits by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/802
* feat(setup): offer a Codex investigation before setup-failure issues by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/813
* feat(release): add opt-in Maven Central publication with JReleaser by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/814
* feat(installer): offer the tutorial after completed setup by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1062
* ci(review): require an explicit assistance disclosure choice by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1037
* chore(deps): update dependency renovate to v44.167.6 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1067
* chore(deps): update dependency renovate to v44.167.7 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1068
* chore(deps): update dependency renovate to v44.168.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1069
* fix(deps): update quarkus.platform.version to v3.40.1.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1070
* chore(deps): update dependency renovate to v44.168.4 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1071
* feat(workflow)!: support branch-only handoff with a No PR label by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/844
* chore(deps): update dependency renovate to v44.171.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1075
* chore(deps): update HyperFrames demo to v0.8.174 by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1074
* fix(build): format unnamed try-with-resources variables by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1077
* fix: confirm worker shutdown before workspace removal by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1080
* chore(deps): update dependency renovate to v44.171.4 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1081
* chore(deps): update dependency renovate to v44.171.5 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1082
* chore(deps): update dependency renovate to v44.171.7 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1084
* feat(installer)!: migrate workflows after supported updates by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/798
* chore(deps): update dependency renovate to v44.172.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1087
* feat(bugbash): add repeatable live bug-bash harness by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/809
* chore(deps): update dependency renovate to v44.172.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1089
* feat(trello): identify every Symphony-authored comment by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/775
* chore(deps): update semgrep/semgrep docker tag to v1.182.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1090
* chore(deps): update dependency renovate to v44.172.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1091
* perf(trello): reuse prerequisite status lookups between polls by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/839
* chore(deps): update dependency renovate to v44.173.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1095
* chore(deps): update dependency renovate to v44.173.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1096
* chore(deps): update dependency renovate to v44.175.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1098
* test: disambiguate diagnostic port privacy assertion by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1099
* chore(deps): update dependency renovate to v44.175.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1100
* chore(deps): update dependency pnpm to v12.15.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1101
* chore(deps): update github/codeql-action action to v4.39.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1102
* chore(deps): update dependency renovate to v44.176.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1103
* chore(deps): update dependency renovate to v44.176.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1104
* chore(deps): update dependency renovate to v44.176.2 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1105
* chore(deps): update semgrep/semgrep docker tag to v1.182.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1106
* chore(deps): update dependency renovate to v44.176.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1107
* chore(deps): update surefire.version to v3.6.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1110
* chore(deps): update dependency renovate to v44.177.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1111
* chore(deps): update dependency renovate to v44.178.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1112
* fix(ci): check every tracked Bash script in the syntax gate by @dannyward630 in https://github.com/martin-francois/symphony-trello/pull/1065
* fix: replace BOM-prefixed Trello credentials in place by @Gabrieljr42 in https://github.com/martin-francois/symphony-trello/pull/956
* test(setup): check parsed writable roots instead of raw YAML by @senthilkumar-r in https://github.com/martin-francois/symphony-trello/pull/863
* test(orchestrator): mutation-audit the orchestrator locks by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/819
* chore(deps): update dependency renovate to v44.178.3 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1115
* chore(deps): update gcr.io/oss-fuzz-base/clusterfuzzlite-run-fuzzers digest to 66a1455 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1116
* chore(deps): update dependency @types/node to v26 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1092
* chore(deps): update dependency renovate to v44.178.5 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1117
* test(setup): check rendered port forms in diagnostics privacy tests by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1114
* refactor(setup): share one Trello credential resolver by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/810
* docs(agents): record who ticks the understanding box and how PRs merge by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1123
* chore(deps): update hyperframes to v0.8.181, re-upload README video only on visible change by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1126
* refactor(orchestrator): publish a read view for status readers by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/848
* chore(deps): hold json-schema-validator on 2.x until Quarkus 4 by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1120
* ci: install pnpm with pnpm/action-setup instead of Corepack by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1122
* chore(deps): update dependency renovate to v44.179.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1131
* chore(deps): update hyperframes to v0.8.186 and refresh the README demo by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1132
* chore(deps): update dependency renovate to v44.179.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1138
* docs(agents): rank easy issues with real benefit higher by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1133
* test(bugbash): signal the cancel test's group only once every member exists by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1137
* feat(workflow): run a Codex review round before handoff by default by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/853
* chore(deps): update gcr.io/oss-fuzz-base/clusterfuzzlite-run-fuzzers digest to e374428 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1145
* chore(deps): update dependency renovate to v44.180.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1146
* fix(release): add breaking-change footers to the release notes by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1149
* feat(cli): add card commands and a Codex skill for connected boards by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1139
* chore(deps): update dependency renovate to v44.181.0 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1164
* chore(deps): update dependency hyperframes to v0.8.188 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1130
* fix(installer): look up the install marker by literal path by @bfs2021 in https://github.com/martin-francois/symphony-trello/pull/1141
* feat(workflow)!: run a multi-round Codex review loop before handoff by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1160
* chore(deps): update gcr.io/oss-fuzz-base/clusterfuzzlite-run-fuzzers digest to 74f2895 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1168
* docs(agents): keep one cohesive change per commit in main by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1158
* feat(scripts): audit issue labels and make sweeps answer people by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1159
* docs(agents): file every issue through the repository's issue forms by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1169
* docs(agents): fix pull request descriptions instead of asking the author by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1172
* fix(agent): end a Codex turn only after the turn timeout of silence by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1163
* fix(tooling): share local and CI commitlint pins by @ect2000 in https://github.com/martin-francois/symphony-trello/pull/1167
* chore(deps): update commitlint monorepo to v21.2.4 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1177
* ci: fail the metadata check when an AI-assisted pull request lacks the confirmation by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1171
* chore(deps): update dependency hyperframes to v0.8.189 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1174
* test(orchestrator): wait for the deadline's runner cancel before verifying it by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1180
* ci: add GitHub Actions security rules and audit the workflows by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1170
* test(orchestrator): advance the stall clock only after the final event is handled by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1181
* ci: keep workflow step code in repository files by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1182
* chore(deps): update dependency hyperframes to v0.8.190 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1183
* fix(setup): reject manifest provenance collisions by @Gabrieljr42 in https://github.com/martin-francois/symphony-trello/pull/1186
* chore(deps): update dependency renovate to v44.181.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1188
* ci: require the understanding box on every pull request by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1184
* docs: gate outside pull requests on the understanding box and merge them at 80% confidence by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1189
* ci: flag and close pull requests with an incomplete description by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1173
* ci(size-labeler): leave ADR lines out of size labels by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1190
* chore(deps): update dependency hyperframes to v0.8.191 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/1191
* test: sanitize JVM launcher environment variables in child process tests by @Mohammed-Atif33 in https://github.com/martin-francois/symphony-trello/pull/1195
* refactor(setup): centralize Windows detection by @Rpfdeveloper in https://github.com/martin-francois/symphony-trello/pull/1194
* docs(agents): calibrate issue size estimates against merged pull requests by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1192
* ci: submit Maven dependencies only from main by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1200
* fix: render WORKFLOW.example.md on a card's first attempt by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1202
* docs: say how maintainers get the pinned pnpm on Node.js 26 by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1203
* fix(diagnostics): report supported setup-local option names by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1206
* fix(cli): resolve the secret-file lookup hint of an unconnected workflow by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/1212

## New Contributors
* @YashRaj0307 made their first contribution in https://github.com/martin-francois/symphony-trello/pull/594
* @nw1n made their first contribution in https://github.com/martin-francois/symphony-trello/pull/662
* @dannyward630 made their first contribution in https://github.com/martin-francois/symphony-trello/pull/1065
* @Gabrieljr42 made their first contribution in https://github.com/martin-francois/symphony-trello/pull/956
* @senthilkumar-r made their first contribution in https://github.com/martin-francois/symphony-trello/pull/863
* @bfs2021 made their first contribution in https://github.com/martin-francois/symphony-trello/pull/1141
* @ect2000 made their first contribution in https://github.com/martin-francois/symphony-trello/pull/1167
* @Mohammed-Atif33 made their first contribution in https://github.com/martin-francois/symphony-trello/pull/1195
* @Rpfdeveloper made their first contribution in https://github.com/martin-francois/symphony-trello/pull/1194

**Full Changelog**: https://github.com/martin-francois/symphony-trello/compare/v1.2.0...v2.0.0

## [1.2.0](https://github.com/martin-francois/symphony-trello/compare/v1.1.1...v1.2.0) (2026-07-14)


### Features

* **setup:** configure workflow repository URLs ([183cd4e](https://github.com/martin-francois/symphony-trello/commit/183cd4eebfae90473280810dff090a110340d94c))
* **setup:** prefer Terra for new workflows ([853362e](https://github.com/martin-francois/symphony-trello/commit/853362ea4f85f6171a1f15c20860095ea37fa434))
* **setup:** use model-specific reasoning efforts ([78a92d9](https://github.com/martin-francois/symphony-trello/commit/78a92d93f289cc5813d4d3fe6bde3a6f96474cfb))


### Bug Fixes

* **ci:** avoid deprecated artifact downloader ([db9c8aa](https://github.com/martin-francois/symphony-trello/commit/db9c8aa6bb14672354d021cfeb547a46ca708111))
* **ci:** pin Scorecard SARIF upload action ([35008f0](https://github.com/martin-francois/symphony-trello/commit/35008f0ed4d246a5e8427e4c41b9d9191c441cc1))
* **ci:** preserve Scorecard result publishing ([866b484](https://github.com/martin-francois/symphony-trello/commit/866b484c784a60cfeed8ca7a61eb110af2f0d08c))
* **ci:** provide valid Scorecard SARIF locations ([74e33fc](https://github.com/martin-francois/symphony-trello/commit/74e33fce727f00603f8ddf6c99ca612abb2eba1f))
* **ci:** support explicit container runtimes ([21a9fc6](https://github.com/martin-francois/symphony-trello/commit/21a9fc6753201b404d698e1310d7d9db4882256b)), closes [#574](https://github.com/martin-francois/symphony-trello/issues/574)
* **deps:** resolve reported security advisories ([b4c04d9](https://github.com/martin-francois/symphony-trello/commit/b4c04d9b815f1a9db119fa5ca6304b82a05323ab))
* **handoff:** mark resumed work after stale blocker ([2ea4ac0](https://github.com/martin-francois/symphony-trello/commit/2ea4ac01b0ced5f9ac84411964185d65017b57cd))
* **installer:** print final handoff after autostart ([03670aa](https://github.com/martin-francois/symphony-trello/commit/03670aab6b41571cd6e82a7e6f6727ee34ed7aaa))
* **orchestrator:** pause dispatch on Codex usage limits ([c1b2798](https://github.com/martin-francois/symphony-trello/commit/c1b279800132b73add220802ca43a45c837499ab))
* preserve model catalog control validation ([01f8bcf](https://github.com/martin-francois/symphony-trello/commit/01f8bcf698c0d053d8dde6f61e92ceff8157dbee))
* **release:** let Release Please refresh metadata ([a626a57](https://github.com/martin-francois/symphony-trello/commit/a626a57eac548d9423eb734ded548a3bc239056f))
* **security:** honor Git worktree boundaries ([b2ef2b2](https://github.com/martin-francois/symphony-trello/commit/b2ef2b2feebf27165d40a6e4466c3b98a3b976c7)), closes [#574](https://github.com/martin-francois/symphony-trello/issues/574)
* **setup:** omit unadvertised reasoning defaults ([779a5bb](https://github.com/martin-francois/symphony-trello/commit/779a5bb972610228c6267adf2eceaf70cc6b84c1))
* **setup:** preserve repository URL settings ([0ec02bf](https://github.com/martin-francois/symphony-trello/commit/0ec02bfd14a9e8538bc08779225c7f862c67d0a2))
* **status:** contain per-workflow evidence failures ([226af8c](https://github.com/martin-francois/symphony-trello/commit/226af8c90f5a748b4866ec8bd2af71de51cc1fda))
* **status:** isolate workflow status evidence ([29767d8](https://github.com/martin-francois/symphony-trello/commit/29767d85b61f7f98ed907c21fbd0558317f101e7))
* **workflow:** require review reply snippets ([06cd2d2](https://github.com/martin-francois/symphony-trello/commit/06cd2d25f1205fb026f785c8b744d69f1e8538e0))
* **workflow:** use unambiguous card repository context ([5e2eb4b](https://github.com/martin-francois/symphony-trello/commit/5e2eb4bf007d366eb3a72741b62ed1cc59e0a74b))


### Documentation

* add OpenSSF Best Practices badge ([25dcd2e](https://github.com/martin-francois/symphony-trello/commit/25dcd2e400f9ca465995397a630ba2f04d43dc76))
* **agents:** clarify verification friction ([da033ca](https://github.com/martin-francois/symphony-trello/commit/da033ca8201a0abfec59ac475ea3ee2a40d5f27b))
* **agents:** prefer native issue dependencies ([e9b784a](https://github.com/martin-francois/symphony-trello/commit/e9b784abb1602f73b7aee5b964528ebd1e1fafd6))
* prefer containerized bug reproduction ([eed4e13](https://github.com/martin-francois/symphony-trello/commit/eed4e13a629a20592066af27320c9346b6517752))
* require live reproduction before bug fixes ([76401a9](https://github.com/martin-francois/symphony-trello/commit/76401a9da28f3f8f17832c2eae9dc832f7752d0a))
* require visible issue development links ([17cf365](https://github.com/martin-francois/symphony-trello/commit/17cf36578e434a82406aaad6a2b878cca644f731))
* **setup:** explain generated workflow locations ([8ce3a8a](https://github.com/martin-francois/symphony-trello/commit/8ce3a8a314967c6ceba67e9a3151d315e8b21eec))
* **workflow:** batch pull request review comments ([b28a11e](https://github.com/martin-francois/symphony-trello/commit/b28a11ee70f0e06463ed7cdf568aecb80bb170a5))
* **workflow:** require ADR before dependency review reply ([1c763c5](https://github.com/martin-francois/symphony-trello/commit/1c763c5c9e1f6765589707180853d5152176911d))
* **workflow:** require dependency and concurrency evidence ([1b9347a](https://github.com/martin-francois/symphony-trello/commit/1b9347a94067c071f504fc9fd5686cf401b029aa))

## [1.1.1](https://github.com/martin-francois/symphony-trello/compare/v1.1.0...v1.1.1) (2026-07-09)


### Bug Fixes

* allow BetterLeaks container to read mounted scans ([a104ee1](https://github.com/martin-francois/symphony-trello/commit/a104ee11e69779d40de8ab0eacf3f262bbc56a0f))
* support MicroOS installer layouts ([d070445](https://github.com/martin-francois/symphony-trello/commit/d0704458987484895f8f57c8064a7be67656578e)), closes [#530](https://github.com/martin-francois/symphony-trello/issues/530)


### Documentation

* add GitHub issue Trello card skill ([8c1413c](https://github.com/martin-francois/symphony-trello/commit/8c1413c270e2506b94b83190cf8e622dfcd1253b))
* cap Codex review runs at 30 minutes ([699e30a](https://github.com/martin-francois/symphony-trello/commit/699e30a1c8d93e3b3c629ab0d87ccaa7439c7174))
* clarify branch review and response workflows ([ea15a43](https://github.com/martin-francois/symphony-trello/commit/ea15a43374032d7228e929ec20aa6d5dc6dcdf1f))
* clarify issue triage and sweep policy ([6ffa652](https://github.com/martin-francois/symphony-trello/commit/6ffa65275f2c018edb64d771084a308a3849b1a9))
* require rebased pull request branches ([f7f8a42](https://github.com/martin-francois/symphony-trello/commit/f7f8a42ed3e3446472f11a2c9d78b532c707fb43))

## [1.1.0](https://github.com/martin-francois/symphony-trello/compare/v1.0.2...v1.1.0) (2026-07-06)


### Features

* install managed local worker autostart ([ad9cda4](https://github.com/martin-francois/symphony-trello/commit/ad9cda458c52e120eb1a925eabc34d086c52ff2a)), closes [#523](https://github.com/martin-francois/symphony-trello/issues/523)


### Documentation

* add breaking-change declaration workflow and labeling ([f4b6221](https://github.com/martin-francois/symphony-trello/commit/f4b62214208a5dbdcbbfea57e4b7985fd66842a7))
* assign issues when implementation starts ([407a66a](https://github.com/martin-francois/symphony-trello/commit/407a66a26ca239778e5327fd65dff31cc7a9fce3))
* clarify needs human review issue triage ([8f61725](https://github.com/martin-francois/symphony-trello/commit/8f61725b6ec61002d753fcdb352972fefb25e721))
* document GitHub secret scanning baseline ([3ed6978](https://github.com/martin-francois/symphony-trello/commit/3ed6978b8c4ef0e0d0711a8d3d7366b58b1b6473))
* remove manual systemd deployment path ([ccee6b3](https://github.com/martin-francois/symphony-trello/commit/ccee6b3800d21dcebcec8e171bc0369f493ade0a))
* remove stale pre-public release wording ([d33af92](https://github.com/martin-francois/symphony-trello/commit/d33af92217bb5eb9147fc73f0b716f932fad3e9b))

## [1.0.2](https://github.com/martin-francois/symphony-trello/compare/v1.0.1...v1.0.2) (2026-07-06)


### Bug Fixes

* guard empty transactional package list ([fb1faf3](https://github.com/martin-francois/symphony-trello/commit/fb1faf354cf077e823530945c2f166bd6dd597a6))
* support MicroOS transactional installs ([d602ec5](https://github.com/martin-francois/symphony-trello/commit/d602ec5af63116d3c49ac6f44af1efdf58ce97fa))


### Documentation

* clarify Codex review loop verification timing ([fb727db](https://github.com/martin-francois/symphony-trello/commit/fb727db2289a80967895f489a1c4d70b453b0a7c))
* clarify workflow repository defaults ([598a447](https://github.com/martin-francois/symphony-trello/commit/598a4478017e7a5fe80b38ec7780301f89dcc67d))

## [1.0.1](https://github.com/martin-francois/symphony-trello/compare/v1.0.0...v1.0.1) (2026-07-05)


### Bug Fixes

* publish release assets before immutable release ([4d7273c](https://github.com/martin-francois/symphony-trello/commit/4d7273c1adef641745fdd2a101f6c7f68369c51d))
* unblock generated Maven dependency submission ([d6428cd](https://github.com/martin-francois/symphony-trello/commit/d6428cd50b424edbfe9fc4bf4e6c8d0f74b44099))

## 1.0.0

First public release of Symphony for Trello.

### Features

- Connect Symphony to one or more Trello boards.
- Create a recommended Trello board or import an existing board.
- Run Codex from Trello cards in isolated local workspaces.
- Move cards through active, review, blocked, merge, and done lists.
- Keep a Trello workpad comment updated with progress, blockers, validation, and handoff notes.
- Use optional GitHub pull request creation, review sweep, and merge flow.
- Manage local workers with `start`, `stop`, `status`, `logs`, and `setup-local check`.
- Install, update, and uninstall with Bash or PowerShell scripts.
- Configure workflows, Codex command settings, concurrency, workspace paths, Trello write controls,
  and local file access.
- View running and retrying work from the local status page and JSON API.
