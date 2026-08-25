package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * El renderizador que algunos mundos llevan dentro como mod.
 *
 * <p>Dibujaba el mismo mapa por segunda vez dentro del proceso del servidor, y
 * al quitarle el disco al guardado del mundo el vigilante de Minecraft dio el
 * servidor por colgado y lo mato con un jugador dentro.</p>
 */
class ServerSideMapTest
{
	@TempDir
	Path temporary;

	@Test
	void aWorldWithoutTheModIsLeftAlone() throws Exception
	{
		Path server = Files.createDirectories( temporary.resolve( "sin-mod" ) );
		Files.createDirectories( server.resolve( "mods" ) );
		Files.writeString( server.resolve( "mods" ).resolve( "fabric-api-0.58.0.jar" ), "nada" );

		assertFalse( ServerSideMap.hasMapMod( server ) );
		assertFalse( ServerSideMap.pauseWhileAnyonePlays( server ) );
		assertFalse( Files.exists( server.resolve( "config" ) ), "Ha escrito configuracion de un mod que no esta" );
	}

	@Test
	void aWorldWithTheModIsSetUpSoItPausesWhileAnyonePlays() throws Exception
	{
		Path server = serverWithTheMod();
		Path configuration = server.resolve( "config" ).resolve( "bluemap" ).resolve( "plugin.conf" );
		Files.createDirectories( configuration.getParent() );
		Files.writeString( configuration, """
				live-player-markers: true
				player-render-limit: 2
				map-update-interval: 1440
				""" );

		assertTrue( ServerSideMap.pauseWhileAnyonePlays( server ) );

		String after = Files.readString( configuration, StandardCharsets.UTF_8 );
		assertTrue( after.contains( "player-render-limit: 1" ),
			"Con dos, jugando solo el mod sigue dibujando: es justo el caso que tumbo el servidor" );
		assertFalse( after.contains( "player-render-limit: 2" ) );
	}

	@Test
	void theRestOfTheConfigurationIsRespected()
	{
		String before = """
				# Un comentario de quien juega
				live-player-markers: true
				hidden-game-modes: [
					"spectator"
				]
				player-render-limit: -1
				map-update-interval: 1440
				""";

		String after = ServerSideMap.withRenderPaused( before );

		assertTrue( after.contains( "# Un comentario de quien juega" ) );
		assertTrue( after.contains( "\"spectator\"" ) );
		assertTrue( after.contains( "map-update-interval: 1440" ) );
		assertTrue( after.contains( "live-player-markers: true" ) );
	}

	@Test
	void theSettingIsAddedWhenTheFileDoesNotHaveIt()
	{
		String before = "live-player-markers: true\n";

		String after = ServerSideMap.withRenderPaused( before );

		assertTrue( after.contains( "player-render-limit: 1" ), "Sin el ajuste, el mod dibuja siempre" );
		assertTrue( after.contains( "live-player-markers: true" ) );
	}

	@Test
	void aMissingFileIsWrittenFromScratch()
	{
		String written = ServerSideMap.withRenderPaused( null );

		assertTrue( written.contains( "player-render-limit: 1" ) );
	}

	@Test
	void whatIsAlreadyRightIsNotRewritten() throws Exception
	{
		Path server = serverWithTheMod();
		Path configuration = server.resolve( "config" ).resolve( "bluemap" ).resolve( "plugin.conf" );
		Files.createDirectories( configuration.getParent() );
		Files.writeString( configuration, "live-player-markers: true\nplayer-render-limit: 1\n" );
		long stamp = Files.getLastModifiedTime( configuration ).toMillis();

		assertFalse( ServerSideMap.pauseWhileAnyonePlays( server ), "Reescribe el fichero en cada arranque" );
		assertEquals( stamp, Files.getLastModifiedTime( configuration ).toMillis() );
	}

	@Test
	void aCommentedOutSettingIsNotMistakenForTheRealOne()
	{
		// El fichero que escribe el propio mod lleva comentada la explicacion de cada
		// ajuste: confundirla con el ajuste dejaria el valor de verdad sin tocar
		String before = "#player-render-limit: -1\nlive-player-markers: true\n";

		String after = ServerSideMap.withRenderPaused( before );

		assertTrue( after.contains( "#player-render-limit: -1" ), "Ha reescrito un comentario" );
		assertNotEquals( before, after, "No ha puesto el ajuste de verdad en ninguna parte" );
		assertTrue( after.lines().anyMatch( line -> line.equals( "player-render-limit: 1" ) ) );
	}

	private Path serverWithTheMod() throws Exception
	{
		Path server = Files.createDirectories( temporary.resolve( "con-mod" ) );
		Path mods = Files.createDirectories( server.resolve( "mods" ) );
		Files.writeString( mods.resolve( "BlueMap-3.13-fabric-1.19.jar" ), "nada" );
		return server;
	}
}
