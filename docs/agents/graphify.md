# Graphify

## Scope

This page covers the optional Graphify knowledge graph support for agents working in this
repository. It does not replace normal source reading, tests, or specification checks.

## Rules

- Use the project-scoped Graphify skill in `.agents/skills/graphify/` when the user asks about
  architecture, file relationships, code paths, or broad repository structure and a graph would make
  the answer more reliable.
- Keep generated graph output local. `graphify-out/` directories and `.graphify_*.json` files are
  ignored and must not be committed.
- Install or refresh the Graphify CLI with:

  ```bash
  uv tool install graphifyy
  ```

- Refresh the committed project skill from the repository root with:

  ```bash
  graphify install --project --platform agents
  ```

- For a local code-only graph that does not need an API key, run:

  ```bash
  graphify extract src --no-cluster
  ```

  This writes `src/graphify-out/graph.json`.
- To query that local graph, run commands such as:

  ```bash
  graphify query "How does setup validation flow?" --graph src/graphify-out/graph.json
  graphify path "TrelloBoardSetup" "WorkflowConfigEditor" --graph src/graphify-out/graph.json
  graphify explain "TrelloBoardSetup" --graph src/graphify-out/graph.json
  ```

- If the user asks for a full repository graph, use the assistant skill flow so the active coding
  agent performs semantic extraction. Prefer the code-only graph when the user only needs source
  structure or call relationships.
- Full semantic extraction can include summaries of documentation or media. Check private-context
  and privacy requirements before running it on private or deployment-specific material.

## References

- [Graphify](https://graphify.net/)
- [Documentation & README](documentation-and-readme.md)
- [Private-context redaction](private-context-redaction.md)
