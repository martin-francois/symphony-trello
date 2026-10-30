// The sync-size-label job in .github/workflows/size-labeler.yml.
// actions/github-script loads this file from the base commit and passes its github, context
// and core objects; see docs/agents/github-actions.md.

// ADR lines are left out of the count unless the other files change no lines; see
// docs/adr/0145-leave-adr-lines-out-of-pull-request-size-labels.md.
const ADR_DIRECTORY = "docs/adr/";

const isCount = (value) => Number.isSafeInteger(value) && value >= 0;
const isOptionalPath = (path) => path === undefined || typeof path === "string";
const isValidFile = (file) =>
  typeof file?.filename === "string"
  && isOptionalPath(file.previous_filename)
  && isCount(file.additions)
  && isCount(file.deletions);
const linesOf = ({ additions, deletions }) => additions + deletions;
const sumOf = (counts) => counts.reduce((total, count) => total + count, 0);
// A renamed or copied file is an ADR only when both its old and its new path are.
const isAdr = ({ filename, previous_filename: previousFilename }) =>
  [filename, previousFilename ?? filename].every((path) => path.startsWith(ADR_DIRECTORY));
const isOther = (file) => !isAdr(file);
const countListedLines = (files) => ({
  adrLines: sumOf(files.filter(isAdr).map(linesOf)),
  otherLines: sumOf(files.filter(isOther).map(linesOf)),
});

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
  let changedFiles;
  try {
    const response = await github.rest.pulls.get({
      owner,
      repo,
      pull_number: issueNumber,
    });
    additions = response.data.additions;
    deletions = response.data.deletions;
    changedFiles = response.data.changed_files;
  } catch (error) {
    core.warning(`Unable to read changed lines for #${issueNumber}: ${error}`);
    return;
  }

  if (!isCount(additions) || !isCount(deletions) || !isCount(additions + deletions) || !isCount(changedFiles)) {
    core.warning(
      `Invalid changed-line counts for #${issueNumber}: additions=${additions}, deletions=${deletions}, changed_files=${changedFiles}`,
    );
    return;
  }

  // Without a usable file list, every line counts from the pull request totals.
  let files;
  try {
    files = await github.paginate(github.rest.pulls.listFiles, {
      owner,
      repo,
      pull_number: issueNumber,
      per_page: 100,
    });
  } catch (error) {
    core.warning(`Unable to read changed files for #${issueNumber}, so every line counts: ${error}`);
  }
  if (files !== undefined && !files.every(isValidFile)) {
    core.warning(`Invalid changed-file entry for #${issueNumber}, so every line counts`);
    files = undefined;
  }

  const listed = files === undefined ? undefined : countListedLines(files);
  // GitHub lists at most 3000 files.
  const listIsComplete = listed !== undefined && files.length >= changedFiles;
  if (listed !== undefined && !listIsComplete) {
    core.info(`#${issueNumber}: GitHub listed ${files.length} of ${changedFiles} changed files, so every line counts`);
  }
  const totalLines = listIsComplete ? listed.adrLines + listed.otherLines : additions + deletions;
  if (!isCount(totalLines)) {
    core.warning(`Invalid changed-line counts for #${issueNumber}: ${totalLines} listed lines`);
    return;
  }
  // Every line counts when the other files change none, as in an ADR-only change or ADRs beside a
  // binary file or a pure rename.
  const changedLines = listIsComplete && listed.otherLines > 0 ? listed.otherLines : totalLines;

  const targetLabel = sizes.find(({ maximum }) => changedLines <= maximum)?.name;
  if (!targetLabel) {
    core.warning(`Unable to classify ${changedLines} changed lines for #${issueNumber}`);
    return;
  }
  const adrLines = listed?.adrLines ?? "an unknown number";
  core.info(
    `#${issueNumber}: ${targetLabel} from ${changedLines} of ${totalLines} changed lines, ${adrLines} in ${ADR_DIRECTORY}`,
  );

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
