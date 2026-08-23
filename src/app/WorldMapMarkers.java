package app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pinta la actividad reciente sobre el mapa 3D.
 *
 * <p>El visor consulta por su cuenta un fichero de marcadores de cada mapa
 * ({@code maps/<mapa>/live/markers.json}), asi que basta con dejarlo escrito:
 * no hace falta reconstruir un solo tile ni tocar el renderizador. Es la forma
 * barata de que el mapa reaccione en segundos mientras el dibujo del terreno
 * va a su ritmo.</p>
 *
 * <p>Solo se pinta lo de la ultima hora y con un techo de marcadores: un mapa
 * sembrado de miles de puntos no se lee, y cada punto es trabajo para el
 * navegador de quien lo mire.</p>
 */
public final class WorldMapMarkers
{
	/** Antiguedad maxima de lo que se pinta. */
	public static final Duration VISIBLE_WINDOW = Duration.ofHours( 1 );
	/** Techo de marcadores por mapa. */
	static final int MAX_MARKERS = 250;
	static final String MARKER_SET_ID = "endershare-activity";

	private static final ObjectMapper JSON = new ObjectMapper();

	/** Nombre del mapa en el visor para cada dimension de Minecraft. */
	private static final Map<String, String> MAP_BY_DIMENSION = Map.of(
			"minecraft:overworld", "overworld",
			"minecraft:the_nether", "nether",
			"minecraft:the_end", "end" );

	private WorldMapMarkers()
	{
	}

	/**
	 * Deja escritos los marcadores de cada dimension.
	 *
	 * @param mapDirectory carpeta del mapa generado
	 * @param activity actividad conocida, de la mas reciente a la mas antigua
	 * @return cuantos marcadores se han pintado en total
	 */
	public static int write( Path mapDirectory, List<BlockActivity> activity )
	{
		Instant cutoff = Instant.now().minus( VISIBLE_WINDOW );
		Map<String, List<BlockActivity>> byMap = new LinkedHashMap<>();
		for( BlockActivity single : activity )
		{
			if( single.at().isBefore( cutoff ) )
				continue;
			String map = mapNameFor( single.world() );
			List<BlockActivity> forThatMap = byMap.computeIfAbsent( map, key -> new ArrayList<>() );
			if( forThatMap.size() < MAX_MARKERS )
				forThatMap.add( single );
		}

		int written = 0;
		// Se recorren las carpetas que existen, no las dimensiones que conocemos:
		// un mapa sin actividad tiene que quedarse SIN marcadores, no con los de antes
		Path maps = mapDirectory.resolve( "web" ).resolve( "maps" );
		if( !Files.isDirectory( maps ) )
			return 0;
		try (java.util.stream.Stream<Path> children = Files.list( maps ))
		{
			for( Path map : children.toList() )
			{
				if( !Files.isDirectory( map ) )
					continue;
				List<BlockActivity> forThatMap = byMap.getOrDefault( map.getFileName().toString(), List.of() );
				writeMarkerFile( map.resolve( "live" ).resolve( "markers.json" ), forThatMap );
				written += forThatMap.size();
			}
		}
		catch( IOException unreadable )
		{
			Log.event( "MAP_MARKERS", "No se pudieron listar los mapas en " + maps, unreadable );
		}
		return written;
	}

	static String mapNameFor( String dimension )
	{
		String key = dimension == null ? "" : dimension.toLowerCase( Locale.ROOT );
		return MAP_BY_DIMENSION.getOrDefault( key, "overworld" );
	}

	private static void writeMarkerFile( Path file, List<BlockActivity> activity )
	{
		ObjectNode root = JSON.createObjectNode();
		if( !activity.isEmpty() )
		{
			ObjectNode set = root.putObject( MARKER_SET_ID );
			set.put( "label", "Recent activity" );
			set.put( "toggleable", true );
			set.put( "defaultHidden", false );
			set.put( "sorting", 0 );
			ObjectNode markers = set.putObject( "markers" );
			for( BlockActivity single : activity )
				markers.set( "activity-" + single.id(), markerFor( single ) );
		}
		// Se mira antes si hace falta: el vigilante pasa cada cinco segundos y sigue
		// repintando la ventana entera una hora despues del ultimo bloque tocado
		LiveFile.write( file, root.toString(), "MAP_MARKERS" );
	}

	private static ObjectNode markerFor( BlockActivity activity )
	{
		ObjectNode marker = JSON.createObjectNode();
		marker.put( "type", "poi" );
		marker.put( "label", activity.describe() );
		ObjectNode position = marker.putObject( "position" );
		position.put( "x", activity.x() );
		// Medio bloque mas arriba: si no, el punto queda enterrado en el propio bloque
		position.put( "y", activity.y() + 0.5 );
		position.put( "z", activity.z() );
		marker.put( "sorting", 0 );
		marker.put( "listed", true );
		marker.put( "detail", detailFor( activity ) );
		// Los que estan lejos no se dibujan: son puntos que nadie puede leer y
		// cuestan lo mismo que los de cerca
		marker.put( "minDistance", 0 );
		marker.put( "maxDistance", 2000 );
		return marker;
	}

	private static String detailFor( BlockActivity activity )
	{
		String who = activity.player() == null || activity.player().isBlank() ? "Someone" : activity.player();
		long minutes = Duration.between( activity.at(), Instant.now() ).toMinutes();
		String when = minutes <= 0 ? "just now" : minutes + " min ago";
		return "<b>" + who + "</b> " + (activity.wasPlaced() ? "placed" : "broke") + " " + activity.blockName()
				+ "<br>" + activity.x() + ", " + activity.y() + ", " + activity.z() + "<br><i>" + when + "</i>";
	}
}
