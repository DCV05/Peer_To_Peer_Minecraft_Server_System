package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Posiciones de jugador sobre el mapa 3D.
 *
 * <p>Dos de estos tests salen de fallos reales encontrados depurando, y los dos
 * fallaban EN SILENCIO: no aparecia nadie en el mapa y no habia ningun error en
 * ninguna parte.</p>
 */
class LivePlayersTest
{
	@TempDir
	Path temporary;

	private static final ObjectMapper JSON = new ObjectMapper();

	@Test
	void readsWhatTheScriptPublishes() throws IOException
	{
		Path world = worldPublishing( """
				[{"uuid":"abc-123","name":"Victor","x":120.5,"y":64.0,"z":-340.2,
				  "yaw":91.2,"pitch":3.4,"health":17.5,"dimension":"minecraft:overworld"}]
				""" );

		List<LivePlayers.Snapshot> players = LivePlayers.read( world );

		assertEquals( 1, players.size() );
		assertEquals( "Victor", players.get( 0 ).name() );
		assertEquals( 120.5, players.get( 0 ).x(), 0.001 );
		assertEquals( 17.5, players.get( 0 ).health(), 0.001 );
		assertEquals( "minecraft:overworld", players.get( 0 ).dimension() );
	}

	@Test
	void aWorldWithoutTheScriptIsNotAnError()
	{
		assertTrue( LivePlayers.read( temporary.resolve( "sin-guion" ) ).isEmpty() );
	}

	@Test
	void halfWrittenFilesAreIgnoredInsteadOfCrashing() throws IOException
	{
		// El guion escribe cada segundo: pillarlo a medias es normal
		Path world = worldPublishing( "[{\"uuid\":\"abc\",\"name\":\"Vic" );

		assertTrue( LivePlayers.read( world ).isEmpty() );
	}

	@Test
	void theFileAlwaysCarriesThePlayersListEvenWhenEmpty() throws Exception
	{
		Path map = mapWith( "overworld" );

		LivePlayers.write( map, List.of() );

		JsonNode root = JSON.readTree( Files.readString( playersFile( map, "overworld" ) ) );
		// Si el visor recibe un objeto sin "players" se rinde y deja de pedir
		// jugadores hasta que alguien recargue la pagina a mano
		assertTrue( root.has( "players" ), "Sin la lista, el visor apaga los jugadores: " + root );
		assertTrue( root.path( "players" ).isArray() );
		assertEquals( 0, root.path( "players" ).size() );
	}

	@Test
	void everyPlayerGetsAFaceOrTheViewerDrawsNothing() throws Exception
	{
		Path map = mapWith( "overworld" );
		// La cara generica la trae el propio visor
		Files.createDirectories( map.resolve( "web/assets" ) );
		Files.writeString( map.resolve( "web/assets/steve.png" ), "imagen" );

		LivePlayers.write( map, List.of( at( "abc-123", "minecraft:overworld" ) ) );

		assertTrue( Files.isRegularFile( map.resolve( "web/maps/overworld/assets/playerheads/abc-123.png" ) ),
				"Sin cara, el visor no dibuja el muñeco y no avisa de nada" );
	}

	@Test
	void somebodyInAnotherDimensionIsMarkedAsForeign() throws Exception
	{
		Path map = mapWith( "overworld", "nether" );

		LivePlayers.write( map, List.of( at( "abc-123", "minecraft:the_nether" ) ) );

		JsonNode inOverworld = playersIn( map, "overworld" ).get( 0 );
		JsonNode inNether = playersIn( map, "nether" ).get( 0 );
		assertTrue( inOverworld.path( "foreign" ).asBoolean(), "Se pintaria en un mapa donde no esta" );
		assertFalse( inNether.path( "foreign" ).asBoolean() );
	}

	@Test
	void thePositionAndTheHeadingTravelToTheViewer() throws Exception
	{
		Path map = mapWith( "overworld" );

		LivePlayers.write( map, List.of( new LivePlayers.Snapshot( "abc", "Victor", 120.5, 64.0, -340.2, 91.2, 3.4,
				20, "minecraft:overworld" ) ) );

		JsonNode player = playersIn( map, "overworld" ).get( 0 );
		assertEquals( 120.5, player.path( "position" ).path( "x" ).asDouble(), 0.001 );
		assertEquals( -340.2, player.path( "position" ).path( "z" ).asDouble(), 0.001 );
		assertEquals( 91.2, player.path( "rotation" ).path( "yaw" ).asDouble(), 0.001 );
	}

	@Test
	void theScriptIsBundledAndInstallsItselfOnce() throws IOException
	{
		Path world = Files.createDirectories( temporary.resolve( "world" ) );

		assertFalse( LivePlayers.scriptInstalledIn( world ) );
		assertTrue( LivePlayers.installScript( world ), "No se ha instalado el guion" );
		assertTrue( LivePlayers.scriptInstalledIn( world ) );
		assertFalse( LivePlayers.installScript( world ), "No debe reinstalarse ni pisar cambios locales" );

		String script = Files.readString( LivePlayers.scriptFileIn( world ) );
		assertTrue( script.contains( "write_file" ) && script.contains( "player('all')" ), script );
	}

	// ---- utilidades ---------------------------------------------------------

	private Path worldPublishing( String json ) throws IOException
	{
		Path world = temporary.resolve( "world" );
		Path file = world.resolve( "scripts" ).resolve( LivePlayers.SCRIPT_NAME + ".data" ).resolve( "players.json" );
		Files.createDirectories( file.getParent() );
		Files.writeString( file, json );
		return world;
	}

	private Path mapWith( String... maps ) throws IOException
	{
		Path map = temporary.resolve( "map" );
		for( String name : maps )
			Files.createDirectories( map.resolve( "web" ).resolve( "maps" ).resolve( name ).resolve( "live" ) );
		return map;
	}

	private Path playersFile( Path map, String mapName )
	{
		return map.resolve( "web" ).resolve( "maps" ).resolve( mapName ).resolve( "live" ).resolve( "players.json" );
	}

	private JsonNode playersIn( Path map, String mapName ) throws Exception
	{
		return JSON.readTree( Files.readString( playersFile( map, mapName ) ) ).path( "players" );
	}

	private static LivePlayers.Snapshot at( String uuid, String dimension )
	{
		return new LivePlayers.Snapshot( uuid, "Victor", 1, 2, 3, 0, 0, 20, dimension );
	}
}
