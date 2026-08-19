package jgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitBackupPreflightTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearBatchOverride() {
        System.clearProperty("p2pmss.gitCommitBatchBytes");
    }

    @Test
    void rejectsAFileGitHubCannotStoreAndIgnoresRuntimeOutput() throws Exception {
        Path world = Files.createDirectories(temporaryDirectory.resolve("world/region"));
        Files.writeString(world.resolve("small.mca"), "region data");
        Path logs = Files.createDirectories(temporaryDirectory.resolve("logs"));
        Files.writeString(logs.resolve("latest.log"), "not part of backup");
        Files.writeString(temporaryDirectory.resolve("world/session.lock"), "runtime lock");

        Path oversized = world.resolve("oversized.mca");
        try(RandomAccessFile sparse = new RandomAccessFile(oversized.toFile(), "rw")) {
            sparse.setLength(GitBackupPreflight.MAX_GITHUB_FILE_BYTES + 1);
        }

        GitBackupPreflight.Result result = GitBackupPreflight.inspect(temporaryDirectory);

        assertFalse(result.safe());
        assertEquals(2, result.fileCount());
        assertEquals(List.of(Path.of("world/region/oversized.mca")),
                result.blockedFiles().stream().map(GitBackupPreflight.FileEntry::relativePath).toList());
        assertTrue(result.message().contains("over 100 MiB"));
    }

    @Test
    void rejectsRepositoriesBeyondGithubRecommendedSizeWithoutOneOversizedFile() {
        List<GitBackupPreflight.FileEntry> files = new ArrayList<>();
        for(int index = 0; index < 120; index++) {
            files.add(new GitBackupPreflight.FileEntry(Path.of("world/region/r." + index + ".mca"),
                    90L * GitBackupPreflight.MEBIBYTE));
        }

        GitBackupPreflight.Result result = GitBackupPreflight.evaluate(files);

        assertFalse(result.safe());
        assertTrue(result.blockedFiles().isEmpty());
        assertTrue(result.message().contains("10 GiB"));
    }

    @Test
    void dividesSelectedFilesIntoBoundedCommitBatches() {
        System.setProperty("p2pmss.gitCommitBatchBytes", "10");
        List<GitBackupPreflight.FileEntry> files = List.of(
                new GitBackupPreflight.FileEntry(Path.of("a"), 6),
                new GitBackupPreflight.FileEntry(Path.of("b"), 4),
                new GitBackupPreflight.FileEntry(Path.of("c"), 7),
                new GitBackupPreflight.FileEntry(Path.of("d"), 3));

        List<List<GitBackupPreflight.FileEntry>> batches = GitBackupPreflight.batches(files);

        assertEquals(2, batches.size());
        assertEquals(List.of(Path.of("a"), Path.of("b")),
                batches.get(0).stream().map(GitBackupPreflight.FileEntry::relativePath).toList());
        assertEquals(List.of(Path.of("c"), Path.of("d")),
                batches.get(1).stream().map(GitBackupPreflight.FileEntry::relativePath).toList());
    }

    @Test
    void managedIgnoreBlockPreservesOwnerRulesAndIsIdempotent() throws Exception {
        Path ignore = temporaryDirectory.resolve(".gitignore");
        Files.writeString(ignore, "custom-plugin-cache/\n");

        assertTrue(GitUtils.ensureBackupIgnoreFile(temporaryDirectory));
        String first = Files.readString(ignore);
        assertTrue(first.contains("custom-plugin-cache/"));
        assertTrue(first.contains("/logs/"));

        assertTrue(GitUtils.ensureBackupIgnoreFile(temporaryDirectory));
        String second = Files.readString(ignore);
        assertEquals(first, second);
        assertEquals(1, second.split("BEGIN P2PMSS", -1).length - 1);
    }
}
