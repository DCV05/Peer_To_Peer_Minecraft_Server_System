package minecraftServerManagement;

import java.nio.file.Files;
import java.nio.file.Path;

/** Server loader installed in a folder, detected by its on-disk fingerprint. */
public enum LoaderKind {
	FORGE("Forge"),
	FABRIC("Fabric");

	private final String displayName;

	LoaderKind(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}

	public static LoaderKind detect(Path serverDirectory) {
		if(serverDirectory == null) return FORGE;
		if(Files.isRegularFile(serverDirectory.resolve(FabricInstaller.SERVER_JAR_NAME))) return FABRIC;
		return FORGE;
	}

	public static LoaderKind fromDisplayName(String displayName) {
		for(LoaderKind kind : values()) {
			if(kind.displayName.equalsIgnoreCase(displayName)) return kind;
		}
		return FORGE;
	}
}
