package ch.fmartin.symphony.trello.setup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class LocalLogTailer {
    private static final int TAIL_CHUNK_SIZE = 8 * 1024;

    void printRecent(List<Path> logFiles, int maxLines, PrintStream out) throws IOException {
        boolean printed = false;
        for (Path logFile : logFiles) {
            if (!Files.isRegularFile(logFile)) {
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
            positions[i] = Files.isRegularFile(logFiles.get(i)) ? Files.size(logFiles.get(i)) : 0L;
        }
        while (!Thread.currentThread().isInterrupted()) {
            for (int i = 0; i < logFiles.size(); i++) {
                Path logFile = logFiles.get(i);
                if (!Files.isRegularFile(logFile)) {
                    continue;
                }
                try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "r")) {
                    file.seek(Math.min(positions[i], file.length()));
                    String line;
                    while ((line = file.readLine()) != null) {
                        out.println(new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
                    }
                    positions[i] = file.getFilePointer();
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
        try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "r")) {
            long position = file.length();
            int newlineCount = 0;
            List<byte[]> chunks = new ArrayList<>();
            while (position > 0 && newlineCount <= maxLines) {
                int bytesToRead = (int) Math.min(TAIL_CHUNK_SIZE, position);
                position -= bytesToRead;
                byte[] chunk = new byte[bytesToRead];
                file.seek(position);
                file.readFully(chunk);
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

    private static String decodeChunks(List<byte[]> reversedChunks) {
        ByteArrayOutputStream tail = new ByteArrayOutputStream(
                reversedChunks.stream().mapToInt(chunk -> chunk.length).sum());
        for (int i = reversedChunks.size() - 1; i >= 0; i--) {
            tail.writeBytes(reversedChunks.get(i));
        }
        return tail.toString(StandardCharsets.UTF_8);
    }
}
