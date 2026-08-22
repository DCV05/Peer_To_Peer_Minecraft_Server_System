package jgit;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Canal de eventos entre peers SOBRE GitHub — la idea de Daniel de "simular
 * websockets a partir de commits". En la rama del candado, cada evento es UN
 * fichero en p2pmss/events/ con el nombre {@code <epochms>-<nick>-<tipo>.json}:
 * nadie edita el fichero de otro, asi que no hay carreras. El nombre lleva toda
 * la informacion esencial, de modo que LISTAR el directorio basta para leer los
 * eventos sin una peticion por fichero.
 *
 * <p>El poll usa ETag ({@code If-None-Match}): las respuestas 304 no cuentan
 * contra el rate limit de la API, y por eso se puede consultar el mundo activo
 * cada pocos segundos sin gastar cuota.</p>
 */
public final class WorldEvents
{
	// El nombre de esta ruta NO se renombra con el resto del proyecto: vive
	// dentro del repositorio del mundo y la leen los DOS peers. Cambiarla
	// dejaria ciego al que todavia no haya actualizado, y con el candado
	// invisible los dos podrian arrancar el mismo mundo a la vez
	public static final String EVENTS_DIRECTORY = "p2pmss/events";
	static final long MAX_EVENT_AGE_MILLIS = 60 * 60 * 1000L;

	/** Un evento publicado por un peer, parseado del nombre de su fichero. */
	public record WorldEvent( long atMillis, String nick, String type, String fileName, String sha )
	{
	}

	private static final Map<String, String> etagByRepo = new ConcurrentHashMap<>();
	private static final Map<String, Set<String>> seenByRepo = new ConcurrentHashMap<>();

	private WorldEvents()
	{
	}

	/**
	 * Publica un evento como fichero nuevo en la rama del candado. Aprovecha
	 * para podar los eventos propios viejos. Nunca lanza: un canal informativo
	 * jamas debe romper el hosting.
	 */
	public static boolean publish( String repoFullName, String type )
	{
		boolean result = false;
		try
		{
			Map<String, String> userData = TokenStore.getSavedUserData();
			String token = userData.get( "token" );
			String nickname = userData.get( "nickname" );

			long now = System.currentTimeMillis();
			String fileName = now + "-" + nickname + "-" + type + ".json";
			ObjectNode event = GitUtils.JSON_MAPPER.createObjectNode()
					.put( "type", type )
					.put( "nick", nickname )
					.put( "at_millis", now );
			ObjectNode body = GitUtils.JSON_MAPPER.createObjectNode()
					.put( "message", "Event " + type + " by " + nickname )
					.put( "content", java.util.Base64.getEncoder()
							.encodeToString( event.toString().getBytes( java.nio.charset.StandardCharsets.UTF_8 ) ) )
					.put( "branch", HostLock.LOCK_BRANCH );
			HttpRequest request = GitUtils.authenticatedRequest( contentsUrl( repoFullName, fileName ), token )
					.PUT( HttpRequest.BodyPublishers.ofString( body.toString() ) )
					.header( "Content-Type", "application/json" )
					.build();
			HttpResponse<String> response = GitUtils.HTTP_CLIENT.send( request, HttpResponse.BodyHandlers.ofString() );
			result = response.statusCode() == 201 || response.statusCode() == 200;

			pruneOwnOldEvents( repoFullName, token, nickname );
		}
		catch( Exception publishFailure )
		{
			app.Log.event( "WORLD_EVENTS", "No se pudo publicar el evento " + type + " en " + repoFullName, publishFailure );
		}
		return result;
	}

	/**
	 * Eventos NUEVOS desde la ultima consulta (dedupe por nombre de fichero).
	 * Con ETag sin cambios (304) o sin directorio (404) devuelve lista vacia.
	 * Nunca lanza.
	 */
	public static List<WorldEvent> fetchNew( String repoFullName )
	{
		List<WorldEvent> result = new ArrayList<>();
		try
		{
			Map<String, String> userData = TokenStore.getSavedUserData();
			String token = userData.get( "token" );

			var builder = GitUtils.authenticatedRequest(
					contentsUrl( repoFullName, null ) + "?ref=" + HostLock.LOCK_BRANCH, token ).GET();
			String etag = etagByRepo.get( repoFullName );
			if( etag != null )
				builder.header( "If-None-Match", etag );
			HttpResponse<String> response = GitUtils.HTTP_CLIENT.send( builder.build(), HttpResponse.BodyHandlers.ofString() );
			if( response.statusCode() != 200 )
				return result;
			response.headers().firstValue( "ETag" ).ifPresent( fresh -> etagByRepo.put( repoFullName, fresh ) );

			Set<String> seen = seenByRepo.computeIfAbsent( repoFullName,
					ignored -> Collections.newSetFromMap( new ConcurrentHashMap<>() ) );
			JsonNode listing = GitUtils.JSON_MAPPER.readTree( response.body() );
			for( JsonNode entry : listing )
			{
				String fileName = entry.path( "name" ).asText( "" );
				WorldEvent event = parseFileName( fileName, entry.path( "sha" ).asText( null ) );
				if( event != null && seen.add( fileName ) )
					result.add( event );
			}
		}
		catch( Exception fetchFailure )
		{
			app.Log.event( "WORLD_EVENTS", "No se pudieron leer los eventos de " + repoFullName, fetchFailure );
		}
		return result;
	}

	/** {@code <epochms>-<nick>-<tipo>.json}; el nick puede llevar guiones, el tipo no. */
	static WorldEvent parseFileName( String fileName, String sha )
	{
		WorldEvent result = null;
		do
		{
			if( fileName == null || !fileName.endsWith( ".json" ) )
				break;
			String base = fileName.substring( 0, fileName.length() - ".json".length() );
			int firstDash = base.indexOf( '-' );
			int lastDash = base.lastIndexOf( '-' );
			if( firstDash <= 0 || lastDash <= firstDash )
				break;
			long atMillis;
			try
			{
				atMillis = Long.parseLong( base.substring( 0, firstDash ) );
			}
			catch( NumberFormatException notAnEvent )
			{
				break;
			}
			String nick = base.substring( firstDash + 1, lastDash );
			String type = base.substring( lastDash + 1 );
			if( nick.isBlank() || type.isBlank() )
				break;
			result = new WorldEvent( atMillis, nick, type, fileName, sha );
		} while( false );
		return result;
	}

	/** Borra los eventos PROPIOS mas viejos que una hora: el directorio no crece sin fin. */
	private static void pruneOwnOldEvents( String repoFullName, String token, String nickname )
	{
		try
		{
			HttpRequest request = GitUtils.authenticatedRequest(
					contentsUrl( repoFullName, null ) + "?ref=" + HostLock.LOCK_BRANCH, token ).GET().build();
			HttpResponse<String> response = GitUtils.HTTP_CLIENT.send( request, HttpResponse.BodyHandlers.ofString() );
			if( response.statusCode() != 200 )
				return;
			long cutoff = System.currentTimeMillis() - MAX_EVENT_AGE_MILLIS;
			for( JsonNode entry : GitUtils.JSON_MAPPER.readTree( response.body() ) )
			{
				WorldEvent event = parseFileName( entry.path( "name" ).asText( "" ), entry.path( "sha" ).asText( null ) );
				if( event == null || !nickname.equals( event.nick() ) || event.atMillis() >= cutoff )
					continue;
				ObjectNode body = GitUtils.JSON_MAPPER.createObjectNode()
						.put( "message", "Prune old event by " + nickname )
						.put( "sha", event.sha() )
						.put( "branch", HostLock.LOCK_BRANCH );
				HttpRequest delete = GitUtils.authenticatedRequest( contentsUrl( repoFullName, event.fileName() ), token )
						.method( "DELETE", HttpRequest.BodyPublishers.ofString( body.toString() ) )
						.header( "Content-Type", "application/json" )
						.build();
				GitUtils.HTTP_CLIENT.send( delete, HttpResponse.BodyHandlers.ofString() );
			}
		}
		catch( Exception pruneFailure )
		{
			// La poda es limpieza oportunista: si falla, el siguiente publish reintenta
		}
	}

	/** Solo para tests: olvida ETags y dedupe para partir de cero. */
	static void resetForTests()
	{
		etagByRepo.clear();
		seenByRepo.clear();
	}

	private static String contentsUrl( String repoFullName, String fileName )
	{
		String[] parts = repoFullName.split( "/", 2 );
		String encodedRepo = GitUtils.encodePathSegment( parts[0] ) + "/" + GitUtils.encodePathSegment( parts[1] );
		return GitUtils.githubApiBase() + "/repos/" + encodedRepo + "/contents/" + EVENTS_DIRECTORY
				+ (fileName != null ? "/" + GitUtils.encodePathSegment( fileName ) : "");
	}
}
