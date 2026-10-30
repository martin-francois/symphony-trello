// The report-source-validation job in .github/workflows/openrewrite-renovate.yml.
// actions/github-script loads this file from the base commit and passes its github, context
// and core objects; see docs/agents/github-actions.md.
module.exports = async ({ github, context, core }) => {
  const sourceNumber = Number(process.env.SOURCE_PULL_REQUEST);
  const sourceSha = process.env.SOURCE_SHA;
  const validationPassed = process.env.VALIDATION_PASSED === "true";
  const generatedChanges = process.env.GENERATED_CHANGES === "true";
  const { owner, repo } = context.repo;
  const repository = `${owner}/${repo}`;
  const response = await github.rest.pulls.get({
    owner,
    repo,
    pull_number: sourceNumber,
  });
  const source = response.data;
  const sourceIsCurrent =
    source.state === "open"
    && source.base.repo.full_name === repository
    && source.base.ref === "main"
    && source.head.repo?.full_name === repository
    && source.head.ref === "renovate/openrewrite-toolchain"
    && source.head.sha === sourceSha
    && source.user?.login === "renovate[bot]"
    && source.labels.some(({ name }) => name === "openrewrite");
  const successfulNoResult = sourceIsCurrent && validationPassed && !generatedChanges;
  await github.rest.repos.createCommitStatus({
    owner,
    repo,
    sha: sourceSha,
    context: "OpenRewrite update validation",
    state: successfulNoResult ? "success" : "failure",
    description: successfulNoResult
      ? "Validated with no generated changes"
      : sourceIsCurrent && validationPassed && generatedChanges
        ? "Generated changes require the derived pull request"
        : "OpenRewrite update validation failed",
    target_url: source.html_url,
  });
};
