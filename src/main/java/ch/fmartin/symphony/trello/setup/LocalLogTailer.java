package ch.fmartin.symphony.trello.setup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class LocalLogTailer {
    private static final int TAIL_CHUNK_SIZE = 8 * 1024;

    void printRecent(List<Path> logFiles, int maxLines, PrintStream out) throws IOException {
        boolean printed = false;
        for (Path logFile : logFiles) {
            if (!isRegularLogFile(logFile)) {
                continue;
            }
            printed = true;
            for (String line : tailLines(logFile, maxLines)) {
                out.println(line);
            }
        }
        if (!printed) {
            throw new TrelloBoardSetupException("setup_logs_missing", "No managed log files found.");
        }
    }

    void follow(List<Path> logFiles, PrintStream out) throws IOException {
        printRecent(logFiles, 100, out);
        long[] positions = new long[logFiles.size()];
        for (int i = 0; i < logFiles.size(); i++) {
            positions[i] = logSizeOrZero(logFiles.get(i));
        }
        while (!Thread.currentThread().isInterrupted()) {
            for (int i = 0; i < logFiles.size(); i++) {
                Path logFile = logFiles.get(i);
                if (!isRegularLogFile(logFile)) {
                    continue;
                }
                try (FileChannel file = openLogFile(logFile)) {
                    file.position(Math.min(positions[i], file.size()));
                    for (String line : readRemainingLines(file)) {
                        out.println(line);
                    }
                    positions[i] = file.position();
                }
            }
            try {
                Thread.sleep(Duration.ofMillis(500));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private List<String> tailLines(Path logFile, int maxLines) throws IOException {
        if (maxLines <= 0) {
            return List.of();
        }
        try (FileChannel file = openLogFile(logFile)) {
            long position = file.size();
            int newlineCount = 0;
            List<byte[]> chunks = new ArrayList<>();
            while (position > 0 && newlineCount <= maxLines) {
                int bytesToRead = (int) Math.min(TAIL_CHUNK_SIZE, position);
                position -= bytesToRead;
                byte[] chunk = new byte[bytesToRead];
                readFully(file, position, chunk);
                chunks.add(chunk);
                for (int i = bytesToRead - 1; i >= 0; i--) {
                    if (chunk[i] == '\n') {
                        newlineCount++;
                    }
                }
            }
            String tailText = decodeChunks(chunks);
            List<String> lines = new ArrayList<>(tailText.lines().toList());
            int start = Math.max(0, lines.size() - maxLines);
            return List.copyOf(lines.subList(start, lines.size()));
        }
    }

    private static List<String> readRemainingLines(FileChannel file) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(TAIL_CHUNK_SIZE);
        int read = file.read(buffer);
        while (read != -1) {
            buffer.flip();
            bytes.write(buffer.array(), 0, buffer.remaining());
            buffer.clear();
            read = file.read(buffer);
        }
        return bytes.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private static void readFully(FileChannel file, long position, byte[] target) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(target);
        file.position(position);
        while (buffer.hasRemaining() && file.read(buffer) != -1) {
            // Continue until the requested chunk has been read or EOF is reached.
        }
    }

    private static long logSizeOrZero(Path logFile) throws IOException {
        if (!isRegularLogFile(logFile)) {
            return 0L;
        }
        try (FileChannel file = openLogFile(logFile)) {
            return file.size();
        }
    }

    private static FileChannel openLogFile(Path logFile) throws IOException {
        return FileChannel.open(logFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isRegularLogFile(Path logFile) {
        return Files.isRegularFile(logFile, LinkOption.NOFOLLOW_LINKS);
    }

    private static String decodeChunks(List<byte[]> reversedChunks) {
        ByteArrayOutputStream tail = new ByteArrayOutputStream(
                reversedChunks.stream().mapToInt(chunk -> chunk.length).sum());
        for (int i = reversedChunks.size() - 1; i >= 0; i--) {
            tail.writeBytes(reversedChunks.get(i));
        }
        return tail.toString(StandardCharsets.UTF_8);
    }
}
