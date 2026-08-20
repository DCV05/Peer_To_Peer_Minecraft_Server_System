package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class SelfUpdaterTest
{

	@TempDir
	Path temporaryDirectory;

	private HttpServer server;

	@BeforeEach
	void isolateDataDirectory()
	{
		System.setProperty( "p2pmss.dataDirectory", temporaryDirectory.resolve( "data" ).toString() );
	}

	@AfterEach
	void tearDown()
	{
		System.clearProperty( "p2pmss.dataDirectory" );
		if( server != null )
			server.stop( 0 );
	}

	private String serveInstaller( byte[] payload, int statusCode ) throws IOException
	{
		server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
		server.createContext( "/installer", exchange ->
		{
			exchange.sendResponseHeaders( statusCode, payload.length );
			try (OutputStream out = exchange.getResponseBody())
			{
				out.write( payload );
			}
		} );
		server.start();
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/installer";
	}

	@Test
	void downloadsTheInstallerAtomicallyIntoTheUpdatesFolder() throws Exception
	{
		byte[] payload = "fake-installer-bytes".getBytes( StandardCharsets.UTF_8 );
		String url = serveInstaller( payload, 200 );

		Path downloaded = SelfUpdater.downloadInstaller( url, "P2PMSS-9.9.9.exe" );

		assertTrue( Files.isRegularFile( downloaded ) );
		assertEquals( "P2PMSS-9.9.9.exe", downloaded.getFileName().toString() );
		assertEquals( "fake-installer-bytes", Files.readString( downloaded ) );
		// La descarga es atomica: no puede quedar un .part huerfano
		assertFalse( Files.exists( SelfUpdater.updatesDirectory().resolve( "P2PMSS-9.9.9.exe.part" ) ) );
	}

	@Test
	void failedDownloadThrowsAndLeavesNoPartialFile() throws Exception
	{
		String url = serveInstaller( "not found".getBytes( StandardCharsets.UTF_8 ), 404 );

		assertThrows( IOException.class, () -> SelfUpdater.downloadInstaller( url, "P2PMSS-9.9.9.exe" ) );
		assertFalse( Files.exists( SelfUpdater.updatesDirectory().resolve( "P2PMSS-9.9.9.exe" ) ) );
		assertFalse( Files.exists( SelfUpdater.updatesDirectory().resolve( "P2PMSS-9.9.9.exe.part" ) ) );
	}

	@Test
	void missingUrlThrowsInsteadOfDownloadingNothing()
	{
		assertThrows( IOException.class, () -> SelfUpdater.downloadInstaller( null, "P2PMSS-9.9.9.jar" ) );
	}

	@Test
	void installerFileNameKeepsThePlatformExtension()
	{
		assertEquals( "P2PMSS-1.8.0.exe", SelfUpdater.installerFileName( "https://example.test/x/P2PMSS-1.8.0.exe", "1.8.0" ) );
		assertEquals( "P2PMSS-1.8.0.dmg", SelfUpdater.installerFileName( "https://example.test/x/P2PMSS-1.8.0.DMG", "1.8.0" ) );
		assertEquals( "P2PMSS-1.8.0.jar", SelfUpdater.installerFileName( null, "1.8.0" ) );
	}

	@Test
	void launchingAMissingInstallerFailsGracefully()
	{
		assertFalse( SelfUpdater.launchInstaller( null ) );
		assertFalse( SelfUpdater.launchInstaller( temporaryDirectory.resolve( "no-existe.exe" ) ) );
	}
}
