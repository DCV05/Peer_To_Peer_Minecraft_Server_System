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
public final class AppPaths
{

	private static final String DATA_DIRECTORY_PROPERTY = "p2pmss.dataDirectory";
	private static volatile Path resolvedDataDirectory = null;

	private AppPaths()
	{
	}

	public static Path data()
	{
		String overridden = System.getProperty( DATA_DIRECTORY_PROPERTY );
		// La property de tests se evalua SIEMPRE: los tests la ponen y quitan por caso
		if( overridden != null )
			return Path.of( overridden );
		Path resolved = resolvedDataDirectory;
		if( resolved == null )
		{
			resolved = resolveDataDirectory();
			resolvedDataDirectory = resolved;
		}
		return resolved;
	}

	public static Path dataFile( String relativeName )
	{
		return data().resolve( relativeName );
	}

	private static Path resolveDataDirectory()
	{
		Path legacy = Path.of( "data" ).toAbsolutePath();
		Path home = Path.of( System.getProperty( "user.home" ), ".p2pmss", "data" );
		Path result = home;
		do
		{
			// Si el home ya existe no hay nada que migrar: es el caso de todos los
			// arranques menos el primero
			if( Files.isDirectory( home ) )
				break;

			try
			{
				Files.createDirectories( home );
			}
			catch( IOException homeUnavailable )
			{
				// Home no escribible (permisos, perfil movil): se degrada al ./data
				// de al lado del jar en vez de dejar la app sin almacenamiento
				Log.event( "APP_PATHS", "No se pudo crear " + home + ", se usa el data local " + legacy, homeUnavailable );
				result = legacy;
				break;
			}

			if( Files.isDirectory( legacy ) && !legacy.equals( home ) )
			{
				try
				{
					migrateLegacyData( legacy, home );
				}
				catch( IOException partialMigration )
				{
					// Migracion incompleta: se sigue con lo copiado; el legacy queda intacto
					Log.event( "APP_PATHS", "Migracion parcial de " + legacy + " a " + home, partialMigration );
				}
			}
		} while( false );
		return result;
	}

	/** Copies the legacy tree into the home directory without deleting the original. */
	private static void migrateLegacyData( Path legacy, Path home ) throws IOException
	{
		try (Stream<Path> tree = Files.walk( legacy ))
		{
			for( Path source : tree.toList() )
			{
				Path destination = home.resolve( legacy.relativize( source ).toString() );
				if( Files.isDirectory( source ) )
				{
					Files.createDirectories( destination );
				}
				else if( !Files.exists( destination ) )
				{
					Files.copy( source, destination, StandardCopyOption.COPY_ATTRIBUTES );
				}
			}
		}
	}
}
