// The disclosure job in .github/workflows/pull-request-disclosure.yml.
// actions/github-script loads this file from the commit the workflow file came from and passes
// its github, context and core objects; see docs/agents/github-actions.md and ADR 0142.
module.exports = async ({ github, context, core }) => {
  const missingLabel = "missing-confirmation";
  const waitingLabel = "waiting-for-author";
  const reviewLabel = "needs-maintainer-review";
  const markerPrefix = "<!-- symphony-trello:missing-confirmation";
  const botLogin = "github-actions[bot]";
  const graceDays = 14;
  const dayMilliseconds = 24 * 60 * 60 * 1000;
  // The daily run's cron time in .github/workflows/pull-request-disclosure.yml; the script test
  // reads the cron and fails when the two differ.
  const sweepHourUtc = 13;
  const sweepMinuteUtc = 17;
  const reopenWindowDays = 90;
  const aiHeading = "## AI Assistance (if used)";
  const checklistHeading = "## Review Checklist";
  const noAssistanceChoice =
    "No AI assistance beyond short completion, search, formatting, or typo fixes";
  const assistedChoice = "AI-assisted PR";
  const confirmation = "I confirm I understand what the code does";
  const { owner, repo } = context.repo;

  function stripHtmlComments(line, commentOpen) {
    let visible = "";
    let cursor = 0;
    let inComment = commentOpen;
    while (cursor < line.length) {
      if (inComment) {
        const end = line.indexOf("-->", cursor);
        if (end < 0) {
          return [visible, true];
        }
        cursor = end + 3;
        inComment = false;
        continue;
      }
      const start = line.indexOf("<!--", cursor);
      if (start < 0) {
        visible += line.slice(cursor);
        break;
      }
      visible += line.slice(cursor, start);
      cursor = start + 4;
      inComment = true;
    }
    return [visible, inComment];
  }

  function openingFence(line) {
    const delimiter = /^ {0,3}(`{3,}|~{3,})/.exec(line)?.[1];
    return delimiter ? { marker: delimiter[0], length: delimiter.length } : null;
  }

  function closesFence(line, fence) {
    const delimiter = /^ {0,3}(`+|~+)[ \t]*$/.exec(line)?.[1];
    return delimiter?.[0] === fence.marker && delimiter.length >= fence.length;
  }

  function visibleMarkdownLines(markdown) {
    const visible = [];
    let commentOpen = false;
    let fence = null;
    for (const rawLine of markdown.replaceAll("\r\n", "\n").replaceAll("\r", "\n").split("\n")) {
      if (fence) {
        if (closesFence(rawLine, fence)) {
          fence = null;
        }
        continue;
      }
      const [withoutComments, nextCommentOpen] = stripHtmlComments(rawLine, commentOpen);
      commentOpen = nextCommentOpen;
      const nextFence = openingFence(withoutComments);
      if (nextFence) {
        fence = nextFence;
        continue;
      }
      visible.push(withoutComments);
    }
    return visible;
  }

  function visibleSection(lines, heading) {
    const headings = lines.flatMap((line, index) => line.trimEnd() === heading ? [index] : []);
    if (headings.length !== 1) {
      return null;
    }
    const start = headings[0] + 1;
    const relativeEnd = lines.slice(start).findIndex((line) => /^##[ \t]+\S/.test(line));
    return relativeEnd < 0 ? lines.slice(start) : lines.slice(start, start + relativeEnd);
  }

  function checkboxState(section, label) {
    const states = section.flatMap((line) => {
      const match = /^- \[([ xX])\] (.+?)[ \t]*$/.exec(line);
      return match?.[2] === label ? [match[1] !== " "] : [];
    });
    return states.length === 1 ? states[0] : undefined;
  }

  // Each problem names its fix for the flag comment, its state for the close comment, and
  // whether the warning talks about the box or the description as a whole. The flag marker
  // records the key of the problem a flag started with.
  const problems = {
    aiSectionMissing: {
      fix: `Please add the \`${aiHeading}\` section from the pull request template back to the description.`,
      closed: "the AI Assistance section has been missing from the description",
      reopens: "Adding it back",
      box: false,
    },
    aiLinesMissing: {
      fix: "Please keep the two AI assistance checkboxes from the pull request template in the description, one line each.",
      closed: "the AI assistance checkboxes have been missing from the description",
      reopens: "Adding them back",
      box: false,
    },
    neitherChoice: {
      fix: `Please tick one AI assistance choice in the description: \`${noAssistanceChoice}\` or \`${assistedChoice}\`.`,
      closed: "neither AI assistance choice has been ticked",
      reopens: "Ticking one",
      box: false,
    },
    bothChoices: {
      fix: "Please tick only one of the two AI assistance choices in the description.",
      closed: "both AI assistance choices have been ticked",
      reopens: "Ticking only one",
      box: false,
    },
    checklistMissing: {
      fix: `Please add the \`${checklistHeading}\` section from the pull request template back to the description.`,
      closed: "the Review Checklist section has been missing from the description",
      reopens: "Adding it back",
      box: false,
    },
    boxLineMissing: {
      fix: `Please keep the \`- [ ] ${confirmation}\` line from the pull request template in the Review Checklist.`,
      closed: `the \`${confirmation}\` line has been missing from the description`,
      reopens: "Adding it back",
      box: false,
    },
    boxUnticked: {
      fix: `Please tick \`${confirmation}\` in the description.`,
      closed: `\`${confirmation}\` has been unticked`,
      reopens: "Ticking it",
      box: true,
    },
  };

  // The rules of scripts/check-pr-compatibility-metadata.ts and the CodeRabbit check; returns the
  // key of the first problem in problems, or null for a complete description.
  function disclosureProblem(body) {
    const lines = visibleMarkdownLines(body ?? "");
    const aiSection = visibleSection(lines, aiHeading);
    if (!aiSection) {
      return "aiSectionMissing";
    }
    const noAssistance = checkboxState(aiSection, noAssistanceChoice);
    const assisted = checkboxState(aiSection, assistedChoice);
    if (noAssistance === undefined || assisted === undefined) {
      return "aiLinesMissing";
    }
    if (!noAssistance && !assisted) {
      return "neitherChoice";
    }
    if (noAssistance && assisted) {
      return "bothChoices";
    }
    const checklist = visibleSection(lines, checklistHeading);
    if (!checklist) {
      return "checklistMissing";
    }
    const understood = checkboxState(checklist, confirmation);
    if (understood === undefined) {
      return "boxLineMissing";
    }
    return understood ? null : "boxUnticked";
  }

  function isHandledAuthor(pullRequest) {
    const login = pullRequest.user?.login ?? "";
    return Number.isSafeInteger(pullRequest.number) && pullRequest.number > 0
      && login !== "" && !login.endsWith("[bot]") && login !== "martinfrancois";
  }

  function flagMarker({ flaggedAt, addedWaiting, restoreReview, problem }) {
    return `${markerPrefix} flagged-at=${flaggedAt} added-waiting=${addedWaiting} restore-review=${restoreReview} problem=${problem} -->`;
  }

  function parseMarker(body) {
    const match =
      new RegExp(`${markerPrefix} flagged-at=(\\S+) added-waiting=(true|false) restore-review=(true|false) problem=(\\w+) -->`)
        .exec(body ?? "");
    if (!match || Number.isNaN(Date.parse(match[1]))) {
      return null;
    }
    return {
      flaggedAt: match[1],
      addedWaiting: match[2] === "true",
      restoreReview: match[3] === "true",
      problem: match[4],
    };
  }

  function closedMarker(flaggedAt) {
    return `${markerPrefix} closed flagged-at=${flaggedAt} -->`;
  }

  // The first daily run at or after the 14 days: the date the flag comment names, and the
  // earliest time the daily run closes, so a late run never closes before that date.
  function closeTime(flaggedAt) {
    const due = Date.parse(flaggedAt) + graceDays * dayMilliseconds;
    const dueDay = new Date(due);
    const run = Date.UTC(dueDay.getUTCFullYear(), dueDay.getUTCMonth(), dueDay.getUTCDate(), sweepHourUtc, sweepMinuteUtc);
    return run < due ? run + dayMilliseconds : run;
  }

  function formatUtcDate(time) {
    return `${new Date(time).toISOString().slice(0, 10)} UTC`;
  }

  async function labelNamesFor(issueNumber) {
    try {
      const labels = await github.paginate(github.rest.issues.listLabelsOnIssue, {
        owner,
        repo,
        issue_number: issueNumber,
      });
      return new Set(labels.map(({ name }) => name));
    } catch (error) {
      core.warning(`Unable to read labels for #${issueNumber}: ${error}`);
      return null;
    }
  }

  // The flag comment carries the flagged-at or resolved marker, each close comment its own, and
  // a failed reopen leaves a marker that ends the automation's part in that pull request.
  async function markerCommentsFor(issueNumber) {
    try {
      const comments = await github.paginate(github.rest.issues.listComments, {
        owner,
        repo,
        issue_number: issueNumber,
        per_page: 100,
      });
      const own = comments.filter(({ body, user }) =>
        user?.login === botLogin && typeof body === "string" && body.includes(markerPrefix));
      const closedMarker = `${markerPrefix} closed`;
      const reopenFailedMarker = `${markerPrefix} reopen-failed`;
      return {
        closed: own.filter(({ body }) => body.includes(closedMarker)),
        flag: own.find(({ body }) => !body.includes(closedMarker) && !body.includes(reopenFailedMarker)) ?? null,
        reopenFailed: own.some(({ body }) => body.includes(reopenFailedMarker)),
      };
    } catch (error) {
      core.warning(`Unable to read comments for #${issueNumber}: ${error}`);
      return null;
    }
  }

  // The newest issue event that matches (the API lists them oldest first), null when there is
  // none, and undefined when the events cannot be read.
  async function latestEvent(issueNumber, matches) {
    try {
      const events = await github.paginate(github.rest.issues.listEvents, {
        owner,
        repo,
        issue_number: issueNumber,
        per_page: 100,
      });
      return events.findLast(matches) ?? null;
    } catch (error) {
      core.warning(`Unable to read events for #${issueNumber}: ${error}`);
      return undefined;
    }
  }

  function isClose({ event }) {
    return event === "closed";
  }

  function isMissingLabelRemoval({ event, label }) {
    return event === "unlabeled" && label?.name === missingLabel;
  }

  async function ensureMissingLabelExists() {
    try {
      await github.rest.issues.getLabel({ owner, repo, name: missingLabel });
      return true;
    } catch (error) {
      if (error?.status !== 404) {
        core.warning(`Unable to read the ${missingLabel} label: ${error}`);
        return false;
      }
    }
    try {
      await github.rest.issues.createLabel({
        owner,
        repo,
        name: missingLabel,
        color: "b60205",
        description: "The pull request description is incomplete; closed after 14 days",
      });
      return true;
    } catch (error) {
      core.warning(`Unable to create the ${missingLabel} label: ${error}`);
      return false;
    }
  }

  async function upsertComment(issueNumber, existing, body) {
    try {
      if (existing) {
        if (existing.body !== body) {
          await github.rest.issues.updateComment({ owner, repo, comment_id: existing.id, body });
        }
        return;
      }
      await github.rest.issues.createComment({ owner, repo, issue_number: issueNumber, body });
    } catch (error) {
      core.warning(`Unable to post the disclosure comment on #${issueNumber}: ${error}`);
    }
  }

  async function flag(pullRequest, problemKey, existing, previous, names, flaggedAtDefault) {
    const issueNumber = pullRequest.number;
    const problem = problems[problemKey];
    const flaggedAt = previous ? previous.flaggedAt : flaggedAtDefault;
    const addedWaiting = previous ? previous.addedWaiting : !names.has(waitingLabel);
    const restoreReview = previous ? previous.restoreReview : addedWaiting && names.has(reviewLabel);
    const closesOn = formatUtcDate(closeTime(flaggedAt));
    const toAdd = [missingLabel, waitingLabel].filter((label) => !names.has(label));
    if (toAdd.includes(missingLabel) && !(await ensureMissingLabelExists())) {
      return;
    }
    if (toAdd.length > 0) {
      try {
        await github.rest.issues.addLabels({ owner, repo, issue_number: issueNumber, labels: toAdd });
      } catch (error) {
        core.warning(`Unable to add ${toAdd.join(", ")} to #${issueNumber}: ${error}`);
        return;
      }
    }
    if (names.has(reviewLabel)) {
      try {
        await github.rest.issues.removeLabel({ owner, repo, issue_number: issueNumber, name: reviewLabel });
      } catch (error) {
        core.warning(`Unable to remove ${reviewLabel} from #${issueNumber}: ${error}`);
      }
    }
    const warning = problem.box
      ? `If it's still unticked on ${closesOn}, it closes automatically. Ticking the box later reopens it.`
      : `If it's still incomplete on ${closesOn}, it closes automatically. Fixing the description later reopens it.`;
    const body = [
      flagMarker({ flaggedAt, addedWaiting, restoreReview, problem: previous ? previous.problem : problemKey }),
      `Thanks for the pull request, @${pullRequest.user.login}! ${problem.fix} Until then it has the \`${waitingLabel}\` label and won't be reviewed.`,
      "",
      warning,
    ].join("\n");
    await upsertComment(issueNumber, existing, body);
  }

  // Removes missing-confirmation and the waiting-for-author the automation added; false when a
  // removal failed.
  async function removeFlagLabels(issueNumber, addedWaiting, names) {
    const toRemove = [
      ...(names.has(missingLabel) ? [missingLabel] : []),
      ...(addedWaiting && names.has(waitingLabel) ? [waitingLabel] : []),
    ];
    for (const label of toRemove) {
      try {
        await github.rest.issues.removeLabel({ owner, repo, issue_number: issueNumber, name: label });
      } catch (error) {
        core.warning(`Unable to remove ${label} from #${issueNumber}: ${error}`);
        return false;
      }
    }
    return true;
  }

  async function resolve(pullRequest, existing) {
    const issueNumber = pullRequest.number;
    if (existing.body.includes(`${markerPrefix} resolved`)) {
      return;
    }
    // An unreadable marker still clears the flag label; the waiting label is left alone.
    const previous = parseMarker(existing.body) ?? { addedWaiting: false, restoreReview: false };
    const names = await labelNamesFor(issueNumber);
    if (!names || !(await removeFlagLabels(issueNumber, previous.addedWaiting, names))) {
      return;
    }
    if (previous.restoreReview && !names.has(reviewLabel)) {
      try {
        await github.rest.issues.addLabels({ owner, repo, issue_number: issueNumber, labels: [reviewLabel] });
      } catch (error) {
        core.warning(`Unable to add ${reviewLabel} to #${issueNumber}: ${error}`);
        return;
      }
    }
    // The handoff ignored author signals while the pull request was flagged (ADR 0075).
    const handoff = !previous.addedWaiting && names.has(waitingLabel)
      ? ` \`${waitingLabel}\` stays for the earlier feedback; comment \`/ready\` once that's addressed.`
      : "";
    await upsertComment(issueNumber, existing, [
      `${markerPrefix} resolved -->`,
      `Thanks, @${pullRequest.user.login}, the description is complete now.${handoff}`,
    ].join("\n"));
  }

  // Claims the problem lasted 14 days only when it is the one the flag started with.
  function closingState(problemKey, previous) {
    const problem = problems[problemKey];
    return problemKey === previous.problem
      ? `${problem.closed} for ${graceDays} days.`
      : `the description has been incomplete for ${graceDays} days. ${problem.fix}`;
  }

  async function close(pullRequest, problemKey, previous, closedComments) {
    const issueNumber = pullRequest.number;
    const marker = closedMarker(previous.flaggedAt);
    try {
      // One closing comment per flag: a retry after a failed close posts none.
      if (!closedComments.some(({ body }) => body.includes(marker))) {
        await github.rest.issues.createComment({
          owner,
          repo,
          issue_number: issueNumber,
          body: [
            marker,
            `Closing: ${closingState(problemKey, previous)} ${problems[problemKey].reopens} reopens this pull request automatically. Don't force-push or rebase this branch until it's reopened.`,
          ].join("\n"),
        });
      }
      await github.rest.pulls.update({ owner, repo, pull_number: issueNumber, state: "closed" });
    } catch (error) {
      core.warning(`Unable to close #${issueNumber}: ${error}`);
    }
  }

  // Only a pull request this automation closed in the current flag cycle, and that nobody closed
  // again since, is reopened; a fork author cannot reopen it, and a person's close stands
  // (ADR 0142).
  async function closedByThisAutomation(issueNumber, previous, closedComments) {
    if (!previous || !closedComments.some(({ body }) => body.includes(closedMarker(previous.flaggedAt)))) {
      return false;
    }
    const lastClose = await latestEvent(issueNumber, isClose);
    return lastClose?.actor?.login === botLogin;
  }

  // GitHub refuses with 422 when the head moved or the fork is gone; the reopen-failed marker then
  // ends the automation's part in the pull request, so it takes its labels off.
  async function reopen(pullRequest, flagComment, previous) {
    const issueNumber = pullRequest.number;
    try {
      await github.rest.pulls.update({ owner, repo, pull_number: issueNumber, state: "open" });
    } catch (error) {
      if (error?.status !== 422) {
        core.warning(`Unable to reopen #${issueNumber}: ${error}`);
        return;
      }
      await upsertComment(issueNumber, null, [
        `${markerPrefix} reopen-failed -->`,
        "Thanks, the description is complete now, but GitHub can't reopen this pull request because its branch changed or was deleted. Please open a new one.",
      ].join("\n"));
      const names = await labelNamesFor(issueNumber);
      if (names) {
        await removeFlagLabels(issueNumber, previous.addedWaiting, names);
      }
      return;
    }
    await resolve(pullRequest, flagComment);
  }

  // A person removed missing-confirmation from a flagged pull request: the maintainer opt-out,
  // which lasts until the description is complete. null when the events cannot be read.
  async function removedByPerson(issueNumber) {
    const removal = await latestEvent(issueNumber, isMissingLabelRemoval);
    return removal === undefined ? null : removal !== null && removal.actor?.login !== botLogin;
  }

  async function currentPullRequest(number) {
    try {
      return (await github.rest.pulls.get({ owner, repo, pull_number: number })).data;
    } catch (error) {
      core.warning(`Unable to read pull request #${number}: ${error}`);
      return null;
    }
  }

  async function handle(pullRequest, flaggedAtDefault, closeWhenDue) {
    if (!isHandledAuthor(pullRequest)) {
      return;
    }
    const problemKey = disclosureProblem(pullRequest.body);
    const comments = await markerCommentsFor(pullRequest.number);
    if (!comments || comments.reopenFailed) {
      return;
    }
    const previous = comments.flag ? parseMarker(comments.flag.body) : null;
    if (pullRequest.state !== "open") {
      if (!problemKey && await closedByThisAutomation(pullRequest.number, previous, comments.closed)) {
        await reopen(pullRequest, comments.flag, previous);
      }
      return;
    }
    if (!problemKey) {
      if (comments.flag) {
        await resolve(pullRequest, comments.flag);
      }
      return;
    }
    const names = await labelNamesFor(pullRequest.number);
    if (!names || (previous && !names.has(missingLabel) && (await removedByPerson(pullRequest.number)) !== false)) {
      return;
    }
    if (closeWhenDue && previous && closeTime(previous.flaggedAt) <= Date.now()) {
      await close(pullRequest, problemKey, previous, comments.closed);
      return;
    }
    await flag(pullRequest, problemKey, comments.flag, previous, names, flaggedAtDefault);
  }

  if (context.eventName === "pull_request_target") {
    const number = context.payload.pull_request?.number;
    if (!Number.isSafeInteger(number) || number <= 0) {
      return;
    }
    const pullRequest = await currentPullRequest(number);
    if (!pullRequest) {
      return;
    }
    await handle(pullRequest, pullRequest.updated_at ?? new Date(Date.now()).toISOString(), false);
    return;
  }

  async function listPullRequests(state, mapPage) {
    try {
      return await github.paginate(github.rest.pulls.list, {
        owner,
        repo,
        state,
        sort: "updated",
        direction: "desc",
        per_page: 100,
      }, mapPage);
    } catch (error) {
      core.warning(`Unable to list ${state} pull requests: ${error}`);
      return null;
    }
  }

  const now = new Date(Date.now()).toISOString();
  const open = await listPullRequests("open");
  for (const listed of open ?? []) {
    if (!listed.labels?.some(({ name }) => name === missingLabel)) {
      continue;
    }
    // Re-read right before acting: the list is a snapshot, and a fix may have landed since.
    const pullRequest = await currentPullRequest(listed.number);
    if (pullRequest) {
      await handle(pullRequest, now, true);
    }
  }

  // Fallback for the edited event on a closed pull request: reopen ones this automation closed
  // in the last 90 days whose description is complete now.
  const cutoff = Date.now() - reopenWindowDays * dayMilliseconds;

  // The list is newest update first, so the first pull request past the cutoff ends the paging.
  function withinReopenWindow(response, done) {
    const end = response.data.findIndex(({ updated_at }) => Date.parse(updated_at ?? "") < cutoff);
    if (end < 0) {
      return response.data;
    }
    done();
    return response.data.slice(0, end);
  }

  for (const listed of (await listPullRequests("closed", withinReopenWindow)) ?? []) {
    if (listed.merged_at || !listed.labels?.some(({ name }) => name === missingLabel)) {
      continue;
    }
    const pullRequest = await currentPullRequest(listed.number);
    if (pullRequest) {
      await handle(pullRequest, now, false);
    }
  }
};
