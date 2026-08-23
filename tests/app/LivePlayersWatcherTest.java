package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * El renderizador y nosotros escribimos en el mismo fichero de jugadores, y el
 * suyo siempre pone <code>{}</code>. Estos tests fijan que nuestra version
 * vuelve, porque ese <code>{}</code> no solo borra los muñecos: si el visor se
 * abre en ese instante, apaga los jugadores hasta que alguien recargue.
 */
class LivePlayersWatcherTest
{
	@TempDir
	Path temporary;

	private static final ObjectMapper JSON = new ObjectMapper();

	private Path repository;
	private Path world;

	@BeforeEach
	void setUp() throws Exception
	{
		System.setProperty( "endershare.dataDirectory", temporary.resolve( "data" ).toString() );
		// Los tests no salen a internet a por las caras de nadie
		System.setProperty( "endershare.skinDownloads", "off" );
		repository = temporary.resolve( "farmland_mc" );
		world = Files.createDirectories( repository.resolve( "world" ) );
		Files.createDirectories( WorldMap.directoryFor( repository ).resolve( "web/maps/overworld/live" ) );
	}

	@AfterEach
	void tearDown()
	{
		System.clearProperty( "endershare.dataDirectory" );
		System.clearProperty( "endershare.skinDownloads" );
	}

	@Test
	void whatTheScriptPublishesEndsUpOnTheMap() throws Exception
	{
		publish( """
				[{"uuid":"abc","name":"Victor","x":10,"y":64,"z":-20,"yaw":90,"pitch":0,
				  "health":20,"dimension":"minecraft:overworld"}]
				""" );
		LivePlayersWatcher watcher = new LivePlayersWatcher( repository, world, players ->
		{
		} );

		watcher.tick();

		JsonNode players = playersOnTheMap();
		assertEquals( 1, players.size() );
		assertEquals( "Victor", players.get( 0 ).path( "name" ).asText() );
	}

	@Test
	void ourVersionComesBackAfterTheRendererWipesIt() throws Exception
	{
		publish( """
				[{"uuid":"abc","name":"Victor","x":10,"y":64,"z":-20,"yaw":90,"pitch":0,
				  "health":20,"dimension":"minecraft:overworld"}]
				""" );
		LivePlayersWatcher watcher = new LivePlayersWatcher( repository, world, players ->
		{
		} );
		watcher.tick();

		// Esto es literalmente lo que escribe el renderizador al guardar su estado
		Files.writeString( playersFile(), "{}" );
		watcher.tick();

		assertTrue( playersOnTheMap().isArray(), "El visor apagaria los jugadores hasta recargar la pagina" );
		assertEquals( 1, playersOnTheMap().size() );
	}

	@Test
	void withNobodyConnectedTheListIsEmptyButExiste() throws Exception
	{
		LivePlayersWatcher watcher = new LivePlayersWatcher( repository, world, players ->
		{
		} );

		watcher.tick();

		JsonNode root = JSON.readTree( Files.readString( playersFile() ) );
		assertTrue( root.has( "players" ), "Sin la lista, el visor deja de pedir jugadores: " + root );
		assertEquals( 0, root.path( "players" ).size() );
	}

	@Test
	void stoppingLeavesTheMapWithoutPlayers() throws Exception
	{
		publish( """
				[{"uuid":"abc","name":"Victor","x":10,"y":64,"z":-20,"yaw":90,"pitch":0,
				  "health":20,"dimension":"minecraft:overworld"}]
				""" );
		LivePlayersWatcher watcher = new LivePlayersWatcher( repository, world, players ->
		{
		} );
		watcher.tick();

		watcher.stop();

		assertEquals( 0, playersOnTheMap().size(), "Los jugadores se quedarian clavados en el mapa" );
	}

	// ---- utilidades ---------------------------------------------------------

	private void publish( String json ) throws Exception
	{
		Path file = world.resolve( "scripts" ).resolve( LivePlayers.SCRIPT_NAME + ".data" ).resolve( "players.json" );
		Files.createDirectories( file.getParent() );
		Files.writeString( file, json );
	}

	private Path playersFile()
	{
		return WorldMap.directoryFor( repository ).resolve( "web/maps/overworld/live/players.json" );
	}

	private JsonNode playersOnTheMap() throws Exception
	{
		return JSON.readTree( Files.readString( playersFile() ) ).path( "players" );
	}
}
