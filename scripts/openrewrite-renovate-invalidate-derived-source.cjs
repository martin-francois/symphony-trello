// The invalidate-derived-source job in .github/workflows/openrewrite-renovate.yml.
// actions/github-script loads this file from the base commit and passes its github, context
// and core objects; see docs/agents/github-actions.md.
module.exports = async ({ github, context, core }) => {
  const { owner, repo } = context.repo;
  const source = context.payload.pull_request;
  const sourceIsEligible =
    source.state === "open"
    && source.base.ref === "main"
    && source.labels.some(({ name }) => name === "openrewrite");
  await github.rest.repos.createCommitStatus({
    owner,
    repo,
    sha: source.head.sha,
    context: "OpenRewrite update validation",
    state: sourceIsEligible ? "pending" : "failure",
    description: sourceIsEligible
      ? `Validating Renovate PR #${source.number}`
      : `Renovate PR #${source.number} is no longer eligible`,
    target_url: source.html_url,
  });

  const branch = `${process.env.GENERATED_BRANCH_PREFIX}${source.number}`;
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
    return;
  }

  const generatedCommit = await github.rest.repos.getCommit({
    owner,
    repo,
    ref: generated.head.sha,
  });
  const sourceIsCurrent =
    sourceIsEligible
    && generatedCommit.data.parents.length === 1
    && generatedCommit.data.parents[0]?.sha === source.head.sha;
  await github.rest.repos.createCommitStatus({
    owner,
    repo,
    sha: generated.head.sha,
    context: "OpenRewrite source freshness",
    state: sourceIsCurrent ? "success" : sourceIsEligible ? "pending" : "failure",
    description: sourceIsCurrent
      ? `Generated from current Renovate PR #${source.number}`
      : sourceIsEligible
        ? `Waiting for current Renovate PR #${source.number}`
        : `Renovate PR #${source.number} is no longer eligible`,
    target_url: source.html_url,
  });
};
