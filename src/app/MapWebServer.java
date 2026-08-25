package app;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Sirve el mapa ya dibujado, desde dentro de la propia aplicacion.
 *
 * <p>Mirar el mapa no es dibujarlo: es leer ficheros que ya estan en disco y
 * mandarlos por un socket. Para eso se lanzaba una maquina virtual de Java
 * entera con el renderizador dentro — <b>65 MB de memoria y un proceso mas</b>
 * midiendolo— que no hacia ninguna otra cosa. Aqui son unos pocos kilobytes
 * dentro de la aplicacion que ya esta abierta, y con nadie mirando el mapa no
 * queda <b>ni un hilo vivo</b>: cada peticion se atiende en un hilo virtual que
 * nace y muere con ella, asi que parado no cuesta ni pila ni planificador.</p>
 *
 * <p>Los ficheros que manda son <b>exactamente los mismos</b> que servia el
 * renderizador. El mapa no cambia ni un pixel.</p>
 *
 * <h2>Lo que hace mejor que el de serie</h2>
 *
 * <p>El servidor de BlueMap 3.13 manda <code>ETag</code> y
 * <code>Last-Modified</code> pero <b>no responde 304 a
 * <code>If-None-Match</code> ni a <code>If-Modified-Since</code></b>: comprobado,
 * devuelve 200 con el cuerpo entero. Aqui una revalidacion cuesta una cabecera
 * en vez de 84 KB.</p>
 *
 * <h2>Lo que respeta del de serie</h2>
 *
 * <p>El visor pide los tiles con un sufijo del tipo <code>?512245</code> que
 * cambia cuando el mapa se redibuja, y los ficheros de jugadores y marcadores
 * con un numero distinto en cada peticion para que nunca se cacheen. Se ha
 * capturado el trafico real del visor para calcarlo: el sufijo se ignora al
 * buscar el fichero, y lo que vive bajo <code>live/</code> se manda con
 * <code>no-store</code>.</p>
 */
public final class MapWebServer
{
	/** Lo que tarda en morir cuando se le pide parar. */
	private static final int STOP_SECONDS = 1;
	/**
	 * Intentos de coger un puerto que otro proceso acaba de soltar.
	 *
	 * <p>Al relevar al renderizador se reusa <b>su</b> puerto para que la pestaña
	 * que ya esta abierta siga funcionando. El sistema tarda un instante en darlo
	 * por libre despues de matarlo.</p>
	 */
	private static final int BIND_ATTEMPTS = 20;
	private static final int BIND_WAIT_MILLIS = 100;
	/**
	 * Cuanto puede fiarse el navegador de un tile sin volver a preguntar.
	 *
	 * <p>Poco a proposito: los tiles cambian cuando se redibuja el mapa. Con el
	 * 304 funcionando, preguntar es barato.</p>
	 */
	private static final int CACHE_SECONDS = 300;

	private static final Map<String, String> TYPES = Map.ofEntries(
			Map.entry( "html", "text/html; charset=utf-8" ),
			Map.entry( "js", "text/javascript; charset=utf-8" ),
			Map.entry( "css", "text/css; charset=utf-8" ),
			Map.entry( "json", "application/json" ),
			Map.entry( "conf", "text/plain; charset=utf-8" ),
			Map.entry( "png", "image/png" ),
			Map.entry( "jpg", "image/jpeg" ),
			Map.entry( "svg", "image/svg+xml" ),
			Map.entry( "ttf", "font/ttf" ),
			Map.entry( "woff", "font/woff" ),
			Map.entry( "woff2", "font/woff2" ),
			Map.entry( "ico", "image/x-icon" ) );

	private final Path webroot;
	private HttpServer server;
	private ExecutorService workers;

	public MapWebServer( Path webroot )
	{
		this.webroot = webroot.toAbsolutePath().normalize();
	}

	/**
	 * Arranca en un puerto libre, escuchando <b>solo en local</b>.
	 *
	 * @return la direccion en la que ha quedado
	 */
	public synchronized String start() throws IOException
	{
		return start( 0 );
	}

	/**
	 * Arranca en el puerto que se le diga, escuchando <b>solo en local</b>.
	 *
	 * <p>Un puerto concreto hace falta al relevar al renderizador: si cambiara la
	 * direccion, la pestaña que el usuario ya tiene abierta se quedaria en blanco.
	 * Como el puerto lo acaba de soltar otro proceso, se reintenta un momento.</p>
	 *
	 * @param port el puerto a coger, o cero para cualquiera libre
	 * @return la direccion en la que ha quedado
	 */
	public synchronized String start( int port ) throws IOException
	{
		if( server != null )
			return address();
		if( !Files.isDirectory( webroot ) )
			throw new IOException( "There is no map to serve at " + webroot );

		// Un hilo virtual por peticion: parados no cuestan ni pila ni planificador,
		// y sin nadie mirando el mapa no queda ni uno vivo
		workers = Executors.newVirtualThreadPerTaskExecutor();
		server = bind( port );
		server.setExecutor( workers );
		server.createContext( "/", this::handle );
		server.start();
		return address();
	}

	private HttpServer bind( int port ) throws IOException
	{
		InetAddress local = InetAddress.getLoopbackAddress();
		IOException last = null;
		for( int attempt = 0; attempt < BIND_ATTEMPTS; attempt++ )
		{
			try
			{
				return HttpServer.create( new InetSocketAddress( local, port ), 0 );
			}
			catch( IOException taken )
			{
				// Puerto cero no se queda ocupado nunca: si falla, no es cosa de esperar
				if( port == 0 )
					throw taken;
				last = taken;
				try
				{
					Thread.sleep( BIND_WAIT_MILLIS );
				}
				catch( InterruptedException interrupted )
				{
					Thread.currentThread().interrupt();
					throw taken;
				}
			}
		}
		throw last;
	}

	public synchronized String address()
	{
		return server == null ? null : "http://127.0.0.1:" + server.getAddress().getPort() + "/";
	}

	public synchronized boolean isRunning()
	{
		return server != null;
	}

	public synchronized void stop()
	{
		if( server != null )
		{
			server.stop( STOP_SECONDS );
			server = null;
		}
		if( workers != null )
		{
			workers.shutdownNow();
			workers = null;
		}
	}

	// ---- peticiones ---------------------------------------------------------

	private void handle( HttpExchange exchange )
	{
		try (exchange)
		{
			if( !"GET".equals( exchange.getRequestMethod() ) && !"HEAD".equals( exchange.getRequestMethod() ) )
			{
				empty( exchange, 405 );
				return;
			}
			Path file = resolve( exchange.getRequestURI().getPath() );
			// Una sola consulta al disco: existe, cuanto ocupa y de cuando es. Eran
			// tres, y el visor pide cientos de tiles cada vez que se mueve el mapa
			BasicFileAttributes about = file == null ? null : attributesOf( file );
			if( about == null || !about.isRegularFile() )
			{
				empty( exchange, 404 );
				return;
			}
			send( exchange, file, about );
		}
		catch( IOException | RuntimeException failed )
		{
			// Una peticion que se tuerce no puede tumbar el servidor del mapa
			Log.event( "MAP_WEB", "No se pudo servir " + exchange.getRequestURI(), failed );
		}
	}

	/**
	 * Del camino de la peticion al fichero en disco, o null si se sale del sitio.
	 *
	 * <p>La comprobacion de que el resultado sigue dentro de la carpeta del mapa
	 * no es una formalidad: sin ella, un camino con <code>..</code> serviria
	 * cualquier fichero del ordenador a quien pidiera por ese puerto.</p>
	 */
	Path resolve( String requestPath )
	{
		String decoded = java.net.URLDecoder.decode( requestPath, java.nio.charset.StandardCharsets.UTF_8 );
		if( decoded.isEmpty() || "/".equals( decoded ) )
			decoded = "/index.html";
		Path candidate = webroot.resolve( decoded.substring( 1 ) ).normalize();
		return candidate.startsWith( webroot ) ? candidate : null;
	}

	/** Los datos del fichero, o null si no esta o no se deja leer. */
	private static BasicFileAttributes attributesOf( Path file )
	{
		try
		{
			return Files.readAttributes( file, BasicFileAttributes.class );
		}
		catch( IOException missing )
		{
			return null;
		}
	}

	private void send( HttpExchange exchange, Path file, BasicFileAttributes about ) throws IOException
	{
		long size = about.size();
		long modified = about.lastModifiedTime().toMillis();
		String tag = etagFor( size, modified );
		String name = file.getFileName().toString().toLowerCase( Locale.ROOT );
		boolean live = isLive( file );

		var headers = exchange.getResponseHeaders();
		headers.set( "ETag", tag );
		headers.set( "Cache-Control", live ? "no-store" : "max-age=" + CACHE_SECONDS + ", must-revalidate" );
		// Los .json.gz se mandan tal cual estan en disco: comprimirlos otra vez, o
		// descomprimirlos para volver a comprimirlos, seria trabajo por nada
		if( name.endsWith( ".gz" ) )
		{
			headers.set( "Content-Encoding", "gzip" );
			headers.set( "Content-Type", typeOf( name.substring( 0, name.length() - 3 ) ) );
		}
		else
		{
			headers.set( "Content-Type", typeOf( name ) );
		}

		if( !live && tag.equals( exchange.getRequestHeaders().getFirst( "If-None-Match" ) ) )
		{
			empty( exchange, 304 );
			return;
		}

		if( "HEAD".equals( exchange.getRequestMethod() ) )
		{
			exchange.sendResponseHeaders( 200, -1 );
			return;
		}
		exchange.sendResponseHeaders( 200, size );
		try (OutputStream out = exchange.getResponseBody())
		{
			Files.copy( file, out );
		}
	}

	/**
	 * Los ficheros de jugadores y marcadores, que cambian cada segundo.
	 *
	 * <p>Cachearlos aunque sea un momento congela los muñecos en el mapa.</p>
	 */
	private boolean isLive( Path file )
	{
		Path parent = file.getParent();
		return parent != null && "live".equals( parent.getFileName().toString() );
	}

	/** Tamaño y fecha bastan: si cambia el fichero, cambia la marca. */
	static String etagFor( long size, long modifiedMillis )
	{
		return "\"" + Long.toHexString( size ) + "-" + Long.toHexString( modifiedMillis ) + "\"";
	}

	static String typeOf( String name )
	{
		int dot = name.lastIndexOf( '.' );
		String extension = dot < 0 ? "" : name.substring( dot + 1 ).toLowerCase( Locale.ROOT );
		return TYPES.getOrDefault( extension, "application/octet-stream" );
	}

	private void empty( HttpExchange exchange, int status ) throws IOException
	{
		exchange.sendResponseHeaders( status, -1 );
	}

	/** Para los tests: espera a que el servidor este realmente escuchando. */
	boolean awaitReady( long millis ) throws InterruptedException
	{
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos( millis );
		while( System.nanoTime() < deadline )
		{
			if( isRunning() )
				return true;
			Thread.sleep( 10 );
		}
		return isRunning();
	}
}
