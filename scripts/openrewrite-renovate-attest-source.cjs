// The attest-source job in .github/workflows/openrewrite-renovate.yml.
// actions/github-script loads this file from the base commit and passes its github, context
// and core objects; see docs/agents/github-actions.md.
module.exports = async ({ github, context, core }) => {
  if (!["opened", "synchronize"].includes(context.payload.action)) {
    return;
  }
  const { owner, repo } = context.repo;
  const source = context.payload.pull_request;
  const senderIsRenovate = context.payload.sender?.login === "renovate[bot]";
  await github.rest.repos.createCommitStatus({
    owner,
    repo,
    sha: source.head.sha,
    context: "OpenRewrite source provenance",
    state: senderIsRenovate ? "success" : "failure",
    description: senderIsRenovate
      ? "Source SHA delivered by Renovate"
      : "Source SHA was not delivered by Renovate",
    target_url: source.html_url,
  });
};
