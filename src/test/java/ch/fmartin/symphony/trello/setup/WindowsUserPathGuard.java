package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.isWindows;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.run;

import java.util.Map;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/// Installer fixtures exercise the registry-backed user PATH; restore it even when a test fails.
final class WindowsUserPathGuard implements BeforeAllCallback, AfterAllCallback {
    private String originalPath = "";
    private boolean captured;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (isWindows()) {
            var result = run(
                    Map.of(),
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "[Console]::Write([Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes("
                            + "[string][Environment]::GetEnvironmentVariable('Path','User'))))");
            result.assertSuccess();
            originalPath = result.output().strip();
            captured = true;
        }
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (captured) {
            run(
                            Map.of("SYMPHONY_TEST_USER_PATH", originalPath),
                            "powershell.exe",
                            "-NoProfile",
                            "-NonInteractive",
                            "-Command",
                            "[Environment]::SetEnvironmentVariable('Path', [Text.Encoding]::Unicode.GetString("
                                    + "[Convert]::FromBase64String($env:SYMPHONY_TEST_USER_PATH)), 'User')")
                    .assertSuccess();
        }
    }
}
