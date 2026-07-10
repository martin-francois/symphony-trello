package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

abstract class ProcessHandleManagedProcessPlatform implements ManagedProcessPlatform {
    @Override
    public ManagedProcessHandle start(
            List<String> command, Path workingDirectory, Map<String, String> environment, Path stdout, Path stderr)
            throws IOException {
        createParentDirectories(stdout);
        createParentDirectories(stderr);
        ProcessBuilder builder = new ProcessBuilder(launchCommand(command))
                .directory(workingDirectory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.appendTo(stdout.toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(stderr.toFile()));
        standardInputRedirect().ifPresent(input -> builder.redirectInput(ProcessBuilder.Redirect.from(input.toFile())));
        configureWorkerEnvironment(builder.environment(), environment);
        return new ManagedProcessHandle(builder.start().pid());
    }

    static void configureWorkerEnvironment(
            Map<String, String> inheritedEnvironment, Map<String, String> configuredEnvironment) {
        inheritedEnvironment.putAll(withoutInstallerCompletionEnvironment(configuredEnvironment));
        inheritedEnvironment.remove(LocalSetup.INSTALLER_COMPLETION_ENV);
    }

    static Map<String, String> withoutInstallerCompletionEnvironment(Map<String, String> environment) {
        Map<String, String> sanitized = new LinkedHashMap<>(environment);
        sanitized.remove(LocalSetup.INSTALLER_COMPLETION_ENV);
        return Map.copyOf(sanitized);
    }

    @Override
    public boolean isAlive(long pid) {
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent();
    }

    @Override
    public boolean isManaged(long pid, Path appHome) {
        return commandArguments(pid)
                .filter(arguments -> isManagedCommand(arguments, appHome, Optional.empty()))
                .isPresent();
    }

    @Override
    public boolean isManaged(long pid, Path appHome, Path workflowPath) {
        return commandArguments(pid)
                .filter(arguments -> isManagedCommand(arguments, appHome, Optional.of(workflowPath)))
                .isPresent();
    }

    static boolean isManagedCommand(List<String> arguments, Path appHome, Optional<Path> workflowPath) {
        String marker =
                "-Dsymphony.trello.managed.app_home=" + appHome.toAbsolutePath().normalize();
        String jar = appHome.toAbsolutePath()
                .normalize()
                .resolve("target/quarkus-app/quarkus-run.jar")
                .toString();
        boolean matchesInstall = arguments.contains(marker) && arguments.contains(jar);
        return workflowPath
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .map(Path::toString)
                .map(workflow -> matchesInstall && arguments.contains(workflow))
                .orElse(matchesInstall);
    }

    @Override
    public boolean stop(long pid, Duration gracefulTimeout, Duration forcedTimeout) {
        return ProcessHandle.of(pid)
                .map(handle -> stopHandle(handle, gracefulTimeout, forcedTimeout))
                .orElse(true);
    }

    private boolean stopHandle(ProcessHandle handle, Duration gracefulTimeout, Duration forcedTimeout) {
        if (!handle.isAlive()) {
            return true;
        }
        handle.descendants()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .forEach(ProcessHandle::destroy);
        handle.destroy();
        if (waitForExit(handle, gracefulTimeout)) {
            return true;
        }
        handle.descendants()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .forEach(ProcessHandle::destroyForcibly);
        handle.destroyForcibly();
        return waitForExit(handle, forcedTimeout);
    }

    protected List<String> launchCommand(List<String> command) {
        return command;
    }

    protected Optional<Path> standardInputRedirect() {
        return Optional.empty();
    }

    private static void createParentDirectories(Path path) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static Optional<List<String>> commandArguments(long pid) {
        return ProcessHandle.of(pid)
                .flatMap(handle -> handle.info().arguments())
                .map(arguments -> List.copyOf(Arrays.asList(arguments)));
    }

    private static boolean waitForExit(ProcessHandle handle, Duration timeout) {
        try {
            handle.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception ignored) {
            return !handle.isAlive();
        }
    }
}
