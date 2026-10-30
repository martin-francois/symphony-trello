// The sync-size-label job in .github/workflows/size-labeler.yml.
// actions/github-script loads this file from the base commit and passes its github, context
// and core objects; see docs/agents/github-actions.md.
module.exports = async ({ github, context, core }) => {
  const issueNumber = Number(process.env.ISSUE_NUMBER);
  const { owner, repo } = context.repo;
  if (!Number.isSafeInteger(issueNumber) || issueNumber <= 0) {
    core.warning(`Invalid pull request number: ${process.env.ISSUE_NUMBER}`);
    return;
  }

  const sizes = [
    { maximum: 49, name: "size XS" },
    { maximum: 199, name: "size S" },
    { maximum: 499, name: "size M" },
    { maximum: 999, name: "size L" },
    { maximum: Number.POSITIVE_INFINITY, name: "size XL" },
  ];
  const managedLabels = sizes.map(({ name }) => name);

  let additions;
  let deletions;
  try {
    const response = await github.rest.pulls.get({
      owner,
      repo,
      pull_number: issueNumber,
    });
    additions = response.data.additions;
    deletions = response.data.deletions;
  } catch (error) {
    core.warning(`Unable to read changed lines for #${issueNumber}: ${error}`);
    return;
  }

  if (
    !Number.isSafeInteger(additions)
    || additions < 0
    || !Number.isSafeInteger(deletions)
    || deletions < 0
    || !Number.isSafeInteger(additions + deletions)
  ) {
    core.warning(`Invalid changed-line counts for #${issueNumber}: additions=${additions}, deletions=${deletions}`);
    return;
  }

  const changedLines = additions + deletions;
  const targetLabel = sizes.find(({ maximum }) => changedLines <= maximum)?.name;
  if (!targetLabel) {
    core.warning(`Unable to classify ${changedLines} changed lines for #${issueNumber}`);
    return;
  }

  let existingLabels;
  try {
    existingLabels = await github.paginate(github.rest.issues.listLabelsOnIssue, {
      owner,
      repo,
      issue_number: issueNumber,
    });
  } catch (error) {
    core.warning(`Unable to read labels for #${issueNumber}: ${error}`);
    return;
  }

  const existingNames = new Set(existingLabels.map(({ name }) => name));
  if (!existingNames.has(targetLabel)) {
    try {
      await github.rest.issues.addLabels({
        owner,
        repo,
        issue_number: issueNumber,
        labels: [targetLabel],
      });
    } catch (error) {
      core.warning(`Unable to add ${targetLabel} label for #${issueNumber}: ${error}`);
      return;
    }
  }

  for (const staleLabel of managedLabels.filter(
    (label) => label !== targetLabel && existingNames.has(label),
  )) {
    try {
      await github.rest.issues.removeLabel({
        owner,
        repo,
        issue_number: issueNumber,
        name: staleLabel,
      });
    } catch (error) {
      core.warning(`Unable to remove ${staleLabel} label for #${issueNumber}: ${error}`);
    }
  }
};
