---
status: accepted
date: 2026-10-31
decision-makers: [François Martin]
consulted:
  - "[ADR 0069](0069-repository-owned-pull-request-size-labeling.md)"
  - "[ADR 0145](0145-leave-adr-lines-out-of-pull-request-size-labels.md)"
  - "[GitHub issue triage: size labels](../agents/github-issue-triage.md#size-labels)"
informed: [Future maintainers, Contributors]
---

# Calibrate issue size estimates from merged pull requests

## Context and Problem Statement

An issue's size label estimates the lines its pull request will change, and the size labeler
measures the pull request with the same buckets and the same ADR rule
([ADR 0145](0145-leave-adr-lines-out-of-pull-request-size-labels.md)). The two can be compared
once the pull request merges.

On 2026-10-31 the closed issues were paired with the merged pull requests that closed them, as
GitHub links them: the issue's `closedByPullRequestsReferences`, the pull request's
`closingIssuesReferences`, and the pull request that closed the issue. A closing keyword found only
in a pull request body, with no GitHub link, was ignored; both such matches were false, such as
the words "Do not fix" in front of an issue number. That gave 220 issues and 174 pull requests.
Each pull request was counted by running `scripts/size-labeler-sync-size-label.cjs` on its
`pulls/{number}/files` list; every count matched the label on the pull request. An issue closed by
several pull requests counts their sum.

Most pairs could not be used:

* 146 issues had no size label when their pull request opened. 145 got one later, 138 of them in
  the 2026-07-17 backfill after the pull request had merged. Those labels describe the result, not
  an estimate, and 85% of them match it.
* 12 of the remaining 74 pairs changed scope: the pull request also closed another issue, the
  issue was rewritten while the pull request was open, the pull request did only part of the issue,
  or the work took a path the issue did not describe. 7 more were unclear and stay out of the
  numbers below.

An evaluation issue that ended with keeping the current code stayed in the comparison when the
issue itself named that outcome.

The remaining 55 pairs show:

| Result | Issues |
| --- | ---: |
| Same label | 24 (44%) |
| Estimate one label too small | 17 |
| Estimate two or more labels too small | 6 |
| Estimate one label too large | 8 |

Adding the 7 unclear pairs gives the same 44%. Counting ADR lines, as the labels did before ADR
0145, gives 40%, so the ADR rule does not explain the gap. Estimates of `size M` were the weakest: 5
of 19 matched and 11 were too small. For the 52 estimates below `size XL`, which has no upper
bound, the actual lines were a median 0.88 times the upper bound of the estimated label, the middle
half 0.40 to 1.68 times.

The study found no difference worth a rule between issues whose body says an agent wrote them and
the others: 48% and 39% matched.

How should triage estimate an issue's size so the estimate matches the pull request?

## Decision Drivers

* An issue label and its pull request label measure the same lines, so they can be compared.
* An estimate is made from the issue text, before anyone writes code.
* A rule stays only if this repository's merged pull requests support it.
* The rules stay short enough to apply while writing an issue.

## Considered Options

* Estimate directly, with rules for the kinds of work that miss most.
* Estimate the code lines and multiply by 3.
* Keep the current guidance.
* Leave tests out of issue and pull request size labels.
* Relabel each closed issue to its pull request's size after merge.

## Decision Outcome

Chosen option: estimate directly, with rules for the kinds of work that miss most.
[GitHub issue triage](../agents/github-issue-triage.md#size-labels) lists the rules. Each rests on
few issues, so each names its evidence here:

* Test-only issues that add a fuzz target, a test fake, a harness or a snapshot suite are usually
  `size L` or larger. All six in the study changed 531 to 2,405 lines, and all six labels were
  smaller:
  [GitHub issue #602](https://github.com/martin-francois/symphony-trello/issues/602),
  [GitHub issue #691](https://github.com/martin-francois/symphony-trello/issues/691),
  [GitHub issue #692](https://github.com/martin-francois/symphony-trello/issues/692),
  [GitHub issue #771](https://github.com/martin-francois/symphony-trello/issues/771),
  [GitHub issue #772](https://github.com/martin-francois/symphony-trello/issues/772) and
  [GitHub issue #773](https://github.com/martin-francois/symphony-trello/issues/773). They are one
  series of fuzzing and snapshot work, and the blind estimates below were also too small for four
  of them.
* Flaky-test fixes: of eight, all labeled `size S` but one, the four that stayed inside one test
  changed 3 to 21 lines (`size XS`). The two that changed a shared fixture changed 91 and 196
  lines (`size S`), and the two that rewrote a fixture helper changed 228 and 344 lines
  (`size M`). Using `size S` when the root cause is unknown is a judgment call: one of the four
  small fixes, [GitHub issue #1128](https://github.com/martin-francois/symphony-trello/issues/1128),
  was filed before its cause was known.
* Installer twins: [GitHub issue #51](https://github.com/martin-francois/symphony-trello/issues/51)
  was `size M`, and its pull request changed 558 lines across `install.sh`, `install.ps1`,
  `uninstall.sh` and `uninstall.ps1`, 830 in total. Among the kept pairs this rule rests on that one
  issue and one unclear one,
  [GitHub issue #551](https://github.com/martin-francois/symphony-trello/issues/551).
  Across all merged pull requests, 14 of the 18 that change at least 20 lines in `install.sh` or
  `install.ps1` change both by similar amounts.

### Blind test of the factor

Across 272 merged pull requests by people and agents with at most 100 files, test files held 51% of
the lines outside `docs/adr/` and code 31%: production code under `src/main/` 19%, scripts 10% and
workflows 2%. Code here means the study's categories: `src/main/`; files under `scripts/` and
`oss-fuzz/` outside `oss-fuzz/corpora/`, and any `.sh` or `.ps1` file, unless it is a test or under
`docs/`; and `.github/workflows/`. Of the 148 of these pull requests with at least 20 code lines,
the whole change was a median 3.0 times the code, the middle half 2.1 to 4.6 times. Of the 140 that
also changed tests, tests were a median 1.8 times the code.

That suggested a formula: estimate the code, multiply by 3. It was tested blind. For each of the 55
kept issues, an agent that saw only the issue text as it stood when the pull request opened, with
size labels removed, and that was told not to read the repository, the pull request or its labels,
estimated the code lines, the test lines and the whole change. Compared with the pull request's
label:

| Method | Same label | Two or more labels off | Too small | Too large |
| --- | ---: | ---: | ---: | ---: |
| Original issue label | 24 | 6 | 23 | 8 |
| Blind direct estimate of the whole change | 28 | 1 | 20 | 7 |
| Blind code estimate times 3 | 23 | 9 | 26 | 6 |
| Times 3, direct estimate below 20 code lines | 25 | 5 | 21 | 9 |
| Blind code plus blind test estimate | 32 | 6 | 20 | 3 |

The factor did not help: times 3 matched fewer labels than a direct estimate and missed by two or
more labels far more often. The blind code estimates were too high, the real code a median 0.79
times the estimate on the 37 issues with at least 20 estimated code lines. The blind test estimates
were too low, 1.3 times the code expected against 2.4 times found on the 32 issues with at least 20
code lines in both. The two errors offset in a direct estimate and not in a formula. Adding the
code and test estimates matched most labels, but it was one of several variants tried on the same
55 issues and missed by two or more labels six times, so it is not a rule either. Every method
still came out too small far more often than too large.

### Consequences

* Good, because each rule names the issues behind it, so a later study can check and replace it.
* Good, because the factor that the in-sample data suggested is recorded as tested and rejected.
* Bad, because the rules cover only three kinds of work; most estimates still rely on judgment,
  and they still come out too small about three times as often as too large.
* Bad, because the flaky-test rule rests on eight issues, and the installer rule on one kept issue
  plus 18 merged pull requests.

### Confirmation

No script checks an estimate; `node scripts/audit-issue-labels.ts` checks only that each issue has
one size label. Triage review checks that a new estimate follows the rules. To recheck the
calibration, repeat the study: pair closed issues with merged pull requests through GitHub's links,
count each pull request with `scripts/size-labeler-sync-size-label.cjs`, take the issue's size label
from its `LabeledEvent` and `UnlabeledEvent` timeline as it stood when the first pull request
opened, drop issues without a label then and pairs whose scope changed, and compare the labels.
Update the rules and this ADR when a rule stops matching.

## Pros and Cons of the Options

### Estimate directly, with rules for the kinds of work that miss most

The writer estimates the whole change as the triage page already says, then applies a rule where
the issue is test-only harness work, a flaky-test fix or an installer change.

* Good, because the blind direct estimate matched more labels than the factor and missed by two
  labels only once.
* Good, because it keeps the five buckets and the counting rule unchanged.
* Bad, because it leaves the general bias toward too small in place.

### Estimate the code lines and multiply by 3

The writer estimates only the code lines and labels the issue with three times that number.

* Good, because in merged pull requests the whole change was a median 3.0 times the code.
* Bad, because in the blind test it matched 23 of 55 labels against 28 for a direct estimate, and
  missed by two or more labels 9 times against once.

### Keep the current guidance

The triage page keeps its current size rules and adds nothing.

* Good, because nothing changes.
* Bad, because the current guidance already asks to count tests, and estimates still came out too
  small 23 times against 8 times too large.

### Leave tests out of issue and pull request size labels

Both the issue estimate and the pull request labeler would skip test files, as they skip ADRs.

* Good, because the estimate would cover only the part that is easiest to picture.
* Bad, because tests are half the lines a reviewer reads here, and a size label measures review
  volume.
* Bad, because the pull request labeler would need a test-path rule next to the ADR rule.

### Relabel each closed issue to its pull request's size after merge

After merge, triage replaces the issue's label with the pull request's label.

* Good, because closed issues would show the real size.
* Bad, because it erases the estimate, so nobody can measure or improve estimating; the 2026-07-17
  backfill made 138 of the 220 pairs unusable this way.
* Bad, because it does nothing for the next estimate.

## More Information

[ADR 0069](0069-repository-owned-pull-request-size-labeling.md) owns the pull request labeler and
[ADR 0145](0145-leave-adr-lines-out-of-pull-request-size-labels.md) its counting rule.
