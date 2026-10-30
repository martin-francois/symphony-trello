// The sync-handoff job in .github/workflows/pull-request-author-handoff.yml.
// actions/github-script loads this file from the base commit and passes its github, context
// and core objects; see docs/agents/github-actions.md.
module.exports = async ({ github, context, core }) => {
  const waitingLabel = "waiting-for-author";
  const reviewLabel = "needs-maintainer-review";
  const { owner, repo } = context.repo;

  const validIssueNumber = (candidate) =>
    Number.isSafeInteger(candidate) && candidate > 0;

  const hasLabel = (labels, name) =>
    Array.isArray(labels) && labels.some((label) => label?.name === name);

  async function labelsFor(issueNumber) {
    try {
      return await github.paginate(github.rest.issues.listLabelsOnIssue, {
        owner,
        repo,
        issue_number: issueNumber,
      });
    } catch (error) {
      core.warning(`Unable to read labels for #${issueNumber}: ${error}`);
      return null;
    }
  }

  async function pullRequestFor(issueNumber) {
    try {
      const response = await github.rest.pulls.get({
        owner,
        repo,
        pull_number: issueNumber,
      });
      return response.data;
    } catch (error) {
      core.warning(`Unable to read pull request #${issueNumber}: ${error}`);
      return null;
    }
  }

  async function removeLabel(issueNumber, label) {
    try {
      await github.rest.issues.removeLabel({
        owner,
        repo,
        issue_number: issueNumber,
        name: label,
      });
      return true;
    } catch (error) {
      core.warning(`Unable to remove ${label} from #${issueNumber}: ${error}`);
      return false;
    }
  }

  async function timelineFor(issueNumber) {
    try {
      return await github.paginate(github.rest.issues.listEventsForTimeline, {
        owner,
        repo,
        issue_number: issueNumber,
        per_page: 100,
      });
    } catch (error) {
      core.warning(`Unable to read the timeline for #${issueNumber}: ${error}`);
      return null;
    }
  }

  function currentWaitingCycle(timeline, issueNumber) {
    const index = timeline.findLastIndex(({ event, label }) =>
      event === "labeled" && label?.name === waitingLabel);
    if (index < 0) {
      core.warning(`Unable to find the current waiting-label cycle for #${issueNumber}`);
      return null;
    }
    const event = timeline[index];
    const token = event?.node_id ?? event?.id;
    if (typeof token !== "string" && !Number.isSafeInteger(token)) {
      core.warning(`Unable to identify the current waiting-label cycle for #${issueNumber}`);
      return null;
    }
    return { index, token: String(token) };
  }

  function sameIdentity(candidate, expected) {
    if (!candidate || !expected) {
      return false;
    }
    if (Number.isSafeInteger(expected.id)) {
      return candidate.id === expected.id;
    }
    return (
      (typeof expected.login === "string" && candidate.login === expected.login) ||
      (typeof expected.slug === "string" && candidate.slug === expected.slug)
    );
  }

  function pullRequestSignalFollowsCycle(timeline, cycleIndex) {
    const payload = context.payload;
    const events = timeline.slice(cycleIndex + 1);
    if (payload.action === "synchronize") {
      return typeof payload.after === "string" && events.some((event) =>
        (event.event === "committed" && event.sha === payload.after) ||
        (event.event === "head_ref_force_pushed" &&
         event.commit_id === payload.after &&
         event.actor?.login === payload.pull_request?.user?.login &&
         event.created_at === payload.pull_request?.updated_at));
    }

    const authorLogin = payload.pull_request?.user?.login;
    const updatedAt = payload.pull_request?.updated_at;
    if (payload.action === "ready_for_review") {
      return events.some(({ actor, created_at, event }) =>
        event === "ready_for_review" &&
        actor?.login === authorLogin &&
        created_at === updatedAt);
    }
    if (payload.action === "review_requested") {
      return events.some((event) =>
        event.event === "review_requested" &&
        event.actor?.login === authorLogin &&
        event.created_at === updatedAt &&
        (sameIdentity(event.requested_reviewer, payload.requested_reviewer) ||
         sameIdentity(event.requested_team, payload.requested_team)));
    }
    return false;
  }

  async function transitionToMaintainerReview(
    issueNumber,
    eventLabels,
    signalFollowsCycle,
  ) {
    if (!hasLabel(eventLabels, waitingLabel)) {
      return;
    }
    const labels = await labelsFor(issueNumber);
    if (!labels) {
      return;
    }
    const names = new Set(labels.map(({ name }) => name));
    if (!names.has(waitingLabel)) {
      return;
    }
    const timeline = await timelineFor(issueNumber);
    if (!timeline) {
      return;
    }
    const cycle = currentWaitingCycle(timeline, issueNumber);
    if (!cycle || !signalFollowsCycle(timeline, cycle.index)) {
      return;
    }

    if (!names.has(reviewLabel)) {
      try {
        await github.rest.issues.addLabels({
          owner,
          repo,
          issue_number: issueNumber,
          labels: [reviewLabel],
        });
      } catch (error) {
        core.warning(`Unable to add ${reviewLabel} to #${issueNumber}: ${error}`);
        return;
      }
    }
    await removeLabel(issueNumber, waitingLabel);
  }

  if (context.eventName === "pull_request_target") {
    const pullRequest = context.payload.pull_request;
    const issueNumber = pullRequest?.number;
    if (!validIssueNumber(issueNumber) || pullRequest.state !== "open") {
      return;
    }

    if (context.payload.action === "labeled") {
      if (context.payload.label?.name !== waitingLabel) {
        return;
      }
      const currentPullRequest = await pullRequestFor(issueNumber);
      if (!currentPullRequest || currentPullRequest.state !== "open") {
        return;
      }
      const labels = await labelsFor(issueNumber);
      if (!labels) {
        return;
      }
      const names = new Set(labels.map(({ name }) => name));
      if (!names.has(waitingLabel)) {
        return;
      }

      const timeline = await timelineFor(issueNumber);
      if (!timeline) {
        return;
      }
      const cycle = currentWaitingCycle(timeline, issueNumber);
      if (!cycle) {
        return;
      }
      if (names.has(reviewLabel)) {
        await removeLabel(issueNumber, reviewLabel);
      }
      const reminderMarker =
        `<!-- symphony-trello:waiting-for-author:${cycle.token} -->`;
      let comments;
      try {
        comments = await github.paginate(github.rest.issues.listComments, {
          owner,
          repo,
          issue_number: issueNumber,
          per_page: 100,
        });
      } catch (error) {
        core.warning(`Unable to read comments for #${issueNumber}: ${error}`);
        return;
      }
      if (comments.some(({ body, user }) =>
        user?.login === "github-actions[bot]" && body?.includes(reminderMarker))) {
        return;
      }
      const reminder = [
        reminderMarker,
        "This pull request is now waiting for author action. When all maintainer feedback has been addressed, re-request review or post a top-level `/ready` comment. Pushing a commit or marking the pull request ready for review also returns it for maintainer attention.",
        "",
        "Replies in review threads and other comments do not change the label.",
      ].join("\n");
      try {
        await github.rest.issues.createComment({
          owner,
          repo,
          issue_number: issueNumber,
          body: reminder,
        });
      } catch (error) {
        core.warning(`Unable to post the author handoff reminder on #${issueNumber}: ${error}`);
      }
      return;
    }

    const authorLogin = pullRequest.user?.login;
    if (!authorLogin || context.payload.sender?.login !== authorLogin) {
      return;
    }
    const currentPullRequest = await pullRequestFor(issueNumber);
    if (!currentPullRequest || currentPullRequest.state !== "open") {
      return;
    }
    await transitionToMaintainerReview(
      issueNumber,
      pullRequest.labels,
      pullRequestSignalFollowsCycle,
    );
    return;
  }

  if (context.eventName !== "issue_comment") {
    return;
  }
  if (context.payload.comment?.body !== "/ready") {
    return;
  }
  const issueNumber = context.payload.issue?.number;
  if (!context.payload.issue?.pull_request || !validIssueNumber(issueNumber)) {
    return;
  }

  const pullRequest = await pullRequestFor(issueNumber);
  if (!pullRequest) {
    return;
  }
  const authorLogin = pullRequest.user?.login;
  if (
    pullRequest.state !== "open" ||
    !authorLogin ||
    context.payload.comment.user?.login !== authorLogin
  ) {
    return;
  }
  await transitionToMaintainerReview(
    issueNumber,
    context.payload.issue.labels,
    (timeline, cycleIndex) => timeline
      .slice(cycleIndex + 1)
      .some(({ event, id }) =>
        event === "commented" && id === context.payload.comment.id),
  );
};
