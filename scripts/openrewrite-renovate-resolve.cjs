// The resolve job in .github/workflows/openrewrite-renovate.yml.
// actions/github-script loads this file from the base commit and passes its github, context
// and core objects; see docs/agents/github-actions.md.
module.exports = async ({ github, context, core }) => {
  const { owner, repo } = context.repo;
  const repository = `${owner}/${repo}`;
  const generatedPrefix = process.env.GENERATED_BRANCH_PREFIX;
  const isManagedRenovatePullRequest = (pullRequest) =>
    Number.isSafeInteger(pullRequest.number)
    && pullRequest.number > 0
    && pullRequest.base?.repo?.full_name === repository
    && pullRequest.head?.repo?.full_name === repository
    && pullRequest.head?.ref === "renovate/openrewrite-toolchain"
    && pullRequest.user?.login === "renovate[bot]";
  const generatedSourceNumber = (pullRequest) => {
    if (
      pullRequest.base?.repo?.full_name !== repository
      || pullRequest.base?.ref !== "main"
      || pullRequest.head?.repo?.full_name !== repository
    ) {
      return undefined;
    }
    const generatedSuffix = pullRequest.head.ref.startsWith(generatedPrefix)
      ? pullRequest.head.ref.slice(generatedPrefix.length)
      : "";
    const sourceNumber = /^\d+$/.test(generatedSuffix)
      ? Number(generatedSuffix)
      : Number.NaN;
    return Number.isSafeInteger(sourceNumber) && sourceNumber > 0
      ? sourceNumber
      : undefined;
  };
  const setCandidates = (numbers) =>
    core.setOutput(
      "pull_request_numbers",
      JSON.stringify([...new Set(numbers)].sort((left, right) => left - right)),
    );

  if (context.eventName === "pull_request_target") {
    const pullRequest = context.payload.pull_request;
    setCandidates(
      pullRequest && isManagedRenovatePullRequest(pullRequest)
        ? [pullRequest.number]
        : [],
    );
    return;
  }

  const requested = process.env.REQUESTED_PULL_REQUEST;
  if (requested) {
    const pullRequestNumber = Number(requested);
    if (!Number.isSafeInteger(pullRequestNumber) || pullRequestNumber <= 0) {
      core.setFailed(`Invalid pull request number: ${requested}`);
      return;
    }
    const response = await github.rest.pulls.get({
      owner,
      repo,
      pull_number: pullRequestNumber,
    });
    setCandidates(
      isManagedRenovatePullRequest(response.data) ? [pullRequestNumber] : [],
    );
    return;
  }

  const pullRequests = await github.paginate(github.rest.pulls.list, {
    owner,
    repo,
    per_page: 100,
    state: "open",
  });
  setCandidates([
    ...pullRequests
      .filter((pullRequest) => isManagedRenovatePullRequest(pullRequest))
      .map(({ number }) => number),
    ...pullRequests
      .map(generatedSourceNumber)
      .filter((sourceNumber) => sourceNumber !== undefined),
  ]);
};
