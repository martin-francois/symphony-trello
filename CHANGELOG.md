# Changelog

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
