package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reglas del mapa que no se pueden romper: no escribir nunca dentro del
 * repositorio del mundo, y encontrar la carpeta del mundo aunque no se llame
 * "world".
 */
class WorldMapTest
{
	@TempDir
	Path temporary;

	@BeforeEach
	void useATemporaryDataDirectory()
	{
		System.setProperty( "endershare.dataDirectory", temporary.resolve( "data" ).toString() );
	}

	@AfterEach
	void restoreDataDirectory()
	{
		System.clearProperty( "endershare.dataDirectory" );
	}

	@Test
	void lookingAtTheMapIsNotTheSameAsWatchingIt() throws IOException
	{
		// Servir el mapa desde la aplicacion no redibuja nada. La pantalla decia
		// "LIVE · al dia, redibujando solo lo que cambie" con solo abrirlo, que es
		// justo lo contrario de lo que estaba pasando
		Path repository = temporary.resolve( "farmland_mc" );
		Path web = WorldMap.directoryFor( repository ).resolve( "web" );
		Files.createDirectories( web.resolve( "maps" ).resolve( "overworld" ) );
		Files.writeString( web.resolve( "index.html" ), "<html>el visor</html>" );

		try
		{
			WorldMap.startServing( repository, temporary.resolve( "world" ), true );

			assertTrue( WorldMap.currentUrl().isPresent(), "El mapa tiene que quedar servido" );
			assertFalse( WorldMap.isRenderingFor( repository ), "Mirar el mapa no puede dibujarlo" );
			assertFalse( WorldMap.isWatchingFor( repository ),
					"Sin renderizador detras no se redibuja nada: decirlo en pantalla es mentir" );
		}
		finally
		{
			WorldMap.stopRendering();
		}
	}

	@Test
	void theMapNeverLivesInsideTheWorldRepository()
	{
		Path repository = temporary.resolve( "farmland_mc" );

		Path mapDirectory = WorldMap.directoryFor( repository );

		assertFalse( mapDirectory.toAbsolutePath().startsWith( repository.toAbsolutePath() ),
				"El mapa acabaria viajando por GitHub en cada respaldo: " + mapDirectory );
		assertTrue( mapDirectory.toAbsolutePath().startsWith( temporary.resolve( "data" ).toAbsolutePath() ) );
	}

	@Test
	void differentWorldsGetDifferentMapFolders()
	{
		Path first = WorldMap.directoryFor( temporary.resolve( "farmland_mc" ) );
		Path second = WorldMap.directoryFor( temporary.resolve( "otro_mundo" ) );

		assertFalse( first.equals( second ) );
	}

	@Test
	void theFolderNameIsValidOnEverySystem()
	{
		String identifier = WorldMap.identifierFor( Path.of( "/tmp", "Mundo de Víctor (2026)" ) );

		assertTrue( identifier.matches( "[a-z0-9._-]+" ), "Nombre no valido como carpeta: " + identifier );
	}

	@Test
	void findsTheWorldFolderNamedInServerProperties() throws IOException
	{
		Path server = temporary.resolve( "server" );
		Files.createDirectories( server.resolve( "farmland" ).resolve( "region" ) );
		Files.writeString( server.resolve( "server.properties" ), "level-name=farmland\nonline-mode=true\n" );

		Optional<Path> world = WorldMap.locateWorld( server );

		assertTrue( world.isPresent() );
		assertEquals( server.resolve( "farmland" ), world.get() );
	}

	@Test
	void fallsBackToTheDefaultWorldFolder() throws IOException
	{
		Path server = temporary.resolve( "server" );
		Files.createDirectories( server.resolve( "world" ).resolve( "region" ) );

		Optional<Path> world = WorldMap.locateWorld( server );

		assertTrue( world.isPresent() );
		assertEquals( server.resolve( "world" ), world.get() );
	}

	@Test
	void aWorldFolderWithoutRegionsIsNotAWorld() throws IOException
	{
		Path server = temporary.resolve( "server" );
		// La carpeta existe pero el servidor no ha llegado a generar el mundo
		Files.createDirectories( server.resolve( "world" ) );

		assertTrue( WorldMap.locateWorld( server ).isEmpty() );
	}

	@Test
	void theMapComesOffForEveryWorld()
	{
		assertFalse( WorldMap.isEnabledFor( temporary.resolve( "farmland_mc" ) ),
				"Encendido de fabrica pondria a renderizar a quien no lo ha pedido" );
	}

	@Test
	void turningItOnAffectsOnlyThatWorld() throws IOException
	{
		Path mine = temporary.resolve( "farmland_mc" );
		Path other = temporary.resolve( "otro_mundo" );

		WorldMap.setEnabledFor( mine, true );

		assertTrue( WorldMap.isEnabledFor( mine ) );
		assertFalse( WorldMap.isEnabledFor( other ), "Se ha encendido el mapa de un mundo que nadie pidio" );
	}

	@Test
	void turningItOffAgainLeavesItOff() throws IOException
	{
		Path repository = temporary.resolve( "farmland_mc" );
		WorldMap.setEnabledFor( repository, true );

		WorldMap.setEnabledFor( repository, false );

		assertFalse( WorldMap.isEnabledFor( repository ) );
	}

	@Test
	void theViewerOpensOnAMapThatAlreadyHasSomethingToShow() throws IOException
	{
		Path maps = temporary.resolve( "maps" );
		// El overworld existe pero esta vacio todavia; el Nether ya tiene dibujo
		Files.createDirectories( maps.resolve( "overworld" ).resolve( "tiles" ) );
		Files.createDirectories( maps.resolve( "nether" ).resolve( "tiles" ).resolve( "0" ) );
		Files.writeString( maps.resolve( "nether/tiles/0/x0z0.png" ), "" );

		assertEquals( "nether", WorldMap.firstMapWithContent( maps ).orElseThrow(),
				"Se abriria un mapa vacio y pareceria que el visor esta roto" );
	}

	@Test
	void theOverworldWinsWhenBothHaveContent() throws IOException
	{
		Path maps = temporary.resolve( "maps" );
		for( String name : new String[] { "overworld", "nether" } )
		{
			Files.createDirectories( maps.resolve( name ).resolve( "tiles" ).resolve( "0" ) );
			Files.writeString( maps.resolve( name ).resolve( "tiles/0/x0z0.png" ), "" );
		}

		assertEquals( "overworld", WorldMap.firstMapWithContent( maps ).orElseThrow(),
				"Al abrir se espera ver el mundo normal, no el Nether" );
	}

	@Test
	void withNothingRenderedThereIsNoMapToOpen() throws IOException
	{
		Path maps = Files.createDirectories( temporary.resolve( "maps" ) );

		assertTrue( WorldMap.firstMapWithContent( maps ).isEmpty() );
	}

	@Test
	void aWorldWithoutAMapYetReportsMissing()
	{
		Path repository = temporary.resolve( "farmland_mc" );

		assertEquals( WorldMap.State.MISSING, WorldMap.stateFor( repository ) );
		assertFalse( WorldMap.hasBuiltMap( repository ) );
	}

	@Test
	void aWorldWithRenderedTilesReportsReady() throws IOException
	{
		Path repository = temporary.resolve( "farmland_mc" );
		Files.createDirectories( WorldMap.directoryFor( repository ).resolve( "web/maps/overworld" ) );

		assertTrue( WorldMap.hasBuiltMap( repository ) );
		assertEquals( WorldMap.State.READY, WorldMap.stateFor( repository ) );
	}
}
