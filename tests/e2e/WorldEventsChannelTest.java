package e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jgit.TokenStore;
import jgit.WorldEvents;

/**
 * El canal de eventos sobre GitHub ("websockets a partir de commits") contra el
 * mock de la API: publicar, listar con dedupe/ETag y podar lo propio viejo.
 * Cada test usa un repo con nombre propio: el estado estatico del canal (ETags
 * y dedupe) queda aislado sin ganchos especiales.
 */
class WorldEventsChannelTest
{
	@TempDir
	Path temporaryDirectory;

	@AfterEach
	void tearDown()
	{
		TokenStore.invalidateSession();
		System.clearProperty( "p2pmss.dataDirectory" );
		System.clearProperty( "p2pmss.githubApiBase" );
	}

	@Test
	void publishesFetchesOnceAndSeesOtherPeersEvents() throws Exception
	{
		try (MockGitHub github = MockGitHub.start())
		{
			System.setProperty( "p2pmss.githubApiBase", github.baseUrl() );
			System.setProperty( "p2pmss.dataDirectory",
					Files.createDirectories( temporaryDirectory.resolve( "data" ) ).toString() );
			assertTrue( TokenStore.saveUserData( "hoster", "hoster@example.test", "local-token" ) );
			String repo = "team/events-flow";
			github.registerRepository( repo );

			assertTrue( WorldEvents.publish( repo, "host_started" ) );
			List<WorldEvents.WorldEvent> first = WorldEvents.fetchNew( repo );
			assertEquals( 1, first.size() );
			assertEquals( "host_started", first.get( 0 ).type() );
			assertEquals( "hoster", first.get( 0 ).nick() );

			// Dedupe + ETag: sin novedades, la siguiente consulta llega vacia
			assertTrue( WorldEvents.fetchNew( repo ).isEmpty() );

			// Otro peer publica y SOLO lo nuevo aparece
			TokenStore.invalidateSession();
			assertTrue( TokenStore.saveUserData( "guest", "guest@example.test", "guest-token" ) );
			assertTrue( WorldEvents.publish( repo, "want_to_play" ) );
			List<WorldEvents.WorldEvent> second = WorldEvents.fetchNew( repo );
			assertEquals( 1, second.size() );
			assertEquals( "want_to_play", second.get( 0 ).type() );
			assertEquals( "guest", second.get( 0 ).nick() );
		}
	}

	@Test
	void prunesOwnEventsOlderThanAnHourOnPublish() throws Exception
	{
		try (MockGitHub github = MockGitHub.start())
		{
			System.setProperty( "p2pmss.githubApiBase", github.baseUrl() );
			System.setProperty( "p2pmss.dataDirectory",
					Files.createDirectories( temporaryDirectory.resolve( "data" ) ).toString() );
			assertTrue( TokenStore.saveUserData( "hoster", "hoster@example.test", "local-token" ) );
			String repo = "team/events-prune";
			github.registerRepository( repo );

			// Un evento propio ANTIGUO plantado a mano en el mock, y uno ajeno igual
			// de viejo que la poda no debe tocar jamas
			HttpClient client = HttpClient.newHttpClient();
			for( String stale : new String[]{"1000-hoster-host_started.json", "1000-guest-host_started.json"} )
			{
				HttpRequest plant = HttpRequest.newBuilder()
						.uri( URI.create( github.baseUrl() + "/repos/" + repo + "/contents/p2pmss/events/" + stale ) )
						.PUT( HttpRequest.BodyPublishers.ofString( "{\"content\":\"e30=\"}" ) )
						.build();
				assertEquals( 201, client.send( plant, HttpResponse.BodyHandlers.ofString() ).statusCode() );
			}

			assertTrue( WorldEvents.publish( repo, "host_stopped" ) );

			HttpRequest listing = HttpRequest.newBuilder()
					.uri( URI.create( github.baseUrl() + "/repos/" + repo + "/contents/p2pmss/events" ) )
					.GET().build();
			String remaining = client.send( listing, HttpResponse.BodyHandlers.ofString() ).body();
			assertFalse( remaining.contains( "1000-hoster-host_started.json" ) );
			assertTrue( remaining.contains( "1000-guest-host_started.json" ) );
			assertTrue( remaining.contains( "-hoster-host_stopped.json" ) );
		}
	}
}
