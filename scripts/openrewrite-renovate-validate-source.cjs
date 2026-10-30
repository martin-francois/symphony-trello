// The source step of the generate job in .github/workflows/openrewrite-renovate.yml.
// actions/github-script loads this file from the base commit and passes its github, context
// and core objects; see docs/agents/github-actions.md.
module.exports = async ({ github, context, core }) => {
  const pullRequestNumber = Number(process.env.SOURCE_PULL_REQUEST);
  const { owner, repo } = context.repo;
  const repository = `${owner}/${repo}`;
  const response = await github.rest.pulls.get({
    owner,
    repo,
    pull_number: pullRequestNumber,
  });
  const pullRequest = response.data;
  if (
    pullRequest.base.repo.full_name !== repository
    || pullRequest.head.repo?.full_name !== repository
    || pullRequest.head.ref !== "renovate/openrewrite-toolchain"
    || pullRequest.user?.login !== "renovate[bot]"
  ) {
    core.setFailed(`Pull request #${pullRequestNumber} is not a same-repository OpenRewrite Renovate update`);
    return;
  }
  const isEligible =
    pullRequest.base.ref === "main"
    && pullRequest.labels.some(({ name }) => name === "openrewrite");
  core.setOutput(
    "state",
    pullRequest.state === "open" && !isEligible ? "ineligible" : pullRequest.state,
  );
  core.setOutput("source_sha", pullRequest.head.sha);
  core.setOutput("source_url", pullRequest.html_url);
  core.setOutput("base_sha", pullRequest.base.sha);
  if (pullRequest.state !== "open" || !isEligible) {
    return;
  }

  const [files, commits] = await Promise.all([
    github.paginate(github.rest.pulls.listFiles, {
      owner,
      repo,
      pull_number: pullRequestNumber,
      per_page: 100,
    }),
    github.paginate(github.rest.pulls.listCommits, {
      owner,
      repo,
      pull_number: pullRequestNumber,
      per_page: 100,
    }),
  ]);
  if (files.length !== 1 || files[0]?.filename !== "pom.xml") {
    core.setFailed(`Pull request #${pullRequestNumber} must change only pom.xml`);
    return;
  }
  if (
    commits.length !== 1
    || commits[0]?.author?.login !== "renovate[bot]"
    || commits[0]?.sha !== pullRequest.head.sha
    || commits[0]?.parents.length !== 1
    || commits[0]?.parents[0]?.sha !== pullRequest.base.sha
  ) {
    core.setFailed(
      `Pull request #${pullRequestNumber} must contain one Renovate-authored commit directly on its current base`,
    );
  }
};
