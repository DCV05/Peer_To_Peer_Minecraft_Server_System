package jgit;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Inspects a Minecraft server before Git stages it.
 *
 * <p>GitHub rejects individual Git objects larger than 100 MiB and limits a
 * single push to 2 GiB. P2PMSS stays comfortably below the push limit by
 * building 256 MiB commits, while this preflight rejects files that cannot be
 * represented by regular Git at all. Generated runtime files are ignored both
 * here and by the managed {@code .gitignore} written by {@link GitUtils}.</p>
 */
public final class GitBackupPreflight {
    public static final long MEBIBYTE = 1024L * 1024L;
    public static final long LARGE_FILE_WARNING_BYTES = 50L * MEBIBYTE;
    public static final long MAX_GITHUB_FILE_BYTES = 100L * MEBIBYTE;
    public static final long COMMIT_BATCH_BYTES = 256L * MEBIBYTE;
    public static final long MAX_RECOMMENDED_REPOSITORY_BYTES = 10L * 1024L * MEBIBYTE;
    private static final String BATCH_SIZE_PROPERTY = "p2pmss.gitCommitBatchBytes";

    /** A regular file selected for backup, relative to the server root. */
    public record FileEntry(Path relativePath, long size) {}

    /** Immutable result used by both initial setup and every later backup. */
    public record Result(
            boolean safe,
            long totalBytes,
            long fileCount,
            List<FileEntry> files,
            List<FileEntry> largeFiles,
            List<FileEntry> blockedFiles,
            String message) {}

    private GitBackupPreflight() {}

    public static Result inspect(Path serverRoot) {
        if(serverRoot == null || !Files.isDirectory(serverRoot)) {
            return new Result(false, 0, 0, List.of(), List.of(), List.of(),
                    "The selected server folder is not accessible.");
        }

        List<FileEntry> files = new ArrayList<>();
        try {
            Files.walkFileTree(serverRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if(directory.equals(serverRoot)) return FileVisitResult.CONTINUE;
                    return isRuntimeOnly(serverRoot.relativize(directory))
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    Path relative = serverRoot.relativize(file);
                    if(attributes.isRegularFile() && !isRuntimeOnly(relative)) {
                        files.add(new FileEntry(relative, attributes.size()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    throw new UnreadableBackupFile(serverRoot.relativize(file), failure);
                }
            });
        } catch(UnreadableBackupFile unreadable) {
            return new Result(false, 0, 0, List.of(), List.of(), List.of(),
                    "The backup cannot read " + portable(unreadable.relativePath) + ".");
        } catch(IOException failure) {
            return new Result(false, 0, 0, List.of(), List.of(), List.of(),
                    "The server folder could not be inspected before backup.");
        }

        files.sort(Comparator.comparing(entry -> portable(entry.relativePath())));
        return evaluate(files);
    }

    static Result evaluate(List<FileEntry> selectedFiles) {
        List<FileEntry> files = List.copyOf(selectedFiles);
        long totalBytes = files.stream().mapToLong(FileEntry::size).sum();
        List<FileEntry> largeFiles = files.stream()
                .filter(file -> file.size() > LARGE_FILE_WARNING_BYTES)
                .toList();
        List<FileEntry> blockedFiles = files.stream()
                .filter(file -> file.size() > MAX_GITHUB_FILE_BYTES)
                .toList();

        if(!blockedFiles.isEmpty()) {
            return new Result(false, totalBytes, files.size(), files, largeFiles, blockedFiles,
                    "GitHub blocks files over 100 MiB. Move or reduce " + summarize(blockedFiles)
                            + " before retrying; no remote repository was created.");
        }
        if(totalBytes > MAX_RECOMMENDED_REPOSITORY_BYTES) {
            return new Result(false, totalBytes, files.size(), files, largeFiles, blockedFiles,
                    "This server needs " + humanSize(totalBytes)
                            + ". P2PMSS caps GitHub world repositories at the recommended 10 GiB size; no remote repository was created.");
        }

        String message = "Backup preflight passed: " + files.size() + " files, " + humanSize(totalBytes) + ".";
        if(!largeFiles.isEmpty()) {
            message += " " + largeFiles.size() + " file(s) exceed GitHub's 50 MiB warning threshold but remain uploadable.";
        }
        return new Result(true, totalBytes, files.size(), files, largeFiles, blockedFiles, message);
    }

    /** Splits files conservatively so every commit/push remains well below 2 GiB. */
    public static List<List<FileEntry>> batches(List<FileEntry> files) {
        long configuredBatchSize = Long.getLong(BATCH_SIZE_PROPERTY, COMMIT_BATCH_BYTES);
        long batchSize = configuredBatchSize > 0 ? configuredBatchSize : COMMIT_BATCH_BYTES;
        List<List<FileEntry>> batches = new ArrayList<>();
        List<FileEntry> current = new ArrayList<>();
        long currentBytes = 0;

        for(FileEntry file : files) {
            if(!current.isEmpty() && currentBytes + file.size() > batchSize) {
                batches.add(List.copyOf(current));
                current.clear();
                currentBytes = 0;
            }
            current.add(file);
            currentBytes += file.size();
        }
        if(!current.isEmpty()) batches.add(List.copyOf(current));
        return List.copyOf(batches);
    }

    /** Mirrors the generated entries in P2PMSS's managed .gitignore section. */
    static boolean isRuntimeOnly(Path relativePath) {
        if(relativePath == null || relativePath.getNameCount() == 0) return false;
        String portable = portable(relativePath);
        String first = relativePath.getName(0).toString();
        String fileName = relativePath.getFileName().toString();
        return ".git".equals(first)
                || "logs".equals(first)
                || "crash-reports".equals(first)
                || "world-import-backups".equals(first)
                || first.startsWith(".p2pmss-import-")
                || ".DS_Store".equals(fileName)
                || "session.lock".equals(fileName)
                || portable.endsWith(".tmp");
    }

    public static String humanSize(long bytes) {
        if(bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while(value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String summarize(List<FileEntry> files) {
        return files.stream().limit(3)
                .map(file -> portable(file.relativePath()) + " (" + humanSize(file.size()) + ")")
                .reduce((left, right) -> left + ", " + right)
                .orElse("the oversized file");
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static final class UnreadableBackupFile extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Path relativePath;

        private UnreadableBackupFile(Path relativePath, IOException cause) {
            super(cause);
            this.relativePath = relativePath;
        }
    }
}
