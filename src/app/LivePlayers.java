package app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Enseña en el mapa 3D donde esta cada jugador, en vivo.
 *
 * <p>La posicion la publica un guion de Carpet —que ya viene instalado en el
 * servidor— en un fichero dentro del mundo. Aqui se lee y se traduce al formato
 * que el visor consulta por su cuenta cada segundo. No hace falta ningun mod
 * nuevo, ni puertos, ni contraseñas.</p>
 *
 * <p><b>No se guarda ningun historial</b>: es una foto del momento que se
 * sobreescribe. Ese fichero no viaja en los respaldos.</p>
 */
public final class LivePlayers
{
	/** Guion de Carpet que publica las posiciones, dentro de la carpeta del mundo. */
	public static final String SCRIPT_NAME = "endershare_players";
	private static final String SCRIPT_RESOURCE = "/scarpet/" + SCRIPT_NAME + ".sc";
	/** Donde deja Carpet lo que escribe el guion. */
	private static final String SCRIPT_OUTPUT = "scripts/" + SCRIPT_NAME + ".data/players.json";

	private static final ObjectMapper JSON = new ObjectMapper();

	/** Un jugador tal y como lo publica el guion. */
	public record Snapshot( String uuid, String name, double x, double y, double z, double yaw, double pitch,
			double health, String dimension )
	{
	}

	private LivePlayers()
	{
	}

	public static Path scriptFileIn( Path worldDirectory )
	{
		return worldDirectory.resolve( "scripts" ).resolve( SCRIPT_NAME + ".sc" );
	}

	public static boolean scriptInstalledIn( Path worldDirectory )
	{
		return Files.isRegularFile( scriptFileIn( worldDirectory ) );
	}

	/**
	 * Deja el guion dentro del mundo si no estaba. Viaja con el mundo, asi que
	 * llega solo a las demas maquinas en el siguiente respaldo.
	 *
	 * @return true si ha hecho falta instalarlo
	 */
	public static boolean installScript( Path worldDirectory ) throws IOException
	{
		Path target = scriptFileIn( worldDirectory );
		if( Files.isRegularFile( target ) )
			return false;
		try (InputStream source = LivePlayers.class.getResourceAsStream( SCRIPT_RESOURCE ))
		{
			if( source == null )
				throw new IOException( "The player script is missing from this build." );
			Files.createDirectories( target.getParent() );
			Files.copy( source, target, StandardCopyOption.REPLACE_EXISTING );
		}
		return true;
	}

	/** Lo ultimo que publico el guion. Lista vacia si aun no ha escrito nada. */
	public static List<Snapshot> read( Path worldDirectory )
	{
		List<Snapshot> players = new ArrayList<>();
		Path file = worldDirectory.resolve( SCRIPT_OUTPUT );
		if( !Files.isRegularFile( file ) )
			return players;
		try
		{
			JsonNode root = JSON.readTree( Files.readString( file, StandardCharsets.UTF_8 ) );
			if( !root.isArray() )
				return players;
			for( JsonNode node : root )
			{
				String uuid = node.path( "uuid" ).asText( "" );
				if( uuid.isBlank() )
					continue;
				players.add( new Snapshot( uuid, node.path( "name" ).asText( "" ), node.path( "x" ).asDouble(),
						node.path( "y" ).asDouble(), node.path( "z" ).asDouble(), node.path( "yaw" ).asDouble(),
						node.path( "pitch" ).asDouble(), node.path( "health" ).asDouble( 20 ),
						node.path( "dimension" ).asText( "minecraft:overworld" ) ) );
			}
		}
		catch( IOException | RuntimeException halfWritten )
		{
			// El guion escribe cada segundo: pillarlo a medias es normal y no es
			// motivo de aviso, en la siguiente vuelta se lee entero
		}
		return players;
	}

	/**
	 * Escribe los jugadores en cada mapa del visor.
	 *
	 * <p>Se escribe en TODOS los mapas, no solo en el de su dimension: el visor
	 * necesita saber que alguien esta en otra dimension para tacharlo, y si el
	 * fichero de un mapa no se toca se quedarian ahi los jugadores de antes.</p>
	 *
	 * @return cuantos jugadores se han publicado
	 */
	public static int write( Path mapDirectory, List<Snapshot> players )
	{
		Path maps = mapDirectory.resolve( "web" ).resolve( "maps" );
		if( !Files.isDirectory( maps ) )
			return 0;
		try (java.util.stream.Stream<Path> children = Files.list( maps ))
		{
			for( Path map : children.toList() )
			{
				if( Files.isDirectory( map ) )
					writeForMap( map, players );
			}
		}
		catch( IOException unreadable )
		{
			Log.event( "LIVE_PLAYERS", "No se pudieron listar los mapas en " + maps, unreadable );
			return 0;
		}
		return players.size();
	}

	private static void writeForMap( Path map, List<Snapshot> players )
	{
		String mapName = map.getFileName().toString();
		ObjectNode root = JSON.createObjectNode();
		// SIEMPRE con la lista, aunque este vacia: si el visor recibe un objeto sin
		// "players" se rinde y deja de pedir jugadores hasta que alguien recargue
		ArrayNode array = root.putArray( "players" );
		for( Snapshot player : players )
		{
			boolean sameMap = WorldMapMarkers.mapNameFor( player.dimension() ).equals( mapName );
			ObjectNode node = array.addObject();
			node.put( "uuid", player.uuid() );
			node.put( "name", player.name() );
			// "foreign" = esta en otra dimension: el visor lo esconde en este mapa
			node.put( "foreign", !sameMap );
			ObjectNode position = node.putObject( "position" );
			position.put( "x", player.x() );
			position.put( "y", player.y() );
			position.put( "z", player.z() );
			ObjectNode rotation = node.putObject( "rotation" );
			rotation.put( "pitch", player.pitch() );
			rotation.put( "yaw", player.yaw() );
			rotation.put( "roll", 0 );
			ensureHead( map, player.uuid() );
		}
		writeAtomically( map.resolve( "live" ).resolve( "players.json" ), root.toString() );
	}

	/**
	 * El visor pide la cara de cada jugador por su identificador y, si no la
	 * encuentra, no dibuja nada. Se intenta la real de Mojang una sola vez por
	 * jugador y, si no se puede, la generica que ya trae el visor: es preferible
	 * un muñeco anonimo a que no aparezca nadie.
	 */
	static void ensureHead( Path map, String uuid )
	{
		Path head = map.resolve( "assets" ).resolve( "playerheads" ).resolve( uuid + ".png" );
		if( Files.exists( head ) )
			return;
		// map = <mapDirectory>/web/maps/<mapa>  ->  <mapDirectory>
		Path mapDirectory = map.getParent().getParent().getParent();
		Path generic = mapDirectory.resolve( "web" ).resolve( "assets" ).resolve( "steve.png" );
		PlayerSkins.ensureFace( mapDirectory, map.getFileName().toString(), uuid, generic );
	}

	private static void writeAtomically( Path file, String content )
	{
		try
		{
			Files.createDirectories( file.getParent() );
			Path temporary = file.resolveSibling( file.getFileName() + ".tmp" );
			Files.writeString( temporary, content, StandardCharsets.UTF_8 );
			Files.move( temporary, file, StandardCopyOption.REPLACE_EXISTING );
		}
		catch( IOException notWritten )
		{
			Log.event( "LIVE_PLAYERS", "No se pudo escribir " + file, notWritten );
		}
	}

	/** Nombre del mapa del visor para una dimension, en minusculas. */
	static String mapFor( String dimension )
	{
		return WorldMapMarkers.mapNameFor( dimension == null ? "" : dimension.toLowerCase( Locale.ROOT ) );
	}
}
