package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Los dos canales tienen que ser estancos.
 *
 * <p>Los ficheros de ejemplo llevan instalador de las TRES plataformas a
 * proposito: el update checker elige el asset segun el sistema donde corre, y
 * un ejemplo con solo dmg y exe pasaba en el portatil y fallaba en el CI, que
 * es Linux y busca el jar.</p>
 *
 * <p>Si una instalacion estable se traga una compilacion de pruebas, alguien
 * que solo queria jugar acaba con una version a medio hacer y sin forma obvia
 * de volver atras. Y al reves: una instalacion de pruebas que salte a la
 * estable se "actualiza" hacia atras y pierde justo lo que se estaba
 * probando.</p>
 */
class UpdateChannelTest
{
	private static final String TEST_REPO = "someone/Some_Fork";

	private HttpServer server;

	@AfterEach
	void tearDown()
	{
		System.clearProperty( "p2pmss.githubApiBase" );
		System.clearProperty( "p2pmss.releasesRepo" );
		System.clearProperty( "p2pmss.channel" );
		if( server != null )
			server.stop( 0 );
	}

	@Test
	void aBuildIsStableUnlessItSaysOtherwise()
	{
		assertEquals( UpdateChecker.STABLE_CHANNEL, UpdateChecker.currentChannel() );

		System.setProperty( "p2pmss.channel", "dev" );
		assertEquals( UpdateChecker.DEV_CHANNEL, UpdateChecker.currentChannel() );
	}

	@Test
	void aStableInstallAsksForTheLatestFinalReleaseOnly() throws Exception
	{
		AtomicReference<String> requestedPath = new AtomicReference<>();
		serve( requestedPath, """
				{
				  "tag_name": "v99.0.0",
				  "html_url": "https://example.test/release",
				  "assets": [ { "name": "Endershare-99.0.0.dmg", "browser_download_url": "https://example.test/a.dmg" },
				              { "name": "Endershare-99.0.0.exe", "browser_download_url": "https://example.test/a.exe" },
				              { "name": "Endershare-99.0.0.jar", "browser_download_url": "https://example.test/a.jar" } ]
				}
				""" );

		UpdateChecker.findNewerRelease();

		assertTrue( requestedPath.get().contains( "/releases/latest" ),
				"El canal estable debe pedir /latest, que por definicion nunca es una preliminar: " + requestedPath.get() );
	}

	@Test
	void aDevInstallOnlyTakesPreReleases() throws Exception
	{
		System.setProperty( "p2pmss.channel", "dev" );
		AtomicReference<String> requestedPath = new AtomicReference<>();
		// La definitiva es mas nueva, pero una instalacion de pruebas no debe cogerla
		serve( requestedPath, """
				[
				  { "tag_name": "v99.9.9", "prerelease": false, "draft": false,
				    "html_url": "https://example.test/stable",
				    "assets": [ { "name": "Endershare-99.9.9.dmg", "browser_download_url": "https://example.test/stable.dmg" },
				                { "name": "Endershare-99.9.9.exe", "browser_download_url": "https://example.test/stable.exe" },
				                { "name": "Endershare-99.9.9.jar", "browser_download_url": "https://example.test/stable.jar" } ] },
				  { "tag_name": "v99.0.1", "prerelease": true, "draft": false,
				    "html_url": "https://example.test/dev",
				    "assets": [ { "name": "Endershare-99.0.1.dmg", "browser_download_url": "https://example.test/dev.dmg" },
				                { "name": "Endershare-99.0.1.exe", "browser_download_url": "https://example.test/dev.exe" },
				                { "name": "Endershare-99.0.1.jar", "browser_download_url": "https://example.test/dev.jar" } ] }
				]
				""" );

		Optional<UpdateChecker.ReleaseInfo> release = UpdateChecker.findNewerRelease();

		assertTrue( requestedPath.get().contains( "/releases" ) );
		assertFalse( requestedPath.get().contains( "/releases/latest" ) );
		assertTrue( release.isPresent() );
		assertEquals( "99.0.1", release.get().version(),
				"Una instalacion de pruebas ha saltado a la version estable" );
	}

	@Test
	void aDevInstallWithNothingNewToTryStaysWhereItIs() throws Exception
	{
		System.setProperty( "p2pmss.channel", "dev" );
		serve( new AtomicReference<>(), """
				[ { "tag_name": "v99.9.9", "prerelease": false, "draft": false, "html_url": "https://example.test/s",
				    "assets": [ { "name": "Endershare-99.9.9.dmg", "browser_download_url": "https://example.test/s.dmg" },
				    { "name": "Endershare-99.9.9.jar", "browser_download_url": "https://example.test/s.jar" } ] } ]
				""" );

		assertTrue( UpdateChecker.findNewerRelease().isEmpty() );
	}

	@Test
	void draftsAreNotOfferedToAnybody()
	{
		com.fasterxml.jackson.databind.JsonNode releases = parse( """
				[ { "tag_name": "v98.0.0", "prerelease": true, "draft": true },
				  { "tag_name": "v97.0.0", "prerelease": true, "draft": false } ]
				""" );

		com.fasterxml.jackson.databind.JsonNode chosen = UpdateChecker.newestPreRelease( releases );

		assertEquals( "v97.0.0", chosen.path( "tag_name" ).asText(),
				"Un borrador no esta publicado: nadie deberia poder instalarlo" );
	}

	@Test
	void theHighestVersionWinsEvenIfItIsNotFirstInTheList()
	{
		// GitHub ordena por fecha de creacion: una republicacion deja delante una vieja
		com.fasterxml.jackson.databind.JsonNode releases = parse( """
				[ { "tag_name": "v1.8.5.10", "prerelease": true, "draft": false },
				  { "tag_name": "v1.8.5.42", "prerelease": true, "draft": false },
				  { "tag_name": "v1.8.5.7",  "prerelease": true, "draft": false } ]
				""" );

		assertEquals( "v1.8.5.42", UpdateChecker.newestPreRelease( releases ).path( "tag_name" ).asText() );
	}

	@Test
	void withoutAnyPreReleaseThereIsNothingToOffer()
	{
		assertEquals( null, UpdateChecker.newestPreRelease( parse( """
				[ { "tag_name": "v1.0.0", "prerelease": false, "draft": false } ]
				""" ) ) );
	}

	// ---- utilidades ---------------------------------------------------------

	private static com.fasterxml.jackson.databind.JsonNode parse( String json )
	{
		try
		{
			return new com.fasterxml.jackson.databind.ObjectMapper().readTree( json );
		}
		catch( Exception malformed )
		{
			throw new IllegalStateException( malformed );
		}
	}

	private void serve( AtomicReference<String> requestedPath, String body ) throws Exception
	{
		server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
		server.createContext( "/repos/" + TEST_REPO, exchange ->
		{
			requestedPath.set( exchange.getRequestURI().toString() );
			byte[] payload = body.getBytes( StandardCharsets.UTF_8 );
			exchange.sendResponseHeaders( 200, payload.length );
			try (OutputStream out = exchange.getResponseBody())
			{
				out.write( payload );
			}
		} );
		server.start();
		System.setProperty( "p2pmss.githubApiBase", "http://127.0.0.1:" + server.getAddress().getPort() );
		System.setProperty( "p2pmss.releasesRepo", TEST_REPO );
	}
}
