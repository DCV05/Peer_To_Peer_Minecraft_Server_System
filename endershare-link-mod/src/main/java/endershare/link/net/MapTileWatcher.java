package endershare.link.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import endershare.link.EndershareLink;

/**
 * Vigila los tiles que BlueMap re-renderiza y avisa por WebSocket para que el
 * visor recargue el mapa. Sin WatchService: un barrido por mtime cada pocos
 * segundos — el mtime de un directorio cambia al escribirse un fichero dentro,
 * asi que solo se desciende a los directorios tocados (cientos de stats, no
 * decenas de miles). Portable a macOS y Windows, donde WatchService cojea.
 */
public final class MapTileWatcher
{

	private static final long SCAN_INTERVAL_MILLIS = 4000;

	private final Path webroot;
	private volatile boolean running = false;
	private Thread worker = null;
	private long lastScanMillis = 0;

	public MapTileWatcher( Path webroot )
	{
		this.webroot = webroot;
	}

	public synchronized void start()
	{
		if( running || !Files.isDirectory( webroot.resolve( "maps" ) ) )
			return;
		running = true;
		lastScanMillis = System.currentTimeMillis();
		worker = new Thread( this::loop, "endershare-link-map-watcher" );
		worker.setDaemon( true );
		worker.start();
	}

	public synchronized void stop()
	{
		running = false;
		if( worker != null )
			worker.interrupt();
		worker = null;
	}

	private void loop()
	{
		while( running )
		{
			try
			{
				Thread.sleep( SCAN_INTERVAL_MILLIS );
			}
			catch( InterruptedException interrupted )
			{
				return;
			}
			if( WsSessions.count() == 0 )
			{
				// Sin nadie mirando no se escanea; el proximo barrido cubre lo
				// acumulado porque el umbral no avanza
				continue;
			}
			try
			{
				scan();
			}
			catch( Exception failed )
			{
				EndershareLink.LOGGER.warn( "Barrido de tiles fallido: {}", failed.toString() );
			}
		}
	}

	private void scan() throws IOException
	{
		long threshold = lastScanMillis;
		long startedAt = System.currentTimeMillis();
		List<String[]> changed = new ArrayList<>();

		Path maps = webroot.resolve( "maps" );
		try( Stream<Path> mapDirs = Files.list( maps ) )
		{
			for( Path mapDir : mapDirs.toList() )
			{
				Path tiles = mapDir.resolve( "tiles" );
				if( !Files.isDirectory( tiles ) )
					continue;
				try( Stream<Path> lodDirs = Files.list( tiles ) )
				{
					for( Path lodDir : lodDirs.toList() )
						scanLod( mapDir.getFileName().toString(), lodDir, threshold, changed );
				}
			}
		}

		lastScanMillis = startedAt;
		if( changed.isEmpty() )
			return;

		JsonArray tiles = new JsonArray();
		for( String[] tile : changed )
		{
			JsonObject entry = new JsonObject();
			entry.addProperty( "map", tile[0] );
			entry.addProperty( "lod", tile[1] );
			entry.addProperty( "tile", tile[2] );
			tiles.add( entry );
		}
		JsonObject payload = new JsonObject();
		payload.add( "tiles", tiles );
		JsonObject message = new JsonObject();
		message.addProperty( "type", "maptile" );
		message.addProperty( "from", "server" );
		message.addProperty( "ts", System.currentTimeMillis() );
		message.add( "payload", payload );
		WsSessions.broadcast( message.toString() );
	}

	// Ojo: reemplazar un tile solo toca el mtime de su directorio x*, no el del
	// LOD, asi que aqui no se puede podar por el mtime del propio lodDir
	private static void scanLod( String map, Path lodDir, long threshold, List<String[]> changed ) throws IOException
	{
		try( Stream<Path> xDirs = Files.list( lodDir ) )
		{
			for( Path xDir : xDirs.toList() )
			{
				if( !Files.isDirectory( xDir ) || mtime( xDir ) < threshold )
					continue;
				try( Stream<Path> files = Files.list( xDir ) )
				{
					for( Path file : files.toList() )
						if( mtime( file ) >= threshold )
							changed.add( new String[] { map, lodDir.getFileName().toString(),
									xDir.getFileName() + "/" + file.getFileName() } );
				}
			}
		}
	}

	private static long mtime( Path path )
	{
		try
		{
			BasicFileAttributes about = Files.readAttributes( path, BasicFileAttributes.class );
			return about.lastModifiedTime().toMillis();
		}
		catch( IOException missing )
		{
			return 0;
		}
	}

}
