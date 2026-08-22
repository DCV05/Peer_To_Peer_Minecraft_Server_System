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
		System.setProperty( "p2pmss.dataDirectory", temporary.resolve( "data" ).toString() );
	}

	@AfterEach
	void restoreDataDirectory()
	{
		System.clearProperty( "p2pmss.dataDirectory" );
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
