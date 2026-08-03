# Reference Provenance

## Reference revisions

| Repository                           | Branch | Revision                                   | Adopted responsibility                                                                            |
| ------------------------------------ | ------ | ------------------------------------------ | ------------------------------------------------------------------------------------------------- |
| `martinfrancois/fmartin.ch`          | `main` | `28365592d59887b09adb9119c7da24e3e1fbcfaa` | Original progressive-disclosure structure                                                         |
| `martinfrancois/foodOrganizationApp` | `main` | `5deaf529bfa9422c54677f91bf4a3c92451e448f` | Contract-impact checks, dependency governance, diagnostic evidence, and escaped-defect acceptance |

The reference repositories were inspected without modification. Project-specific frontend,
inventory, authentication, PWA, and Kubernetes rules were not copied into Symphony for Trello.

## Adoption map

| Reference practice                                                     | Symphony target                                                   | Result                                                                             |
| ---------------------------------------------------------------------- | ----------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| Minimal root plus topic-owned agent pages                              | `AGENTS.md`, `docs/agents/**`                                     | Retained from the shared `fmartin.ch` lineage                                      |
| Repeated contract-impact analysis                                      | `specification-and-adr-discipline.md`, `default-workflow.md`      | Adapted to the CLI, generated workflows, Trello, deployment, and release artifacts |
| Version-source ownership, immutable pins, and age-gated digest updates | `dependency-updates.md`, `renovate.json`, repository script tests | Adapted to Maven, Node tooling, GitHub Actions, and tool containers                |
| Diagnostics-first investigation and evidence-gap closure               | `private-context-redaction.md`                                    | Adapted while preserving Symphony's stricter private-context redaction             |
| Escaped-defect and release-acceptance analysis                         | `testing.md`                                                      | Adapted to CLI, installer, filesystem, Trello, process, and live boundaries        |

## References

- [Progressive disclosure source](progressive-disclosure-source.md)
- [Maintaining agent docs](maintaining-agent-docs.md)
