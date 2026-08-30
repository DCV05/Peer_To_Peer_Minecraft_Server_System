package link;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.Log;
import jgit.TokenStore;

/**
 * Sube a GitHub Pages SOLO lo que cambia del mapa de BlueMap, sin clonar nada:
 * cada pocos minutos mientras se hostea (y al parar) se buscan los tiles con
 * mtime posterior a la ultima publicacion y se suben como UN commit por repo
 * via la Git Data API (blobs → tree → commit → ref).
 *
 * <p>Regla de tamano (Pages corta en ~1 GB por sitio): el lowres de todo el
 * mundo va siempre; el hires SOLO de los tiles que cubren chunks que algun
 * jugador cargo (lo cuenta el feed `chunks` del WebSocket), no de todo lo que
 * BlueMap renderiza. Los hires que ya viven en el repo principal se actualizan
 * ahi; los nuevos van al shard, y el service worker del visor los encuentra.</p>
 *
 * <p>Config en {@code p2pmss/map-publish.json} del server (viaja con el mundo);
 * los tiles visitados en {@code p2pmss/map-visited.json}. Sin config o sin
 * token con permiso, el publicador se queda callado.</p>
 */
public final class MapPublisher
{

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String API = "https://api.github.com";
	private static final long INTERVAL_MILLIS = 10 * 60 * 1000L;
	private static final long FIRST_DELAY_MILLIS = 2 * 60 * 1000L;
	private static final int MAX_FILES_PER_RUN = 600;
	private static final long MAX_BYTES_PER_RUN = 150L * 1024 * 1024;
	private static final long SHARD_BUDGET_BYTES = 900L * 1024 * 1024;

	private static Timer timer = null;
	private static volatile File serverFolder = null;
	private static final Set<String> visitedTiles = Collections.synchronizedSet( new HashSet<>() );
	private static final Object publishLock = new Object();

	private MapPublisher()
	{
	}

	// ---- FASE 1 — Ciclo de vida (lo llama MainFrame al hostear) ------------

	public static synchronized void start( File folder )
	{
		stop();
		serverFolder = folder;
		if( folder == null || !configFile( folder ).exists() )
			return;
		loadVisited( folder );
		timer = new Timer( "endershare-map-publisher", true );
		timer.scheduleAtFixedRate( new TimerTask()
		{
			@Override
			public void run()
			{
				publishQuietly();
			}
		}, FIRST_DELAY_MILLIS, INTERVAL_MILLIS );
	}

	public static synchronized void stop()
	{
		if( timer != null )
		{
			timer.cancel();
			timer = null;
			// Ultima foto tras la sesion, fuera del hilo del que para
			Thread last = new Thread( MapPublisher::publishQuietly, "endershare-map-publisher-final" );
			last.setDaemon( true );
			last.start();
		}
	}

	/** Chunks cargados vistos por el WebSocket: se apuntan como tiles visitados. */
	public static void noteLoadedChunks( String dimension, List<int[]> chunkCoords )
	{
		String map = dimension == null ? "overworld" : dimension.replace( "minecraft:", "" ).replace( "the_", "" );
		boolean added = false;
		for( int[] chunk : chunkCoords )
			added |= visitedTiles.add( map + ":" + Math.floorDiv( chunk[0], 2 ) + "," + Math.floorDiv( chunk[1], 2 ) );
		File folder = serverFolder;
		if( added && folder != null )
			saveVisited( folder );
	}

	private static void publishQuietly()
	{
		File folder = serverFolder;
		if( folder == null )
			return;
		try
		{
			publish( folder );
		}
		catch( Exception failed )
		{
			Log.event( "MAP_PUBLISH", "Publicacion del mapa fallida", failed );
		}
	}

	// ---- FASE 2 — Publicacion --------------------------------------------

	public record Report( int uploaded, long bytes, int skippedHires, int pending )
	{
	}

	public static Report publish( File folder ) throws Exception
	{
		synchronized( publishLock )
		{
			ObjectNode config = (ObjectNode) JSON.readTree( configFile( folder ) );
			if( !config.path( "enabled" ).asBoolean( true ) )
				return new Report( 0, 0, 0, 0 );
			String token = TokenStore.loadToken();
			if( token == null || token.isBlank() )
				return new Report( 0, 0, 0, 0 );

			// Los visitados se releen en cada publicacion: valen tambien fuera
			// del ciclo start/stop (pruebas, publicacion manual)
			loadVisited( folder );
			String mainRepo = config.path( "main" ).asText();
			List<String> shards = new ArrayList<>();
			for( JsonNode shard : config.path( "shards" ) )
				shards.add( shard.asText() );
			long since = config.path( "lastPublishMillis" ).asLong( 0 );
			long startedAt = System.currentTimeMillis();

			Path maps = folder.toPath().resolve( "bluemap" ).resolve( "web" ).resolve( "maps" );
			if( !Files.isDirectory( maps ) )
				return new Report( 0, 0, 0, 0 );

			GitHub github = new GitHub( token );
			Map<String, Long> mainSizes = github.treeSizes( mainRepo );
			Map<String, Map<String, Long>> shardSizes = new LinkedHashMap<>();
			for( String shard : shards )
				shardSizes.put( shard, github.treeSizes( shard ) );

			Map<String, List<Path>> byRepo = new LinkedHashMap<>();
			int skippedHires = 0;
			int pending = 0;
			long bytes = 0;
			int files = 0;
			// Los ficheros van en orden de mtime: cuando se llena la ronda, el
			// umbral avanza hasta el ultimo subido y la siguiente sigue desde ahi
			long cutoff = startedAt;
			List<Path> changed = changedFiles( maps, since );
			for( Path file : changed )
			{
				String rel = "maps/" + maps.relativize( file ).toString().replace( File.separatorChar, '/' );
				boolean hires = rel.contains( "/tiles/0/" );
				if( hires && !isVisited( rel ) && !mainSizes.containsKey( rel ) )
				{
					skippedHires++;
					continue;
				}
				if( files >= MAX_FILES_PER_RUN || bytes >= MAX_BYTES_PER_RUN )
				{
					if( pending == 0 )
						cutoff = Files.getLastModifiedTime( file ).toMillis() - 1;
					pending++;
					continue;
				}
				long size = Files.size( file );
				String target = mainRepo;
				if( hires && !mainSizes.containsKey( rel ) )
				{
					target = null;
					for( Map.Entry<String, Map<String, Long>> shard : shardSizes.entrySet() )
					{
						long used = shard.getValue().values().stream().mapToLong( Long::longValue ).sum();
						if( shard.getValue().containsKey( rel ) || used + size < SHARD_BUDGET_BYTES )
						{
							target = shard.getKey();
							shard.getValue().put( rel, size );
							break;
						}
					}
					if( target == null )
					{
						skippedHires++;
						continue;
					}
				}
				byRepo.computeIfAbsent( target, key -> new ArrayList<>() ).add( file );
				files++;
				bytes += size;
			}

			for( Map.Entry<String, List<Path>> entry : byRepo.entrySet() )
			{
				Map<String, byte[]> contents = new LinkedHashMap<>();
				for( Path file : entry.getValue() )
					contents.put( "maps/" + maps.relativize( file ).toString().replace( File.separatorChar, '/' ),
							Files.readAllBytes( file ) );
				github.commitFiles( entry.getKey(), contents,
						"Mapa: " + contents.size() + " tiles actualizados por Endershare" );
			}

			config.put( "lastPublishMillis", pending == 0 ? startedAt : cutoff );
			Files.writeString( configFile( folder ).toPath(), JSON.writerWithDefaultPrettyPrinter().writeValueAsString( config ),
					StandardCharsets.UTF_8 );
			Log.event( "MAP_PUBLISH", "Subidos " + files + " ficheros (" + bytes / 1048576 + " MB), "
					+ skippedHires + " hires no visitados omitidos, " + pending + " pendientes" );
			return new Report( files, bytes, skippedHires, pending );
		}
	}

	private static List<Path> changedFiles( Path maps, long since ) throws Exception
	{
		List<Path> result = new ArrayList<>();
		try( Stream<Path> walk = Files.walk( maps ) )
		{
			for( Path file : walk.toList() )
			{
				if( !Files.isRegularFile( file ) )
					continue;
				String name = file.getFileName().toString();
				String rel = maps.relativize( file ).toString();
				if( name.startsWith( "." ) || name.endsWith( ".filepart" ) || rel.contains( "live" + File.separator ) )
					continue;
				if( Files.getLastModifiedTime( file ).toMillis() > since )
					result.add( file );
			}
		}
		// Orden por mtime: permite avanzar el umbral entre rondas sin repetir
		result.sort( ( a, b ) ->
		{
			try
			{
				return Long.compare( Files.getLastModifiedTime( a ).toMillis(), Files.getLastModifiedTime( b ).toMillis() );
			}
			catch( Exception unreadable )
			{
				return 0;
			}
		} );
		return result;
	}

	/** Un tile hires esta "visitado" si su celda (mapa:x,z) la cargo alguien. */
	private static boolean isVisited( String rel )
	{
		// rel = maps/<mapa>/tiles/0/<ruta partida>.json.gz
		String[] parts = rel.split( "/" );
		if( parts.length < 5 )
			return false;
		String map = parts[1];
		StringBuilder xs = new StringBuilder(), zs = new StringBuilder();
		boolean onZ = false;
		for( int i = 4; i < parts.length; i++ )
		{
			String piece = i == parts.length - 1 ? parts[i].substring( 0, parts[i].indexOf( '.' ) ) : parts[i];
			if( piece.startsWith( "x" ) )
				xs.append( piece.substring( 1 ) );
			else if( piece.startsWith( "z" ) )
			{
				onZ = true;
				zs.append( piece.substring( 1 ) );
			}
			else if( onZ )
				zs.append( piece );
			else
				xs.append( piece );
		}
		try
		{
			return visitedTiles.contains( map + ":" + Integer.parseInt( xs.toString() ) + "," + Integer.parseInt( zs.toString() ) );
		}
		catch( NumberFormatException odd )
		{
			return false;
		}
	}

	// ---- FASE 3 — Persistencia -------------------------------------------

	static File configFile( File folder )
	{
		return new File( new File( folder, "p2pmss" ), "map-publish.json" );
	}

	private static File visitedFile( File folder )
	{
		return new File( new File( folder, "p2pmss" ), "map-visited.json" );
	}

	private static void loadVisited( File folder )
	{
		visitedTiles.clear();
		File file = visitedFile( folder );
		if( !file.exists() )
			return;
		try
		{
			for( JsonNode tile : JSON.readTree( file ) )
				visitedTiles.add( tile.asText() );
		}
		catch( Exception unreadable )
		{
			Log.event( "MAP_PUBLISH", "map-visited.json ilegible, se empieza de cero", unreadable );
		}
	}

	private static void saveVisited( File folder )
	{
		try
		{
			ArrayNode list = JSON.createArrayNode();
			synchronized( visitedTiles )
			{
				for( String tile : visitedTiles )
					list.add( tile );
			}
			File file = visitedFile( folder );
			file.getParentFile().mkdirs();
			Files.writeString( file.toPath(), list.toString(), StandardCharsets.UTF_8 );
		}
		catch( Exception failed )
		{
			Log.event( "MAP_PUBLISH", "No se pudo guardar map-visited.json", failed );
		}
	}

	// ---- FASE 4 — Cliente minimo de la Git Data API -----------------------

	static final class GitHub
	{
		private final String token;
		private final HttpClient http = HttpClient.newBuilder().connectTimeout( Duration.ofSeconds( 15 ) ).build();

		GitHub( String token )
		{
			this.token = token;
		}

		private JsonNode call( String method, String path, JsonNode body ) throws Exception
		{
			HttpRequest.Builder request = HttpRequest.newBuilder( URI.create( API + path ) )
					.timeout( Duration.ofSeconds( 60 ) )
					.header( "Authorization", "Bearer " + token )
					.header( "Accept", "application/vnd.github+json" );
			if( body == null )
				request.method( method, HttpRequest.BodyPublishers.noBody() );
			else
				request.header( "Content-Type", "application/json" )
						.method( method, HttpRequest.BodyPublishers.ofString( body.toString() ) );
			HttpResponse<String> response = http.send( request.build(), HttpResponse.BodyHandlers.ofString() );
			if( response.statusCode() / 100 != 2 )
				throw new IllegalStateException( method + " " + path + " → " + response.statusCode() + ": "
						+ response.body().substring( 0, Math.min( 200, response.body().length() ) ) );
			return JSON.readTree( response.body() );
		}

		/** Rutas y tamaños del arbol de main: decide donde va cada tile. */
		Map<String, Long> treeSizes( String repo ) throws Exception
		{
			Map<String, Long> sizes = new HashMap<>();
			JsonNode tree = call( "GET", "/repos/" + repo + "/git/trees/main?recursive=1", null );
			for( JsonNode entry : tree.path( "tree" ) )
				if( "blob".equals( entry.path( "type" ).asText() ) )
					sizes.put( entry.path( "path" ).asText(), entry.path( "size" ).asLong( 0 ) );
			return sizes;
		}

		private static final int SLICE = 100;

		/**
		 * GitHub rechaza arboles grandes de golpe (422 "input too large"): se
		 * commitea en rebanadas encadenadas, y si una rebanada aun es demasiado
		 * se parte por la mitad hasta un minimo de 10.
		 */
		void commitFiles( String repo, Map<String, byte[]> files, String message ) throws Exception
		{
			List<Map.Entry<String, byte[]>> all = new ArrayList<>( files.entrySet() );
			int size = SLICE;
			int from = 0;
			while( from < all.size() )
			{
				int to = Math.min( all.size(), from + size );
				Map<String, byte[]> slice = new LinkedHashMap<>();
				for( Map.Entry<String, byte[]> entry : all.subList( from, to ) )
					slice.put( entry.getKey(), entry.getValue() );
				try
				{
					commitSlice( repo, slice, message + " (" + ( to ) + "/" + all.size() + ")" );
					from = to;
				}
				catch( IllegalStateException failed )
				{
					if( !failed.getMessage().contains( "422" ) || size <= 10 )
						throw failed;
					size = Math.max( 10, size / 2 );
				}
			}
		}

		private void commitSlice( String repo, Map<String, byte[]> files, String message ) throws Exception
		{
			JsonNode ref = call( "GET", "/repos/" + repo + "/git/ref/heads/main", null );
			String parent = ref.path( "object" ).path( "sha" ).asText();
			JsonNode parentCommit = call( "GET", "/repos/" + repo + "/git/commits/" + parent, null );
			String baseTree = parentCommit.path( "tree" ).path( "sha" ).asText();

			ArrayNode entries = JSON.createArrayNode();
			for( Map.Entry<String, byte[]> file : files.entrySet() )
			{
				ObjectNode blob = JSON.createObjectNode();
				blob.put( "content", Base64.getEncoder().encodeToString( file.getValue() ) );
				blob.put( "encoding", "base64" );
				String sha = call( "POST", "/repos/" + repo + "/git/blobs", blob ).path( "sha" ).asText();
				ObjectNode entry = JSON.createObjectNode();
				entry.put( "path", file.getKey() );
				entry.put( "mode", "100644" );
				entry.put( "type", "blob" );
				entry.put( "sha", sha );
				entries.add( entry );
			}
			ObjectNode tree = JSON.createObjectNode();
			tree.put( "base_tree", baseTree );
			tree.set( "tree", entries );
			String treeSha = call( "POST", "/repos/" + repo + "/git/trees", tree ).path( "sha" ).asText();

			ObjectNode commit = JSON.createObjectNode();
			commit.put( "message", message );
			commit.put( "tree", treeSha );
			commit.set( "parents", JSON.createArrayNode().add( parent ) );
			String commitSha = call( "POST", "/repos/" + repo + "/git/commits", commit ).path( "sha" ).asText();

			ObjectNode update = JSON.createObjectNode();
			update.put( "sha", commitSha );
			call( "PATCH", "/repos/" + repo + "/git/refs/heads/main", update );
		}
	}

}
