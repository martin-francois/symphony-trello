// The coderabbit-config-guard job in .github/workflows/coderabbit-config-guard.yml.
// actions/github-script loads this file from the base commit and passes its github, context
// and core objects; see docs/agents/github-actions.md.
module.exports = async ({ github, context, core }) => {
  if (context.payload.pull_request.user.login === "martinfrancois") {
    return;
  }
  const files = await github.paginate(github.rest.pulls.listFiles, {
    ...context.repo,
    pull_number: context.payload.pull_request.number,
    per_page: 100,
  });
  // GitHub truncates this API at 3000 files, even with pagination.
  if (files.length >= 3000) {
    core.setFailed("Cannot verify CodeRabbit configuration at GitHub's 3000-file limit. Split this PR.");
    return;
  }
  if (files.some(file => file.filename === ".coderabbit.yaml"
      || file.previous_filename === ".coderabbit.yaml")) {
    core.setFailed("Only martinfrancois may change .coderabbit.yaml.");
  }
};
