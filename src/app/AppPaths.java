package app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Single resolver for the app's private storage. The data lives in the user's
 * home ({@code ~/.endershare/data}) so the app works no matter where the jar
 * sits (Program Files, Desktop, a freshly extracted ZIP...).
 *
 * <p>Se migran dos sitios antiguos, sin borrar ninguno: la carpeta del nombre
 * anterior ({@code ~/.p2pmss/data}) y un {@code ./data} de al lado del jar. Una
 * persona que actualiza no puede perder su sesion de GitHub ni la lista de sus
 * servidores porque la aplicacion haya cambiado de nombre.</p>
 */
public final class AppPaths
{

	private static final String DATA_DIRECTORY_PROPERTY = "endershare.dataDirectory";
	/** Nombre anterior de la propiedad; se sigue aceptando para no romper scripts. */
	private static final String LEGACY_DATA_DIRECTORY_PROPERTY = "p2pmss.dataDirectory";
	private static volatile Path resolvedDataDirectory = null;

	private AppPaths()
	{
	}

	public static Path data()
	{
		String overridden = System.getProperty( DATA_DIRECTORY_PROPERTY );
		if( overridden == null )
			overridden = System.getProperty( LEGACY_DATA_DIRECTORY_PROPERTY );
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
		Path besideTheJar = Path.of( "data" ).toAbsolutePath();
		Path previousName = Path.of( System.getProperty( "user.home" ), ".p2pmss", "data" );
		Path home = Path.of( System.getProperty( "user.home" ), ".endershare", "data" );
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
				Log.event( "APP_PATHS", "No se pudo crear " + home + ", se usa el data local " + besideTheJar,
						homeUnavailable );
				result = besideTheJar;
				break;
			}

			// Primero lo del nombre anterior, que es lo que tiene la gente que ya
			// usaba la aplicacion; despues el ./data suelto, mas viejo todavia
			for( Path older : new Path[] { previousName, besideTheJar } )
			{
				if( !Files.isDirectory( older ) || older.equals( home ) )
					continue;
				try
				{
					copyMissingFiles( older, home );
				}
				catch( IOException partialMigration )
				{
					// Migracion incompleta: se sigue con lo copiado; el origen queda intacto
					Log.event( "APP_PATHS", "Migracion parcial de " + older + " a " + home, partialMigration );
				}
			}
		} while( false );
		return result;
	}

	/** Copia lo que falte sin borrar ni pisar nada del destino. */
	private static void copyMissingFiles( Path from, Path to ) throws IOException
	{
		try (Stream<Path> tree = Files.walk( from ))
		{
			for( Path source : tree.toList() )
			{
				Path destination = to.resolve( from.relativize( source ).toString() );
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
