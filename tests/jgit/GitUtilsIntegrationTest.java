package jgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import view.MainFrame;

class GitUtilsIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void configureSession() {
        System.setProperty("p2pmss.dataDirectory", temporaryDirectory.resolve("data").toString());
        assertTrue(TokenStore.saveUserData("hoster", "hoster@example.test", "local-token"));
    }

    @AfterEach
    void clearConfiguration() {
        TokenStore.invalidateSession();
        System.clearProperty("p2pmss.dataDirectory");
        System.clearProperty("p2pmss.gitCommitBatchBytes");
        MainFrame.serverOpenedDirectory = null;
    }

    @Test
    void preservesAllJoinedRepositoriesWithoutDuplicates() {
        GitUtils.saveRepoJoined("owner/first-server");
        GitUtils.saveRepoJoined("owner/second-server");
        GitUtils.saveRepoJoined("owner/first-server");

        List<String> joined = GitUtils.getRepoJoined();
        assertNotNull(joined);
        assertEquals(List.of("owner/first-server", "owner/second-server"), joined);
    }

    @Test
    void synchronizesTwoHostsAndReportsThenRecoversRejectedPush() throws Exception {
        Path remote = temporaryDirectory.resolve("remote.git");
        try(Git ignored = Git.init().setBare(true).setDirectory(remote.toFile()).call()) {}

        Path hostA = Files.createDirectories(temporaryDirectory.resolve("host-a"));
        Files.writeString(hostA.resolve("server.properties"), "server-port=25565\n");
        Files.writeString(hostA.resolve("user_jvm_args.txt"), "-Xmx2G\n");
        Files.writeString(hostA.resolve("world.txt"), "world-v1\n");
        Path world = Files.createDirectories(hostA.resolve("world/region"));
        Files.writeString(hostA.resolve("world/level.dat"), "level-v1\n");
        Files.writeString(world.resolve("r.0.0.mca"), "region-v1\n");

        assertTrue(GitUtils.createRepoInPath(hostA));
        assertTrue(GitUtils.linkLocalRepoToExternal(remote.toUri().toString(), "unused", hostA));
        assertTrue(GitUtils.hasRemoteOrigin(hostA));
        assertTrue(GitUtils.setSkipWorktree(hostA, Path.of("server.properties"), true));
        assertTrue(GitUtils.setSkipWorktree(hostA, Path.of("user_jvm_args.txt"), true));
        assertFalse(GitUtils.setSkipWorktree(hostA, temporaryDirectory.resolve("outside.txt"), true));

        try(Git git = Git.open(hostA.toFile())) {
            DirCacheEntry entry = git.getRepository().readDirCache().getEntry("server.properties");
            assertNotNull(entry);
            assertTrue(entry.isAssumeValid());
            ObjectId head = git.getRepository().resolve("HEAD");
            try(RevWalk walk = new RevWalk(git.getRepository())) {
                RevCommit commit = walk.parseCommit(head);
                assertEquals("hoster", commit.getAuthorIdent().getName());
                assertEquals("hoster@example.test", commit.getCommitterIdent().getEmailAddress());
            }
        }

        Files.writeString(hostA.resolve("server.properties"), "server-port=25570\n");
        Files.writeString(hostA.resolve("world.txt"), "world-v2\n");
        MainFrame.serverOpenedDirectory = hostA.toFile();
        assertTrue(GitUtils.autoCommitAndPush(false));

		Path hostB = temporaryDirectory.resolve("host-b");
		assertTrue(GitUtils.cloneRepoFromUrl(hostB, remote.toUri().toString()));
		assertEquals("server-port=25565\n", Files.readString(hostB.resolve("server.properties")));
		assertEquals("world-v2\n", Files.readString(hostB.resolve("world.txt")));
		assertEquals("level-v1\n", Files.readString(hostB.resolve("world/level.dat")));
		assertEquals("region-v1\n", Files.readString(hostB.resolve("world/region/r.0.0.mca")));
		try(Git git = Git.open(hostB.toFile())) {
			assertTrue(git.getRepository().readDirCache().getEntry("server.properties").isAssumeValid());
			assertTrue(git.getRepository().readDirCache().getEntry("user_jvm_args.txt").isAssumeValid());
		}

        Files.writeString(hostA.resolve("world.txt"), "world-v3\n");
        MainFrame.serverOpenedDirectory = hostA.toFile();
        assertTrue(GitUtils.autoCommitAndPush(false));

        Files.writeString(hostB.resolve("world.txt"), "dirty-local\n");
        assertFalse(GitUtils.pull(hostB));
        Files.writeString(hostB.resolve("world.txt"), "world-v2\n");
        assertTrue(GitUtils.pull(hostB));
        assertEquals("world-v3\n", Files.readString(hostB.resolve("world.txt")));

        Files.writeString(hostA.resolve("world.txt"), "world-v4\n");
        MainFrame.serverOpenedDirectory = hostA.toFile();
        assertTrue(GitUtils.autoCommitAndPush(false));

        assertTrue(TokenStore.saveUserData("guest", "guest@example.test", "guest-local-token"));
        assertTrue(GitUtils.setLocalIdentity(hostB));
        Files.writeString(hostB.resolve("host-b.txt"), "pending local commit\n");
        MainFrame.serverOpenedDirectory = hostB.toFile();
        assertFalse(GitUtils.autoCommitAndPush(false));

        try(Git git = Git.open(hostB.toFile())) {
            assertNotNull(git.getRepository().resolve("HEAD"));
        }

        assertTrue(GitUtils.pull(hostB));
        assertTrue(GitUtils.autoCommitAndPush(false));

        Path verifier = temporaryDirectory.resolve("verifier");
        try(Git git = Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(verifier.toFile()).call()) {
            Set<String> authors = new HashSet<>();
            for(RevCommit commit : git.log().call()) authors.add(commit.getAuthorIdent().getName());
            assertTrue(authors.contains("hoster"));
            assertTrue(authors.contains("guest"));
        }
        assertTrue(Files.exists(verifier.resolve("host-b.txt")));
        assertEquals("world-v4\n", Files.readString(verifier.resolve("world.txt")));
        assertEquals("level-v1\n", Files.readString(verifier.resolve("world/level.dat")));
        assertEquals("region-v1\n", Files.readString(verifier.resolve("world/region/r.0.0.mca")));
    }

    @Test
    void removesOriginSoFailedSetupCanBeRetried() throws Exception {
        Path remote = temporaryDirectory.resolve("retry-remote.git");
        try(Git ignored = Git.init().setBare(true).setDirectory(remote.toFile()).call()) {}
        Path local = Files.createDirectories(temporaryDirectory.resolve("retry-local"));
        Files.writeString(local.resolve("world.txt"), "world\n");

        assertTrue(GitUtils.createRepoInPath(local));
        assertTrue(GitUtils.linkLocalRepoToExternal(remote.toUri().toString(), "unused", local));
        assertTrue(GitUtils.hasRemoteOrigin(local));

        GitUtils.removeRemoteOrigin(local);
        assertFalse(GitUtils.hasRemoteOrigin(local));
        assertTrue(GitUtils.linkLocalRepoToExternal(remote.toUri().toString(), "unused", local));
    }

    @Test
    void uploadsLargeInitialTreesAsMultipleVerifiedCommits() throws Exception {
        System.setProperty("p2pmss.gitCommitBatchBytes", "24");
        Path remote = temporaryDirectory.resolve("batched-remote.git");
        try(Git ignored = Git.init().setBare(true).setDirectory(remote.toFile()).call()) {}

        Path local = Files.createDirectories(temporaryDirectory.resolve("batched-local/world/region"));
        Path serverRoot = temporaryDirectory.resolve("batched-local");
        for(int index = 0; index < 8; index++) {
            Files.writeString(local.resolve("r." + index + ".mca"), "region-payload-" + index + "-abcdefghij\n");
        }
        Files.createDirectories(serverRoot.resolve("logs"));
        Files.writeString(serverRoot.resolve("logs/latest.log"), "runtime-only");

        assertTrue(GitUtils.createRepoInPath(serverRoot));
        assertTrue(GitUtils.linkLocalRepoToExternal(remote.toUri().toString(), "unused", serverRoot));

        Path verifier = temporaryDirectory.resolve("batched-verifier");
        try(Git git = Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(verifier.toFile()).call()) {
			int commits = 0;
			for(RevCommit ignored : git.log().call()) commits++;
			assertTrue(commits > 2, "the initial tree should be split into several remote commits");
        }
        for(int index = 0; index < 8; index++) {
            assertTrue(Files.exists(verifier.resolve("world/region/r." + index + ".mca")));
        }
        assertFalse(Files.exists(verifier.resolve("logs/latest.log")));
    }

	@Test
	void preflightsEveryLaterBackupBeforeMovingRemoteHead() throws Exception {
		Path remote = temporaryDirectory.resolve("preflight-remote.git");
		try(Git ignored = Git.init().setBare(true).setDirectory(remote.toFile()).call()) {}
		Path local = Files.createDirectories(temporaryDirectory.resolve("preflight-local/world/region"));
		Path serverRoot = temporaryDirectory.resolve("preflight-local");
		Files.writeString(local.resolve("r.0.0.mca"), "small-region\n");

		assertTrue(GitUtils.createRepoInPath(serverRoot));
		assertTrue(GitUtils.linkLocalRepoToExternal(remote.toUri().toString(), "unused", serverRoot));
		ObjectId confirmedHead;
		try(Git remoteGit = Git.open(remote.toFile())) {
			confirmedHead = remoteGit.getRepository().resolve("refs/heads/master");
		}

		Path oversized = local.resolve("r.1.0.mca");
		try(RandomAccessFile sparse = new RandomAccessFile(oversized.toFile(), "rw")) {
			sparse.setLength(GitBackupPreflight.MAX_GITHUB_FILE_BYTES + 1);
		}
		GitUtils.BackupPushResult result = GitUtils.commitAndPush(serverRoot, true);

		assertFalse(result.success());
		assertTrue(result.message().contains("over 100 MiB"));
		try(Git remoteGit = Git.open(remote.toFile())) {
			assertEquals(confirmedHead, remoteGit.getRepository().resolve("refs/heads/master"));
		}
	}
}
