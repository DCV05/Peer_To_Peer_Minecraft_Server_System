package e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.eclipse.jgit.api.Git;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jgit.GitUtils;
import jgit.HostLock;
import jgit.TokenStore;
import minecraftServerManagement.FabricInstaller;
import minecraftServerManagement.ForgeUtils;
import minecraftServerManagement.PlayerPresenceTracker;
import view.MainFrame;

/**
 * El flujo entero con un servidor de Minecraft REAL y un JUGADOR real (bot de
 * protocolo): instalar con nuestro instalador, arrancar con nuestro comando,
 * detectar el "Done" con nuestro detector, ver al jugador entrar y salir con
 * nuestro contador de presencia, cerrar con backup y liberar el candado.
 *
 * <p>Etiquetado {@code e2e-mc}: no corre en el {@code mvn test} rapido porque
 * descarga el server de Fabric y tarda minutos. Se lanza con
 * {@code mvn -Pe2e-mc test} (job propio del CI).</p>
 */
@Tag("e2e-mc")
class MinecraftServerE2ETest
{

	// La pareja version de Minecraft <-> version de MCProtocolLib (pom) van
	// juntas: si se sube una hay que subir la otra
	private static final String MINECRAFT_VERSION = "1.21.7";
	private static final String FABRIC_LOADER_VERSION = "0.19.3";
	private static final String BOT_NAME = "BotVictor";

	@TempDir
	Path temporaryDirectory;

	private Process serverProcess;
	private MockGitHub github;

	/**
	 * La carpeta de datos se aisla ANTES que nada.
	 *
	 * <p>Se aislaba dentro del ensayo. Si algo fallaba antes de esa linea, el
	 * {@code tearDown} se ejecutaba igual y su
	 * {@link TokenStore#invalidateSession()} borraba la sesion de GitHub <b>de
	 * verdad</b> de quien estuviera corriendo los tests.</p>
	 */
	@BeforeEach
	void isolateTheDataDirectory() throws Exception
	{
		System.setProperty( "endershare.dataDirectory",
				java.nio.file.Files.createDirectories( temporaryDirectory.resolve( "data" ) ).toString() );
	}

	@AfterEach
	void tearDown() throws Exception
	{
		if( serverProcess != null && serverProcess.isAlive() )
		{
			serverProcess.destroy();
			serverProcess.waitFor( 30, TimeUnit.SECONDS );
			if( serverProcess.isAlive() )
				serverProcess.destroyForcibly();
		}
		TokenStore.invalidateSession();
		System.clearProperty( "endershare.dataDirectory" );
		System.clearProperty( "endershare.githubApiBase" );
		HostLock.clearPublishedDetails();
		MainFrame.serverOpenedDirectory = null;
		if( github != null )
			github.close();
	}

	@Test
	void fullHostingLifecycleWithARealServerAndARealPlayer() throws Exception
	{
		// ---- Identidad y GitHub falso --------------------------------------
		github = MockGitHub.start();
		System.setProperty( "endershare.githubApiBase", github.baseUrl() );
		System.setProperty( "endershare.dataDirectory", temporaryDirectory.resolve( "data" ).toString() );
		assertTrue( TokenStore.saveUserData( "hostA", "hostA@example.test", "token-hostA" ) );
		String worldRepo = "dcv/e2e-world";
		github.registerRepository( worldRepo );

		// ---- Instalar el server con NUESTRO instalador ---------------------
		Path server = Files.createDirectories( temporaryDirectory.resolve( "server" ) );
		FabricInstaller.installServerChecked( server, MINECRAFT_VERSION, FABRIC_LOADER_VERSION );
		assertTrue( ForgeUtils.acceptEULA( server ) );

		int port = freePort();
		// Mundo plano, distancia corta y sin bosses: el arranque en CI baja de
		// minutos a segundos y el test no depende de la potencia del runner
		Files.writeString( server.resolve( "server.properties" ), """
				server-port=%d
				online-mode=false
				level-type=minecraft\\:flat
				generate-structures=false
				view-distance=4
				simulation-distance=4
				spawn-protection=0
				max-players=4
				motd=Endershare E2E
				""".formatted( port ) );

		// ---- Enlazar el backup y coger el candado, como el flujo real ------
		Path remote = temporaryDirectory.resolve( "remote.git" );
		try (Git ignored = Git.init().setBare( true ).setDirectory( remote.toFile() ).call())
		{
		}
		assertTrue( GitUtils.createRepoInPath( server ) );
		assertTrue( GitUtils.linkLocalRepoToExternal( remote.toUri().toString(), "unused", server ) );
		assertTrue( HostLock.acquire( worldRepo ).acquired() );

		// ---- Arrancar con NUESTRO comando y NUESTRO detector de "Done" -----
		serverProcess = ForgeUtils.executeMinecraftServer( server );
		assertTrue( serverProcess != null && serverProcess.isAlive(), "El server de Fabric no arranco" );

		PlayerPresenceTracker presence = new PlayerPresenceTracker();
		presence.reset( 4 );
		CountDownLatch ready = new CountDownLatch( 1 );
		StringBuilder console = new StringBuilder();
		Thread consolePump = new Thread( () ->
		{
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader( serverProcess.getInputStream(), StandardCharsets.UTF_8 ) ))
			{
				String line;
				while( (line = reader.readLine()) != null )
				{
					synchronized( console )
					{
						console.append( line ).append( '\n' );
					}
					presence.acceptLine( line );
					if( ForgeUtils.isServerReadyLine( line ) )
						ready.countDown();
				}
			}
			catch( Exception endOfStream )
			{
				// El stream muere con el proceso: fin normal del bombeo
			}
		}, "e2e-console-pump" );
		consolePump.setDaemon( true );
		consolePump.start();

		assertTrue( ready.await( 240, TimeUnit.SECONDS ), "El server no llego a Done. Consola:\n" + consoleTail( console ) );

		// ---- Publicar la foto del host como hace la app --------------------
		HostLock.publishDetails( new HostLock.HostDetails( "127.0.0.1:" + port,
				presence.snapshot().onlineCount(), presence.snapshot().maxPlayers(), MINECRAFT_VERSION ) );
		assertTrue( HostLock.heartbeat( worldRepo ) );

		// ---- Un JUGADOR real entra por protocolo ---------------------------
		MinecraftProtocol protocol = new MinecraftProtocol( BOT_NAME );
		ClientSession bot = ClientNetworkSessionFactory.factory()
				.setRemoteSocketAddress( new InetSocketAddress( "127.0.0.1", port ) )
				.setProtocol( protocol )
				.create();
		bot.connect();

		assertTrue( waitFor( () -> presence.snapshot().onlineCount() == 1, 60 ),
				"El jugador no llego a entrar. Consola:\n" + consoleTail( console ) );
		assertTrue( presence.snapshot().players().contains( BOT_NAME ) );

		// ---- El jugador se va y la presencia lo refleja --------------------
		bot.disconnect( "bye" );
		assertTrue( waitFor( () -> presence.snapshot().onlineCount() == 0, 60 ),
				"La salida del jugador no se detecto. Consola:\n" + consoleTail( console ) );

		// ---- Parada limpia, backup y candado libre -------------------------
		try (BufferedWriter stdin = new BufferedWriter(
				new OutputStreamWriter( serverProcess.getOutputStream(), StandardCharsets.UTF_8 ) ))
		{
			stdin.write( "stop\n" );
			stdin.flush();
		}
		assertTrue( serverProcess.waitFor( 120, TimeUnit.SECONDS ), "El server no paro con stop" );
		assertEquals( 0, serverProcess.exitValue() );
		assertTrue( Files.isDirectory( server.resolve( "world" ) ), "El server no genero el mundo" );

		MainFrame.serverOpenedDirectory = server.toFile();
		assertTrue( GitUtils.autoCommitAndPush( true ), "El backup de cierre fallo" );
		assertTrue( HostLock.release( worldRepo ) );
		assertFalse( HostLock.readStatus( worldRepo ).locked() );
	}

	private static int freePort() throws Exception
	{
		try (ServerSocket socket = new ServerSocket( 0 ))
		{
			return socket.getLocalPort();
		}
	}

	private static boolean waitFor( BooleanSupplier condition, long timeoutSeconds ) throws InterruptedException
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( timeoutSeconds );
		while( System.nanoTime() < deadline )
		{
			if( condition.getAsBoolean() )
				return true;
			Thread.sleep( 500 );
		}
		return condition.getAsBoolean();
	}

	private static String consoleTail( StringBuilder console )
	{
		synchronized( console )
		{
			String all = console.toString();
			String[] lines = all.split( "\n" );
			int from = Math.max( 0, lines.length - 40 );
			return String.join( "\n", java.util.Arrays.copyOfRange( lines, from, lines.length ) );
		}
	}
}
