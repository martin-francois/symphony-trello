// The pom step of the generate job in .github/workflows/openrewrite-renovate.yml.
// actions/github-script loads this file from the base commit and passes its github, context
// and core objects; see docs/agents/github-actions.md.
module.exports = async ({ github, context, core }) => {
  const fs = require("fs");
  const { owner, repo } = context.repo;
  const response = await github.rest.repos.getContent({
    owner,
    repo,
    path: "pom.xml",
    ref: process.env.SOURCE_SHA,
  });
  const pom = response.data;
  if (
    Array.isArray(pom)
    || pom.type !== "file"
    || pom.encoding !== "base64"
    || typeof pom.content !== "string"
  ) {
    core.setFailed("The Renovate source does not contain a regular base64-encoded pom.xml");
    return;
  }
  fs.writeFileSync(
    "source/pom.xml",
    Buffer.from(pom.content.replaceAll("\n", ""), "base64"),
  );
};
