---
status: accepted
date: 2026-10-31
decision-makers: [François Martin]
consulted:
  - "[GitHub issue #1142](https://github.com/martin-francois/symphony-trello/issues/1142)"
  - "[Decision on GitHub issue #1142](https://github.com/martin-francois/symphony-trello/issues/1142#issuecomment-6542305319)"
  - "[GitHub PR #1139](https://github.com/martin-francois/symphony-trello/pull/1139)"
  - "[ADR 0101](0101-share-one-trello-credential-resolver.md)"
  - "[ADR 0134](0134-manage-board-cards-through-cli-commands-and-a-codex-skill.md)"
informed: [Future maintainers, Contributors]
---

# Bind Trello credentials to an endpoint

## Context and Problem Statement

`symphony-trello start --workflow PATH` accepts a workflow file that is not in the connected-board
manifest. For such a workflow, `start` read the default `.env` file and sent its Trello key and
token to the workflow's `tracker.endpoint`: first in the credential preflight, then from the worker
it launched. The only check on `tracker.endpoint` was that it is an absolute http(s) URL with a
host. A connected workflow had the same gap after an edit, because the running worker reloads an
edited workflow. So anyone who can write a workflow file and run `start`, for example a shell
agent in a normal terminal, could point `tracker.endpoint` at a host they control and receive the
token of the whole Trello account.

[GitHub PR #1139](https://github.com/martin-francois/symphony-trello/pull/1139) closed the same
hole in the `card` commands by accepting only connected workflows and only
`https://api.trello.com/1`. `start` needed its own rule. Which rule keeps `start --workflow`
useful for the fake Trello servers that the live bug bash and the tests rely on, while a workflow
file alone can no longer redirect credentials?

## Decision Drivers

* A workflow file alone must not be able to send credentials it did not supply to a host of its
  choosing, whether or not the workflow is connected.
* Every setup that uses Trello's own API must keep working unchanged, because that is nearly
  every setup.
* A custom endpoint, such as a local fake Trello server, must stay possible with the default
  `.env` file and with `start --workflow`.
* The refusal must tell the user what to do, in one line they can copy.
* One rule for every command that sends the key and token: `start`, the worker it launches, the
  setup commands, and the `card` commands.

## Considered Options

* Option A: `start --workflow` accepts only a connected workflow, like the `card` commands.
* Option B: `start` runs an unconnected workflow but never reads the default `.env` file for it.
* Option C: for an unconnected workflow, `start` refuses any `tracker.endpoint` other than
  `https://api.trello.com/1`.
* Bind the credentials to an endpoint, the way SSH ties a key to a host.

## Decision Outcome

Chosen option: bind the credentials to an endpoint.

* An env file, the default `.env` or one passed with `--env`, may set `TRELLO_API_ENDPOINT`.
  Without it, the file's key and token belong to `https://api.trello.com/1`. Credentials from the
  shell environment follow the same rule through a `TRELLO_API_ENDPOINT` environment variable.
* The binding comes from the same source as the key and token: the same env file, or the shell
  environment when the credentials come from there. A workflow, a different env file, or a
  command-line option never supplies or overrides the binding for credentials it did not supply.
  A key or token written as a literal or `file:` value in the workflow came from the workflow, so
  the workflow's endpoint is its endpoint.
* Before any command sends the key and token, it compares the endpoint it would send them to with
  the endpoint they belong to, after normalizing scheme and host case, a trailing dot in the host,
  the scheme's default port, and trailing slashes. On a mismatch it refuses with
  `setup_credentials_endpoint_mismatch` (`credentials_endpoint_mismatch` inside the worker) and
  names both endpoints, the source, and the `TRELLO_API_ENDPOINT=...` line to add to that source.
  The check runs in `ConfigResolver.resolve`, which `start`, the `card` commands, and the worker
  at startup and on every reload all use, and in `TrelloCredentialResolver` for the setup commands
  that have no workflow.
* This applies to connected and unconnected workflows alike. `start --workflow` keeps accepting
  unconnected workflows.
* One mechanism everywhere. The `card` commands send the key and token to the bound endpoint
  exactly like `start`, the worker, and the setup commands. Their earlier rule from
  [ADR 0134](0134-manage-board-cards-through-cli-commands-and-a-codex-skill.md) and
  [GitHub PR #1139](https://github.com/martin-francois/symphony-trello/pull/1139), which accepted
  only `https://api.trello.com/1` and failed with `setup_card_endpoint_not_trello`, is gone, and so
  is that code. The owner's reason is the principle of least surprise: the same concern must not
  have different mechanisms in different places. The card commands keep their
  connected-workflows-only rule, because it limits which boards an agent may change, not where the
  credentials go. A command-specific variant of a security rule needs a documented reason;
  [Java style & design preferences](../agents/java-style.md#complexity-reuse-and-centralization)
  records that principle.
* The binding compares endpoints in one canonical spelling, shared with the `--endpoint` option:
  `https://api.trello.com` and `https://api.trello.com/1` are the same endpoint everywhere.
  Encoded path separators remain encoded. Decoding `%2F` would equate different request paths.
  URI equality ignores hexadecimal escape case, so `%2F` and `%2f` remain equivalent.
  Error messages hide malformed endpoints and endpoints with user information, queries, or
  fragments, because those values can contain credentials.
* Setup writes the binding into the env file only when the file holds no credential this command
  did not check. Saving one value into a file whose other credential is bound elsewhere is refused
  with the same code, so a file never rebinds a credential the command never sent anywhere.
  Any non-empty variable can supply a workflow credential through a `$VAR` reference. Setup
  therefore refuses a binding change while any non-empty variable other than the binding itself
  remains unreplaced. Checking only the two default credential names misses custom references;
  scanning connected workflows misses unconnected workflows. The user can choose a separate env
  file or explicitly change the binding after checking every consumer of the existing file.
* When setup saves credentials that were passed with `--key` and `--token` or entered at the
  prompt, it writes `TRELLO_API_ENDPOINT` to the same env file whenever the file would otherwise
  bind them to another endpoint than the one setup used. A setup against a custom endpoint then
  starts its worker without a further step.
* No HTTP client that carries the key and token follows a redirect. The Java `HttpClient` default
  is `Redirect.NEVER`, and tests pin it for the setup API and the worker's Trello client, so a
  permitted endpoint cannot forward the credentials to another host.

### Consequences

* Good, because a workflow file alone cannot redirect credentials, and a workflow edited while its
  worker runs fails the reload instead of leaking the token.
* Good, because every setup that uses Trello's own API keeps working unchanged.
* Good, because the fake Trello servers in the live bug bash and in tests keep working with one
  extra line in the env file or one extra environment variable.
* Bad, because this is a breaking change for a workflow whose `tracker.endpoint` is not
  `https://api.trello.com/1`: its env file or shell environment needs
  `TRELLO_API_ENDPOINT=<that endpoint>`.
* Bad, because a user who runs several custom endpoints from one env file needs one env file per
  endpoint, passed with `--env`.
* Bad, because unrelated non-empty env variables also prevent automatic rebinding. Their names
  alone do not establish whether a workflow uses their values as credentials.
* Bad, because a user who already exports `TRELLO_API_ENDPOINT` for another tool, with a value
  that is not the Trello API, now binds Symphony's credentials to that value. The refusal names it.
* Bad, because `setup_card_endpoint_not_trello` disappears: a script that matched that code now
  sees `setup_credentials_endpoint_mismatch`.

### Confirmation

* `LocalWorkerManagerTest` starts an unconnected workflow with a foreign endpoint against a fake
  server and checks that the server receives no request, that a connected workflow edited to a
  foreign endpoint is refused, that `TRELLO_API_ENDPOINT` in the env file or the shell environment
  lets the start through, and that an env file cannot bind credentials the shell environment
  supplied.
* `ConfigResolverTest` checks the same rule for the worker, including a literal workflow value
  that needs no binding.
* `TrelloCredentialEndpointTest` covers the normalization cases, encoded separators, and messages
  that hide unsafe endpoint values. `TrelloCredentialStoreTest` refuses rebinding when custom
  variables remain and verifies that a refused write leaves the file unchanged.
* `TrelloCredentialResolverTest` and `TrelloCredentialStoreTest` cover the setup commands and the
  `TRELLO_API_ENDPOINT` line that setup writes.
* `TrelloBoardSetupTest` and `TrelloClientTest` check that a redirect is not followed.
* `BoardCardCommandsTest` runs the card commands against a fake Trello server that the env file
  binds the credentials to, and refuses an unbound or mismatched endpoint before any request,
  whatever `tracker.kind` says.

## Pros and Cons of the Options

### Option A: accept only connected workflows

`start --workflow` would refuse a workflow that is not in the connected-board manifest, like the
`card` commands do. Starting an unconnected workflow would need `import-board` or `setup-local`
first.

* Good, because it is the smallest change and matches the `card` commands.
* Bad, because a connected workflow edited to another endpoint still receives the credentials, and
  the running worker reloads such an edit.
* Bad, because the live bug bash and the installer tests start unconnected workflows.

### Option B: never read the default env file for an unconnected workflow

`start` would still run an unconnected workflow, but its credentials would have to come from
`--env` or the shell environment.

* Good, because the default `.env` file is protected without a new setting.
* Bad, because `--env` and shell credentials go to any endpoint, so a workflow file plus a
  convenient `export` leak the same way.
* Bad, because a connected workflow edited to another endpoint is not covered at all.

### Option C: refuse any endpoint other than the Trello API for an unconnected workflow

`start` would accept an unconnected workflow only when its `tracker.endpoint` is
`https://api.trello.com/1`.

* Good, because the default `.env` file goes only to Trello.
* Bad, because a local fake Trello server would need a connected workflow, and the live bug bash
  and the tests connect boards through that very fake server.
* Bad, because a connected workflow edited to another endpoint is not covered.

### Bind the credentials to an endpoint

Each source of credentials, an env file or the shell environment, names the one endpoint its key
and token may be sent to, with the Trello API as the default. Every command compares the endpoint
it would use with that binding before sending anything.

* Good, because the rule covers connected and unconnected workflows, the worker's reload, the
  setup commands, and the `card` commands with one comparison.
* Good, because a custom endpoint stays possible with one line in the env file.
* Bad, because it adds one setting, `TRELLO_API_ENDPOINT`, that users of a custom endpoint must
  know.
* Bad, because it is a breaking change for every setup with a custom endpoint.

## More Information

The compatibility decision is breaking. The migration for a workflow whose `tracker.endpoint` is
not `https://api.trello.com/1` is to add `TRELLO_API_ENDPOINT=<that endpoint>` to the env file
that holds its credentials, or to export the same variable next to the credentials in the shell
environment. The refusal prints that line.
