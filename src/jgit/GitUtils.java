package jgit;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JOptionPane;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import minecraftServerManagement.ForgeUtils;
import view.MainFrame;

public class GitUtils {

	public static volatile boolean serverAutoSaveIsActive = false;
	public static int autoSaveSecondsInterval = 10/*minutes*/ * 60; // Default 10 min: pierde poco y no infla el repo.
	public static Thread autoSaveProcess = null; //By default.

	public static final Path JOINED_REPOS = app.AppPaths.dataFile("joined_repos.properties");
	private static final String GITHUB_API_PROPERTY = "p2pmss.githubApiBase";
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
	static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
	static final ObjectMapper JSON_MAPPER = new ObjectMapper();
	private static final String BACKUP_IGNORE_START = "# BEGIN P2PMSS MANAGED BACKUP EXCLUDES";
	private static final String BACKUP_IGNORE_END = "# END P2PMSS MANAGED BACKUP EXCLUDES";
	private static final List<String> BACKUP_IGNORE_LINES = List.of(
			BACKUP_IGNORE_START,
			"# Runtime output and local rollback data are not part of a playable server backup.",
			"/logs/",
			"/crash-reports/",
			"/world-import-backups/",
			"/.p2pmss-import-*/",
			"**/session.lock",
			"**/*.tmp",
			"**/.DS_Store",
			BACKUP_IGNORE_END);

	public record GitHubRepositoryResult(boolean success, String cloneUrl, int statusCode, String message, boolean existing) {}
	public record PrivateBackupSetupResult(boolean success, boolean alreadyLinked, boolean existingRemote, String message) {}
	public record BackupPushResult(boolean success, int committedBatches, String message) {}

	/**
	 * Creates (or recovers) the private GitHub repository for a server, performs
	 * the initial verified push and applies local-only flags. This method is
	 * synchronous by design; callers must run it outside Swing's event thread.
	 */
	public static PrivateBackupSetupResult configurePrivateBackup(Path repoDirectory, String repoName) {
		if(repoDirectory == null || !Files.isDirectory(repoDirectory)) {
			return new PrivateBackupSetupResult(false, false, false, "The selected server folder is not accessible.");
		}
		if(repoName == null || repoName.isBlank()) {
			return new PrivateBackupSetupResult(false, false, false, "The server needs a valid repository name.");
		}

		if(!ensureBackupIgnoreFile(repoDirectory)) {
			return new PrivateBackupSetupResult(false, false, false, "The backup exclusion file could not be prepared.");
		}
		GitBackupPreflight.Result preflight = GitBackupPreflight.inspect(repoDirectory);
		if(!preflight.safe()) {
			return new PrivateBackupSetupResult(false, false, false, preflight.message());
		}

		Map<String, String> userData;
		try {
			userData = TokenStore.getSavedUserData();
		} catch(Exception invalidSession) {
			return new PrivateBackupSetupResult(false, false, false, "Sign into GitHub again before protecting this world.");
		}

		if(repoExistInPath(repoDirectory) && hasRemoteOrigin(repoDirectory)) {
			if(!setLocalIdentity(repoDirectory)) {
				return new PrivateBackupSetupResult(false, true, true, "The repository identity could not be configured.");
			}
			BackupPushResult backup = commitAndPush(repoDirectory, false);
			if(!backup.success()) return new PrivateBackupSetupResult(false, true, true, backup.message());
			if(!protectMachineLocalFiles(repoDirectory)) {
				return new PrivateBackupSetupResult(false, true, true, "The world was pushed, but local-only files could not be protected.");
			}
			return new PrivateBackupSetupResult(true, true, true,
					backup.committedBatches() == 0 ? "Private GitHub backup is linked and remote state was confirmed." : backup.message());
		}

		GitHubRepositoryResult remote = createRepoInGitHub(userData.get("token"), repoName);
		if(!remote.success()) {
			return new PrivateBackupSetupResult(false, false, remote.existing(), remote.message());
		}
		if(!createRepoIfNotExistsInPath(repoDirectory)) {
			return new PrivateBackupSetupResult(false, false, remote.existing(), "The local Git repository could not be initialized.");
		}
		BackupPushResult initialBackup = linkLocalRepoToExternalResult(remote.cloneUrl(), repoDirectory);
		if(!initialBackup.success()) {
			String message = remote.existing()
					? "The existing repository could not accept the next backup batch. " + initialBackup.message()
					: "GitHub did not accept the next initial backup batch. " + initialBackup.message();
			return new PrivateBackupSetupResult(false, false, remote.existing(), message);
		}
		if(!protectMachineLocalFiles(repoDirectory)) {
			return new PrivateBackupSetupResult(false, false, remote.existing(), "The world was pushed, but local-only files could not be protected.");
		}
		return new PrivateBackupSetupResult(true, false, remote.existing(),
				(remote.existing() ? "Existing private repository linked. " : "Private repository created. ")
						+ initialBackup.message());
	}

	/**
	 * Adds a small managed block without overwriting ignore rules written by the
	 * server owner. Keeping runtime logs out of Git reduces initial upload size
	 * and prevents a running console log from making pulls perpetually dirty.
	 */
	static boolean ensureBackupIgnoreFile(Path repoDirectory) {
		Path ignoreFile = repoDirectory.resolve(".gitignore");
		try {
			List<String> original = Files.exists(ignoreFile)
					? new java.util.ArrayList<>(Files.readAllLines(ignoreFile))
					: new java.util.ArrayList<>();
			List<String> updated = new java.util.ArrayList<>();
			boolean insideManagedBlock = false;
			int insertionPoint = -1;
			for(String line : original) {
				if(BACKUP_IGNORE_START.equals(line)) {
					insideManagedBlock = true;
					if(insertionPoint < 0) insertionPoint = updated.size();
					continue;
				}
				if(insideManagedBlock && BACKUP_IGNORE_END.equals(line)) {
					insideManagedBlock = false;
					continue;
				}
				if(!insideManagedBlock) updated.add(line);
			}
			if(insertionPoint < 0) {
				if(!updated.isEmpty() && !updated.getLast().isBlank()) updated.add("");
				insertionPoint = updated.size();
			}
			updated.addAll(insertionPoint, BACKUP_IGNORE_LINES);
			Files.write(ignoreFile, updated, StandardCharsets.UTF_8);
			return true;
		} catch(IOException failure) {
			return false;
		}
	}

	static boolean protectMachineLocalFiles(Path repoDirectory) {
		return protectMachineLocalFile(repoDirectory, Path.of("server.properties"))
				&& protectMachineLocalFile(repoDirectory, Path.of("user_jvm_args.txt"));
	}

	private static boolean protectMachineLocalFile(Path repoDirectory, Path relativePath) {
		// Decide by index membership, not disk existence: Forge creates server.properties on the
		// first boot, so the file can exist on disk while it was never staged (it is covered by
		// .git/info/exclude instead). Asking setSkipWorktree to flag a non-indexed file made the
		// whole stop backup report PUSH FAILED even though every batch was already confirmed.
		if(isTrackedInIndex(repoDirectory, relativePath)) return setSkipWorktree(repoDirectory, relativePath, true);
		Path exclude = repoDirectory.resolve(".git/info/exclude");
		try {
			Files.createDirectories(exclude.getParent());
			List<String> lines = Files.exists(exclude) ? Files.readAllLines(exclude) : new java.util.ArrayList<>();
			String pattern = relativePath.toString().replace('\\', '/');
			if(!lines.contains(pattern)) {
				lines.add(pattern);
				Files.write(exclude, lines);
			}
			return true;
		} catch(IOException failure) {
			return false;
		}
	}

	private static boolean isTrackedInIndex(Path repoDirectory, Path relativePath) {
		try (Git git = Git.open(repoDirectory.toFile())) {
			String indexPath = relativePath.toString().replace('\\', '/');
			return git.getRepository().readDirCache().getEntry(indexPath) != null;
		} catch(Exception e) {
			return false;
		}
	}

	public static boolean createRepoInPath(Path repoDirectory) {
		Map<String, String> userdata;
		try {userdata = TokenStore.getSavedUserData();} catch(Exception e) {return false;}
		try (Git git = Git.init().setDirectory(repoDirectory.toFile()).call()) {
			return setLocalIdentity(git, userdata);
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}
	
	public static boolean createRepoIfNotExistsInPath(Path repoDirectory) {
		return repoExistInPath(repoDirectory) || createRepoInPath(repoDirectory);
	}
	
	public static boolean repoExistInPath(Path repoDirectory) {
		try (Git ignored = Git.open(repoDirectory.toFile())) {
			return true;
		}
		catch(IOException e) {
			return false;
		}
	}

	public static boolean hasRemoteOrigin(Path repoDirectory) {
		try (Git git = Git.open(repoDirectory.toFile())) {
			String originUrl = git.getRepository().getConfig().getString("remote", "origin", "url");
			return originUrl != null && !originUrl.isBlank();
		} catch (IOException e) {
			return false;
		}
	}

	/** Resolves "owner/repo" from the origin URL, or null when the server is not linked. */
	public static String remoteRepoFullName(Path repoDirectory) {
		try (Git git = Git.open(repoDirectory.toFile())) {
			return parseRepoFullName(git.getRepository().getConfig().getString("remote", "origin", "url"));
		} catch (IOException e) {
			return null;
		}
	}

	static String parseRepoFullName(String originUrl) {
		if(originUrl == null || originUrl.isBlank()) return null;
		Matcher matcher = Pattern.compile("github\\.com[:/]([^/:]+)/([^/:]+?)(?:\\.git)?/?$").matcher(originUrl.trim());
		if(!matcher.find()) return null;
		return matcher.group(1) + "/" + matcher.group(2);
	}

	public static void removeRemoteOrigin(Path repoDirectory) {
		try (Git git = Git.open(repoDirectory.toFile())) {
			StoredConfig config = git.getRepository().getConfig();
			config.unsetSection("remote", "origin");
			config.save();
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
	
	public static GitHubRepositoryResult createRepoInGitHub(String token, String repoName) {
		String json;
		try {
			json = JSON_MAPPER.createObjectNode().put("name", repoName).put("private", true).toString();
		} catch(Exception e) {
			return new GitHubRepositoryResult(false, null, 0, "The repository request could not be created.", false);
		}

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(githubApiBase() + "/user/repos"))
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.timeout(REQUEST_TIMEOUT)
				.header("Authorization", "Bearer " + token)
				.header("User-Agent", "Peer_To_Peer_Minecraft_Server_System/1.0")
				.header("Accept", "application/vnd.github+json")
				.header("Content-Type", "application/json")
				.header("X-GitHub-Api-Version", "2022-11-28")
				.build();

		HttpResponse<String> response;
		try {
			response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch(Exception e) {
			return new GitHubRepositoryResult(false, null, 0, "GitHub could not be reached. Check the connection and try again.", false);
		}
		if(response.statusCode() == 401) TokenStore.invalidateSession();

		if(response.statusCode() == 201) {
			return repositoryResultFromBody(response.body(), response.statusCode(), false);
		}

		if(response.statusCode() == 422) {
			try {
				Map<String, String> userData = TokenStore.getSavedUserData();
				GitHubRepositoryResult existing = getExistingRepository(token, userData.get("nickname"), repoName);
				if(existing.success()) return existing;
			} catch(Exception ignored) {}
		}

		return new GitHubRepositoryResult(false, null, response.statusCode(), githubErrorMessage(response), false);
	}

	private static GitHubRepositoryResult getExistingRepository(String token, String owner, String repoName) {
		String url = githubApiBase() + "/repos/" + encodePathSegment(owner) + "/" + encodePathSegment(repoName);
		HttpRequest request = authenticatedRequest(url, token).GET().build();
		try {
			HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			if(response.statusCode() == 401) TokenStore.invalidateSession();
			if(response.statusCode() == 200) return repositoryResultFromBody(response.body(), response.statusCode(), true);
			return new GitHubRepositoryResult(false, null, response.statusCode(), githubErrorMessage(response), true);
		} catch(Exception e) {
			return new GitHubRepositoryResult(false, null, 0, "The existing repository could not be checked.", true);
		}
	}

	private static GitHubRepositoryResult repositoryResultFromBody(String body, int statusCode, boolean existing) {
		try {
			JsonNode json = JSON_MAPPER.readTree(body);
			String cloneUrl = json.path("clone_url").asText(null);
			if(cloneUrl == null || cloneUrl.isBlank()) throw new IOException("clone_url missing");
			String message = existing ? "The existing repository will be linked." : "Repository created.";
			return new GitHubRepositoryResult(true, cloneUrl, statusCode, message, existing);
		} catch(Exception e) {
			return new GitHubRepositoryResult(false, null, statusCode, "GitHub returned an invalid repository response.", existing);
		}
	}

	private static String githubErrorMessage(HttpResponse<String> response) {
		try {
			String message = JSON_MAPPER.readTree(response.body()).path("message").asText();
			if(!message.isBlank()) return "GitHub error " + response.statusCode() + ": " + message;
		} catch(Exception ignored) {}
		return "GitHub error " + response.statusCode() + ".";
	}
	
	public static Map<String, String> convertJsonStringToMap(String json){
		Map<String, String> responseMap = new HashMap<>();
		json = json.replaceAll("[{|}]", "");
		for(String keyValueLine : json.split(",\"")) /*Split by: ," */ {
			String[] cells = keyValueLine.split("\":")/*Split by: ": */;
			responseMap.put(cells[0].replace('"', ' ').trim(), cells[1].replace('"', ' ').trim());
		}
		return responseMap;
	}
	
	public static List<Map<String, Object>> convertJsonStringToMapJson(String json){
		ObjectMapper mapper = new ObjectMapper();
		try {
			return mapper.readValue(json, new TypeReference<>() {});
		} catch (Exception e) {
			return null;
		}
	}
	
	public static boolean linkLocalRepoToExternal(String cloneUrl, String token, Path repoDirectory) {
		return linkLocalRepoToExternalResult(cloneUrl, repoDirectory).success();
	}

	private static BackupPushResult linkLocalRepoToExternalResult(String cloneUrl, Path repoDirectory) {
		Map<String, String> userdata;
		try {userdata = TokenStore.getSavedUserData();}
		catch(Exception e) {return new BackupPushResult(false, 0, "Sign into GitHub again before retrying.");}
		
		try (Git git = Git.open(repoDirectory.toFile())) {
			if(cloneUrl == null || cloneUrl.isBlank()) return new BackupPushResult(false, 0, "GitHub did not provide a repository URL.");
			if(!setLocalIdentity(git, userdata)) return new BackupPushResult(false, 0, "The local Git identity could not be configured.");

			StoredConfig config = git.getRepository().getConfig();
			config.setString("remote", "origin", "url", cloneUrl);
			config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
			config.save();
		} catch (Exception e) {
			e.printStackTrace();
			return new BackupPushResult(false, 0, "The local repository could not be linked to GitHub.");
		}

		return commitAndPush(repoDirectory, false);
	}

	/*
	 * Writes the identity of the account signed into the application into the LOCAL configuration of the repository (.git/config).
	 * The local configuration takes precedence over the user's global ~/.gitconfig, so the commits made by this application are
	 * always signed with the application account, and the git identity of the machine is neither inherited nor modified.
	 */
	public static boolean setLocalIdentity(Git git, Map<String, String> userdata) {
		try {
			if(userdata == null || isBlank(userdata.get("nickname")) || isBlank(userdata.get("email"))) return false;
			StoredConfig config = git.getRepository().getConfig();
			config.setString("user", null, "name", userdata.get("nickname"));
			config.setString("user", null, "email", userdata.get("email"));
			config.save();
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	public static boolean setLocalIdentity(Path repoDirectory) {
		Map<String, String> userdata;
		try {userdata = TokenStore.getSavedUserData();} catch(Exception e) {return false;}

		try (Git git = Git.open(repoDirectory.toFile())) {
			return setLocalIdentity(git, userdata);
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	public static boolean setSkipWorktree(Path repoDirectory, Path filePath, boolean shouldSkip) {
        try (Git git = Git.open(repoDirectory.toFile())) {
			return setSkipWorktree(git, repoDirectory, filePath, shouldSkip);
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
    }

	private static boolean setSkipWorktree(Git git, Path repoDirectory, Path filePath, boolean shouldSkip) {
		Path absoluteRepo = repoDirectory.toAbsolutePath().normalize();
		Path absoluteFile = filePath.isAbsolute() ? filePath.toAbsolutePath().normalize() : absoluteRepo.resolve(filePath).normalize();
		if(!absoluteFile.startsWith(absoluteRepo)) return false;
		if(!Files.exists(absoluteFile)) return true;

		String relativePath = absoluteRepo.relativize(absoluteFile).toString().replace('\\', '/');
		Repository repo = git.getRepository();

		DirCache cache = null;
		try {
			if(repo.readDirCache().getEntry(relativePath) == null) return false;
			cache = repo.lockDirCache();
			DirCacheEditor editor = cache.editor();
			editor.add(new DirCacheEditor.PathEdit(relativePath) {
				@Override
				public void apply(DirCacheEntry entry) {
					// JGit exposes the skip-worktree bit as read-only. Assume-valid is its
					// supported local index flag and keeps these tracked, host-specific files
					// out of normal status/add/commit operations without requiring native Git.
					entry.setAssumeValid(shouldSkip);
				}
			});
			editor.commit();
			return true;
		} catch(Exception e) {
			e.printStackTrace();
			return false;
		} finally {
			if(cache != null) cache.unlock();
		}
	}
	public static boolean inviteHostingUser(String username) {
		try {
			Map<String, String> userData = TokenStore.getSavedUserData();
			String repo = MainFrame.getServerName();
			return inviteHostingUser(userData.get("token"), userData.get("nickname"), repo, username);
		} catch (Exception e) {
			JOptionPane.showMessageDialog(null, "Something went wrong, try again.", "Error", JOptionPane.ERROR_MESSAGE);
			return false;
		}
	}
	
	public static boolean inviteHostingUser(String token, String owner, String repo, String username) {
		String json = """
				{
					"permission": "push"
				}
				""";
		
		HttpRequest request = authenticatedRequest(
				githubApiBase() + "/repos/" + encodePathSegment(owner) + "/" + encodePathSegment(repo) + "/collaborators/" + encodePathSegment(username),
				token)
				.PUT(HttpRequest.BodyPublishers.ofString(json))
				.header("Content-Type", "application/json")
				.build();
		
		HttpResponse<String> response;
		try {
			response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch(Exception e) {
			JOptionPane.showMessageDialog(null, "Something went wrong, try again.", "Error", JOptionPane.ERROR_MESSAGE);
			return false;
		}
		if(response.statusCode() == 401) TokenStore.invalidateSession();
		return response.statusCode() == 201 || response.statusCode() == 204;
	};
	
	public static List<Map<String, Object>> getAllPendingInvitations() {
		String token;
		try {token = TokenStore.getSavedUserData().get("token");} catch (Exception e) {return null;}
		
		HttpRequest request = authenticatedRequest(githubApiBase() + "/user/repository_invitations", token)
				.GET()
				.build();
		HttpResponse<String> response;
		try {
			response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch(Exception e) {
			JOptionPane.showMessageDialog(null, "Something went wrong, try again.", "Error", JOptionPane.ERROR_MESSAGE);
			return null;
		}
		
		if(response.statusCode() == 401) TokenStore.invalidateSession();
		if(response.statusCode() != 200) return null;
		try {
			return JSON_MAPPER.readValue(response.body(), new TypeReference<>() {});
		} catch(Exception e) {
			return null;
		}
	}
	
	public static boolean acceptInvitationById(int id) {
		String token;
		try {
			token = TokenStore.loadToken();
		} catch (Exception e) {return false;}
		
		HttpRequest request = authenticatedRequest(githubApiBase() + "/user/repository_invitations/" + id, token)
				.method("PATCH", HttpRequest.BodyPublishers.noBody())
				.build();
		
		HttpResponse<String> response;
		try {
			response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch(Exception e) {
			return false;
		}
		if(response.statusCode() == 401) TokenStore.invalidateSession();
		if(Integer.toString(response.statusCode()).startsWith("2")) //Accepts all 200 result codes.
			return true;			
		
		return false;
	}
	
	public static void saveRepoJoined(String repo) {
		Map<String, String> userData;
		try {userData = TokenStore.getSavedUserData();}
		catch(Exception e) {
			JOptionPane.showMessageDialog(null, "Session invalid, consider sign in again.", "Error", JOptionPane.ERROR_MESSAGE); 
			return;
		}
		
		String nickname = userData.get("nickname");
		Path joinedRepos = joinedReposPath();
		Properties props = new Properties();
		String propertyName = "joined_repos_by_" + nickname;
		try {
			Files.createDirectories(joinedRepos.getParent());
			if(Files.exists(joinedRepos)) {
				try(FileInputStream in = new FileInputStream(joinedRepos.toFile())) {
					props.load(in);
				}
			}

			Set<String> repos = new LinkedHashSet<>();
			String previousValue = props.getProperty(propertyName, "");
			if(!previousValue.isBlank()) repos.addAll(Arrays.asList(previousValue.split(",")));
			repos.add(repo);
			props.setProperty(propertyName, String.join(",", repos));
			try(FileOutputStream out = new FileOutputStream(joinedRepos.toFile())) {
				props.store(out, "Updated joined repos by users.");
			}
		}
		catch(IOException e) {
			JOptionPane.showMessageDialog(null, "File not found or inaccessible " + joinedRepos, "Error", JOptionPane.ERROR_MESSAGE);
		}
	}
	
	public static List<String> getRepoJoined() {
		Path joinedRepos = joinedReposPath();
		if(!(Files.exists(joinedRepos))) return null;
		
		Properties props = new Properties();
		try(FileInputStream in = new FileInputStream(joinedRepos.toFile())){
			Map<String, String> userData = TokenStore.getSavedUserData();
			
			props.load(in);
			if(props.containsKey("joined_repos_by_" + userData.get("nickname"))) {
				String[] reposArray = props.getProperty("joined_repos_by_" + userData.get("nickname")).split(",");
				return Arrays.asList(reposArray);
			}
		}
		catch(IOException ioe) {
			JOptionPane.showMessageDialog(null, "File not found or inaccessible " + joinedRepos, "Error", JOptionPane.ERROR_MESSAGE);
		}
		catch(Exception e) {
			JOptionPane.showMessageDialog(null, "Session invalid, consider sign in again.", "Error", JOptionPane.ERROR_MESSAGE);
		}
		
		return null;
	}
	
	public static boolean cloneRepoInPath(Path clonePath, String repoFullName) {
		return cloneRepoFromUrl(clonePath, "https://github.com/%s.git".formatted(repoFullName));
	}

	static boolean cloneRepoFromUrl(Path clonePath, String cloneUrl) {
		Map<String, String> userdata;
		try {userdata = TokenStore.getSavedUserData();} 
		catch(Exception e) {
			JOptionPane.showMessageDialog(null, "Session invalid, consider sign in again.", "Error", JOptionPane.ERROR_MESSAGE);
			return false;
		}
		
		UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider(userdata.get("nickname"), userdata.get("token"));
		
		try (Git git = Git.cloneRepository()
				.setURI(cloneUrl)
				.setDirectory(clonePath.toFile())
				.setCredentialsProvider(credentials)
				.call()) {

			if(!setLocalIdentity(git, userdata)) return false;
			if(!setSkipWorktree(git, clonePath, Path.of("server.properties"), true)) return false;
			if(!setSkipWorktree(git, clonePath, Path.of("user_jvm_args.txt"), true)) return false;

			return true;
		}
		catch (Exception e) {
			e.printStackTrace();
			JOptionPane.showMessageDialog(null, "File not found or inaccessible " + clonePath + " or git error occurred.", "Error", JOptionPane.ERROR_MESSAGE);
		}
		return false;
	}
	
	public static boolean autoCommitAndPush() {
		return autoCommitAndPush(false);
	}
	
	public static boolean autoCommitAndPush(boolean isServerStopping) {
		return MainFrame.serverOpenedDirectory != null
				&& commitAndPush(MainFrame.serverOpenedDirectory.toPath(), isServerStopping).success();
	}

	/**
	 * Commits and pushes the selected server in bounded, verified batches.
	 *
	 * <p>A clean pending HEAD is pushed first, which resumes a batch committed
	 * during an earlier network failure. Each following commit remains below
	 * 256 MiB of uncompressed input and is checked before the next one is created.</p>
	 */
	public static synchronized BackupPushResult commitAndPush(Path repoDirectory, boolean isServerStopping) {
		if(repoDirectory == null || !Files.isDirectory(repoDirectory)) {
			return new BackupPushResult(false, 0, "The selected server folder is not accessible.");
		}
		if(!ensureBackupIgnoreFile(repoDirectory)) {
			return new BackupPushResult(false, 0, "The backup exclusion file could not be updated.");
		}
		GitBackupPreflight.Result preflight = GitBackupPreflight.inspect(repoDirectory);
		if(!preflight.safe()) return new BackupPushResult(false, 0, preflight.message());

		Map<String, String> userdata;
		try {userdata = TokenStore.getSavedUserData();} 
		catch(Exception e) {
			return new BackupPushResult(false, 0, "The GitHub session is invalid. Sign in again and retry.");
		}
		
		UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider(userdata.get("nickname"), userdata.get("token"));
		
		try (Git git = Git.open(repoDirectory.toFile())) {
			if(!setLocalIdentity(git, userdata)) {
				return new BackupPushResult(false, 0, "The local Git identity could not be configured.");
			}

			Status status = git.status().call();
			// A failed push can leave a clean working tree with one or more local
			// commits pending. Confirm those before reporting an unchanged world as
			// safe. With dirty files we commit first, so a rejected push still leaves
			// a clean tree that can be pulled/merged and retried without data loss.
			if(status.isClean() && git.getRepository().resolve("HEAD") != null) {
				PushCheckResult pending = pushAndCheckDetailed(git, credentials);
				if(!pending.success()) return new BackupPushResult(false, 0, pending.message());
			}

			Set<String> changedPaths = backupPaths(status);
			if(changedPaths.isEmpty()) {
				return new BackupPushResult(true, 0, "GitHub already has the latest confirmed world state.");
			}

			Map<String, GitBackupPreflight.FileEntry> existingFiles = new HashMap<>();
			for(GitBackupPreflight.FileEntry file : preflight.files()) {
				existingFiles.put(portablePath(file.relativePath()), file);
			}
			List<GitBackupPreflight.FileEntry> changedFiles = new java.util.ArrayList<>();
			for(String changedPath : changedPaths) {
				GitBackupPreflight.FileEntry file = existingFiles.get(changedPath);
				if(file != null) changedFiles.add(file);
				else changedFiles.add(new GitBackupPreflight.FileEntry(Path.of(changedPath), 0));
			}
			changedFiles.sort(java.util.Comparator.comparing(file -> portablePath(file.relativePath())));
			List<List<GitBackupPreflight.FileEntry>> batches = GitBackupPreflight.batches(changedFiles);
			int committedBatches = 0;
			for(int index = 0; index < batches.size(); index++) {
				List<GitBackupPreflight.FileEntry> batch = batches.get(index);
				var add = git.add();
				boolean hasExistingFiles = false;
				for(GitBackupPreflight.FileEntry file : batch) {
					String relative = portablePath(file.relativePath());
					if(Files.exists(repoDirectory.resolve(file.relativePath()))) {
						add.addFilepattern(relative);
						hasExistingFiles = true;
					} else if(status.getMissing().contains(relative)) {
						git.rm().addFilepattern(relative).call();
					}
				}
				if(hasExistingFiles) add.call();

				Status staged = git.status().call();
				if(!hasStagedChanges(staged)) continue;
				String reason = isServerStopping ? " after clean server stop" : "";
				git.commit()
						.setAuthor(userdata.get("nickname"), userdata.get("email"))
						.setCommitter(userdata.get("nickname"), userdata.get("email"))
						.setMessage("World backup %d/%d by %s on %s%s".formatted(
								index + 1, batches.size(), userdata.get("email"), LocalDate.now(), reason))
						.call();
				committedBatches++;

				PushCheckResult pushed = pushAndCheckDetailed(git, credentials);
				if(!pushed.success()) {
					return new BackupPushResult(false, committedBatches,
							"Backup batch " + (index + 1) + "/" + batches.size() + " remains local. " + pushed.message());
				}
			}

			String largeFileNote = preflight.largeFiles().isEmpty() ? ""
					: " GitHub accepted " + preflight.largeFiles().size() + " file(s) above its 50 MiB warning threshold.";
			return new BackupPushResult(true, committedBatches,
					"GitHub confirmed " + committedBatches + " backup batch(es); the protected server tree now totals "
							+ GitBackupPreflight.humanSize(preflight.totalBytes()) + "." + largeFileNote);
		} catch (Exception e) {
			e.printStackTrace();
			return new BackupPushResult(false, 0,
					"Git could not finish the backup: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
		}
	}

	private static boolean pushAndCheck(Git git, UsernamePasswordCredentialsProvider credentials) throws GitAPIException {
		return pushAndCheckDetailed(git, credentials).success();
	}

	private record PushCheckResult(boolean success, String message) {}

	private static PushCheckResult pushAndCheckDetailed(Git git, UsernamePasswordCredentialsProvider credentials) throws GitAPIException {
		Iterable<PushResult> pushResults = git.push().setCredentialsProvider(credentials).call();
		boolean updateFound = false;
		for(PushResult pushResult : pushResults) {
			for(RemoteRefUpdate update : pushResult.getRemoteUpdates()) {
				updateFound = true;
				RemoteRefUpdate.Status status = update.getStatus();
				if(status != RemoteRefUpdate.Status.OK && status != RemoteRefUpdate.Status.UP_TO_DATE) {
					System.err.println("Git push rejected for " + update.getRemoteName() + ": " + status + " " + update.getMessage());
					String detail = update.getMessage() == null ? "" : " (" + update.getMessage() + ")";
					return new PushCheckResult(false, "GitHub rejected " + update.getRemoteName() + ": " + status + detail + ".");
				}
			}
		}
		return new PushCheckResult(updateFound,
				updateFound ? "GitHub accepted the push." : "Git did not report a remote branch update.");
	}

	private static Set<String> backupPaths(Status status) {
		Set<String> paths = new LinkedHashSet<>();
		paths.addAll(status.getAdded());
		paths.addAll(status.getChanged());
		paths.addAll(status.getModified());
		paths.addAll(status.getMissing());
		paths.addAll(status.getRemoved());
		paths.addAll(status.getUntracked());
		paths.addAll(status.getConflicting());
		paths.removeIf(path -> GitBackupPreflight.isRuntimeOnly(Path.of(path)));
		return paths;
	}

	private static boolean hasStagedChanges(Status status) {
		return !status.getAdded().isEmpty() || !status.getChanged().isEmpty() || !status.getRemoved().isEmpty();
	}

	private static String portablePath(Path path) {
		return path.toString().replace('\\', '/');
	}
	
	public static void setAutoSaveInterval(int seconds) {
		autoSaveSecondsInterval = seconds;
		saveAutoSaveInterval();
		if(serverAutoSaveIsActive) {
			serverAutoSaveIsActive = false;
			activeAutoSave();
		}
	}
	
	public static void saveAutoSaveInterval() {
		saveAutoSaveInterval(autoSaveSecondsInterval);
	}
	
	public static void saveAutoSaveInterval(int seconds) {
		if(seconds != 0 && seconds < 2 * 60) throw new RuntimeException("Autosave interval can not be lower than 2 minutes");
		
		Path networkNamePath = app.AppPaths.dataFile("networkName.properties");
		if(!(Files.exists(networkNamePath))) return;
		
		Properties props = new Properties();
		try(FileInputStream in = new FileInputStream(networkNamePath.toFile()); FileOutputStream out = new FileOutputStream(networkNamePath.toFile())){
			props.load(in);
			
			int savedSeconds = -1;
			if(props.containsKey("autoSaveInterval")) savedSeconds = Integer.parseInt(props.getProperty("autoSaveInterval"));
			if(savedSeconds == seconds) return;
			
			props.setProperty("autoSaveInterval", Integer.toString(seconds));
			props.store(out, "Updated seconds interval");
		} catch (IOException e) {
			JOptionPane.showMessageDialog(null, "File not found or inaccessible " + networkNamePath, "Error", JOptionPane.ERROR_MESSAGE);
			e.printStackTrace();
		}
		if(autoSaveSecondsInterval != seconds) autoSaveSecondsInterval = seconds;
	}
	
	public static int getSavedAutoSaveInteval() {
		Path networkNamePath = app.AppPaths.dataFile("networkName.properties");
		if(!(Files.exists(networkNamePath))) return autoSaveSecondsInterval;
		
		Properties props = new Properties();
		try(FileInputStream in = new FileInputStream(networkNamePath.toFile())){
			props.load(in);
			if(props.containsKey("autoSaveInterval"))
				autoSaveSecondsInterval = Integer.parseInt(props.getProperty("autoSaveInterval"));
			return autoSaveSecondsInterval;
		}
		catch(IOException ioe) {
			JOptionPane.showMessageDialog(null, "File not found or inaccessible " + networkNamePath, "Error", JOptionPane.ERROR_MESSAGE);
			ioe.printStackTrace();
		}
		return autoSaveSecondsInterval;
	}
	
	/** Serializes live saves against the stop backup so both never commit at once. */
	private static final Object LIVE_SAVE_MUTEX = new Object();
	static long saveConfirmationTimeoutSeconds = 60;

	public static void activeAutoSave() {
		if(serverAutoSaveIsActive) return;
		if(autoSaveSecondsInterval <= 0) return;
		if(MainFrame.serverIsOn && MainFrame.serverProcess != null && MainFrame.serverWriter != null) {
			autoSaveProcess = new Thread(() -> {
				serverAutoSaveIsActive = true;
				// El arranque acaba de pushear el mundo al día: el primer guardado espera un intervalo entero
				while(serverAutoSaveIsActive && MainFrame.serverProcess != null && MainFrame.serverProcess.isAlive()) {
					try {
						Thread.sleep(autoSaveSecondsInterval * 1000L);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						break;
					}
					if(!serverAutoSaveIsActive || MainFrame.serverProcess == null || !MainFrame.serverProcess.isAlive()) break;
					performLiveSave();
				}
				serverAutoSaveIsActive = false;
			}, "p2pmss-live-autosave");
			autoSaveProcess.setDaemon(true);
			autoSaveProcess.start();
		}
	}

	/**
	 * One hot snapshot: freeze writes, flush, wait for the server's own "Saved the
	 * game" confirmation, commit the whole server tree, and ALWAYS re-enable
	 * saving. A failed push retries on the next tick instead of killing the loop
	 * (transient antivirus/network hiccups must not disable live protection).
	 */
	static void performLiveSave() {
		synchronized(LIVE_SAVE_MUTEX) {
			boolean pushed = false;
			try {
				ForgeUtils.sendCommand("/save-off", MainFrame.serverProcess, MainFrame.serverWriter);
				boolean flushed = ForgeUtils.flushWorldToDisk(MainFrame.serverProcess, MainFrame.serverWriter,
						saveConfirmationTimeoutSeconds);
				if(flushed) {
					ForgeUtils.sendCommand("/say Saving world, creating backup...", MainFrame.serverProcess, MainFrame.serverWriter);
					pushed = autoCommitAndPush();
				}
			} finally {
				// Pase lo que pase, el server recupera su guardado normal
				ForgeUtils.sendCommand("/save-on", MainFrame.serverProcess, MainFrame.serverWriter);
			}
			if(MainFrame.window != null) {
				MainFrame.window.appendDashboardActivity(pushed
						? "Live world backup confirmed on GitHub"
						: "Live world backup could not be confirmed; retrying on the next interval");
			}
		}
	}

	/** Stops the autosave loop and waits for any in-flight snapshot before the stop backup runs. */
	public static void stopAutoSaveAndWait() {
		serverAutoSaveIsActive = false;
		Thread process = autoSaveProcess;
		if(process != null) process.interrupt();
		synchronized(LIVE_SAVE_MUTEX) { /* espera al lote en vuelo */ }
		autoSaveProcess = null;
	}
	
	public static Boolean isRemoteRepoHeadFordward(Path repoPath) {
		Map<String, String> userdata;
		try {userdata = TokenStore.getSavedUserData();} catch(Exception e) {return false;}
		UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider(userdata.get("nickname"), userdata.get("token"));
		
		try (Git git = Git.open(repoPath.toFile())) {

		    git.fetch().setCredentialsProvider(credentials).call();

		    Repository repo = git.getRepository();
		  
		    String branch = repo.getBranch();

		    ObjectId local  = repo.resolve("HEAD");
		    ObjectId remote = repo.resolve("refs/remotes/origin/" + branch);

		    try (RevWalk walk = new RevWalk(repo)) {

		        RevCommit localCommit  = walk.parseCommit(local);
		        RevCommit remoteCommit = walk.parseCommit(remote);

		        if (walk.isMergedInto(localCommit, remoteCommit)) {
		            return true;
		        } else if (walk.isMergedInto(remoteCommit, localCommit)) {
		            return false;
		        }
		    }
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}
	
	public static boolean pull(Path repoPath) {
		Map<String, String> userdata;
		try {userdata = TokenStore.getSavedUserData();} catch(Exception e) {return false;}
		UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider(userdata.get("nickname"), userdata.get("token"));
		
		try (Git git = Git.open(repoPath.toFile())) {
			if(!git.status().call().isClean() || !hasRemoteOrigin(repoPath)) return false;
			PullResult result = git.pull()
				.setCredentialsProvider(credentials)
				.call();
			return result.isSuccessful();
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return false;
	}

	static HttpRequest.Builder authenticatedRequest(String url, String token) {
		return HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(REQUEST_TIMEOUT)
				.header("Authorization", "Bearer " + token)
				.header("User-Agent", "Peer_To_Peer_Minecraft_Server_System/1.0")
				.header("Accept", "application/vnd.github+json")
				.header("X-GitHub-Api-Version", "2022-11-28");
	}

	static String githubApiBase() {
		String base = System.getProperty(GITHUB_API_PROPERTY, "https://api.github.com");
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	private static Path joinedReposPath() {
		return app.AppPaths.dataFile("joined_repos.properties");
	}

	static String encodePathSegment(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}
}
