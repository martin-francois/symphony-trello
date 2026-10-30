// The invalidate-reconciled-source job in .github/workflows/openrewrite-renovate.yml.
// actions/github-script loads this file from the base commit and passes its github, context
// and core objects; see docs/agents/github-actions.md.
module.exports = async ({ github, context, core }) => {
  const sourceNumber = Number(process.env.SOURCE_PULL_REQUEST);
  const { owner, repo } = context.repo;
  const repository = `${owner}/${repo}`;
  const sourceResponse = await github.rest.pulls.get({
    owner,
    repo,
    pull_number: sourceNumber,
  });
  const source = sourceResponse.data;
  if (
    !Number.isSafeInteger(sourceNumber)
    || sourceNumber <= 0
    || source.base.repo.full_name !== repository
    || source.head.repo?.full_name !== repository
    || source.head.ref !== "renovate/openrewrite-toolchain"
    || source.user?.login !== "renovate[bot]"
  ) {
    core.setFailed(`Pull request #${sourceNumber} is not a same-repository Renovate source`);
    return;
  }

  const sourceIsEligible =
    source.state === "open"
    && source.base.ref === "main"
    && source.labels.some(({ name }) => name === "openrewrite");
  let provenanceIsTrusted = false;
  if (sourceIsEligible) {
    const sourceStatuses = await github.paginate(
      github.rest.repos.listCommitStatusesForRef,
      {
        owner,
        repo,
        ref: source.head.sha,
        per_page: 100,
      },
    );
    const provenance = sourceStatuses.find(
      ({ context: statusContext }) =>
        statusContext === "OpenRewrite source provenance",
    );
    provenanceIsTrusted =
      provenance?.state === "success"
      && provenance.creator?.login === "github-actions[bot]"
      && provenance.description === "Source SHA delivered by Renovate"
      && provenance.target_url === source.html_url;
  }

  await github.rest.repos.createCommitStatus({
    owner,
    repo,
    sha: source.head.sha,
    context: "OpenRewrite update validation",
    state: sourceIsEligible && provenanceIsTrusted ? "pending" : "failure",
    description: !sourceIsEligible
      ? `Renovate PR #${sourceNumber} is no longer eligible`
      : provenanceIsTrusted
        ? `Validating Renovate PR #${sourceNumber}`
        : "Renovate source provenance is missing or invalid",
    target_url: source.html_url,
  });

  const branch = `${process.env.GENERATED_BRANCH_PREFIX}${sourceNumber}`;
  const generatedPullRequests = await github.paginate(github.rest.pulls.list, {
    owner,
    repo,
    base: "main",
    head: `${owner}:${branch}`,
    per_page: 100,
    state: "open",
  });
  const generated = generatedPullRequests[0];
  if (!generated) {
    if (sourceIsEligible && !provenanceIsTrusted) {
      core.setFailed(`Pull request #${sourceNumber} lacks trusted Renovate provenance`);
    }
    return;
  }

  const generatedCommit = await github.rest.repos.getCommit({
    owner,
    repo,
    ref: generated.head.sha,
  });
  const sourceIsCurrent =
    sourceIsEligible
    && provenanceIsTrusted
    && generatedCommit.data.parents.length === 1
    && generatedCommit.data.parents[0]?.sha === source.head.sha;
  await github.rest.repos.createCommitStatus({
    owner,
    repo,
    sha: generated.head.sha,
    context: "OpenRewrite source freshness",
    state: sourceIsCurrent
      ? "success"
      : sourceIsEligible && provenanceIsTrusted
        ? "pending"
        : "failure",
    description: sourceIsCurrent
      ? `Generated from current Renovate PR #${sourceNumber}`
      : sourceIsEligible && provenanceIsTrusted
        ? `Waiting for current Renovate PR #${sourceNumber}`
        : sourceIsEligible
          ? `Renovate PR #${sourceNumber} lacks trusted provenance`
          : `Renovate PR #${sourceNumber} is no longer eligible`,
    target_url: source.html_url,
  });
  if (sourceIsEligible && !provenanceIsTrusted) {
    core.setFailed(`Pull request #${sourceNumber} lacks trusted Renovate provenance`);
  }
};
