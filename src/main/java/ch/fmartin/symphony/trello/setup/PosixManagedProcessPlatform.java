package ch.fmartin.symphony.trello.setup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class PosixManagedProcessPlatform extends ProcessHandleManagedProcessPlatform {
    private static final Path DEV_NULL = Path.of("/dev/null");
    private static final Path USR_BIN_SETSID = Path.of("/usr/bin/setsid");
    private static final Path USR_BIN_NOHUP = Path.of("/usr/bin/nohup");

    @Override
    protected List<String> launchCommand(List<String> command) {
        List<String> detachedCommand = new ArrayList<>(command.size() + 2);
        if (Files.isExecutable(USR_BIN_SETSID)) {
            detachedCommand.add(USR_BIN_SETSID.toString());
        }
        detachedCommand.add(Files.isExecutable(USR_BIN_NOHUP) ? USR_BIN_NOHUP.toString() : "nohup");
        detachedCommand.addAll(command);
        return List.copyOf(detachedCommand);
    }

    @Override
    protected Optional<Path> standardInputRedirect() {
        return Files.isRegularFile(DEV_NULL) ? Optional.of(DEV_NULL) : Optional.empty();
    }
}
