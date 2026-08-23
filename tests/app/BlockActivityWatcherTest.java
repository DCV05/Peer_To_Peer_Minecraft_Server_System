package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * El ciclo entero: alguien rompe un bloque en el mundo, la aplicacion lo lee,
 * lo guarda y aparece pintado en el mapa.
 */
class BlockActivityWatcherTest
{
	@TempDir
	Path temporary;

	private Path repository;
	private Path world;

	@BeforeEach
	void setUp() throws Exception
	{
		System.setProperty( "endershare.dataDirectory", temporary.resolve( "data" ).toString() );
		repository = temporary.resolve( "farmland_mc" );
		world = Files.createDirectories( repository.resolve( "world" ) );
		Files.createDirectories( WorldMap.directoryFor( repository ).resolve( "web/maps/overworld/live" ) );
		createLedgerSchema();
	}

	@AfterEach
	void tearDown()
	{
		System.clearProperty( "endershare.dataDirectory" );
	}

	@Test
	void aBrokenBlockEndsUpStoredAndPaintedOnTheMap() throws Exception
	{
		List<BlockActivity> notified = new ArrayList<>();
		BlockActivityWatcher watcher = new BlockActivityWatcher( repository, world, notified::addAll );
		assertTrue( watcher.detectorInstalled() );

		recordBlockChange( 1, "block-break", 120, 64, -340 );
		watcher.tick();

		assertEquals( 1, notified.size(), "No ha llegado el aviso a la interfaz" );
		assertEquals( 1, watcher.activityLog().size(), "No se ha guardado" );
		JsonNode markers = markersOnTheMap();
		assertEquals( 1, markers.size(), "No se ha pintado en el mapa" );
		assertTrue( markers.elements().next().path( "label" ).asText().contains( "120, 64, -340" ) );
	}

	@Test
	void aQuietWorldCostsNothing() throws Exception
	{
		List<BlockActivity> notified = new ArrayList<>();
		BlockActivityWatcher watcher = new BlockActivityWatcher( repository, world, notified::addAll );

		watcher.tick();
		watcher.tick();

		assertTrue( notified.isEmpty() );
		// Sin novedades no se toca siquiera el fichero de marcadores
		assertFalse( Files.exists( markerFile() ), "Se esta reescribiendo el mapa sin que haya pasado nada" );
	}

	@Test
	void theMarkersComeBackAfterTheRendererWipesThem() throws Exception
	{
		BlockActivityWatcher watcher = new BlockActivityWatcher( repository, world, activity ->
		{
		} );
		recordBlockChange( 1, "block-break", 120, 64, -340 );
		watcher.tick();
		assertEquals( 1, markersOnTheMap().size() );

		// El renderizador guarda su estado cada dos minutos y al hacerlo pisa este
		// fichero con los suyos, que estan vacios
		Files.writeString( markerFile(), "{}" );
		watcher.tick();

		assertEquals( 1, markersOnTheMap().size(),
				"La actividad desapareceria del mapa cada dos minutos sin motivo aparente" );
	}

	@Test
	void theMarkersAreNotRepaintedWhileNothingHappens() throws Exception
	{
		BlockActivityWatcher watcher = new BlockActivityWatcher( repository, world, activity ->
		{
		} );
		recordBlockChange( 1, "block-break", 120, 64, -340 );
		watcher.tick();
		java.nio.file.attribute.FileTime before = java.nio.file.attribute.FileTime.fromMillis( 1_600_000_000_000L );
		Files.setLastModifiedTime( markerFile(), before );

		watcher.tick();
		watcher.tick();

		// La ventana visible dura una hora: sin esto se repinta entera cada cinco
		// segundos durante una hora despues del ultimo bloque tocado
		assertEquals( before, Files.getLastModifiedTime( markerFile() ),
				"Se esta repintando el mapa sin que haya pasado nada" );
	}

	@Test
	void whatWasAlreadyReadIsNotSentTwice() throws Exception
	{
		List<BlockActivity> notified = new ArrayList<>();
		BlockActivityWatcher watcher = new BlockActivityWatcher( repository, world, notified::addAll );
		recordBlockChange( 1, "block-break", 1, 2, 3 );
		watcher.tick();

		watcher.tick();
		recordBlockChange( 2, "block-place", 4, 5, 6 );
		watcher.tick();

		assertEquals( 2, notified.size(), "Se han vuelto a mandar sucesos ya vistos" );
	}

	@Test
	void whatHappenedBeforeTurningItOnIsNotReplayed() throws Exception
	{
		recordBlockChange( 1, "block-break", 1, 2, 3 );
		recordBlockChange( 2, "block-place", 4, 5, 6 );

		List<BlockActivity> notified = new ArrayList<>();
		BlockActivityWatcher watcher = new BlockActivityWatcher( repository, world, notified::addAll );
		watcher.start();
		try
		{
			watcher.tick();
		}
		finally
		{
			watcher.stop();
		}

		assertTrue( notified.isEmpty(), "Al encenderlo se volcaria de golpe todo el historial del mundo" );
	}

	@Test
	void aWorldWithoutTheModIsSimplyNotWatched() throws Exception
	{
		Path bare = Files.createDirectories( temporary.resolve( "otro" ).resolve( "world" ) );
		BlockActivityWatcher watcher = new BlockActivityWatcher( temporary.resolve( "otro" ), bare, activity ->
		{
		} );

		assertFalse( watcher.detectorInstalled() );
		watcher.tick();
	}

	// ---- utilidades ---------------------------------------------------------

	private JsonNode markersOnTheMap() throws Exception
	{
		return new ObjectMapper().readTree( Files.readString( markerFile() ) )
				.path( WorldMapMarkers.MARKER_SET_ID ).path( "markers" );
	}

	private Path markerFile()
	{
		return WorldMap.directoryFor( repository ).resolve( "web/maps/overworld/live/markers.json" );
	}

	private Connection open() throws Exception
	{
		return DriverManager.getConnection( "jdbc:sqlite:" + LedgerDatabase.databaseIn( world ) );
	}

	private void recordBlockChange( int id, String action, int x, int y, int z ) throws Exception
	{
		int actionId = "block-break".equals( action ) ? 1 : 2;
		try (Connection connection = open(); Statement statement = connection.createStatement())
		{
			// Con la hora actual: en el mapa solo se pinta lo reciente, asi que una
			// fecha fija dejaria de pintarse en cuanto el test envejeciera
			statement.executeUpdate( "INSERT INTO actions (id, action_id, time, x, y, z, world_id, object_id,"
					+ " old_object_id, source, player_id) VALUES (" + id + ", " + actionId
					+ ", datetime('now'), " + x + ", " + y + ", " + z + ", 1, 1, 1, 1, 1)" );
		}
	}

	private void createLedgerSchema() throws Exception
	{
		try (Connection connection = open(); Statement statement = connection.createStatement())
		{
			statement.executeUpdate( "CREATE TABLE players (id INTEGER PRIMARY KEY, player_id TEXT,"
					+ " player_name TEXT, first_join TEXT, last_join TEXT)" );
			statement.executeUpdate( "CREATE TABLE ActionIdentifiers (id INTEGER PRIMARY KEY, action_identifier TEXT)" );
			statement.executeUpdate( "CREATE TABLE ObjectIdentifiers (id INTEGER PRIMARY KEY, identifier TEXT)" );
			statement.executeUpdate( "CREATE TABLE worlds (id INTEGER PRIMARY KEY, identifier TEXT)" );
			statement.executeUpdate( "CREATE TABLE sources (id INTEGER PRIMARY KEY, name TEXT)" );
			statement.executeUpdate( "CREATE TABLE actions (id INTEGER PRIMARY KEY, action_id INTEGER, time TEXT,"
					+ " x INTEGER, y INTEGER, z INTEGER, world_id INTEGER, object_id INTEGER, old_object_id INTEGER,"
					+ " block_state TEXT, old_block_state TEXT, source INTEGER, player_id INTEGER, extra_data TEXT,"
					+ " rolled_back INTEGER DEFAULT 0)" );
			statement.executeUpdate( "INSERT INTO players VALUES (1, 'uuid-victor', 'Victor', '', '')" );
			statement.executeUpdate( "INSERT INTO ActionIdentifiers VALUES (1, 'block-break'), (2, 'block-place')" );
			statement.executeUpdate( "INSERT INTO ObjectIdentifiers VALUES (1, 'minecraft:obsidian')" );
			statement.executeUpdate( "INSERT INTO worlds VALUES (1, 'minecraft:overworld')" );
			statement.executeUpdate( "INSERT INTO sources VALUES (1, 'player')" );
		}
	}
}
