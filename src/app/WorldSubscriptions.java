package app;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Lista persistente de mundos suscritos: los repositorios de GitHub que este
 * usuario quiere vigilar aunque no los tenga abiertos.
 *
 * <p>Se guarda por usuario, igual que la lista de repos unidos: en una maquina
 * compartida cada cuenta ve solo sus mundos. Al leer, la lista se une con la de
 * invitaciones aceptadas ({@code joined_repos.properties}) y la union se
 * persiste, de modo que la migracion es implicita e idempotente.</p>
 */
public final class WorldSubscriptions
{

	// ---- FASE 1 — Almacenamiento -------------------------------------------

	private static final String STORAGE_FILE = "subscriptions.properties";
	private static final String LEGACY_JOINED_FILE = "joined_repos.properties";
	private static final String KEY_PREFIX = "subscriptions_by_";
	private static final String LEGACY_KEY_PREFIX = "joined_repos_by_";

	private WorldSubscriptions()
	{
	}

	/**
	 * Mundos suscritos del usuario, en orden de suscripcion y sin duplicados.
	 * Incluye (y absorbe) los repos unidos por invitacion; si la union aporta
	 * algo nuevo se persiste al momento para completar la migracion.
	 */
	public static synchronized List<String> all( String nickname )
	{
		List<String> result = List.of();
		do
		{
			if( nickname == null || nickname.isBlank() )
				break;

			Set<String> subscriptions = readList( storageFile(), KEY_PREFIX + nickname );
			Set<String> joined = readList( legacyJoinedFile(), LEGACY_KEY_PREFIX + nickname );
			Set<String> union = new LinkedHashSet<>( subscriptions );
			union.addAll( joined );

			if( !union.equals( subscriptions ) )
				writeList( nickname, union );

			result = new ArrayList<>( union );
		} while( false );
		return result;
	}

	/** Añade un mundo a la lista. Devuelve true solo si es nuevo. */
	public static synchronized boolean subscribe( String nickname, String repoFullName )
	{
		boolean result = false;
		do
		{
			if( nickname == null || nickname.isBlank() || repoFullName == null || repoFullName.isBlank() )
				break;

			Set<String> union = new LinkedHashSet<>( all( nickname ) );
			if( !union.add( repoFullName.trim() ) )
				break;

			result = writeList( nickname, union );
		} while( false );
		return result;
	}

	/** Quita un mundo de la lista. Devuelve true solo si estaba. */
	public static synchronized boolean unsubscribe( String nickname, String repoFullName )
	{
		boolean result = false;
		do
		{
			if( nickname == null || nickname.isBlank() || repoFullName == null )
				break;

			Set<String> union = new LinkedHashSet<>( all( nickname ) );
			if( !union.remove( repoFullName.trim() ) )
				break;

			result = writeList( nickname, union );
		} while( false );
		return result;
	}

	// ---- FASE 2 — Lectura y escritura del properties -----------------------

	private static Set<String> readList( Path file, String propertyName )
	{
		Set<String> result = new LinkedHashSet<>();
		if( Files.isRegularFile( file ) )
		{
			Properties properties = new Properties();
			try (FileInputStream in = new FileInputStream( file.toFile() ))
			{
				properties.load( in );
				String stored = properties.getProperty( propertyName, "" );
				if( !stored.isBlank() )
				{
					Arrays.stream( stored.split( "," ) )
							.map( String::trim )
							.filter( repo -> !repo.isEmpty() )
							.forEach( result::add );
				}
			}
			catch( IOException readFailure )
			{
				// Fichero corrupto o ilegible: se sigue con lo que se pudo leer; la
				// proxima escritura lo regenera
				Log.event( "SUBSCRIPTIONS", "No se pudo leer " + file, readFailure );
			}
		}
		return result;
	}

	private static boolean writeList( String nickname, Set<String> repos )
	{
		boolean result = false;
		Path file = storageFile();
		Properties properties = new Properties();
		try
		{
			Files.createDirectories( file.getParent() );
			if( Files.isRegularFile( file ) )
			{
				try (FileInputStream in = new FileInputStream( file.toFile() ))
				{
					properties.load( in );
				}
			}
			properties.setProperty( KEY_PREFIX + nickname, String.join( ",", repos ) );
			try (FileOutputStream out = new FileOutputStream( file.toFile() ))
			{
				properties.store( out, "Subscribed worlds by user." );
			}
			result = true;
		}
		catch( IOException writeFailure )
		{
			Log.event( "SUBSCRIPTIONS", "No se pudo guardar la lista de mundos en " + file, writeFailure );
		}
		return result;
	}

	private static Path storageFile()
	{
		return AppPaths.dataFile( STORAGE_FILE );
	}

	private static Path legacyJoinedFile()
	{
		return AppPaths.dataFile( LEGACY_JOINED_FILE );
	}
}
