# Changelog

## [1.1.1](https://github.com/martin-francois/symphony-trello/compare/v1.1.0...v1.1.1) (2026-07-09)


### Bug Fixes

* allow BetterLeaks container to read mounted scans ([a104ee1](https://github.com/martin-francois/symphony-trello/commit/a104ee11e69779d40de8ab0eacf3f262bbc56a0f))
* support MicroOS installer layouts ([d070445](https://github.com/martin-francois/symphony-trello/commit/d0704458987484895f8f57c8064a7be67656578e)), closes [#530](https://github.com/martin-francois/symphony-trello/issues/530)


### Documentation

* add GitHub issue Trello card skill ([8c1413c](https://github.com/martin-francois/symphony-trello/commit/8c1413c270e2506b94b83190cf8e622dfcd1253b))
* cap Codex review runs at 30 minutes ([699e30a](https://github.com/martin-francois/symphony-trello/commit/699e30a1c8d93e3b3c629ab0d87ccaa7439c7174))
* clarify branch review and response workflows ([ea15a43](https://github.com/martin-francois/symphony-trello/commit/ea15a43374032d7228e929ec20aa6d5dc6dcdf1f))
* clarify issue triage and sweep policy ([6ffa652](https://github.com/martin-francois/symphony-trello/commit/6ffa65275f2c018edb64d771084a308a3849b1a9))
* require rebased pull request branches ([f7f8a42](https://github.com/martin-francois/symphony-trello/commit/f7f8a42ed3e3446472f11a2c9d78b532c707fb43))

## [1.1.0](https://github.com/martin-francois/symphony-trello/compare/v1.0.2...v1.1.0) (2026-07-06)


### Features

* install managed local worker autostart ([ad9cda4](https://github.com/martin-francois/symphony-trello/commit/ad9cda458c52e120eb1a925eabc34d086c52ff2a)), closes [#523](https://github.com/martin-francois/symphony-trello/issues/523)


### Documentation

* add breaking-change declaration workflow and labeling ([f4b6221](https://github.com/martin-francois/symphony-trello/commit/f4b62214208a5dbdcbbfea57e4b7985fd66842a7))
* assign issues when implementation starts ([407a66a](https://github.com/martin-francois/symphony-trello/commit/407a66a26ca239778e5327fd65dff31cc7a9fce3))
* clarify needs human review issue triage ([8f61725](https://github.com/martin-francois/symphony-trello/commit/8f61725b6ec61002d753fcdb352972fefb25e721))
* document GitHub secret scanning baseline ([3ed6978](https://github.com/martin-francois/symphony-trello/commit/3ed6978b8c4ef0e0d0711a8d3d7366b58b1b6473))
* remove manual systemd deployment path ([ccee6b3](https://github.com/martin-francois/symphony-trello/commit/ccee6b3800d21dcebcec8e171bc0369f493ade0a))
* remove stale pre-public release wording ([d33af92](https://github.com/martin-francois/symphony-trello/commit/d33af92217bb5eb9147fc73f0b716f932fad3e9b))

## [1.0.2](https://github.com/martin-francois/symphony-trello/compare/v1.0.1...v1.0.2) (2026-07-06)


### Bug Fixes

* guard empty transactional package list ([fb1faf3](https://github.com/martin-francois/symphony-trello/commit/fb1faf354cf077e823530945c2f166bd6dd597a6))
* support MicroOS transactional installs ([d602ec5](https://github.com/martin-francois/symphony-trello/commit/d602ec5af63116d3c49ac6f44af1efdf58ce97fa))


### Documentation

* clarify Codex review loop verification timing ([fb727db](https://github.com/martin-francois/symphony-trello/commit/fb727db2289a80967895f489a1c4d70b453b0a7c))
* clarify workflow repository defaults ([598a447](https://github.com/martin-francois/symphony-trello/commit/598a4478017e7a5fe80b38ec7780301f89dcc67d))

## [1.0.1](https://github.com/martin-francois/symphony-trello/compare/v1.0.0...v1.0.1) (2026-07-05)


### Bug Fixes

* publish release assets before immutable release ([4d7273c](https://github.com/martin-francois/symphony-trello/commit/4d7273c1adef641745fdd2a101f6c7f68369c51d))
* unblock generated Maven dependency submission ([d6428cd](https://github.com/martin-francois/symphony-trello/commit/d6428cd50b424edbfe9fc4bf4e6c8d0f74b44099))

## 1.0.0

First public release of Symphony for Trello.

### Features

- Connect Symphony to one or more Trello boards.
- Create a recommended Trello board or import an existing board.
- Run Codex from Trello cards in isolated local workspaces.
- Move cards through active, review, blocked, merge, and done lists.
- Keep a Trello workpad comment updated with progress, blockers, validation, and handoff notes.
- Use optional GitHub pull request creation, review sweep, and merge flow.
- Manage local workers with `start`, `stop`, `status`, `logs`, and `setup-local check`.
- Install, update, and uninstall with Bash or PowerShell scripts.
- Configure workflows, Codex command settings, concurrency, workspace paths, Trello write controls,
  and local file access.
- View running and retrying work from the local status page and JSON API.
