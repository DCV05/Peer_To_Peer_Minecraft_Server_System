package jgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostLockTest
{
	private static final String REPO = "hoster/farmland";

	@TempDir
	Path temporaryDirectory;

	private HttpServer server;

	// Estado mutable del GitHub simulado
	private boolean lockBranchExists = true;
	private String lockContent = null;
	private String lockSha = null;
	private Instant lockCommitDate = null;
	private boolean forcePutConflict = false;
	private int refCreations = 0;
	private int shaCounter = 0;

	@BeforeEach
	void startApi() throws Exception
	{
		System.setProperty( "endershare.dataDirectory", temporaryDirectory.resolve( "data" ).toString() );
		assertTrue( TokenStore.saveUserData( "hoster", "hoster@example.test", "test-token" ) );

		server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
		server.createContext( "/", this::handleRequest );
		server.start();
		System.setProperty( "endershare.githubApiBase", "http://127.0.0.1:" + server.getAddress().getPort() );
	}

	@AfterEach
	void stopApi()
	{
		if( server != null )
			server.stop( 0 );
		TokenStore.invalidateSession();
		System.clearProperty( "endershare.dataDirectory" );
		System.clearProperty( "endershare.githubApiBase" );
	}

	private void placeLock( String hostNickname, Instant commitDate ) throws Exception
	{
		lockContent = GitUtils.JSON_MAPPER.createObjectNode()
				.put( "host_nickname", hostNickname )
				.put( "machine", "test-machine" )
				.put( "started_at", commitDate.toString() )
				.put( "lease_seconds", HostLock.DEFAULT_LEASE_SECONDS )
				.toString();
		lockSha = "sha-" + (++shaCounter);
		lockCommitDate = commitDate;
	}

	@Test
	void acquiresTheLockWhenFree()
	{
		HostLock.AcquireResult result = HostLock.acquire( REPO );
		assertTrue( result.acquired(), result.message() );
		assertNotNull( lockContent );
		assertTrue( lockContent.contains( "\"host_nickname\":\"hoster\"" ) );
	}

	@Test
	void createsTheLockBranchWhenMissing()
	{
		lockBranchExists = false;
		HostLock.AcquireResult result = HostLock.acquire( REPO );
		assertTrue( result.acquired(), result.message() );
		assertEquals( 1, refCreations );
	}

	@Test
	void refusesWhenAnotherHostHoldsAFreshLock() throws Exception
	{
		placeLock( "OtherHost", Instant.now() );
		HostLock.AcquireResult result = HostLock.acquire( REPO );
		assertFalse( result.acquired() );
		assertTrue( result.blockedByPeer() );
		assertTrue( result.message().contains( "OtherHost" ), result.message() );
	}

	@Test
	void takesOverAStaleLock() throws Exception
	{
		placeLock( "OtherHost", Instant.now().minusSeconds( HostLock.DEFAULT_LEASE_SECONDS * 4 ) );
		HostLock.AcquireResult result = HostLock.acquire( REPO );
		assertTrue( result.acquired(), result.message() );
		assertTrue( result.message().contains( "OtherHost" ) );
		assertTrue( lockContent.contains( "\"host_nickname\":\"hoster\"" ) );
	}

	@Test
	void resumesItsOwnFreshLockAfterACrash() throws Exception
	{
		placeLock( "hoster", Instant.now() );
		HostLock.AcquireResult result = HostLock.acquire( REPO );
		assertTrue( result.acquired(), result.message() );
	}

	@Test
	void reportsTheWinnerWhenThePutRaceIsLost() throws Exception
	{
		forcePutConflict = true;
		// La relectura tras el conflicto debe encontrar al ganador de la carrera
		placeLock( "RaceWinner", Instant.now() );
		HostLock.AcquireResult result = HostLock.acquire( REPO );
		assertFalse( result.acquired() );
		assertTrue( result.blockedByPeer() );
		assertTrue( result.message().contains( "RaceWinner" ), result.message() );
	}

	@Test
	void heartbeatRefreshesOnlyItsOwnLock() throws Exception
	{
		placeLock( "hoster", Instant.now() );
		String shaBefore = lockSha;
		assertTrue( HostLock.heartbeat( REPO ) );
		assertFalse( shaBefore.equals( lockSha ) );

		placeLock( "OtherHost", Instant.now() );
		assertFalse( HostLock.heartbeat( REPO ) );
	}

	@Test
	void releaseDeletesOnlyItsOwnLock() throws Exception
	{
		placeLock( "hoster", Instant.now() );
		assertTrue( HostLock.release( REPO ) );
		assertNull( lockContent );

		placeLock( "OtherHost", Instant.now() );
		assertFalse( HostLock.release( REPO ) );
		assertNotNull( lockContent );
	}

	@Test
	void failsClosedWhenGitHubIsUnreachable()
	{
		System.setProperty( "endershare.githubApiBase", "http://127.0.0.1:1" );
		HostLock.AcquireResult result = HostLock.acquire( REPO );
		assertFalse( result.acquired() );
		assertFalse( result.blockedByPeer() );
	}

	private void handleRequest( HttpExchange exchange ) throws IOException
	{
		String path = exchange.getRequestURI().getPath();
		String method = exchange.getRequestMethod();
		try
		{
			if( path.equals( "/repos/" + REPO + "/git/ref/heads/" + HostLock.LOCK_BRANCH ) && method.equals( "GET" ) )
			{
				if( lockBranchExists )
					respond( exchange, 200, "{\"object\":{\"sha\":\"branch-sha\"}}" );
				else
					respond( exchange, 404, "{\"message\":\"Not Found\"}" );
				return;
			}
			if( path.equals( "/repos/" + REPO ) && method.equals( "GET" ) )
			{
				respond( exchange, 200, "{\"default_branch\":\"main\"}" );
				return;
			}
			if( path.equals( "/repos/" + REPO + "/git/ref/heads/main" ) && method.equals( "GET" ) )
			{
				respond( exchange, 200, "{\"object\":{\"sha\":\"main-sha\"}}" );
				return;
			}
			if( path.equals( "/repos/" + REPO + "/git/refs" ) && method.equals( "POST" ) )
			{
				refCreations++;
				lockBranchExists = true;
				respond( exchange, 201, "{\"ref\":\"refs/heads/" + HostLock.LOCK_BRANCH + "\"}" );
				return;
			}
			if( path.equals( "/repos/" + REPO + "/contents/" + HostLock.LOCK_FILE_PATH ) )
			{
				handleContents( exchange, method );
				return;
			}
			if( path.equals( "/repos/" + REPO + "/commits" ) && method.equals( "GET" ) )
			{
				if( lockCommitDate == null )
				{
					respond( exchange, 200, "[]" );
					return;
				}
				respond( exchange, 200, "[{\"commit\":{\"committer\":{\"date\":\"" + lockCommitDate + "\"}}}]" );
				return;
			}
			respond( exchange, 404, "{\"message\":\"unexpected " + method + " " + path + "\"}" );
		}
		finally
		{
			exchange.close();
		}
	}

	private void handleContents( HttpExchange exchange, String method ) throws IOException
	{
		if( method.equals( "GET" ) )
		{
			if( lockContent == null )
			{
				respond( exchange, 404, "{\"message\":\"Not Found\"}" );
				return;
			}
			String encoded = Base64.getEncoder().encodeToString( lockContent.getBytes( StandardCharsets.UTF_8 ) );
			respond( exchange, 200, "{\"sha\":\"" + lockSha + "\",\"content\":\"" + encoded + "\"}" );
			return;
		}
		JsonNode body = GitUtils.JSON_MAPPER.readTree( new String( exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8 ) );
		String expectedSha = body.path( "sha" ).asText( null );
		if( method.equals( "PUT" ) )
		{
			if( forcePutConflict )
			{
				respond( exchange, 409, "{\"message\":\"conflict\"}" );
				return;
			}
			if( lockContent != null && !lockSha.equals( expectedSha ) )
			{
				respond( exchange, 409, "{\"message\":\"sha mismatch\"}" );
				return;
			}
			if( lockContent == null && expectedSha != null )
			{
				respond( exchange, 409, "{\"message\":\"sha for missing file\"}" );
				return;
			}
			lockContent = new String( Base64.getDecoder().decode( body.path( "content" ).asText() ), StandardCharsets.UTF_8 );
			lockSha = "sha-" + (++shaCounter);
			lockCommitDate = Instant.now();
			respond( exchange, lockContent == null ? 201 : 200, "{\"content\":{\"sha\":\"" + lockSha + "\"}}" );
			return;
		}
		if( method.equals( "DELETE" ) )
		{
			if( lockContent == null || !lockSha.equals( expectedSha ) )
			{
				respond( exchange, 409, "{\"message\":\"sha mismatch\"}" );
				return;
			}
			lockContent = null;
			lockSha = null;
			lockCommitDate = null;
			respond( exchange, 200, "{\"content\":null}" );
			return;
		}
		respond( exchange, 405, "{\"message\":\"method\"}" );
	}

	private void respond( HttpExchange exchange, int status, String body ) throws IOException
	{
		byte[] bytes = body.getBytes( StandardCharsets.UTF_8 );
		exchange.getResponseHeaders().set( "Content-Type", "application/json" );
		exchange.sendResponseHeaders( status, bytes.length );
		try (OutputStream out = exchange.getResponseBody())
		{
			out.write( bytes );
		}
	}
}
