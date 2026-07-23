# Changelog

## 1.3.0 (2026-07-23)

## What's Changed
* test(concurrency): audit production guards by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/583
* test(orchestrator): synchronize command request publication by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/595
* test(setup): isolate managed-port selection by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/596
* fix(ci): rotate size-label reconciliation by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/599
* feat(release): credit contributors in changelog by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/598
* feat(build): add guarded OpenRewrite maintenance lane by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/592
* feat(build): automate OpenRewrite updates by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/604
* docs: explain queueing cards for future Codex capacity by @YashRaj0307 in https://github.com/martin-francois/symphony-trello/pull/594
* fix(ci): keep Error Prone updates compatible by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/607
* test(ci): isolate host-dependent fixtures by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/608
* chore(deps): update openrewrite toolchain by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/606
* test(ci): decouple guards from dependency pins by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/610
* chore(deps): update dependency org.openrewrite:rewrite-maven to v8.87.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/611
* fix(ci): handle SpotBugs 4.10 false positives by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/612
* fix(ci): bound parallel test resources by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/615
* docs: add demo video to README by @martinfrancois in https://github.com/martin-francois/symphony-trello/pull/605
* fix(deps): update all dependencies by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/609
* fix(deps): update all dependencies by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/617
* chore(deps): update dependency pnpm to v11.13.1 by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/621
* chore(deps): update all dependencies by @renovate[bot] in https://github.com/martin-francois/symphony-trello/pull/622

## New Contributors
* @YashRaj0307 made their first contribution in https://github.com/martin-francois/symphony-trello/pull/594

**Full Changelog**: https://github.com/martin-francois/symphony-trello/compare/v1.2.0...v1.3.0

## [1.2.0](https://github.com/martin-francois/symphony-trello/compare/v1.1.1...v1.2.0) (2026-07-14)


### Features

* **setup:** configure workflow repository URLs ([183cd4e](https://github.com/martin-francois/symphony-trello/commit/183cd4eebfae90473280810dff090a110340d94c))
* **setup:** prefer Terra for new workflows ([853362e](https://github.com/martin-francois/symphony-trello/commit/853362ea4f85f6171a1f15c20860095ea37fa434))
* **setup:** use model-specific reasoning efforts ([78a92d9](https://github.com/martin-francois/symphony-trello/commit/78a92d93f289cc5813d4d3fe6bde3a6f96474cfb))


### Bug Fixes

* **ci:** avoid deprecated artifact downloader ([db9c8aa](https://github.com/martin-francois/symphony-trello/commit/db9c8aa6bb14672354d021cfeb547a46ca708111))
* **ci:** pin Scorecard SARIF upload action ([35008f0](https://github.com/martin-francois/symphony-trello/commit/35008f0ed4d246a5e8427e4c41b9d9191c441cc1))
* **ci:** preserve Scorecard result publishing ([866b484](https://github.com/martin-francois/symphony-trello/commit/866b484c784a60cfeed8ca7a61eb110af2f0d08c))
* **ci:** provide valid Scorecard SARIF locations ([74e33fc](https://github.com/martin-francois/symphony-trello/commit/74e33fce727f00603f8ddf6c99ca612abb2eba1f))
* **ci:** support explicit container runtimes ([21a9fc6](https://github.com/martin-francois/symphony-trello/commit/21a9fc6753201b404d698e1310d7d9db4882256b)), closes [#574](https://github.com/martin-francois/symphony-trello/issues/574)
* **deps:** resolve reported security advisories ([b4c04d9](https://github.com/martin-francois/symphony-trello/commit/b4c04d9b815f1a9db119fa5ca6304b82a05323ab))
* **handoff:** mark resumed work after stale blocker ([2ea4ac0](https://github.com/martin-francois/symphony-trello/commit/2ea4ac01b0ced5f9ac84411964185d65017b57cd))
* **installer:** print final handoff after autostart ([03670aa](https://github.com/martin-francois/symphony-trello/commit/03670aab6b41571cd6e82a7e6f6727ee34ed7aaa))
* **orchestrator:** pause dispatch on Codex usage limits ([c1b2798](https://github.com/martin-francois/symphony-trello/commit/c1b279800132b73add220802ca43a45c837499ab))
* preserve model catalog control validation ([01f8bcf](https://github.com/martin-francois/symphony-trello/commit/01f8bcf698c0d053d8dde6f61e92ceff8157dbee))
* **release:** let Release Please refresh metadata ([a626a57](https://github.com/martin-francois/symphony-trello/commit/a626a57eac548d9423eb734ded548a3bc239056f))
* **security:** honor Git worktree boundaries ([b2ef2b2](https://github.com/martin-francois/symphony-trello/commit/b2ef2b2feebf27165d40a6e4466c3b98a3b976c7)), closes [#574](https://github.com/martin-francois/symphony-trello/issues/574)
* **setup:** omit unadvertised reasoning defaults ([779a5bb](https://github.com/martin-francois/symphony-trello/commit/779a5bb972610228c6267adf2eceaf70cc6b84c1))
* **setup:** preserve repository URL settings ([0ec02bf](https://github.com/martin-francois/symphony-trello/commit/0ec02bfd14a9e8538bc08779225c7f862c67d0a2))
* **status:** contain per-workflow evidence failures ([226af8c](https://github.com/martin-francois/symphony-trello/commit/226af8c90f5a748b4866ec8bd2af71de51cc1fda))
* **status:** isolate workflow status evidence ([29767d8](https://github.com/martin-francois/symphony-trello/commit/29767d85b61f7f98ed907c21fbd0558317f101e7))
* **workflow:** require review reply snippets ([06cd2d2](https://github.com/martin-francois/symphony-trello/commit/06cd2d25f1205fb026f785c8b744d69f1e8538e0))
* **workflow:** use unambiguous card repository context ([5e2eb4b](https://github.com/martin-francois/symphony-trello/commit/5e2eb4bf007d366eb3a72741b62ed1cc59e0a74b))


### Documentation

* add OpenSSF Best Practices badge ([25dcd2e](https://github.com/martin-francois/symphony-trello/commit/25dcd2e400f9ca465995397a630ba2f04d43dc76))
* **agents:** clarify verification friction ([da033ca](https://github.com/martin-francois/symphony-trello/commit/da033ca8201a0abfec59ac475ea3ee2a40d5f27b))
* **agents:** prefer native issue dependencies ([e9b784a](https://github.com/martin-francois/symphony-trello/commit/e9b784abb1602f73b7aee5b964528ebd1e1fafd6))
* prefer containerized bug reproduction ([eed4e13](https://github.com/martin-francois/symphony-trello/commit/eed4e13a629a20592066af27320c9346b6517752))
* require live reproduction before bug fixes ([76401a9](https://github.com/martin-francois/symphony-trello/commit/76401a9da28f3f8f17832c2eae9dc832f7752d0a))
* require visible issue development links ([17cf365](https://github.com/martin-francois/symphony-trello/commit/17cf36578e434a82406aaad6a2b878cca644f731))
* **setup:** explain generated workflow locations ([8ce3a8a](https://github.com/martin-francois/symphony-trello/commit/8ce3a8a314967c6ceba67e9a3151d315e8b21eec))
* **workflow:** batch pull request review comments ([b28a11e](https://github.com/martin-francois/symphony-trello/commit/b28a11ee70f0e06463ed7cdf568aecb80bb170a5))
* **workflow:** require ADR before dependency review reply ([1c763c5](https://github.com/martin-francois/symphony-trello/commit/1c763c5c9e1f6765589707180853d5152176911d))
* **workflow:** require dependency and concurrency evidence ([1b9347a](https://github.com/martin-francois/symphony-trello/commit/1b9347a94067c071f504fc9fd5686cf401b029aa))

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
