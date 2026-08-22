package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Marcadores de actividad sobre el mapa 3D.
 *
 * <p>El visor lee estos ficheros por su cuenta, asi que el formato tiene que
 * ser el que el espera: un fallo aqui no da ningun error, simplemente no
 * aparece nada y no hay forma de saber por que.</p>
 */
class WorldMapMarkersTest
{
	@TempDir
	Path temporary;

	private static final ObjectMapper JSON = new ObjectMapper();

	@Test
	void eachDimensionGetsItsOwnMarkers() throws Exception
	{
		Path map = mapWith( "overworld", "nether" );

		WorldMapMarkers.write( map, List.of( at( 1, "minecraft:overworld", 100, 64, 200 ),
				at( 2, "minecraft:the_nether", 10, 30, 20 ) ) );

		assertEquals( 1, markersIn( map, "overworld" ).size() );
		assertEquals( 1, markersIn( map, "nether" ).size(), "El Nether tiene su propio mapa y sus propios marcadores" );
	}

	@Test
	void theMarkerCarriesWhoDidWhatAndWhere() throws Exception
	{
		Path map = mapWith( "overworld" );

		WorldMapMarkers.write( map, List.of( at( 1, "minecraft:overworld", 120, 64, -340 ) ) );

		JsonNode marker = markersIn( map, "overworld" ).elements().next();
		assertEquals( "poi", marker.path( "type" ).asText() );
		assertTrue( marker.path( "label" ).asText().contains( "Victor" ), marker.toString() );
		assertEquals( 120, marker.path( "position" ).path( "x" ).asInt() );
		assertEquals( -340, marker.path( "position" ).path( "z" ).asInt() );
		// Medio bloque por encima, o el punto queda enterrado dentro del bloque
		assertEquals( 64.5, marker.path( "position" ).path( "y" ).asDouble(), 0.001 );
	}

	@Test
	void aMapWithoutActivityIsLeftClean() throws Exception
	{
		Path map = mapWith( "overworld", "nether" );
		WorldMapMarkers.write( map, List.of( at( 1, "minecraft:overworld", 1, 2, 3 ) ) );

		WorldMapMarkers.write( map, List.of() );

		assertTrue( markerFileOf( map, "overworld" ).isEmpty(),
				"Los marcadores viejos se quedarian pegados en el mapa para siempre" );
	}

	@Test
	void whatIsTooOldIsNotPainted() throws Exception
	{
		Path map = mapWith( "overworld" );
		BlockActivity old = new BlockActivity( 1, Instant.now().minus( WorldMapMarkers.VISIBLE_WINDOW ).minusSeconds( 60 ),
				"Victor", BlockActivity.BROKEN, "minecraft:stone", "minecraft:overworld", 1, 2, 3 );

		WorldMapMarkers.write( map, List.of( old ) );

		assertTrue( markerFileOf( map, "overworld" ).isEmpty() );
	}

	@Test
	void thereIsACeilingSoTheViewerDoesNotChoke() throws Exception
	{
		Path map = mapWith( "overworld" );
		List<BlockActivity> lots = new ArrayList<>();
		for( int index = 1; index <= WorldMapMarkers.MAX_MARKERS + 100; index++ )
			lots.add( at( index, "minecraft:overworld", index, 64, index ) );

		int written = WorldMapMarkers.write( map, lots );

		assertEquals( WorldMapMarkers.MAX_MARKERS, written );
		assertEquals( WorldMapMarkers.MAX_MARKERS, markersIn( map, "overworld" ).size() );
	}

	@Test
	void anUnknownDimensionFallsBackToTheMainMap()
	{
		assertEquals( "overworld", WorldMapMarkers.mapNameFor( "algun:mod_raro" ) );
		assertEquals( "nether", WorldMapMarkers.mapNameFor( "minecraft:the_nether" ) );
		assertEquals( "end", WorldMapMarkers.mapNameFor( "minecraft:the_end" ) );
	}

	// ---- utilidades ---------------------------------------------------------

	private Path mapWith( String... maps ) throws Exception
	{
		Path map = temporary.resolve( "map" );
		for( String name : maps )
			Files.createDirectories( map.resolve( "web" ).resolve( "maps" ).resolve( name ).resolve( "live" ) );
		return map;
	}

	private JsonNode markersIn( Path map, String mapName ) throws Exception
	{
		return JSON.readTree( Files.readString( markerFile( map, mapName ) ) ).path( WorldMapMarkers.MARKER_SET_ID )
				.path( "markers" );
	}

	private JsonNode markerFileOf( Path map, String mapName ) throws Exception
	{
		return JSON.readTree( Files.readString( markerFile( map, mapName ) ) );
	}

	private Path markerFile( Path map, String mapName )
	{
		return map.resolve( "web" ).resolve( "maps" ).resolve( mapName ).resolve( "live" ).resolve( "markers.json" );
	}

	private static BlockActivity at( long id, String world, int x, int y, int z )
	{
		return new BlockActivity( id, Instant.now(), "Victor", BlockActivity.BROKEN, "minecraft:obsidian", world, x, y,
				z );
	}
}
