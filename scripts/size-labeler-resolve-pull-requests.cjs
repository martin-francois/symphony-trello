// The resolve_pull_requests job in .github/workflows/size-labeler.yml.
// actions/github-script loads this file from the base commit and passes its github, context
// and core objects; see docs/agents/github-actions.md.
module.exports = async ({ github, context, core }) => {
  const { owner, repo } = context.repo;
  const setIssueNumbers = (issueNumbers) => {
    const uniqueIssueNumbers = [...new Set(issueNumbers)];
    if (uniqueIssueNumbers.length > 256) {
      core.setFailed(
        `Cannot reconcile ${uniqueIssueNumbers.length} pull requests in one 256-job matrix`,
      );
      return;
    }
    core.setOutput("issue_numbers", JSON.stringify(uniqueIssueNumbers));
  };

  if (context.eventName === "pull_request_target") {
    const issueNumber = context.payload.pull_request?.number;
    if (!Number.isSafeInteger(issueNumber) || issueNumber <= 0) {
      core.warning(`Invalid pull request number: ${issueNumber}`);
      return;
    }
    setIssueNumbers([issueNumber]);
    return;
  }

  if (context.eventName === "schedule") {
    let openPullRequests;
    try {
      openPullRequests = await github.paginate(github.rest.pulls.list, {
        owner,
        repo,
        per_page: 100,
        state: "open",
      });
    } catch (error) {
      core.warning(`Unable to list open pull requests: ${error}`);
      return;
    }

    const openIssueNumbers = [
      ...new Set(
        openPullRequests
          .filter(
            (candidate) =>
              Number.isSafeInteger(candidate.number)
              && candidate.number > 0
              && candidate.state === "open"
              && candidate.base?.repo?.full_name === `${owner}/${repo}`,
          )
          .map(({ number }) => number),
      ),
    ].sort((left, right) => left - right);
    const maximumScheduledPullRequests = 100;
    const scheduledHour = Math.floor(Date.now() / (60 * 60 * 1000));
    const start =
      openIssueNumbers.length === 0
        ? 0
        : ((scheduledHour % openIssueNumbers.length) * maximumScheduledPullRequests)
          % openIssueNumbers.length;
    const rotatedIssueNumbers = [
      ...openIssueNumbers.slice(start),
      ...openIssueNumbers.slice(0, start),
    ];
    setIssueNumbers(rotatedIssueNumbers.slice(0, maximumScheduledPullRequests));
    return;
  }

  const workflowRun = context.payload.workflow_run;
  if (!workflowRun) {
    return;
  }

  const associatedPullRequests = workflowRun.pull_requests ?? [];
  if (associatedPullRequests.length > 0) {
    const issueNumbers = associatedPullRequests.map(({ number }) => number);
    if (issueNumbers.some((number) => !Number.isSafeInteger(number) || number <= 0)) {
      core.warning("Unable to resolve pull requests: invalid associated pull request number");
      return;
    }
    setIssueNumbers(issueNumbers);
    return;
  }

  const headRepository = workflowRun.head_repository?.full_name;
  const headBranch = workflowRun.head_branch;
  const headSha = workflowRun.head_sha;
  const headRepositoryParts = headRepository?.split("/") ?? [];
  if (
    headRepositoryParts.length !== 2
    || headRepositoryParts.some((part) => !part)
    || !headBranch
    || !headSha
  ) {
    core.warning(
      "Unable to resolve pull request: missing trusted head repository, branch, or SHA",
    );
    return;
  }

  let candidatePullRequests;
  try {
    candidatePullRequests = await github.paginate(
      github.rest.pulls.list,
      {
        owner,
        repo,
        head: `${headRepositoryParts[0]}:${headBranch}`,
        state: "open",
      },
    );
  } catch (error) {
    core.warning(
      `Unable to resolve pull request for ${headRepository}@${headSha}: ${error}`,
    );
    return;
  }

  const matchingPullRequests = candidatePullRequests.filter(
    (candidate) =>
      Number.isSafeInteger(candidate.number)
      && candidate.number > 0
      && candidate.state === "open"
      && candidate.base?.repo?.full_name === `${owner}/${repo}`
      && candidate.head?.repo?.full_name === headRepository
      && candidate.head?.ref === headBranch
      && candidate.head?.sha === headSha,
  );
  if (matchingPullRequests.length === 0) {
    core.warning(
      `Unable to find an open pull request for ${headRepository}@${headSha}`,
    );
    return;
  }

  setIssueNumbers(matchingPullRequests.map(({ number }) => number));
};
