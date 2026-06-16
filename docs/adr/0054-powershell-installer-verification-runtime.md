---
status: accepted
date: 2026-06-16
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #417](https://github.com/martin-francois/symphony-trello/issues/417)"
  - "[GitHub PR #420](https://github.com/martin-francois/symphony-trello/pull/420)"
informed: [Future maintainers, Contributors]
---

# Verify PowerShell Installer Behavior on Native Windows CI

## Context and Problem Statement

Symphony for Trello has POSIX installers for macOS, Linux, and WSL2, plus a native PowerShell
installer for Windows. The Windows path is best effort. The recommended Windows setup path remains
WSL2 with the Linux installer.

Before this decision, Linux CI also ran PowerShell smoke checks through
`scripts/pwsh-docker.sh`, which starts PowerShell from Microsoft's .NET SDK container image. That
made Linux CI cover some PowerShell script behavior, but it also pulled a large container image and
tested a non-Windows PowerShell runtime. The native Windows job already exists and is the only hosted
runtime that matches the platform where `install.ps1` is meant to run.

How should the repository verify PowerShell installer behavior without making the quick-start work
harder to maintain or making Linux CI depend on a large container image?

## Decision Drivers

* Keep the recommended Windows setup path as WSL2 with the Linux installer.
* Keep native Windows PowerShell support as best effort, not the primary setup path.
* Verify PowerShell behavior on the closest hosted runtime to real users: Windows PowerShell on
  Windows.
* Avoid making Linux CI depend on pulling a large PowerShell container image for every normal run.
* Keep an explicit local Linux fallback for maintainers who need to reproduce PowerShell checks.
* Keep Bash and PowerShell installer behavior aligned where practical.

## Considered Options

* Run PowerShell checks on the native Windows CI job.
* Keep running PowerShell checks through Linux Docker in normal CI.
* Run PowerShell checks wherever native `pwsh` happens to be installed.
* Remove PowerShell checks from CI and rely on manual testing.
* Remove native PowerShell support and document WSL2 only.

## Decision Outcome

Chosen option: run PowerShell installer checks on the native Windows CI job.

The Windows CI job runs:

```powershell
.\install.ps1 --dry-run --no-onboard
.\uninstall.ps1 --dry-run --yes
.\mvnw.cmd -q '-Djunit.parallel.enabled=false' '-Dtest=InstallerScriptTest#*powershell*+*powerShell*' test
.\mvnw.cmd -q '-Djunit.parallel.enabled=false' -Dtest=InstallerScriptLifecycleTest#powershellInstallerLifecycleInstallsStartsStopsAndUninstallsWithFakeJavaOnWindows test
```

The same Windows job also runs PSScriptAnalyzer for `install.ps1` and `uninstall.ps1`.

Linux CI no longer pulls the .NET SDK container image only to run PowerShell. The Linux test job runs
the normal Java verification without setting `SYMPHONY_TRELLO_TEST_PWSH`. On non-Windows hosts,
PowerShell-pattern tests skip unless a maintainer explicitly sets `SYMPHONY_TRELLO_TEST_PWSH`.

The repository keeps `scripts/pwsh-docker.sh` for local Linux verification:

```bash
./scripts/pwsh-docker.sh -NoProfile -File ./install.ps1 --dry-run --no-onboard
./scripts/pwsh-docker.sh -NoProfile -File ./uninstall.ps1 --dry-run --yes
SYMPHONY_TRELLO_TEST_PWSH=./scripts/pwsh-docker.sh ./mvnw -Dtest=InstallerScriptTest test
```

By default, `scripts/pwsh-docker.sh` sets
`SYMPHONY_TRELLO_ALLOW_NON_WINDOWS_PWSH_FOR_TEST=1` so the Windows-only platform guard can be
exercised from Linux. Maintainers can set `SYMPHONY_TRELLO_PWSH_ALLOW_NON_WINDOWS_TEST_RUNTIME=0`
to prove the guard rejects non-Windows PowerShell.

### Consequences

* Good, because hosted PowerShell behavior is validated on the user platform it targets.
* Good, because Linux CI no longer depends on a large Docker image for normal verification.
* Good, because contributors can still run the PowerShell checks from Linux when Docker is
  available.
* Good, because native Windows remains best effort and does not displace the recommended WSL2 setup
  path.
* Bad, because local Linux `./mvnw verify` does not execute PowerShell-pattern tests unless
  `SYMPHONY_TRELLO_TEST_PWSH` is set.
* Bad, because Windows-specific issues are now mainly found by the hosted Windows job or by
  maintainers who opt into the Docker fallback.

### Confirmation

Normal local verification remains:

```bash
./mvnw -q spotless:check verify
```

PowerShell verification on Linux can be run with:

```bash
SYMPHONY_TRELLO_TEST_PWSH=./scripts/pwsh-docker.sh ./mvnw -q '-Djunit.parallel.enabled=false' '-Dtest=InstallerScriptTest#*powershell*+*powerShell*' test
```

That command should report all PowerShell-pattern tests executed with zero skips when Docker is
available.

Hosted CI should show:

* Linux `test` job passes without `SYMPHONY_TRELLO_TEST_PWSH`.
* Windows `windows-powershell` job passes dry runs, PSScriptAnalyzer, PowerShell option tests, and
  the generated-wrapper lifecycle test.

## Pros and Cons of the Options

### Run PowerShell Checks on the Native Windows CI Job

Run script dry runs, PSScriptAnalyzer, focused PowerShell option tests, and generated-wrapper
lifecycle tests on the hosted Windows runner.

* Good, because it tests the platform where `install.ps1` is intended to run.
* Good, because failures are reported under a clear `windows-powershell` job.
* Good, because Linux CI no longer pays the image-pull and container-start cost.
* Bad, because developers on Linux need the explicit Docker fallback for local PowerShell coverage.

### Keep Running PowerShell Checks Through Linux Docker in Normal CI

Keep the old model where Linux CI pulls a Microsoft .NET SDK container and runs `pwsh` inside it.

* Good, because it gives Linux CI one place to run POSIX, Java, and PowerShell checks.
* Good, because it matches the local `scripts/pwsh-docker.sh` fallback exactly.
* Bad, because it tests a non-Windows PowerShell runtime.
* Bad, because every normal Linux CI run depends on a large container image.
* Bad, because Docker/image availability can fail for reasons unrelated to the PowerShell scripts.

### Run PowerShell Checks Wherever Native `pwsh` Happens to Be Installed

Let tests run when `pwsh` is available on any host and skip otherwise.

* Good, because it is simple and requires no test-specific environment variable.
* Bad, because Linux hosts with `pwsh` would run a non-Windows runtime by accident.
* Bad, because the same test command would cover different behavior depending on developer machine
  state.
* Bad, because CI coverage could silently narrow if a runner image changes.

### Remove PowerShell Checks From CI

Keep PowerShell scripts in the repository but rely on manual verification before release.

* Good, because CI would be simpler.
* Bad, because native Windows support could regress without a hosted signal.
* Bad, because best-effort support still needs automated checks for syntax, option parsing, and
  wrapper generation.

### Remove Native PowerShell Support and Document WSL2 Only

Stop shipping `install.ps1` and `uninstall.ps1`; support Windows only through WSL2.

* Good, because it would remove a secondary installer implementation.
* Good, because WSL2 is already the recommended Windows path.
* Bad, because users who expect a native Windows installer would lose the best-effort path.
* Bad, because removing the path is a product-support decision, not only a CI simplification.

## More Information

This decision is about where and how PowerShell installer checks run. It does not change the product
setup recommendation: Windows users should prefer WSL2 with the Linux installer, while native
Windows PowerShell remains best effort.
