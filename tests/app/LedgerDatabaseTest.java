package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lectura de la base del mod que registra los bloques.
 *
 * <p>Los tests montan una base con el MISMO esquema que crea Ledger 1.2.5
 * (tomado de su codigo fuente). Si el esquema real cambiara, el mundo seguiria
 * funcionando pero la actividad dejaria de aparecer en silencio, y eso es justo
 * lo que estos tests tienen que cazar.</p>
 */
class LedgerDatabaseTest
{
	@TempDir
	Path temporary;

	@Test
	void readsWhoPlacedAndBrokeEachBlock() throws Exception
	{
		Path world = createWorldWithLedger();

		List<BlockActivity> activity = LedgerDatabase.readNew( world, 0 );

		assertEquals( 2, activity.size() );
		BlockActivity broken = activity.get( 0 );
		assertEquals( "Victor", broken.player() );
		assertEquals( BlockActivity.BROKEN, broken.action() );
		assertEquals( "minecraft:obsidian", broken.block() );
		assertEquals( 120, broken.x() );
		assertEquals( 64, broken.y() );
		assertEquals( -340, broken.z() );
		assertTrue( broken.describe().contains( "Victor broke obsidian at 120, 64, -340" ), broken.describe() );

		assertTrue( activity.get( 1 ).wasPlaced() );
	}

	@Test
	void onlyBlockChangesAreRead() throws Exception
	{
		Path world = createWorldWithLedger();
		try (Connection connection = open( world ); Statement statement = connection.createStatement())
		{
			// Ledger apunta cientos de tipos de suceso; el mapa solo pinta bloques
			statement.executeUpdate( "INSERT INTO ActionIdentifiers (id, action_identifier) VALUES (3, 'item-insert')" );
			statement.executeUpdate( "INSERT INTO actions (id, action_id, time, x, y, z, world_id, object_id,"
					+ " old_object_id, source, player_id) VALUES (3, 3, '2026-08-22 20:00:00', 1, 2, 3, 1, 1, 1, 1, 1)" );
		}

		List<BlockActivity> activity = LedgerDatabase.readNew( world, 0 );

		assertEquals( 2, activity.size(), "Se ha colado un suceso que no es un cambio de bloque" );
	}

	@Test
	void nothingIsReadTwice() throws Exception
	{
		Path world = createWorldWithLedger();

		List<BlockActivity> first = LedgerDatabase.readNew( world, 0 );
		List<BlockActivity> second = LedgerDatabase.readNew( world, first.get( first.size() - 1 ).id() );

		assertTrue( second.isEmpty(), "Se repetirian los mismos sucesos en cada pasada" );
	}

	@Test
	void theHourIsReadAsUtcAndNotAsLocalTime() throws Exception
	{
		Path world = createWorldWithLedger();
		try (java.sql.Connection connection = open( world );
				java.sql.Statement statement = connection.createStatement())
		{
			statement.executeUpdate( "INSERT INTO actions (id, action_id, time, x, y, z, world_id, object_id,"
					+ " old_object_id, source, player_id) VALUES (9, 1, datetime('now'), 1, 2, 3, 1, 1, 1, 1, 1)" );
		}

		BlockActivity justNow = LedgerDatabase.readNew( world, 8 ).get( 0 );

		long secondsOff = Math.abs( java.time.Duration.between( justNow.at(), java.time.Instant.now() ).toSeconds() );
		// El mod guarda en UTC; leerlo en la zona del ordenador restaba dos horas
		// en España y dejaba toda la actividad fuera de la ventana de lo reciente,
		// asi que el mapa no pintaba nunca nada
		assertTrue( secondsOff < 60, "Lo que acaba de pasar parece de hace " + secondsOff + " segundos" );
	}

	@Test
	void aWorldWithoutTheModIsNotAnError()
	{
		Path world = temporary.resolve( "sin-mod" );

		assertFalse( LedgerDatabase.isInstalledIn( world ) );
		assertTrue( LedgerDatabase.readNew( world, 0 ).isEmpty() );
		assertTrue( LedgerDatabase.lastId( world ).isEmpty() );
	}

	@Test
	void theCursorStartsAtWhatIsAlreadyThere() throws Exception
	{
		Path world = createWorldWithLedger();

		assertEquals( 2L, LedgerDatabase.lastId( world ).orElseThrow(),
				"Sin esto, al encender el seguimiento se reproduciria el historial entero" );
	}

	@Test
	void aReadNeverBringsBackMoreThanItsLimit() throws Exception
	{
		Path world = createWorldWithLedger();
		try (Connection connection = open( world ); Statement statement = connection.createStatement())
		{
			for( int index = 3; index < LedgerDatabase.MAX_ROWS_PER_READ + 50; index++ )
				statement.executeUpdate( "INSERT INTO actions (id, action_id, time, x, y, z, world_id, object_id,"
						+ " old_object_id, source, player_id) VALUES (" + index + ", 1, '2026-08-22 20:00:00', 1, 2, 3,"
						+ " 1, 1, 1, 1, 1)" );
		}

		List<BlockActivity> activity = LedgerDatabase.readNew( world, 0 );

		assertEquals( LedgerDatabase.MAX_ROWS_PER_READ, activity.size(),
				"Una racha de minado se traeria media base de golpe" );
	}

	// ---- utilidades ---------------------------------------------------------

	private Connection open( Path world ) throws Exception
	{
		return DriverManager.getConnection( "jdbc:sqlite:" + LedgerDatabase.databaseIn( world ) );
	}

	/** Esquema identico al de Ledger 1.2.5. */
	private Path createWorldWithLedger() throws Exception
	{
		Path world = Files.createDirectories( temporary.resolve( "world" ) );
		try (Connection connection = open( world ); Statement statement = connection.createStatement())
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
			statement.executeUpdate( "INSERT INTO ObjectIdentifiers VALUES (1, 'minecraft:obsidian'),"
					+ " (2, 'minecraft:oak_planks')" );
			statement.executeUpdate( "INSERT INTO worlds VALUES (1, 'minecraft:overworld')" );
			statement.executeUpdate( "INSERT INTO sources VALUES (1, 'player')" );
			statement.executeUpdate( "INSERT INTO actions (id, action_id, time, x, y, z, world_id, object_id,"
					+ " old_object_id, source, player_id) VALUES"
					+ " (1, 1, '2026-08-22 20:00:00', 120, 64, -340, 1, 1, 1, 1, 1),"
					+ " (2, 2, '2026-08-22 20:00:05', 121, 65, -341, 1, 2, 2, 1, 1)" );
		}
		return world;
	}
}
