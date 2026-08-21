package e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * GitHub falso para los tests E2E: implementa, con la misma semantica que la
 * API real, los unicos endpoints que la app usa — el fichero del host-lock por
 * Contents API (con el check de sha que hace atomico el arbitraje), las refs de
 * la rama del lock y la fecha de commit del ultimo latido.
 *
 * <p>Con esto, el flujo completo host/invitado se prueba en cada push del CI
 * sin red, sin cuenta y sin cuota: la app apunta aqui via la system property
 * {@code p2pmss.githubApiBase} que ya existe para tests.</p>
 */
public final class MockGitHub implements AutoCloseable
{

	// ---- FASE 1 — Estado por repositorio -----------------------------------

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final class RepoState
	{
		final String defaultBranch = "master";
		final Set<String> branches = new HashSet<>( Set.of( "master" ) );
		String lockContentBase64;
		String lockSha;
		Instant lockCommittedAt;
		int shaCounter;
		final Map<String, String> eventFiles = new java.util.LinkedHashMap<>();
		int eventEtagVersion;
	}

	private final Map<String, RepoState> repositories = new HashMap<>();
	private final HttpServer server;

	private MockGitHub( HttpServer server )
	{
		this.server = server;
	}

	public static MockGitHub start() throws IOException
	{
		HttpServer server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
		MockGitHub github = new MockGitHub( server );
		server.createContext( "/", github::handle );
		server.start();
		return github;
	}

	public String baseUrl()
	{
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	public synchronized void registerRepository( String repoFullName )
	{
		repositories.put( repoFullName, new RepoState() );
	}

	/** Para asserts: el JSON del lease publicado tal y como lo veria un peer. */
	public synchronized JsonNode currentLease( String repoFullName ) throws IOException
	{
		RepoState repo = repositories.get( repoFullName );
		if( repo == null || repo.lockContentBase64 == null )
			return null;
		byte[] decoded = java.util.Base64.getDecoder().decode( repo.lockContentBase64.replace( "\n", "" ) );
		return JSON.readTree( new String( decoded, StandardCharsets.UTF_8 ) );
	}

	@Override
	public void close()
	{
		server.stop( 0 );
	}

	// ---- FASE 2 — Enrutado -------------------------------------------------

	private synchronized void handle( HttpExchange exchange ) throws IOException
	{
		try
		{
			String path = exchange.getRequestURI().getPath();
			String method = exchange.getRequestMethod();
			if( !path.startsWith( "/repos/" ) )
			{
				respond( exchange, 404, "{\"message\":\"Not Found\"}" );
				return;
			}
			String[] parts = path.substring( "/repos/".length() ).split( "/", 3 );
			if( parts.length < 2 )
			{
				respond( exchange, 404, "{\"message\":\"Not Found\"}" );
				return;
			}
			String repoName = parts[0] + "/" + parts[1];
			RepoState repo = repositories.get( repoName );
			if( repo == null )
			{
				respond( exchange, 404, "{\"message\":\"Not Found\"}" );
				return;
			}
			String rest = parts.length == 3 ? parts[2] : "";

			if( rest.isEmpty() && "GET".equals( method ) )
			{
				respond( exchange, 200, "{\"default_branch\":\"" + repo.defaultBranch + "\"}" );
			}
			else if( rest.startsWith( "git/ref/heads/" ) && "GET".equals( method ) )
			{
				String branch = rest.substring( "git/ref/heads/".length() );
				if( repo.branches.contains( branch ) )
					respond( exchange, 200, "{\"object\":{\"sha\":\"base-" + branch + "\"}}" );
				else
					respond( exchange, 404, "{\"message\":\"Not Found\"}" );
			}
			else if( "git/refs".equals( rest ) && "POST".equals( method ) )
			{
				JsonNode body = JSON.readTree( exchange.getRequestBody() );
				String ref = body.path( "ref" ).asText( "" ).replace( "refs/heads/", "" );
				// 422 si ya existe: identico a la carrera real entre dos peers
				if( repo.branches.add( ref ) )
					respond( exchange, 201, "{\"ref\":\"refs/heads/" + ref + "\"}" );
				else
					respond( exchange, 422, "{\"message\":\"Reference already exists\"}" );
			}
			else if( "contents/p2pmss/host-lock.json".equals( rest ) )
			{
				handleLockContents( exchange, method, repo );
			}
			else if( "contents/p2pmss/events".equals( rest ) && "GET".equals( method ) )
			{
				handleEventsListing( exchange, repo );
			}
			else if( rest.startsWith( "contents/p2pmss/events/" ) )
			{
				handleEventFile( exchange, method, repo, rest.substring( "contents/p2pmss/events/".length() ) );
			}
			else if( "commits".equals( rest ) && "GET".equals( method ) )
			{
				ArrayNode commits = JSON.createArrayNode();
				if( repo.lockCommittedAt != null )
				{
					ObjectNode commit = commits.addObject();
					commit.putObject( "commit" ).putObject( "committer" )
							.put( "date", repo.lockCommittedAt.toString() );
				}
				respond( exchange, 200, commits.toString() );
			}
			else if( "releases/latest".equals( rest ) && "GET".equals( method ) )
			{
				respond( exchange, 404, "{\"message\":\"Not Found\"}" );
			}
			else
			{
				respond( exchange, 404, "{\"message\":\"Not Found\"}" );
			}
		}
		finally
		{
			exchange.close();
		}
	}

	// ---- FASE 3 — Contents API del lock con semantica de sha ---------------

	private void handleLockContents( HttpExchange exchange, String method, RepoState repo ) throws IOException
	{
		switch( method )
		{
			case "GET" ->
			{
				if( repo.lockContentBase64 == null )
					respond( exchange, 404, "{\"message\":\"Not Found\"}" );
				else
				{
					ObjectNode file = JSON.createObjectNode()
							.put( "sha", repo.lockSha )
							.put( "content", repo.lockContentBase64 );
					respond( exchange, 200, file.toString() );
				}
			}
			case "PUT" ->
			{
				JsonNode body = JSON.readTree( exchange.getRequestBody() );
				String expectedSha = body.path( "sha" ).asText( null );
				// El check de sha ES el arbitraje: pisar sin el sha correcto se
				// rechaza igual que en la API real
				if( repo.lockContentBase64 != null && expectedSha == null )
				{
					respond( exchange, 422, "{\"message\":\"sha required\"}" );
					return;
				}
				if( repo.lockContentBase64 != null && !repo.lockSha.equals( expectedSha ) )
				{
					respond( exchange, 409, "{\"message\":\"sha mismatch\"}" );
					return;
				}
				boolean creation = repo.lockContentBase64 == null;
				repo.lockContentBase64 = body.path( "content" ).asText( "" );
				repo.lockSha = "lock-sha-" + (++repo.shaCounter);
				repo.lockCommittedAt = Instant.now();
				respond( exchange, creation ? 201 : 200, "{\"content\":{\"sha\":\"" + repo.lockSha + "\"}}" );
			}
			case "DELETE" ->
			{
				JsonNode body = JSON.readTree( exchange.getRequestBody() );
				String expectedSha = body.path( "sha" ).asText( null );
				if( repo.lockContentBase64 == null )
				{
					respond( exchange, 404, "{\"message\":\"Not Found\"}" );
					return;
				}
				if( !repo.lockSha.equals( expectedSha ) )
				{
					respond( exchange, 409, "{\"message\":\"sha mismatch\"}" );
					return;
				}
				repo.lockContentBase64 = null;
				repo.lockSha = null;
				repo.lockCommittedAt = null;
				respond( exchange, 200, "{}" );
			}
			default -> respond( exchange, 404, "{\"message\":\"Not Found\"}" );
		}
	}

	// ---- Canal de eventos: listado con ETag + fichero-por-evento -----------

	private void handleEventsListing( HttpExchange exchange, RepoState repo ) throws IOException
	{
		if( repo.eventFiles.isEmpty() )
		{
			respond( exchange, 404, "{\"message\":\"Not Found\"}" );
			return;
		}
		// El ETag reproduce lo esencial de la API real: sin cambios, 304 y a otra cosa
		String etag = "\"events-" + repo.eventEtagVersion + "\"";
		String requested = exchange.getRequestHeaders().getFirst( "If-None-Match" );
		exchange.getResponseHeaders().add( "ETag", etag );
		if( etag.equals( requested ) )
		{
			exchange.sendResponseHeaders( 304, -1 );
			return;
		}
		ArrayNode listing = JSON.createArrayNode();
		for( Map.Entry<String, String> file : repo.eventFiles.entrySet() )
		{
			listing.addObject().put( "name", file.getKey() ).put( "sha", file.getValue() );
		}
		respond( exchange, 200, listing.toString() );
	}

	private void handleEventFile( HttpExchange exchange, String method, RepoState repo, String fileName ) throws IOException
	{
		switch( method )
		{
			case "PUT" ->
			{
				JSON.readTree( exchange.getRequestBody() );
				String sha = "event-sha-" + (++repo.shaCounter);
				repo.eventFiles.put( fileName, sha );
				repo.eventEtagVersion++;
				respond( exchange, 201, "{\"content\":{\"sha\":\"" + sha + "\"}}" );
			}
			case "DELETE" ->
			{
				JsonNode body = JSON.readTree( exchange.getRequestBody() );
				String expectedSha = body.path( "sha" ).asText( null );
				String currentSha = repo.eventFiles.get( fileName );
				if( currentSha == null )
					respond( exchange, 404, "{\"message\":\"Not Found\"}" );
				else if( !currentSha.equals( expectedSha ) )
					respond( exchange, 409, "{\"message\":\"sha mismatch\"}" );
				else
				{
					repo.eventFiles.remove( fileName );
					repo.eventEtagVersion++;
					respond( exchange, 200, "{}" );
				}
			}
			default -> respond( exchange, 404, "{\"message\":\"Not Found\"}" );
		}
	}

	private static void respond( HttpExchange exchange, int status, String body ) throws IOException
	{
		byte[] bytes = body.getBytes( StandardCharsets.UTF_8 );
		exchange.getResponseHeaders().set( "Content-Type", "application/json" );
		exchange.sendResponseHeaders( status, bytes.length );
		exchange.getResponseBody().write( bytes );
	}
}
