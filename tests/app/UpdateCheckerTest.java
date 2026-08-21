package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class UpdateCheckerTest
{

	private static final String TEST_REPO = "someone/Some_Fork";

	private HttpServer server;

	@AfterEach
	void tearDown()
	{
		System.clearProperty( "p2pmss.githubApiBase" );
		System.clearProperty( "p2pmss.releasesRepo" );
		if( server != null )
			server.stop( 0 );
	}

	private void serveLatestRelease( String body, int statusCode ) throws Exception
	{
		server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
		server.createContext( "/repos/" + TEST_REPO + "/releases/latest", exchange ->
		{
			byte[] payload = body.getBytes( StandardCharsets.UTF_8 );
			exchange.sendResponseHeaders( statusCode, payload.length );
			try (OutputStream out = exchange.getResponseBody())
			{
				out.write( payload );
			}
		} );
		server.start();
		System.setProperty( "p2pmss.githubApiBase", "http://127.0.0.1:" + server.getAddress().getPort() );
		System.setProperty( "p2pmss.releasesRepo", TEST_REPO );
	}

	@Test
	void repoAndVersionComeFromTheBuildWithSystemPropertyOverride()
	{
		assertEquals( UpdateChecker.DEFAULT_RELEASES_REPO, UpdateChecker.releasesRepo() );
		System.setProperty( "p2pmss.releasesRepo", TEST_REPO );
		assertEquals( TEST_REPO, UpdateChecker.releasesRepo() );
		// La version bakeada por Maven debe existir y ser numerica, no el placeholder
		assertFalse( UpdateChecker.normalizeVersion( UpdateChecker.currentVersion() ).isEmpty() );
	}

	@Test
	void newerCompleteReleaseIsReportedWithThePlatformInstaller() throws Exception
	{
		serveLatestRelease( """
				{
				  "tag_name": "v99.0.0-p2p",
				  "html_url": "https://github.com/%s/releases/tag/v99.0.0-p2p",
				  "assets": [
				    { "name": "README.txt", "browser_download_url": "https://example.test/readme" },
				    { "name": "Endershare-99.0.0.jar", "browser_download_url": "https://example.test/app.jar" },
				    { "name": "Endershare-99.0.0.dmg", "browser_download_url": "https://example.test/app.dmg" },
				    { "name": "Endershare-99.0.0.exe", "browser_download_url": "https://example.test/app.exe" }
				  ]
				}
				""".formatted( TEST_REPO ), 200 );

		Optional<UpdateChecker.ReleaseInfo> release = UpdateChecker.findNewerRelease();
		assertTrue( release.isPresent() );
		assertEquals( "99.0.0", release.get().version() );
		// El test corre en mac (local) y linux (CI): la URL esperada es la del
		// primer instalador preferido por la plataforma actual
		String extension = UpdateChecker.preferredAssetExtensions( System.getProperty( "os.name", "" ) ).get( 0 );
		assertEquals( "https://example.test/app" + extension, release.get().downloadUrl() );
	}

	@Test
	void halfUploadedReleaseIsNotOfferedOnDesktopPlatforms() throws Exception
	{
		// Solo el jar subido (el CI aun compila el dmg/exe): en mac/windows el
		// chequeo debe callar y reintentar mas tarde, jamas instalar el jar
		serveLatestRelease( """
				{
				  "tag_name": "v99.0.0-p2p",
				  "assets": [ { "name": "Endershare-99.0.0.jar", "browser_download_url": "https://example.test/app.jar" } ]
				}
				""", 200 );

		String os = System.getProperty( "os.name", "" ).toLowerCase();
		Optional<UpdateChecker.ReleaseInfo> release = UpdateChecker.findNewerRelease();
		if( os.contains( "mac" ) || os.contains( "win" ) )
			assertTrue( release.isEmpty() );
		else
			assertTrue( release.isPresent() );
	}

	@Test
	void currentOrOlderReleaseIsIgnored() throws Exception
	{
		serveLatestRelease( "{ \"tag_name\": \"v" + UpdateChecker.currentVersion() + "-p2p\", \"assets\": [] }", 200 );
		assertTrue( UpdateChecker.findNewerRelease().isEmpty() );
	}

	@Test
	void apiFailureMeansNoUpdate() throws Exception
	{
		serveLatestRelease( "{ \"message\": \"rate limited\" }", 403 );
		assertTrue( UpdateChecker.findNewerRelease().isEmpty() );
	}

	@Test
	void releaseWithoutAPlatformInstallerIsNotOfferedYet() throws Exception
	{
		// El CI adjunta los instaladores minutos despues de publicarse la release:
		// sin asset compatible no hay oferta, y el siguiente chequeo la recogera
		serveLatestRelease( "{ \"tag_name\": \"v99.0.0-p2p\", \"assets\": [] }", 200 );
		assertTrue( UpdateChecker.findNewerRelease().isEmpty() );
	}

	@Test
	void malformedTagMeansNoUpdate() throws Exception
	{
		serveLatestRelease( "{ \"tag_name\": \"latest-build\", \"assets\": [] }", 200 );
		assertTrue( UpdateChecker.findNewerRelease().isEmpty() );
	}

	@Test
	void assetIsPickedByPlatformWithJarFallback() throws Exception
	{
		com.fasterxml.jackson.databind.JsonNode assets = new com.fasterxml.jackson.databind.ObjectMapper().readTree( """
				[
				  { "name": "P2PMSS-99.0.0.jar", "browser_download_url": "https://example.test/app.jar" },
				  { "name": "P2PMSS-99.0.0.dmg", "browser_download_url": "https://example.test/app.dmg" },
				  { "name": "P2PMSS-99.0.0.exe", "browser_download_url": "https://example.test/app.exe" }
				]
				""" );
		assertEquals( "https://example.test/app.dmg", UpdateChecker.pickDownloadUrl( assets, "Mac OS X" ) );
		assertEquals( "https://example.test/app.exe", UpdateChecker.pickDownloadUrl( assets, "Windows 11" ) );
		assertEquals( "https://example.test/app.jar", UpdateChecker.pickDownloadUrl( assets, "Linux" ) );

		// Release a medio subir (solo el jar): en mac/windows NO se ofrece nada —
		// el jar suelto dejaba la app instalada vieja; el siguiente chequeo la
		// recogera cuando el instalador nativo este colgado
		com.fasterxml.jackson.databind.JsonNode onlyJar = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
				"[ { \"name\": \"P2PMSS-99.0.0.jar\", \"browser_download_url\": \"https://example.test/app.jar\" } ]" );
		assertEquals( null, UpdateChecker.pickDownloadUrl( onlyJar, "Mac OS X" ) );
		assertEquals( null, UpdateChecker.pickDownloadUrl( onlyJar, "Windows 11" ) );
		assertEquals( "https://example.test/app.jar", UpdateChecker.pickDownloadUrl( onlyJar, "Linux" ) );
	}

	@Test
	void versionNormalizationAndComparison()
	{
		assertEquals( "1.7.1", UpdateChecker.normalizeVersion( "v1.7.1-p2p" ) );
		assertEquals( "2.0", UpdateChecker.normalizeVersion( "V2.0" ) );
		assertEquals( "", UpdateChecker.normalizeVersion( "nightly" ) );
		assertTrue( UpdateChecker.isNewer( "1.7.2", "1.7.1" ) );
		assertTrue( UpdateChecker.isNewer( "1.8", "1.7.9" ) );
		assertTrue( UpdateChecker.isNewer( "1.7.1.1", "1.7.1" ) );
		assertFalse( UpdateChecker.isNewer( "1.7.1", "1.7.1" ) );
		assertFalse( UpdateChecker.isNewer( "1.7.0", "1.7.1" ) );
	}
}
