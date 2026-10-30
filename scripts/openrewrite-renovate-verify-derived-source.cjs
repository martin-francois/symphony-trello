// The verify-derived-source job in .github/workflows/openrewrite-renovate.yml.
// actions/github-script loads this file from the base commit and passes its github, context
// and core objects; see docs/agents/github-actions.md.
module.exports = async ({ github, context, core }) => {
  const { owner, repo } = context.repo;
  const generated = context.payload.pull_request;
  const repository = `${owner}/${repo}`;
  const isRenovateSource =
    generated.base.repo?.full_name === repository
    && generated.base.ref === "main"
    && generated.head.repo?.full_name === repository
    && generated.head.ref === "renovate/openrewrite-toolchain"
    && generated.user?.login === "renovate[bot]";
  const generatedPrefix = process.env.GENERATED_BRANCH_PREFIX;
  const generatedSuffix =
    generated.base.repo?.full_name === repository
    && generated.base.ref === "main"
    && generated.head.repo?.full_name === repository
    && generated.head.ref.startsWith(generatedPrefix)
      ? generated.head.ref.slice(generatedPrefix.length)
      : "";
  const generatedMatch = generatedSuffix.match(/^(\d+)$/);
  if (!isRenovateSource && !generatedMatch) {
    const associatedPullRequests = await github.paginate(
      github.rest.repos.listPullRequestsAssociatedWithCommit,
      {
        owner,
        repo,
        commit_sha: generated.head.sha,
        per_page: 100,
      },
    );
    const governedPullRequestSharesCommit = associatedPullRequests.some(
      (pullRequest) =>
        pullRequest.state === "open"
        && pullRequest.base.repo?.full_name === repository
        && pullRequest.base.ref === "main"
        && pullRequest.head.repo?.full_name === repository
        && (
          (
            pullRequest.head.ref === "renovate/openrewrite-toolchain"
            && pullRequest.user?.login === "renovate[bot]"
          )
          || (
            pullRequest.head.ref.startsWith(generatedPrefix)
            && /^\d+$/.test(pullRequest.head.ref.slice(generatedPrefix.length))
          )
        ),
    );
    if (governedPullRequestSharesCommit) {
      return;
    }
    await Promise.all([
      github.rest.repos.createCommitStatus({
        owner,
        repo,
        sha: generated.head.sha,
        context: "OpenRewrite source provenance",
        state: "success",
        description: "Not an OpenRewrite Renovate source",
        target_url: generated.html_url,
      }),
      github.rest.repos.createCommitStatus({
        owner,
        repo,
        sha: generated.head.sha,
        context: "OpenRewrite update validation",
        state: "success",
        description: "Not an OpenRewrite Renovate source",
        target_url: generated.html_url,
      }),
      github.rest.repos.createCommitStatus({
        owner,
        repo,
        sha: generated.head.sha,
        context: "OpenRewrite source freshness",
        state: "success",
        description: "Not a generated OpenRewrite pull request",
        target_url: generated.html_url,
      }),
    ]);
    return;
  }

  if (isRenovateSource) {
    await github.rest.repos.createCommitStatus({
      owner,
      repo,
      sha: generated.head.sha,
      context: "OpenRewrite source freshness",
      state: "success",
      description: "Not a generated OpenRewrite pull request",
      target_url: generated.html_url,
    });
    return;
  }

  await Promise.all([
    github.rest.repos.createCommitStatus({
      owner,
      repo,
      sha: generated.head.sha,
      context: "OpenRewrite source provenance",
      state: "success",
      description: "Not an OpenRewrite Renovate source",
      target_url: generated.html_url,
    }),
    github.rest.repos.createCommitStatus({
      owner,
      repo,
      sha: generated.head.sha,
      context: "OpenRewrite update validation",
      state: "success",
      description: "Not an OpenRewrite Renovate source",
      target_url: generated.html_url,
    }),
  ]);

  const sourceNumber = Number(generatedMatch?.[1]);
  if (!Number.isSafeInteger(sourceNumber) || sourceNumber <= 0) {
    core.setFailed(`Invalid generated branch: ${generated.head.ref}`);
    return;
  }

  const [sourceResponse, generatedCommit] = await Promise.all([
    github.rest.pulls.get({
      owner,
      repo,
      pull_number: sourceNumber,
    }),
    github.rest.repos.getCommit({
      owner,
      repo,
      ref: generated.head.sha,
    }),
  ]);
  const source = sourceResponse.data;
  const sourceIsEligible =
    generated.head.repo?.full_name === repository
    && source.state === "open"
    && source.base.repo.full_name === repository
    && source.base.ref === "main"
    && source.head.repo?.full_name === repository
    && source.head.ref === "renovate/openrewrite-toolchain"
    && source.user?.login === "renovate[bot]"
    && source.labels.some(({ name }) => name === "openrewrite");
  const sourceIsCurrent =
    sourceIsEligible
    && generatedCommit.data.parents.length === 1
    && generatedCommit.data.parents[0]?.sha === source.head.sha;
  await github.rest.repos.createCommitStatus({
    owner,
    repo,
    sha: generated.head.sha,
    context: "OpenRewrite source freshness",
    state: sourceIsCurrent ? "success" : "failure",
    description: sourceIsCurrent
      ? `Generated from current Renovate PR #${sourceNumber}`
      : `Does not match current Renovate PR #${sourceNumber}`,
    target_url: source.html_url,
  });
  if (!sourceIsCurrent) {
    core.setFailed(`Generated pull request does not match current Renovate PR #${sourceNumber}`);
  }
};
