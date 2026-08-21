package jgit;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Arbitra qué peer puede hostear un mundo mediante un fichero de lease en GitHub.
 *
 * El lease vive como un JSON pequeño en una rama dedicada, de modo que los
 * latidos no ensucian nunca el historial del mundo. Cada escritura pasa por la
 * Contents API con el sha del blob esperado, y eso convierte a GitHub en el
 * árbitro: cuando dos peers compiten, solo un PUT entra y el perdedor recibe un
 * conflicto en vez de pisar el lease del otro.
 *
 * La frescura no confía jamás en el reloj local. La edad de un lease es la
 * distancia entre la fecha del último commit del fichero de lock y la cabecera
 * Date que GitHub mandó en esa MISMA respuesta, así que un peer con el reloj
 * roto no puede ni acaparar ni robar el lock.
 */
public final class HostLock
{

	// ---- FASE 1 — Contrato del lease ---------------------------------------

	public static final String LOCK_BRANCH = "host-lock";
	public static final String LOCK_FILE_PATH = "p2pmss/host-lock.json";
	/** A heartbeat commit refreshes the lease every 5 minutes while Forge runs. */
	public static final long HEARTBEAT_SECONDS = 300;
	/** Three missed heartbeats mean the host is gone and the lock can be taken over. */
	public static final long DEFAULT_LEASE_SECONDS = 900;

	public record Status( boolean locked, boolean mine, boolean stale, String hostNickname,
			Instant lastHeartbeat, long leaseSeconds, String contentSha, HostDetails details )
	{
		/** Nadie hostea: es tambien el default seguro cuando no hay fichero de lock. */
		static Status free()
		{
			return new Status( false, false, false, null, null, DEFAULT_LEASE_SECONDS, null, HostDetails.empty() );
		}

		/** Hay lease escrito; quien lo tiene y si esta caducado lo dicen los flags. */
		static Status held( boolean mine, boolean stale, String hostNickname, Instant lastHeartbeat,
				long leaseSeconds, String contentSha, HostDetails details )
		{
			return new Status( true, mine, stale, hostNickname, lastHeartbeat, leaseSeconds, contentSha, details );
		}
	}

	/**
	 * Datos que el host publica junto al lease para que los invitados los vean
	 * sin mas canal que el propio candado: direccion publica del tunel, aforo y
	 * version de Minecraft. Todos opcionales: un lease antiguo sin estos campos
	 * sigue siendo valido (compatibilidad entre versiones mezcladas de peers).
	 */
	public record HostDetails( String tunnelAddress, int onlinePlayers, int maxPlayers, String minecraftVersion )
	{
		public static HostDetails empty()
		{
			return new HostDetails( null, -1, -1, null );
		}
	}

	// El host activo la actualiza (tunel arriba, roster cambiado) y cada
	// escritura del lease la adjunta; los peers solo la leen
	private static volatile HostDetails publishedDetails = HostDetails.empty();

	public static void publishDetails( HostDetails details )
	{
		publishedDetails = details == null ? HostDetails.empty() : details;
	}

	public static void clearPublishedDetails()
	{
		publishedDetails = HostDetails.empty();
	}

	public record AcquireResult( boolean acquired, boolean blockedByPeer, String message )
	{
	}

	// ---- FASE 2 — Ciclo de vida del lock -----------------------------------

	/**
	 * Reclama el rol de host para este peer. Devuelve una negativa nombrando al
	 * host actual cuando otro tiene un lease fresco; los lease caducados y el
	 * lease huérfano de este mismo peer (una caída anterior) se toman en silencio.
	 */
	public static AcquireResult acquire( String repoFullName )
	{
		AcquireResult result;
		do
		{
			Map<String, String> userData;
			try
			{
				userData = TokenStore.getSavedUserData();
			}
			catch( Exception invalidSession )
			{
				result = new AcquireResult( false, false, "Sign into GitHub again before arbitrating the host lock." );
				break;
			}

			String token = userData.get( "token" );
			String nickname = userData.get( "nickname" );
			try
			{
				if( !ensureLockBranch( repoFullName, token ) )
				{
					result = new AcquireResult( false, false, "The host lock branch could not be prepared on GitHub." );
					break;
				}

				Status current = read( repoFullName, token, nickname );
				if( current.locked() && !current.stale() && !current.mine() )
				{
					result = new AcquireResult( false, true, refusalMessage( current ) );
					break;
				}

				int statusCode = writeLease( repoFullName, token, nickname, current.contentSha(),
						"Host lock acquired by " + nickname );
				if( statusCode == 200 || statusCode == 201 )
				{
					String how;
					if( !current.locked() )
						how = "GitHub host lock acquired.";
					else if( current.mine() )
						how = "Resumed this peer's own host lock.";
					else
						how = "Took over a stale host lock from " + current.hostNickname() + ".";
					result = new AcquireResult( true, false, how );
					break;
				}
				if( statusCode == 409 || statusCode == 422 )
				{
					// Otro peer ganó la carrera entre la lectura y el PUT: GitHub arbitró
					// por nosotros, así que releemos para nombrar al ganador de verdad
					Status winner = read( repoFullName, token, nickname );
					if( winner.locked() && !winner.mine() )
						result = new AcquireResult( false, true, refusalMessage( winner ) );
					else
						result = new AcquireResult( false, false, "The host lock changed while acquiring it. Try again." );
					break;
				}

				result = new AcquireResult( false, false, "GitHub rejected the host lock write (HTTP " + statusCode + ")." );
			}
			catch( Exception arbitrationFailure )
			{
				// Sin arbitraje no se arranca: dos servidores sobre el mismo mundo
				// corrompen el historial, y eso no tiene vuelta atrás
				app.Log.event( "HOST_LOCK", "No se pudo arbitrar el lock de host en " + repoFullName, arbitrationFailure );
				result = new AcquireResult( false, false,
						"GitHub could not be reached to arbitrate the host lock. The server was not started to avoid a hosting conflict." );
			}
		} while( false );
		return result;
	}

	/** Refresca el lease de este peer. Devuelve false cuando el lock ya no es nuestro. */
	public static boolean heartbeat( String repoFullName )
	{
		boolean result;
		do
		{
			try
			{
				Map<String, String> userData = TokenStore.getSavedUserData();
				String token = userData.get( "token" );
				String nickname = userData.get( "nickname" );

				Status current = read( repoFullName, token, nickname );
				if( !current.locked() || !current.mine() )
				{
					result = false;
					break;
				}

				int statusCode = writeLease( repoFullName, token, nickname, current.contentSha(),
						"Host lock heartbeat by " + nickname );
				result = statusCode == 200 || statusCode == 201;
			}
			catch( Exception heartbeatFailure )
			{
				// Un latido perdido no para el mundo: quedan dos antes de que el lease
				// caduque y otro peer pueda tomarlo
				app.Log.event( "HOST_LOCK", "Latido del lock fallido en " + repoFullName, heartbeatFailure );
				result = false;
			}
		} while( false );
		return result;
	}

	/**
	 * Borra el lease de este peer justo tras el backup de parada verificado, para
	 * que el rol de host quede libre al instante en vez de esperar a que caduque
	 * el último latido. Nunca borra el lock de otro peer.
	 */
	public static boolean release( String repoFullName )
	{
		boolean result;
		do
		{
			try
			{
				Map<String, String> userData = TokenStore.getSavedUserData();
				String token = userData.get( "token" );
				String nickname = userData.get( "nickname" );

				Status current = read( repoFullName, token, nickname );
				if( !current.locked() )
				{
					// Ya no hay nada que liberar: el objetivo se cumple igual
					result = true;
					break;
				}
				if( !current.mine() )
				{
					result = false;
					break;
				}

				ObjectNode body = GitUtils.JSON_MAPPER.createObjectNode()
						.put( "message", "Host lock released by " + nickname )
						.put( "sha", current.contentSha() )
						.put( "branch", LOCK_BRANCH );
				HttpRequest request = GitUtils.authenticatedRequest( contentsUrl( repoFullName ), token )
						.method( "DELETE", HttpRequest.BodyPublishers.ofString( body.toString() ) )
						.header( "Content-Type", "application/json" )
						.build();
				HttpResponse<String> response = GitUtils.HTTP_CLIENT.send( request, HttpResponse.BodyHandlers.ofString() );
				result = response.statusCode() == 200;
			}
			catch( Exception releaseFailure )
			{
				// Si no se pudo borrar, el lease caduca solo por falta de latidos
				app.Log.event( "HOST_LOCK", "No se pudo liberar el lock de host en " + repoFullName, releaseFailure );
				result = false;
			}
		} while( false );
		return result;
	}

	// ---- FASE 3 — Lectura del lease y frescura -----------------------------

	/** Estado del lock para mostrarlo en la interfaz, nunca para decidir si se hostea. */
	public static Status readStatus( String repoFullName )
	{
		Status result;
		try
		{
			Map<String, String> userData = TokenStore.getSavedUserData();
			result = read( repoFullName, userData.get( "token" ), userData.get( "nickname" ) );
		}
		catch( Exception readFailure )
		{
			// Default seguro para la vista: quien decide de verdad es acquire(), que
			// ante un fallo de red se niega a arrancar
			app.Log.event( "HOST_LOCK", "No se pudo leer el estado del lock en " + repoFullName, readFailure );
			result = Status.free();
		}
		return result;
	}

	static Status read( String repoFullName, String token, String myNickname ) throws Exception
	{
		Status result;
		do
		{
			HttpRequest request = GitUtils.authenticatedRequest( contentsUrl( repoFullName ) + "?ref=" + LOCK_BRANCH, token )
					.GET().build();
			HttpResponse<String> response = GitUtils.HTTP_CLIENT.send( request, HttpResponse.BodyHandlers.ofString() );
			if( response.statusCode() == 404 )
			{
				// Sin fichero de lock no hay host: nadie ha reclamado nunca este mundo
				result = Status.free();
				break;
			}
			// Un error distinto de 404 NO es "libre": propagarlo evita que dos peers
			// se crean host porque GitHub devolvio un 500
			if( response.statusCode() != 200 )
				throw new IllegalStateException( "Host lock read failed: HTTP " + response.statusCode() );

			JsonNode file = GitUtils.JSON_MAPPER.readTree( response.body() );
			String contentSha = file.path( "sha" ).asText( null );
			String encoded = file.path( "content" ).asText( "" ).replace( "\n", "" ).replace( "\r", "" );
			byte[] decoded = Base64.getDecoder().decode( encoded );
			JsonNode lease = GitUtils.JSON_MAPPER.readTree( new String( decoded, StandardCharsets.UTF_8 ) );

			String host = lease.path( "host_nickname" ).asText( null );
			long leaseSeconds = lease.path( "lease_seconds" ).asLong( DEFAULT_LEASE_SECONDS );
			if( leaseSeconds <= 0 )
				leaseSeconds = DEFAULT_LEASE_SECONDS;

			Heartbeat heartbeat = lastLockCommit( repoFullName, token );
			// Sin fecha de commit se considera caducado: mejor permitir el relevo que
			// dejar un mundo bloqueado para siempre por un lease ilegible
			boolean stale = heartbeat.commitDate() == null
					|| Duration.between( heartbeat.commitDate(), heartbeat.serverNow() ).getSeconds() > leaseSeconds;
			boolean mine = host != null && host.equals( myNickname );
			result = Status.held( mine, stale, host, heartbeat.commitDate(), leaseSeconds, contentSha,
					detailsFrom( lease ) );
		} while( false );
		return result;
	}

	private record Heartbeat( Instant commitDate, Instant serverNow )
	{
	}

	/** La edad del lease sale de la fecha de commit y la cabecera Date de GitHub, jamas del reloj local. */
	private static Heartbeat lastLockCommit( String repoFullName, String token ) throws Exception
	{
		Heartbeat result;
		do
		{
			String url = GitUtils.githubApiBase() + "/repos/" + encodedRepo( repoFullName )
					+ "/commits?path=" + LOCK_FILE_PATH + "&sha=" + LOCK_BRANCH + "&per_page=1";
			HttpRequest request = GitUtils.authenticatedRequest( url, token ).GET().build();
			HttpResponse<String> response = GitUtils.HTTP_CLIENT.send( request, HttpResponse.BodyHandlers.ofString() );

			// Si GitHub no manda Date caemos al reloj local: es el unico momento en
			// que se usa, y solo para no quedarnos sin referencia temporal
			Instant serverNow = response.headers().firstValue( "Date" )
					.map( date -> ZonedDateTime.parse( date, DateTimeFormatter.RFC_1123_DATE_TIME ).toInstant() )
					.orElse( Instant.now() );
			if( response.statusCode() != 200 )
			{
				result = new Heartbeat( null, serverNow );
				break;
			}

			JsonNode commits = GitUtils.JSON_MAPPER.readTree( response.body() );
			if( !commits.isArray() || commits.isEmpty() )
			{
				result = new Heartbeat( null, serverNow );
				break;
			}

			String date = commits.get( 0 ).path( "commit" ).path( "committer" ).path( "date" ).asText( null );
			result = new Heartbeat( date == null ? null : Instant.parse( date ), serverNow );
		} while( false );
		return result;
	}

	// ---- FASE 4 — Escritura del lease y rama del lock ----------------------

	private static int writeLease( String repoFullName, String token, String nickname,
			String expectedSha, String commitMessage ) throws Exception
	{
		ObjectNode lease = leaseJson( nickname, publishedDetails );
		String encodedLease = Base64.getEncoder().encodeToString( lease.toString().getBytes( StandardCharsets.UTF_8 ) );
		ObjectNode body = GitUtils.JSON_MAPPER.createObjectNode()
				.put( "message", commitMessage )
				.put( "content", encodedLease )
				.put( "branch", LOCK_BRANCH );
		// Mandar el sha esperado es lo que hace atomica la operacion: sin el, un PUT
		// ciego pisaria el lease que otro peer acaba de escribir
		if( expectedSha != null )
			body.put( "sha", expectedSha );

		HttpRequest request = GitUtils.authenticatedRequest( contentsUrl( repoFullName ), token )
				.PUT( HttpRequest.BodyPublishers.ofString( body.toString() ) )
				.header( "Content-Type", "application/json" )
				.build();
		return GitUtils.HTTP_CLIENT.send( request, HttpResponse.BodyHandlers.ofString() ).statusCode();
	}

	/** Cuerpo del lease. Visible para tests: es el contrato entre host e invitados. */
	static ObjectNode leaseJson( String nickname, HostDetails details )
	{
		ObjectNode lease = GitUtils.JSON_MAPPER.createObjectNode()
				.put( "host_nickname", nickname )
				.put( "machine", System.getProperty( "user.name", "unknown" ) )
				.put( "started_at", Instant.now().toString() )
				.put( "lease_seconds", DEFAULT_LEASE_SECONDS );
		// Campos opcionales: solo se escriben cuando hay dato, y los lectores
		// antiguos los ignoran — versiones mezcladas de peers conviven sin drama
		if( details.tunnelAddress() != null && !details.tunnelAddress().isBlank() )
			lease.put( "tunnel_address", details.tunnelAddress() );
		if( details.onlinePlayers() >= 0 )
			lease.put( "online_players", details.onlinePlayers() );
		if( details.maxPlayers() >= 0 )
			lease.put( "max_players", details.maxPlayers() );
		if( details.minecraftVersion() != null && !details.minecraftVersion().isBlank() )
			lease.put( "minecraft_version", details.minecraftVersion() );
		return lease;
	}

	/** Lado lector del contrato: tolera leases antiguos sin ninguno de los campos. */
	static HostDetails detailsFrom( JsonNode lease )
	{
		return new HostDetails(
				lease.path( "tunnel_address" ).asText( null ),
				lease.path( "online_players" ).asInt( -1 ),
				lease.path( "max_players" ).asInt( -1 ),
				lease.path( "minecraft_version" ).asText( null ) );
	}

	static boolean ensureLockBranch( String repoFullName, String token ) throws Exception
	{
		boolean result;
		do
		{
			String refUrl = GitUtils.githubApiBase() + "/repos/" + encodedRepo( repoFullName ) + "/git/ref/heads/" + LOCK_BRANCH;
			HttpResponse<String> existing = GitUtils.HTTP_CLIENT.send(
					GitUtils.authenticatedRequest( refUrl, token ).GET().build(), HttpResponse.BodyHandlers.ofString() );
			if( existing.statusCode() == 200 )
			{
				result = true;
				break;
			}
			// Solo un 404 significa "no existe todavia"; cualquier otro codigo es un
			// problema real y no se debe intentar crear la rama a ciegas
			if( existing.statusCode() != 404 )
			{
				result = false;
				break;
			}

			String repositoryUrl = GitUtils.githubApiBase() + "/repos/" + encodedRepo( repoFullName );
			HttpResponse<String> repository = GitUtils.HTTP_CLIENT.send(
					GitUtils.authenticatedRequest( repositoryUrl, token ).GET().build(),
					HttpResponse.BodyHandlers.ofString() );
			if( repository.statusCode() != 200 )
			{
				result = false;
				break;
			}
			String defaultBranch = GitUtils.JSON_MAPPER.readTree( repository.body() ).path( "default_branch" ).asText( null );
			if( defaultBranch == null )
			{
				result = false;
				break;
			}

			String baseRefUrl = repositoryUrl + "/git/ref/heads/" + GitUtils.encodePathSegment( defaultBranch );
			HttpResponse<String> baseRef = GitUtils.HTTP_CLIENT.send(
					GitUtils.authenticatedRequest( baseRefUrl, token ).GET().build(),
					HttpResponse.BodyHandlers.ofString() );
			if( baseRef.statusCode() != 200 )
			{
				result = false;
				break;
			}
			String baseSha = GitUtils.JSON_MAPPER.readTree( baseRef.body() ).path( "object" ).path( "sha" ).asText( null );
			if( baseSha == null )
			{
				result = false;
				break;
			}

			ObjectNode body = GitUtils.JSON_MAPPER.createObjectNode()
					.put( "ref", "refs/heads/" + LOCK_BRANCH )
					.put( "sha", baseSha );
			HttpResponse<String> created = GitUtils.HTTP_CLIENT.send(
					GitUtils.authenticatedRequest( repositoryUrl + "/git/refs", token )
							.POST( HttpRequest.BodyPublishers.ofString( body.toString() ) )
							.header( "Content-Type", "application/json" )
							.build(),
					HttpResponse.BodyHandlers.ofString() );
			// 422 = otro peer creó la rama en la misma carrera; para nosotros vale igual
			result = created.statusCode() == 201 || created.statusCode() == 422;
		} while( false );
		return result;
	}

	// ---- FASE 5 — Mensajes y URLs ------------------------------------------

	private static String refusalMessage( Status status )
	{
		String since = status.lastHeartbeat() == null ? "an unknown time" : status.lastHeartbeat().toString();
		return status.hostNickname() + " is already hosting this world (last heartbeat: " + since + ").";
	}

	private static String contentsUrl( String repoFullName )
	{
		return GitUtils.githubApiBase() + "/repos/" + encodedRepo( repoFullName ) + "/contents/" + LOCK_FILE_PATH;
	}

	/** Owner y nombre se codifican por separado: la barra que los une no debe escaparse. */
	private static String encodedRepo( String repoFullName )
	{
		int slash = repoFullName.indexOf( '/' );
		String owner = repoFullName.substring( 0, slash );
		String name = repoFullName.substring( slash + 1 );
		return GitUtils.encodePathSegment( owner ) + "/" + GitUtils.encodePathSegment( name );
	}
}
