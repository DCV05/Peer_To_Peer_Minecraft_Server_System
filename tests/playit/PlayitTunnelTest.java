package playit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayitTunnelTest
{
	@TempDir
	Path temporaryDirectory;

	private HttpServer server;

	// Estado mutable del playit simulado
	private int claimSetupCalls = 0;
	private boolean claimAccepted = false;
	private boolean claimRejected = false;
	private int tunnelCreateCalls = 0;
	private boolean tunnelExists = false;
	private boolean rejectSecret = false;

	@BeforeEach
	void startApi() throws Exception
	{
		server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
		server.createContext( "/", this::handleRequest );
		server.start();
		System.setProperty( "p2pmss.playitApiBase", "http://127.0.0.1:" + server.getAddress().getPort() );
	}

	@AfterEach
	void stopApi()
	{
		if( server != null )
			server.stop( 0 );
		System.clearProperty( "p2pmss.playitApiBase" );
	}

	@Test
	void claimFlowExchangesTheCodeForTheSecretOnceAccepted()
	{
		claimAccepted = true;
		PlayitTunnel.ClaimOutcome outcome = PlayitTunnel.claimAgent( "aabbccddeeff0011", 30 );
		assertTrue( outcome.ok(), String.valueOf( outcome.error() ) );
		assertEquals( "secret-key-0123456789-0123456789-xx", outcome.secretKey() );
		// El primer sondeo devuelve WaitingForUserVisit: el canje exige 2a pasada
		assertTrue( claimSetupCalls >= 2 );
	}

	@Test
	void claimFailsClearlyWhenTheUserRejects()
	{
		claimRejected = true;
		PlayitTunnel.ClaimOutcome outcome = PlayitTunnel.claimAgent( "aabbccddeeff0011", 30 );
		assertFalse( outcome.ok() );
		assertTrue( outcome.error().contains( "rejected" ), outcome.error() );
	}

	@Test
	void ensureTunnelReusesTheExistingMinecraftTunnel() throws Exception
	{
		tunnelExists = true;
		String address = PlayitTunnel.ensureTunnel( "secret-key-0123456789-0123456789-xx" );
		assertEquals( "farmland.ply.gg:25565", address );
		assertEquals( 0, tunnelCreateCalls );
	}

	@Test
	void ensureTunnelCreatesOneWhenTheAccountHasNone() throws Exception
	{
		String address = PlayitTunnel.ensureTunnel( "secret-key-0123456789-0123456789-xx" );
		assertEquals( "farmland.ply.gg:25565", address );
		assertEquals( 1, tunnelCreateCalls );
	}

	@Test
	void ensureTunnelFailsClosedWhenPlayitRejectsTheSecret()
	{
		rejectSecret = true;
		IOException failure = assertThrows( IOException.class,
				() -> PlayitTunnel.ensureTunnel( "secret-key-0123456789-0123456789-xx" ) );
		assertTrue( failure.getMessage().contains( "invalid authentication" ), failure.getMessage() );
	}

	@Test
	void agentFileRoundTripsAndValidatesTheSecret() throws Exception
	{
		assertNull( PlayitAgentFile.load( temporaryDirectory ) );

		PlayitAgentFile agent = new PlayitAgentFile();
		agent.enabled = true;
		agent.secret_key = "secret-key-0123456789-0123456789-xx";
		agent.tunnel_address = "farmland.ply.gg:25565";
		agent.save( temporaryDirectory );

		PlayitAgentFile loaded = PlayitAgentFile.load( temporaryDirectory );
		assertNotNull( loaded );
		assertTrue( loaded.readyToStart() );
		assertEquals( "farmland.ply.gg:25565", loaded.tunnel_address );

		loaded.enabled = false;
		assertFalse( loaded.readyToStart() );
		loaded.enabled = true;
		loaded.secret_key = "corta";
		assertFalse( loaded.readyToStart() );
	}

	@Test
	void tcpBridgeClaimsAndProxiesBothDirections() throws Exception
	{
		byte[] claimToken = {1, 2, 3, 4};
		byte[] receivedToken = new byte[4];
		AtomicInteger closes = new AtomicInteger();

		try (ServerSocket playitSide = new ServerSocket( 0, 1, java.net.InetAddress.getLoopbackAddress() );
				ServerSocket minecraftSide = new ServerSocket( 0, 1, java.net.InetAddress.getLoopbackAddress() ))
		{

			Thread playitServer = new Thread( () ->
			{
				try (Socket connection = playitSide.accept())
				{
					connection.getInputStream().readNBytes( receivedToken, 0, 4 );
					connection.getOutputStream().write( new byte[8] ); // confirmacion del claim
					connection.getOutputStream().write( "PLAYER-HELLO".getBytes( StandardCharsets.UTF_8 ) );
					connection.getOutputStream().flush();
					byte[] reply = connection.getInputStream().readNBytes( "SERVER-REPLY".length() );
					assertEquals( "SERVER-REPLY", new String( reply, StandardCharsets.UTF_8 ) );
				}
				catch( IOException | AssertionError ignored )
				{
				}
			} );
			Thread minecraftServer = new Thread( () ->
			{
				try (Socket connection = minecraftSide.accept())
				{
					byte[] hello = connection.getInputStream().readNBytes( "PLAYER-HELLO".length() );
					assertEquals( "PLAYER-HELLO", new String( hello, StandardCharsets.UTF_8 ) );
					connection.getOutputStream().write( "SERVER-REPLY".getBytes( StandardCharsets.UTF_8 ) );
					connection.getOutputStream().flush();
				}
				catch( IOException | AssertionError ignored )
				{
				}
			} );
			playitServer.start();
			minecraftServer.start();

			Thread bridge = TcpBridge.open(
					new InetSocketAddress( "127.0.0.1", playitSide.getLocalPort() ),
					claimToken,
					minecraftSide.getLocalPort(),
					closes::incrementAndGet );

			playitServer.join( 10_000 );
			minecraftServer.join( 10_000 );
			bridge.join( 10_000 );
		}

		assertEquals( 1, receivedToken[0] );
		assertEquals( 4, receivedToken[3] );
		assertEquals( 1, closes.get(), "onClose debe ejecutarse exactamente una vez" );
	}

	private void handleRequest( HttpExchange exchange ) throws IOException
	{
		String path = exchange.getRequestURI().getPath();
		try
		{
			switch( path )
			{
				case "/claim/setup" ->
				{
					claimSetupCalls++;
					if( claimRejected )
					{
						respond( exchange, "{\"status\":\"success\",\"data\":\"UserRejected\"}" );
						return;
					}
					if( claimAccepted && claimSetupCalls >= 2 )
					{
						respond( exchange, "{\"status\":\"success\",\"data\":\"UserAccepted\"}" );
						return;
					}
					respond( exchange, "{\"status\":\"success\",\"data\":\"WaitingForUserVisit\"}" );
				}
				case "/claim/exchange" -> respond( exchange,
						"{\"status\":\"success\",\"data\":{\"secret_key\":\"secret-key-0123456789-0123456789-xx\"}}" );
				case "/v1/agents/rundata" ->
				{
					if( rejectSecret )
					{
						respond( exchange, 401, "{\"status\":\"error\",\"data\":\"unauthorized\"}" );
						return;
					}
					respond( exchange, "{\"status\":\"success\",\"data\":{\"agent_id\":\"agent-1\"}}" );
				}
				case "/v1/tunnels/list" ->
				{
					if( !tunnelExists )
					{
						respond( exchange, "{\"status\":\"success\",\"data\":{\"tunnels\":[]}}" );
						return;
					}
					respond( exchange, "{\"status\":\"success\",\"data\":{\"tunnels\":[{"
							+ "\"id\":\"t-1\",\"name\":\"P2PMSS\",\"tunnel_type\":\"minecraft-java\","
							+ "\"connect_addresses\":[{\"type\":\"auto\",\"value\":{\"address\":\"farmland.ply.gg:25565\"}}]}]}}" );
				}
				case "/v1/tunnels/create" ->
				{
					tunnelCreateCalls++;
					tunnelExists = true;
					respond( exchange, "{\"status\":\"success\",\"data\":{\"id\":\"t-1\"}}" );
				}
				default -> respond( exchange, 404, "{\"status\":\"error\",\"data\":\"unexpected " + path + "\"}" );
			}
		}
		finally
		{
			exchange.close();
		}
	}

	private void respond( HttpExchange exchange, String body ) throws IOException
	{
		respond( exchange, 200, body );
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
