package minecraftServerManagement;

import java.nio.file.Files;
import java.nio.file.Path;

/** Server loader installed in a folder, detected by its on-disk fingerprint. */
public enum LoaderKind
{
	FORGE("Forge"), FABRIC("Fabric");

	private final String displayName;

	LoaderKind( String displayName )
	{
		this.displayName = displayName;
	}

	public String displayName()
	{
		return displayName;
	}

	/** Forge es el default: es lo que habia antes de soportar Fabric y no deja huella propia en disco. */
	public static LoaderKind detect( Path serverDirectory )
	{
		LoaderKind result = FORGE;
		do
		{
			if( serverDirectory == null )
				break;
			Path fabricServerJar = serverDirectory.resolve( FabricInstaller.SERVER_JAR_NAME );
			if( Files.isRegularFile( fabricServerJar ) )
				result = FABRIC;
		} while( false );
		return result;
	}

	public static LoaderKind fromDisplayName( String displayName )
	{
		// Con el bucle dentro, un break del patron de salida unica saldria del for:
		// se usa una unica variable result y se corta el recorrido al encontrarlo
		LoaderKind result = FORGE;
		for( LoaderKind kind : values() )
		{
			if( kind.displayName.equalsIgnoreCase( displayName ) )
			{
				result = kind;
				break;
			}
		}
		return result;
	}
}
