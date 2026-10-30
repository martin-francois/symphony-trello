// The sync-breaking-label job in .github/workflows/compatibility-labeler.yml.
// actions/github-script loads this file from the base commit and passes its github, context
// and core objects; see docs/agents/github-actions.md.
module.exports = async ({ github, context, core }) => {
  const pullRequest = context.payload.pull_request;
  const node = pullRequest ?? context.payload.issue;
  if (!node || !node.number) {
    return;
  }

  const body = node.body || "";
  const decisions = new Map([
    ["- [x] Compatible: no previously supported, working usage stops working", "compatible"],
    ["- [x] Breaking: previously supported, working usage stops working", "breaking"],
    ["- [x] Unsure: maintainer decision required", "unsure"],
  ]);

  function stripHtmlComments(line, commentOpen) {
    let visible = "";
    let cursor = 0;
    let inComment = commentOpen;
    while (cursor < line.length) {
      if (inComment) {
        const end = line.indexOf("-->", cursor);
        if (end < 0) {
          return [visible, true];
        }
        cursor = end + 3;
        inComment = false;
        continue;
      }
      const start = line.indexOf("<!--", cursor);
      if (start < 0) {
        visible += line.slice(cursor);
        break;
      }
      visible += line.slice(cursor, start);
      cursor = start + 4;
      inComment = true;
    }
    return [visible, inComment];
  }

  function openingFence(line) {
    const delimiter = /^ {0,3}(`{3,}|~{3,})/.exec(line)?.[1];
    return delimiter ? { marker: delimiter[0], length: delimiter.length } : null;
  }

  function closesFence(line, fence) {
    const delimiter = /^ {0,3}(`+|~+)[ \t]*$/.exec(line)?.[1];
    return delimiter?.[0] === fence.marker && delimiter.length >= fence.length;
  }

  function visibleMarkdownLines(markdown) {
    const visible = [];
    let commentOpen = false;
    let fence = null;
    for (const rawLine of markdown.replaceAll("\r\n", "\n").replaceAll("\r", "\n").split("\n")) {
      if (fence) {
        if (closesFence(rawLine, fence)) {
          fence = null;
        }
        continue;
      }
      const [withoutComments, nextCommentOpen] = stripHtmlComments(rawLine, commentOpen);
      commentOpen = nextCommentOpen;
      const nextFence = openingFence(withoutComments);
      if (nextFence) {
        fence = nextFence;
        continue;
      }
      visible.push(withoutComments);
    }
    return visible;
  }

  function pullRequestDecision(markdown) {
    const lines = visibleMarkdownLines(markdown);
    const headings = lines.flatMap((line, index) =>
      line.trimEnd() === "## Compatibility Decision" ? [index] : []);
    if (headings.length !== 1) {
      return null;
    }
    const start = headings[0] + 1;
    const relativeEnd = lines.slice(start).findIndex((line) => /^##[ \t]+\S/.test(line));
    const section = relativeEnd < 0 ? lines.slice(start) : lines.slice(start, start + relativeEnd);
    const selected = section
      .filter((line) => /^- \[[xX]\] .+[ \t]*$/.test(line))
      .map((line) => line.trimEnd().replace("[X]", "[x]"));
    return selected.length === 1 ? decisions.get(selected[0]) ?? null : null;
  }

  function issueFormIsBreaking(markdown) {
    const normalized = markdown.replaceAll("\r\n", "\n").replaceAll("\r", "\n");
    const heading = /^### Affects existing users\?[ \t]*$/im.exec(normalized);
    if (!heading) {
      return false;
    }
    const remainder = normalized.slice(heading.index + heading[0].length);
    const nextHeading = /^###[ \t]+/m.exec(remainder);
    const section = nextHeading ? remainder.slice(0, nextHeading.index) : remainder;
    return /^May break existing usage[ \t]*$/m.test(section);
  }

  const isBreaking = pullRequest
    ? pullRequestDecision(body) === "breaking"
    : issueFormIsBreaking(body);

  const label = "breaking change";
  const { owner, repo } = context.repo;

  let hasLabel = false;
  try {
    hasLabel = (await github.paginate(github.rest.issues.listLabelsOnIssue, {
      owner,
      repo,
      issue_number: node.number,
    })).some((item) => item.name === label);
  } catch (error) {
    core.warning(`Unable to read labels for #${node.number}: ${error}`);
    return;
  }

  if (isBreaking && !hasLabel) {
    try {
      await github.rest.issues.addLabels({
        owner,
        repo,
        issue_number: node.number,
        labels: [label],
      });
    } catch (error) {
      core.warning(`Unable to add ${label} label for #${node.number}: ${error}`);
    }
    return;
  }

  if (!isBreaking && hasLabel) {
    try {
      await github.rest.issues.removeLabel({
        owner,
        repo,
        issue_number: node.number,
        name: label,
      });
    } catch (error) {
      core.warning(`Unable to remove ${label} label for #${node.number}: ${error}`);
    }
  }
};
