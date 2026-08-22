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

/**
 * La configuracion del mapa decide cuanto ocupa y cuanto molesta al ordenador.
 * Un descuido aqui son gigabytes de mas o un equipo inservible durante una hora.
 */
class WorldMapConfigTest
{
	@TempDir
	Path temporary;

	@Test
	void generatesOneMapPerExistingDimensionAndSkipsTheMissingOnes() throws IOException
	{
		Path world = temporary.resolve( "world" );
		Files.createDirectories( world.resolve( "region" ) );
		Files.createDirectories( world.resolve( "DIM-1" ).resolve( "region" ) );
		// Sin End: un mundo puede no tenerlo, y no debe generarse un mapa vacio
		Path maps = temporary.resolve( "map-output" );

		List<String> generated = WorldMapConfig.write( maps, world, WorldMapConfig.Options.standard( 8123 ) );

		assertEquals( List.of( "overworld", "nether" ), generated );
		assertTrue( Files.isRegularFile( maps.resolve( "config/maps/overworld.conf" ) ) );
		assertTrue( Files.isRegularFile( maps.resolve( "config/maps/nether.conf" ) ) );
		assertFalse( Files.exists( maps.resolve( "config/maps/end.conf" ) ) );
	}

	@Test
	void fullDetailIsWhatDecidesTheSize() throws IOException
	{
		Path world = temporary.resolve( "world" );
		Files.createDirectories( world.resolve( "region" ) );

		WorldMapConfig.write( temporary.resolve( "heavy" ), world, new WorldMapConfig.Options( true, 2, 8123 ) );
		WorldMapConfig.write( temporary.resolve( "light" ), world, new WorldMapConfig.Options( false, 2, 8124 ) );

		assertTrue( Files.readString( temporary.resolve( "heavy/config/maps/overworld.conf" ) )
				.contains( "save-hires-layer: true" ) );
		assertTrue( Files.readString( temporary.resolve( "light/config/maps/overworld.conf" ) )
				.contains( "save-hires-layer: false" ) );
	}

	@Test
	void theViewerKeepsTheMeasuredViewDistancesAndNotTheConservativeDefaults() throws IOException
	{
		Path world = temporary.resolve( "world" );
		Files.createDirectories( world.resolve( "region" ) );

		WorldMapConfig.write( temporary, world, WorldMapConfig.Options.standard( 8123 ) );

		String webapp = Files.readString( temporary.resolve( "config/webapp.conf" ) );
		// De fabrica vienen 100 y 2000; medido, con 350 y 7000 el visor seguia a 120 fps
		assertTrue( webapp.contains( "hires-slider-default: 350" ), webapp );
		assertTrue( webapp.contains( "lowres-slider-default: 7000" ), webapp );
		assertTrue( webapp.contains( "enable-free-flight: true" ) );
	}

	@Test
	void theWebServerOnlyListensOnThisComputer() throws IOException
	{
		Path world = temporary.resolve( "world" );
		Files.createDirectories( world.resolve( "region" ) );

		WorldMapConfig.write( temporary, world, WorldMapConfig.Options.standard( 9321 ) );

		String webserver = Files.readString( temporary.resolve( "config/webserver.conf" ) );
		assertTrue( webserver.contains( "ip: \"127.0.0.1\"" ), "El mapa no debe abrirse al exterior sin pedirlo" );
		assertTrue( webserver.contains( "port: 9321" ) );
	}

	@Test
	void neverGrabsEveryProcessorOfTheMachine()
	{
		int threads = WorldMapConfig.defaultThreadCount();

		assertTrue( threads >= 1 );
		assertTrue( threads <= 6, "Con mas hilos el ordenador se queda inservible mientras renderiza" );
		assertTrue( threads <= Math.max( 1, Runtime.getRuntime().availableProcessors() / 2 ) );
	}

	@Test
	void whileSomebodyIsPlayingHereTheRenderStepsAside()
	{
		int playing = WorldMapConfig.threadCountFor( true );
		int idle = WorldMapConfig.threadCountFor( false );

		assertTrue( playing >= 1 );
		assertTrue( playing <= 2, "Renderizar a toda maquina durante la partida da tirones al juego" );
		assertTrue( playing <= idle );
	}

	@Test
	void theNetherIsNotWipedOutByTheOverworldCaveSetting() throws IOException
	{
		Path world = temporary.resolve( "world" );
		Files.createDirectories( world.resolve( "region" ) );
		Files.createDirectories( world.resolve( "DIM-1" ).resolve( "region" ) );

		WorldMapConfig.write( temporary, world, WorldMapConfig.Options.standard( 8123 ) );

		String nether = Files.readString( temporary.resolve( "config/maps/nether.conf" ) );
		// El Nether ENTERO es una cueva: con el valor del overworld (55) se borra
		// casi todo el mapa y queda practicamente vacio
		assertTrue( nether.contains( "remove-caves-below-y: -10000" ), nether );
		// Y sin cortar el techo solo se ve la plancha de piedra base de arriba
		assertTrue( nether.contains( "max-y: 90" ), nether );
	}

	@Test
	void eachDimensionIsLitAndColouredAsItShould() throws IOException
	{
		Path world = temporary.resolve( "world" );
		Files.createDirectories( world.resolve( "region" ) );
		Files.createDirectories( world.resolve( "DIM-1" ).resolve( "region" ) );
		Files.createDirectories( world.resolve( "DIM1" ).resolve( "region" ) );

		WorldMapConfig.write( temporary, world, WorldMapConfig.Options.standard( 8123 ) );

		String overworld = Files.readString( temporary.resolve( "config/maps/overworld.conf" ) );
		String nether = Files.readString( temporary.resolve( "config/maps/nether.conf" ) );
		String end = Files.readString( temporary.resolve( "config/maps/end.conf" ) );

		assertTrue( overworld.contains( "sky-color: \"#7dabff\"" ) );
		assertTrue( nether.contains( "sky-color: \"#290000\"" ), "El Nether saldria con cielo azul" );
		assertTrue( end.contains( "sky-color: \"#080010\"" ) );
		// Sin cielo propio, la luz tiene que venir del ambiente o se ve todo negro
		assertTrue( nether.contains( "world-sky-light: 0" ) && nether.contains( "ambient-light: 0.6" ), nether );
		assertTrue( end.contains( "world-sky-light: 0" ) && end.contains( "ambient-light: 0.6" ), end );
		assertFalse( overworld.contains( "max-y:" ), "El overworld no se corta por arriba" );
	}

	@Test
	void theMapPointsAtTheRightFolderForEachDimension() throws IOException
	{
		Path world = temporary.resolve( "world" );
		Files.createDirectories( world.resolve( "region" ) );
		Files.createDirectories( world.resolve( "DIM1" ).resolve( "region" ) );

		WorldMapConfig.write( temporary, world, WorldMapConfig.Options.standard( 8123 ) );

		assertTrue( Files.readString( temporary.resolve( "config/maps/end.conf" ) )
				.contains( world.resolve( "DIM1" ).toAbsolutePath().toString() ) );
	}
}
