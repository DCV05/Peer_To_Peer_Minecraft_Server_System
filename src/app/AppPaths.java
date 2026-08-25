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
 *
 * <p>La mudanza <b>se reintenta en cada arranque hasta que sale entera</b>, y un
 * fichero que no se deje copiar no detiene a los demas. Antes no era asi y costo
 * una sesion de GitHub: la primera mudanza se atraganto con un fichero, dejo lo
 * demas copiado, y como a partir de entonces la carpeta nueva ya existia no se
 * volvio a intentar nunca. Los dos ficheros de la sesion se quedaron en la
 * carpeta vieja y la aplicacion pedia autenticarse otra vez sin explicar por
 * que.</p>
 */
public final class AppPaths
{

	private static final String DATA_DIRECTORY_PROPERTY = "endershare.dataDirectory";
	/** Nombre anterior de la propiedad; se sigue aceptando para no romper scripts. */
	private static final String LEGACY_DATA_DIRECTORY_PROPERTY = "p2pmss.dataDirectory";
	/**
	 * Se deja cuando la mudanza ha salido entera. Mientras no este, se reintenta.
	 *
	 * <p>Hace falta para no resucitar lo que la persona ha borrado a proposito:
	 * sin marca, cerrar la sesion volveria a copiarla de la carpeta vieja en el
	 * siguiente arranque y no habria forma de salir.</p>
	 */
	private static final String MIGRATION_MARK = "migrated-from-p2pmss";
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
			// La marca es la unica forma de saber que la mudanza salio entera. Que la
			// carpeta exista no dice nada: puede haberla creado una mudanza a medias
			if( migrationIsDone( home ) )
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
			boolean complete = true;
			for( Path older : new Path[] { previousName, besideTheJar } )
			{
				if( !Files.isDirectory( older ) || older.equals( home ) )
					continue;
				complete &= copyMissingFiles( older, home );
			}
			if( complete )
				markMigrationDone( home );
			else
				Log.event( "APP_PATHS", "Mudanza a " + home + " incompleta: se reintentara al arrancar" );
		} while( false );
		return result;
	}

	static boolean migrationIsDone( Path home )
	{
		return Files.exists( home.resolve( MIGRATION_MARK ) );
	}

	private static void markMigrationDone( Path home )
	{
		try
		{
			Files.writeString( home.resolve( MIGRATION_MARK ),
					"Lo que hubiera en ~/.p2pmss/data y en ./data ya esta aqui.\n" );
		}
		catch( IOException notWritten )
		{
			// Sin marca la mudanza se reintenta en el siguiente arranque, que es
			// inofensivo: solo copia lo que falte
			Log.event( "APP_PATHS", "No se pudo dejar la marca de mudanza en " + home, notWritten );
		}
	}

	/**
	 * Copia lo que falte sin borrar ni pisar nada del destino.
	 *
	 * <p>Cada fichero va por su cuenta: uno que no se deje copiar no puede
	 * llevarse por delante a los que vienen detras. Detras venia la sesion.</p>
	 *
	 * @return true si se ha copiado todo lo que faltaba
	 */
	private static boolean copyMissingFiles( Path from, Path to )
	{
		boolean complete = true;
		try (Stream<Path> tree = Files.walk( from ))
		{
			for( Path source : tree.toList() )
			{
				if( !copyOne( from, to, source ) )
					complete = false;
			}
		}
		catch( IOException | RuntimeException unreadable )
		{
			// Ni siquiera se ha podido recorrer el origen: se reintentara
			Log.event( "APP_PATHS", "No se pudo recorrer " + from, unreadable );
			complete = false;
		}
		return complete;
	}

	private static boolean copyOne( Path from, Path to, Path source )
	{
		boolean copied = true;
		try
		{
			Path destination = to.resolve( from.relativize( source ).toString() );
			if( Files.isDirectory( source ) )
				Files.createDirectories( destination );
			else if( !Files.exists( destination ) )
				Files.copy( source, destination, StandardCopyOption.COPY_ATTRIBUTES );
		}
		catch( IOException | RuntimeException notCopied )
		{
			Log.event( "APP_PATHS", "No se pudo traer " + source, notCopied );
			copied = false;
		}
		return copied;
	}
}
