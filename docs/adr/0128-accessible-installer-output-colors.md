---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #51](https://github.com/martin-francois/symphony-trello/issues/51)"
  - "[NO_COLOR convention](https://no-color.org/)"
  - "[CLICOLOR and CLICOLOR_FORCE convention](https://bixense.com/clicolors/)"
  - "[WCAG 2.2 contrast minimum](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)"
  - "[ADR 0054](0054-powershell-installer-verification-runtime.md)"
informed: [Future maintainers, Contributors]
---

# Color Installer Output With A Fixed 256-Color Palette And Standard Opt-Outs

## Context and Problem Statement

`install.sh`, `uninstall.sh`, `install.ps1`, and `uninstall.ps1` print section headings and status
lines such as `OK`, `NOTE`, `RUN`, `WOULD`, and `WOULD REMOVE` as plain text. In a long first-run
install it is hard to find the next section or spot a `NOTE` among `RUN` lines.
[GitHub issue #51](https://github.com/martin-francois/symphony-trello/issues/51) asks for color with
blue as the main accent.

Color has costs. Escape sequences in redirected logs, CI output, and test transcripts make them
harder to read and to compare. Some people turn color off on purpose. A color that reads well on a
dark terminal can be faint on a light one. The scripts also run without a checkout, through
`curl ... | bash` and `irm ... | iex`, so each script must carry its own helper code.

The questions are which colors to use, how to send them to the terminal, and when to turn them on.

## Decision Drivers

* Plain output must stay byte for byte the same, so logs, CI output, tests, and terminal snapshots
  keep working.
* The words carry the meaning. Color only helps the eye find them.
* Colored text must stay readable on common dark and light terminal backgrounds.
* People who turn color off with a standard setting must get plain output.
* The PowerShell scripts must keep working in Windows PowerShell 5.1, PowerShell 7, and hosts that
  cannot render escape sequences.
* No new tool or library, because the scripts run before anything is installed.

## Considered Options

* A fixed 256-color palette sent as ANSI SGR sequences, behind `NO_COLOR`, `CLICOLOR_FORCE`,
  `CLICOLOR`, a terminal check, and `TERM`
* The terminal theme's 16-color palette
* 24-bit RGB colors
* `tput` in Bash and `Write-Host -ForegroundColor` in PowerShell
* No color

## Decision Outcome

Chosen option: "A fixed 256-color palette sent as ANSI SGR sequences", because it is the only option
whose contrast does not depend on the user's theme, it needs no tool, and Bash and PowerShell can
share the same palette and the same rules.

The palette uses bold text and three colors from the xterm 256-color cube:

| Use | Labels | Index | RGB |
| --- | --- | --- | --- |
| Accent | Headings, `RUN`, `WOULD`, `STOP`, `REMOVE`, plan labels | 32 | `#0087d7` |
| Success | `OK` | 64 | `#5f8700` |
| Attention | `NEEDED`, `NOTE`, `SKIP`, `KILL` | 166 | `#d75f00` |

WCAG 2.2 contrast ratios against common terminal backgrounds:

| Background | Accent | Success | Attention |
| --- | --- | --- | --- |
| Black `#000000` | 5.45 | 4.95 | 5.53 |
| Windows Terminal Campbell `#0c0c0c` | 5.07 | 4.61 | 5.15 |
| VS Code dark `#1e1e1e` | 4.32 | 3.93 | 4.39 |
| One Dark `#282c34` | 3.63 | 3.30 | 3.68 |
| Solarized dark `#002b36` | 3.89 | 3.54 | 3.95 |
| Windows PowerShell console `#012456` | 3.92 | 3.57 | 3.98 |
| White `#ffffff` | 3.86 | 4.24 | 3.80 |
| Solarized light `#fdf6e3` | 3.57 | 3.93 | 3.52 |
| GitHub light `#f6f8fa` | 3.62 | 3.98 | 3.57 |

No single color reaches the WCAG 4.5:1 text minimum on both One Dark and white. The best possible
single color reaches 3.74:1 on both. The accent reaches 3.57:1 or more on every background in the
table, and every color stays above 3:1. This palette therefore does not meet the WCAG 1.4.3 minimum
for normal text on every background. Bold terminal text does not count as WCAG large text either,
because terminal fonts are smaller than 18.7px bold. The project accepts this because color is only
an accent: each colored word is bold, keeps its meaning without color, and sits next to plain text
in the terminal's own foreground color, so WCAG 1.4.1 (no meaning by color alone) still holds.

Color is decided once when a script starts, in this order:

1. `NO_COLOR` set to any non-empty value turns color off.
2. `CLICOLOR_FORCE` set to any non-empty value other than `0` turns color on, even when output is
   redirected.
3. `CLICOLOR=0` turns color off.
4. Otherwise color needs a terminal. Bash checks that stdout is a terminal and that `TERM` is set
   and is not `dumb`. PowerShell checks that output is not redirected, that `TERM` is not `dumb`, and
   that the host reports `SupportsVirtualTerminal`. Windows does not usually set `TERM`, so
   PowerShell does not require it.

`NO_COLOR` wins over `CLICOLOR_FORCE` because it is the setting a person uses to say they do not
want color at all. `CLICOLOR_FORCE` is the force switch because it is the documented companion of
`CLICOLOR`. `FORCE_COLOR` was not added: Node.js tools give it different meanings for `0`, `1`,
`2`, and `3`, and one force switch is enough for tests and for people who pipe the output into a
pager.

Prompts and error messages on stderr stay plain. The PTY dialog tests and terminal snapshots match
prompts as text, and an error that a user copies into an issue should not carry escape sequences.

Each script defines the helper once between `# >>> output style >>>` and `# <<< output style <<<`.
Bash scripts call `print_heading` and `print_status`. PowerShell scripts call `Write-Heading` and
`Write-Status`. The status helpers print two spaces, the colored label, and then the rest of the
line exactly as before, so the plain output keeps its columns.

### Consequences

* Good, because headings and status words are easier to find in a terminal.
* Good, because redirected output, CI logs, and `TERM=dumb` terminals get exactly the same bytes as
  before.
* Good, because contrast is known in advance instead of depending on the theme.
* Good, because `NO_COLOR`, `CLICOLOR`, and `CLICOLOR_FORCE` work the same way in all four scripts.
* Bad, because the colors do not follow the user's theme. A theme with an unusual background color
  can still lower the contrast.
* Bad, because the helper block exists twice in Bash and twice in PowerShell. A test keeps each pair
  identical.
* Bad, because 256-color sequences need a terminal that supports them. The Linux virtual console
  maps them to its 16 colors.
* Bad, because terminal transcripts recorded with a color `TERM`, such as the guided-install
  snapshot, now contain the escape sequences.

### Confirmation

* `InstallerScriptTest#posixInstallerColorsRedirectedOutputOnlyWhenForced` checks that redirected
  output is plain, that `CLICOLOR_FORCE=1` adds the palette, that `NO_COLOR` wins, and that colored
  output with the escape sequences removed equals the plain output.
* `InstallerScriptTest#posixUninstallerColorsTerminalOutputOnlyForColorCapableTerminals` runs the
  uninstaller in a pseudo-terminal with a color `TERM`, `TERM=dumb`, no `TERM`, `NO_COLOR`, an empty
  `NO_COLOR`, `CLICOLOR=0`, and `CLICOLOR_FORCE`.
* `InstallerScriptTest#powershellScriptsColorRedirectedOutputOnlyWhenForcedWhenAvailable` checks the
  same rules for both PowerShell scripts on the Windows CI lane.
* `InstallerScriptTest#installerAndUninstallerShareOneOutputStyleBlock` fails when the two copies of
  a helper block differ.
* `InstallerScriptFixture` removes inherited `NO_COLOR`, `CLICOLOR`, `CLICOLOR_FORCE`, and `TERM`
  so installer tests do not depend on the terminal that started Maven.

## Pros and Cons of the Options

### A Fixed 256-Color Palette Sent As ANSI SGR Sequences

The scripts write `ESC[1;38;5;<index>m` before a heading or status label and `ESC[0m` after it. The
palette indexes are fixed, and the standard environment variables plus a terminal check decide
whether the sequences are written at all.

* Good, because the contrast against common backgrounds can be measured and stays at 3.3:1 or more.
* Good, because Terminal.app, iTerm2, GNOME Terminal, Konsole, Windows Terminal, and the Windows 10+
  console all render 256-color sequences.
* Good, because Bash and PowerShell use the same sequences.
* Bad, because the colors ignore the user's theme.

### The Terminal Theme's 16-Color Palette

The scripts write the basic SGR codes such as `34` for blue and `94` for bright blue, and the
terminal theme decides the RGB value.

* Good, because a theme can tune the colors to its background.
* Bad, because many default themes do not. Standard blue is 2.23:1 on black in xterm and 2.38:1 on
  the Windows Terminal Campbell background. Solarized light maps bright blue to `#839496`, which is
  2.93:1 on its own background.
* Bad, because the same code can be readable in one theme and faint in another, so the result
  cannot be checked.

### 24-Bit RGB Colors

The scripts write `ESC[38;2;R;G;Bm` with exact RGB values.

* Good, because any RGB value can be picked.
* Bad, because the best single color is only 3.74:1 on both One Dark and white, close to the chosen
  256-color accent, so exact RGB values buy little contrast.
* Bad, because some terminals still in use, such as older Apple Terminal releases, do not render
  24-bit color and approximate or ignore it.

### `tput` In Bash And `Write-Host -ForegroundColor` In PowerShell

Bash asks terminfo for the sequences with `tput setaf`, and PowerShell passes a `ConsoleColor` to
`Write-Host`.

* Good, because terminfo knows what the terminal supports.
* Bad, because `tput` is not installed everywhere, for example in minimal containers, and needs a
  correct `TERM`.
* Bad, because `ConsoleColor` only offers the 16 theme colors, with the contrast problems above.
* Bad, because PowerShell drops `-ForegroundColor` when output is redirected, so `CLICOLOR_FORCE`
  could not be honored and the colored path could not be tested in CI.

### No Color

The scripts keep printing plain text.

* Good, because nothing changes.
* Bad, because it does not solve the scanning problem in the issue.

## More Information

The plain output is unchanged, so the redirected installer snapshot
`src/test/snapshots/installer/dry-run-no-onboard.approved.txt` from
[GitHub PR #793](https://github.com/martin-francois/symphony-trello/pull/793) stays valid. That pull
request's guided-install snapshot runs in a pseudo-terminal with `TERM=xterm-256color`, so its
baseline gains the escape sequences once both changes are on `main`.

[GitHub issue #551](https://github.com/martin-francois/symphony-trello/issues/551) covers changing
the wording and grouping of installer progress output. This decision only adds color to the
existing lines.
