package jgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubApiTest
{
	@TempDir
	Path temporaryDirectory;

	private HttpServer server;
	private final AtomicReference<String> scenario = new AtomicReference<>( "created" );
	private final AtomicReference<String> receivedContentType = new AtomicReference<>();
	private final AtomicReference<String> receivedRepositoryBody = new AtomicReference<>();
	private final AtomicReference<String> localRemoteUrl = new AtomicReference<>();
	private final AtomicInteger repositoryCreateRequests = new AtomicInteger();

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
		System.clearProperty( "endershare.githubApiBase" );
		System.clearProperty( "endershare.dataDirectory" );
		System.clearProperty( "endershare.gitCommitBatchBytes" );
	}

	@Test
	void acceptsOnlyCreatedRepositoryResponsesWithCloneUrl()
	{
		scenario.set( "created" );
		GitUtils.GitHubRepositoryResult result = GitUtils.createRepoInGitHub( "test-token", "server" );

		assertTrue( result.success() );
		assertFalse( result.existing() );
		assertEquals( 201, result.statusCode() );
		assertEquals( "https://github.example/hoster/server.git", result.cloneUrl() );
		assertTrue( receivedContentType.get().startsWith( "application/json" ) );

		scenario.set( "malformed" );
		result = GitUtils.createRepoInGitHub( "test-token", "server" );
		assertFalse( result.success() );
		assertEquals( 201, result.statusCode() );
	}

	@Test
	void recoversAnExistingRepositoryAfterValidationResponse()
	{
		scenario.set( "existing" );
		GitUtils.GitHubRepositoryResult result = GitUtils.createRepoInGitHub( "test-token", "server" );

		assertTrue( result.success() );
		assertTrue( result.existing() );
		assertEquals( "https://github.example/hoster/server.git", result.cloneUrl() );
	}

	@Test
	void unauthorizedResponseInvalidatesStoredSession()
	{
		scenario.set( "unauthorized" );
		GitUtils.GitHubRepositoryResult result = GitUtils.createRepoInGitHub( "test-token", "server" );

		assertFalse( result.success() );
		assertEquals( 401, result.statusCode() );
		assertFalse( TokenStore.sessionIsOpened() );
	}

	@Test
	void handlesInviteListAndAcceptanceResponses()
	{
		scenario.set( "invitations" );
		assertTrue( GitUtils.inviteHostingUser( "test-token", "hoster", "server", "guest" ) );

		List<Map<String, Object>> invitations = GitUtils.getAllPendingInvitations();
		assertNotNull( invitations );
		assertEquals( 1, invitations.size() );
		assertEquals( 7, ((Number) invitations.get( 0 ).get( "id" )).intValue() );
		assertTrue( GitUtils.acceptInvitationById( 7 ) );
	}

	@Test
	void preflightRejectsAnOversizedWorldBeforeCreatingRemoteOrLocalRepository() throws Exception
	{
		Path serverDirectory = Files.createDirectories( temporaryDirectory.resolve( "oversized-server/world/region" ) );
		Path oversized = serverDirectory.resolve( "r.0.0.mca" );
		try (RandomAccessFile sparse = new RandomAccessFile( oversized.toFile(), "rw" ))
		{
			sparse.setLength( GitBackupPreflight.MAX_GITHUB_FILE_BYTES + 1 );
		}

		GitUtils.PrivateBackupSetupResult result = GitUtils.configurePrivateBackup(
				temporaryDirectory.resolve( "oversized-server" ), "oversized-server" );

		assertFalse( result.success() );
		assertTrue( result.message().contains( "over 100 MiB" ) );
		assertEquals( 0, repositoryCreateRequests.get() );
		assertFalse( Files.exists( temporaryDirectory.resolve( "oversized-server/.git" ) ) );
	}

	@Test
	void createsPrivateRemoteAndUploadsEveryInitialBatchEndToEnd() throws Exception
	{
		System.setProperty( "endershare.gitCommitBatchBytes", "24" );
		Path remote = temporaryDirectory.resolve( "api-created-remote.git" );
		try (Git ignored = Git.init().setBare( true ).setDirectory( remote.toFile() ).call())
		{
		}
		localRemoteUrl.set( remote.toUri().toString() );
		scenario.set( "local-created" );

		Path regions = Files.createDirectories( temporaryDirectory.resolve( "api-server/world/region" ) );
		Path serverRoot = temporaryDirectory.resolve( "api-server" );
		Files.writeString( serverRoot.resolve( "server.properties" ), "server-port=25565\n" );
		Files.writeString( serverRoot.resolve( "user_jvm_args.txt" ), "-Xmx2G\n" );
		for( int index = 0; index < 6; index++ )
		{
			Files.writeString( regions.resolve( "r." + index + ".mca" ), "region-payload-" + index + "-abcdefghij\n" );
		}

		GitUtils.PrivateBackupSetupResult result = GitUtils.configurePrivateBackup( serverRoot, "api-server" );

		assertTrue( result.success(), result.message() );
		assertEquals( 1, repositoryCreateRequests.get() );
		assertTrue( receivedRepositoryBody.get().contains( "\"private\":true" ) );
		assertTrue( GitUtils.hasRemoteOrigin( serverRoot ) );

		Path verifier = temporaryDirectory.resolve( "api-verifier" );
		try (Git git = Git.cloneRepository().setURI( remote.toUri().toString() ).setDirectory( verifier.toFile() ).call())
		{
			int commits = 0;
			for( var commit : git.log().call() )
			{
				commits++;
				assertFalse( commit.getFullMessage().contains( "after clean server stop" ) );
			}
			assertTrue( commits > 2 );
		}
		assertTrue( Files.exists( verifier.resolve( "world/region/r.5.mca" ) ) );
	}

	private void handleRequest( HttpExchange exchange ) throws IOException
	{
		String path = exchange.getRequestURI().getPath();
		String method = exchange.getRequestMethod();
		String currentScenario = scenario.get();

		if( path.equals( "/user/repos" ) && method.equals( "POST" ) )
		{
			repositoryCreateRequests.incrementAndGet();
			receivedContentType.set( exchange.getRequestHeaders().getFirst( "Content-Type" ) );
			receivedRepositoryBody.set( new String( exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8 ) );
			switch( currentScenario )
			{
				case "created" -> send( exchange, 201, "{\"clone_url\":\"https://github.example/hoster/server.git\"}" );
				case "local-created" -> send( exchange, 201, "{\"clone_url\":\"" + localRemoteUrl.get() + "\"}" );
				case "malformed" -> send( exchange, 201, "{\"name\":\"server\"}" );
				case "existing" -> send( exchange, 422, "{\"message\":\"Validation Failed\"}" );
				case "unauthorized" -> send( exchange, 401, "{\"message\":\"Bad credentials\"}" );
				default -> send( exchange, 500, "{\"message\":\"Unexpected scenario\"}" );
			}
			return;
		}

		if( path.equals( "/repos/hoster/server" ) && method.equals( "GET" ) && currentScenario.equals( "existing" ) )
		{
			send( exchange, 200, "{\"clone_url\":\"https://github.example/hoster/server.git\"}" );
			return;
		}

		if( currentScenario.equals( "invitations" ) )
		{
			if( path.equals( "/repos/hoster/server/collaborators/guest" ) && method.equals( "PUT" ) )
			{
				send( exchange, 201, "{}" );
				return;
			}
			if( path.equals( "/user/repository_invitations" ) && method.equals( "GET" ) )
			{
				send( exchange, 200, "[{\"id\":7,\"repository\":{\"full_name\":\"owner/server\"}}]" );
				return;
			}
			if( path.equals( "/user/repository_invitations/7" ) && method.equals( "PATCH" ) )
			{
				send( exchange, 204, "" );
				return;
			}
		}

		send( exchange, 404, "{\"message\":\"Not found\"}" );
	}

	private static void send( HttpExchange exchange, int status, String body ) throws IOException
	{
		byte[] bytes = body.getBytes( StandardCharsets.UTF_8 );
		exchange.getResponseHeaders().set( "Content-Type", "application/json" );
		if( status == 204 )
		{
			exchange.sendResponseHeaders( status, -1 );
		}
		else
		{
			exchange.sendResponseHeaders( status, bytes.length );
			exchange.getResponseBody().write( bytes );
		}
		exchange.close();
	}
}
