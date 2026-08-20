package app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Single resolver for the app's private storage. The data lives in the user's
 * home ({@code ~/.p2pmss/data}) so the app works no matter where the jar sits
 * (Program Files, Desktop, a freshly extracted ZIP...). A legacy {@code ./data}
 * folder next to the jar is migrated in on first run and kept as a fallback if
 * the home directory cannot be created.
 */
public final class AppPaths {

	private static final String DATA_DIRECTORY_PROPERTY = "p2pmss.dataDirectory";
	private static volatile Path resolvedDataDirectory = null;

	private AppPaths() {}

	public static Path data() {
		String overridden = System.getProperty(DATA_DIRECTORY_PROPERTY);
		// La property de tests se evalua SIEMPRE: los tests la ponen y quitan por caso
		if(overridden != null) return Path.of(overridden);
		Path resolved = resolvedDataDirectory;
		if(resolved == null) {
			resolved = resolveDataDirectory();
			resolvedDataDirectory = resolved;
		}
		return resolved;
	}

	public static Path dataFile(String relativeName) {
		return data().resolve(relativeName);
	}

	private static Path resolveDataDirectory() {
		Path legacy = Path.of("data").toAbsolutePath();
		Path home = Path.of(System.getProperty("user.home"), ".p2pmss", "data");

		if(Files.isDirectory(home)) return home;

		try {
			Files.createDirectories(home);
		} catch(IOException homeUnavailable) {
			return legacy;
		}

		if(Files.isDirectory(legacy) && !legacy.equals(home)) {
			try {
				migrateLegacyData(legacy, home);
			} catch(IOException partialMigration) {
				// Migracion incompleta: se sigue con lo copiado; el legacy queda intacto
			}
		}
		return home;
	}

	/** Copies the legacy tree into the home directory without deleting the original. */
	private static void migrateLegacyData(Path legacy, Path home) throws IOException {
		try(Stream<Path> tree = Files.walk(legacy)) {
			for(Path source : tree.toList()) {
				Path destination = home.resolve(legacy.relativize(source).toString());
				if(Files.isDirectory(source)) {
					Files.createDirectories(destination);
				} else if(!Files.exists(destination)) {
					Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
		}
	}
}
