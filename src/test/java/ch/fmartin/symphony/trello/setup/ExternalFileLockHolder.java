package ch.fmartin.symphony.trello.setup;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Holds a Java file lock in a separate test process until its standard input closes.
public final class ExternalFileLockHolder {
    private ExternalFileLockHolder() {}

    public static void main(String[] arguments) throws Exception {
        Path lockPath = Path.of(arguments[0]);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            System.out.println("locked");
            System.out.flush();
            System.in.read();
        }
    }
}
