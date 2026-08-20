package app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Properties;

/**
 * Checks the GitHub releases of the app and reports when a newer version
 * than the running one is published. The repository to watch and the current
 * version are baked into the jar at build time (see the p2pmss.releasesRepo
 * property in pom.xml and src/resources/p2pmss-update.properties), so every
 * fork points at its own releases just by compiling. Network failures are
 * treated as "no update available" so the startup flow keeps working offline.
 */
public final class UpdateChecker {

	static final String DEFAULT_RELEASES_REPO = "DCV05/Peer_To_Peer_Minecraft_Server_System";
	static final String DEFAULT_VERSION = "0.0.0";
	private static final String BUILD_PROPERTIES_RESOURCE = "/p2pmss-update.properties";
	private static final String GITHUB_API_PROPERTY = "p2pmss.githubApiBase";
	private static final String RELEASES_REPO_PROPERTY = "p2pmss.releasesRepo";
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
	private static volatile Properties buildProperties = null;

	public record ReleaseInfo(String version, String pageUrl, String downloadUrl) {}

	private UpdateChecker() {}

	private static String apiBase() {
		return System.getProperty(GITHUB_API_PROPERTY, "https://api.github.com");
	}

	private static Properties loadBuildProperties() {
		Properties loaded = buildProperties;
		if(loaded != null) return loaded;
		loaded = new Properties();
		try(InputStream in = UpdateChecker.class.getResourceAsStream(BUILD_PROPERTIES_RESOURCE)) {
			if(in != null) loaded.load(in);
		} catch(IOException ignored) {}
		buildProperties = loaded;
		return loaded;
	}

	/** A baked value is only usable when Maven actually filtered the placeholder. */
	private static String bakedProperty(String key) {
		String value = loadBuildProperties().getProperty(key, "").trim();
		return value.isEmpty() || value.startsWith("${") ? null : value;
	}

	/** owner/repo whose releases are watched: system property > baked at build > default. */
	public static String releasesRepo() {
		String overridden = System.getProperty(RELEASES_REPO_PROPERTY);
		if(overridden != null && !overridden.isBlank()) return overridden.trim();
		String baked = bakedProperty("releases.repo");
		return baked != null ? baked : DEFAULT_RELEASES_REPO;
	}

	/** Version of the running build, baked from the pom version at compile time. */
	public static String currentVersion() {
		String baked = bakedProperty("app.version");
		return baked != null ? baked : DEFAULT_VERSION;
	}

	/** Returns the latest published release only when it is strictly newer than {@link #currentVersion()}. */
	public static Optional<ReleaseInfo> findNewerRelease() {
		try {
			HttpClient client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(apiBase() + "/repos/" + releasesRepo() + "/releases/latest"))
					.timeout(REQUEST_TIMEOUT)
					.header("Accept", "application/vnd.github+json")
					.GET()
					.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if(response.statusCode() != 200) return Optional.empty();

			JsonNode release = JSON_MAPPER.readTree(response.body());
			String version = normalizeVersion(release.path("tag_name").asText(""));
			if(version.isEmpty() || !isNewer(version, normalizeVersion(currentVersion()))) return Optional.empty();

			String pageUrl = release.path("html_url").asText("https://github.com/" + releasesRepo() + "/releases");
			String downloadUrl = null;
			for(JsonNode asset : release.path("assets")) {
				if(asset.path("name").asText("").endsWith(".jar")) {
					downloadUrl = asset.path("browser_download_url").asText(null);
					break;
				}
			}
			return Optional.of(new ReleaseInfo(version, pageUrl, downloadUrl));
		} catch(Exception unreachable) {
			return Optional.empty();
		}
	}

	/** Strips the leading "v" and any "-suffix" so "v1.7.1-p2p" compares as "1.7.1". */
	static String normalizeVersion(String tag) {
		if(tag == null) return "";
		String cleaned = tag.trim();
		if(cleaned.startsWith("v") || cleaned.startsWith("V")) cleaned = cleaned.substring(1);
		int suffix = cleaned.indexOf('-');
		if(suffix >= 0) cleaned = cleaned.substring(0, suffix);
		return cleaned.matches("\\d+(\\.\\d+)*") ? cleaned : "";
	}

	/** Numeric segment-by-segment comparison; missing segments count as zero. */
	static boolean isNewer(String candidate, String current) {
		if(candidate.isEmpty() || current.isEmpty()) return false;
		String[] candidateParts = candidate.split("\\.");
		String[] currentParts = current.split("\\.");
		int length = Math.max(candidateParts.length, currentParts.length);
		for(int i = 0; i < length; i++) {
			int left = i < candidateParts.length ? Integer.parseInt(candidateParts[i]) : 0;
			int right = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
			if(left != right) return left > right;
		}
		return false;
	}
}
