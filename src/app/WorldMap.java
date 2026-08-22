package app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Mapa 3D del mundo, navegable desde el navegador.
 *
 * <p>El renderizador es BlueMap (licencia MIT), que ya esta escrito en Java. En
 * vez de meterlo en el classpath de la aplicacion, se descarga su jar la primera
 * vez que hace falta y se ejecuta como <b>proceso aparte</b>. Tres razones:
 * el instalador no engorda con 4 MB que casi nadie va a usar, un render que se
 * atragante no puede llevarse la aplicacion por delante, y el proceso se puede
 * lanzar con prioridad baja para que renderizar no estorbe.</p>
 *
 * <p>La comunicacion es por su salida de texto, que {@link WorldMapProgress}
 * traduce a la barra de progreso de siempre.</p>
 *
 * <p><b>Nunca escribe dentro del repositorio del mundo</b>: el mapa vive en la
 * carpeta de datos de la aplicacion. Son 10 GB de ficheros regenerables y no
 * tienen nada que hacer viajando por GitHub en cada respaldo.</p>
 */
public final class WorldMap
{
	/** En que punto esta el mapa de un mundo. */
	public enum State
	{
		/** No se ha generado nunca. */
		MISSING,
		/** Generandose ahora mismo. */
		RENDERING,
		/** Generado y listo para abrir. */
		READY
	}

	/**
	 * Version del renderizador. La 3.13 cubre mundos hasta Minecraft 1.19.4,
	 * que es lo que corren los mundos de esta aplicacion. Para mundos mas
	 * nuevos hay que subirla (las versiones 5.x cubren las recientes).
	 */
	static final String RENDERER_VERSION = "3.13";
	private static final String RENDERER_URL = "https://github.com/BlueMap-Minecraft/BlueMap/releases/download/v"
			+ RENDERER_VERSION + "/BlueMap-" + RENDERER_VERSION + "-cli.jar";
	/** Por debajo de esto la descarga vino cortada o es una pagina de error. */
	private static final long MINIMUM_RENDERER_BYTES = 1_000_000L;
	/** Marca de que el mapa de esa carpeta se genero con detalle de bloque. */
	private static final String FULL_DETAIL_MARK = "full-detail";
	/** Marca de que el mapa esta activado para ese mundo. Sin ella, apagado. */
	private static final String ENABLED_MARK = "enabled";
	/** Altura de la camara al abrir: por encima del terreno en las tres dimensiones. */
	private static final int VIEWER_HEIGHT = 200;
	/** Distancia de la camara: lo bastante lejos para ver la zona de una pieza. */
	private static final int VIEWER_DISTANCE = 1200;
	private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes( 5 );

	private static final AtomicReference<Process> runningProcess = new AtomicReference<>();
	private static final AtomicReference<String> runningUrl = new AtomicReference<>();
	private static volatile Path runningWorld;
	/** Cierto cuando la pasada completa termino y el proceso solo vigila cambios. */
	private static volatile boolean watchingOnly = false;
	/** Aviso a la interfaz de que el mapa cambio de estado, para refrescarla. */
	private static volatile Runnable stateListener = null;

	/** La interfaz se apunta aqui para enterarse de cuando termina la pasada. */
	public static void setStateListener( Runnable listener )
	{
		stateListener = listener;
	}

	private static void announceStateChange()
	{
		Runnable listener = stateListener;
		if( listener != null )
			listener.run();
	}

	private WorldMap()
	{
	}

	/** Carpeta donde vive el mapa de un mundo, fuera del repositorio. */
	public static Path directoryFor( Path worldRepository )
	{
		return AppPaths.data().resolve( "maps" ).resolve( identifierFor( worldRepository ) );
	}

	/** Identificador estable y valido como nombre de carpeta en los tres sistemas. */
	static String identifierFor( Path worldRepository )
	{
		Path name = worldRepository.toAbsolutePath().getFileName();
		String base = name == null ? "world" : name.toString();
		String sanitised = base.toLowerCase( Locale.ROOT ).replaceAll( "[^a-z0-9._-]", "-" );
		return sanitised.isBlank() ? "world" : sanitised;
	}

	/**
	 * Encuentra la carpeta del mundo dentro de la del servidor. El nombre lo
	 * decide {@code level-name} en server.properties; si no esta, Minecraft usa
	 * "world". Se comprueba que tenga ficheros de region: una carpeta con el
	 * nombre correcto pero vacia no sirve para renderizar nada.
	 */
	public static Optional<Path> locateWorld( Path serverDirectory )
	{
		List<String> candidates = new ArrayList<>();
		Path properties = serverDirectory.resolve( "server.properties" );
		if( Files.isRegularFile( properties ) )
		{
			try
			{
				for( String line : Files.readAllLines( properties, StandardCharsets.UTF_8 ) )
				{
					String trimmed = line.trim();
					if( trimmed.startsWith( "level-name=" ) )
					{
						String name = trimmed.substring( "level-name=".length() ).trim();
						if( !name.isEmpty() )
							candidates.add( name );
						break;
					}
				}
			}
			catch( IOException unreadable )
			{
				Log.event( "WORLD_MAP", "No se pudo leer " + properties, unreadable );
			}
		}
		candidates.add( "world" );

		Optional<Path> found = Optional.empty();
		for( String candidate : candidates )
		{
			Path directory = serverDirectory.resolve( candidate );
			if( Files.isDirectory( directory.resolve( "region" ) ) )
			{
				found = Optional.of( directory );
				break;
			}
		}
		return found;
	}

	public static State stateFor( Path worldRepository )
	{
		State state = State.MISSING;
		do
		{
			if( isRenderingFor( worldRepository ) )
			{
				state = State.RENDERING;
				break;
			}
			if( hasBuiltMap( worldRepository ) )
				state = State.READY;
		}
		while( false );
		return state;
	}

	/**
	 * Si el mapa esta activado para ese mundo. <b>Viene apagado</b>: renderizar
	 * cuesta tiempo y disco, y quien no quiera mapa no tiene por que pagarlo.
	 * Se enciende por servidor, no para toda la aplicacion, porque no todos los
	 * mundos merecen el gasto.
	 */
	public static boolean isEnabledFor( Path worldRepository )
	{
		return Files.exists( directoryFor( worldRepository ).resolve( ENABLED_MARK ) );
	}

	public static void setEnabledFor( Path worldRepository, boolean enabled ) throws IOException
	{
		Path mark = directoryFor( worldRepository ).resolve( ENABLED_MARK );
		if( enabled )
		{
			Files.createDirectories( mark.getParent() );
			Files.writeString( mark, "The 3D map is enabled for this world.\n" );
		}
		else
		{
			Files.deleteIfExists( mark );
		}
	}

	/**
	 * Con que calidad se genero el mapa que hay en disco. Hace falta para poder
	 * reanudar la vigilancia sin cambiar de calidad a media construccion: mezclar
	 * las dos deja zonas con detalle y zonas sin el, y nadie sabria cuales.
	 */
	public static boolean wasBuiltWithFullDetail( Path worldRepository )
	{
		return Files.exists( directoryFor( worldRepository ).resolve( FULL_DETAIL_MARK ) );
	}

	private static void rememberQuality( Path mapDirectory, boolean fullDetail ) throws IOException
	{
		Path mark = mapDirectory.resolve( FULL_DETAIL_MARK );
		if( fullDetail )
			Files.writeString( mark, "The map in this folder was built block by block.\n" );
		else
			Files.deleteIfExists( mark );
	}

	/** Cierto si ya hay mapa generado en disco, se este renderizando o no. */
	public static boolean hasBuiltMap( Path worldRepository )
	{
		boolean result = false;
		Path tiles = directoryFor( worldRepository ).resolve( "web" ).resolve( "maps" );
		if( Files.isDirectory( tiles ) )
		{
			try (Stream<Path> children = Files.list( tiles ))
			{
				result = children.findAny().isPresent();
			}
			catch( IOException unreadable )
			{
				Log.event( "WORLD_MAP", "No se pudo mirar " + tiles, unreadable );
			}
		}
		return result;
	}

	/** Hay un proceso de mapa vivo para ese mundo, dibujando o vigilando. */
	public static boolean isRunningFor( Path worldRepository )
	{
		Process process = runningProcess.get();
		return process != null && process.isAlive() && worldRepository.equals( runningWorld );
	}

	/** Esta dibujando ahora mismo (la pasada aun no ha terminado). */
	public static boolean isRenderingFor( Path worldRepository )
	{
		return isRunningFor( worldRepository ) && !watchingOnly;
	}

	/**
	 * Termino la pasada y ahora solo vigila: el mundo entero esta dibujado y se
	 * redibuja unicamente lo que cambie. Es el estado en el que interesa
	 * quedarse, y en pantalla no puede confundirse con "construyendo".
	 */
	public static boolean isWatchingFor( Path worldRepository )
	{
		return isRunningFor( worldRepository ) && watchingOnly;
	}

	/** Direccion del mapa mientras el proceso este vivo; vacio si no lo esta. */
	public static Optional<String> currentUrl()
	{
		Process process = runningProcess.get();
		return process != null && process.isAlive() ? Optional.ofNullable( runningUrl.get() ) : Optional.empty();
	}

	/**
	 * Direccion para abrir el visor en un sitio donde de verdad se vea algo.
	 *
	 * <p>Sin esto el visor abre siempre el overworld y a altura cero: mientras se
	 * dibuja el mundo, el overworld puede estar todavia vacio, y una camara a la
	 * altura del suelo (o bajo la lava, en el Nether) parece un mapa roto. Se
	 * elige un mapa que ya tenga contenido y una altura desde la que se ve el
	 * terreno.</p>
	 */
	public static Optional<String> viewerUrlFor( Path worldRepository )
	{
		Optional<String> base = currentUrl();
		if( base.isEmpty() )
			return base;
		Path maps = directoryFor( worldRepository ).resolve( "web" ).resolve( "maps" );
		String map = firstMapWithContent( maps ).orElse( "overworld" );
		// Altura de sobrevuelo y distancia amplia: se ve el terreno de una pieza
		return Optional.of( base.get() + "#" + map + ":0:" + VIEWER_HEIGHT + ":0:" + VIEWER_DISTANCE
				+ ":0:0:0:0:perspective" );
	}

	/** El primer mapa con tiles dibujados, en el orden en que se enseñan. */
	static Optional<String> firstMapWithContent( Path maps )
	{
		if( !Files.isDirectory( maps ) )
			return Optional.empty();
		// Se prefiere el overworld: es lo que la gente espera ver al abrir
		List<String> preferred = List.of( "overworld", "nether", "end" );
		List<String> candidates = new ArrayList<>( preferred );
		try (Stream<Path> children = Files.list( maps ))
		{
			for( Path child : children.toList() )
			{
				String name = child.getFileName().toString();
				if( !candidates.contains( name ) )
					candidates.add( name );
			}
		}
		catch( IOException unreadable )
		{
			Log.event( "WORLD_MAP", "No se pudieron listar los mapas en " + maps, unreadable );
		}
		for( String candidate : candidates )
		{
			if( hasTiles( maps.resolve( candidate ) ) )
				return Optional.of( candidate );
		}
		return Optional.empty();
	}

	/** Un mapa "tiene contenido" cuando su capa mas general ya trae algo dibujado. */
	private static boolean hasTiles( Path map )
	{
		Path tiles = map.resolve( "tiles" );
		if( !Files.isDirectory( tiles ) )
			return false;
		try (Stream<Path> levels = Files.walk( tiles, 4 ))
		{
			return levels.anyMatch( path -> path.getFileName().toString().endsWith( ".json.gz" )
					|| path.getFileName().toString().endsWith( ".png" ) );
		}
		catch( IOException unreadable )
		{
			return false;
		}
	}

	/**
	 * Genera (o actualiza) el mapa y lo deja servido. Vuelve en cuanto el
	 * proceso arranca: el progreso se publica en {@link TransferProgress}.
	 *
	 * @param worldRepository carpeta del repositorio del mundo
	 * @param worldDirectory carpeta del mundo dentro de el (la que tiene level.dat)
	 * @param fullDetail true para calidad maxima; false para la version ligera
	 * @return la direccion donde se sirve el mapa
	 */
	public static String startRendering( Path worldRepository, Path worldDirectory, boolean fullDetail )
			throws IOException
	{
		return startRendering( worldRepository, worldDirectory, fullDetail, false );
	}

	/**
	 * @param gameRunningHere true si en este equipo hay una partida en marcha, para
	 *        renderizar con menos hilos y no estropearla
	 */
	public static String startRendering( Path worldRepository, Path worldDirectory, boolean fullDetail,
			boolean gameRunningHere ) throws IOException
	{
		return startRendering( worldRepository, worldDirectory, fullDetail, gameRunningHere, false );
	}

	/**
	 * @param redrawEverything rehacer el mapa entero en vez de solo lo que haya
	 *        cambiado en el mundo. Hace falta cuando lo que cambia es COMO se
	 *        dibuja (calidad, ajustes de una dimension): el renderizador mira los
	 *        ficheros del mundo, no la configuracion, asi que sin esto no
	 *        redibujaria nada y el boton de rehacer no haria nada.
	 */
	public static String startRendering( Path worldRepository, Path worldDirectory, boolean fullDetail,
			boolean gameRunningHere, boolean redrawEverything ) throws IOException
	{
		stopRendering();

		Path mapDirectory = directoryFor( worldRepository );
		Files.createDirectories( mapDirectory );
		Path renderer = ensureRenderer();

		int port = freePort();
		List<String> dimensions = WorldMapConfig.write( mapDirectory, worldDirectory,
				new WorldMapConfig.Options( fullDetail, WorldMapConfig.threadCountFor( gameRunningHere ), port ) );
		if( dimensions.isEmpty() )
			throw new IOException( "That world has no region files to render yet." );
		rememberQuality( mapDirectory, fullDetail );
		// Construir un mapa es decir que lo quieres: no hay que activarlo aparte
		setEnabledFor( worldRepository, true );

		List<String> command = new ArrayList<>();
		// -r renderiza, -u se queda vigilando los ficheros de region para
		// actualizar solo lo que cambie, -w sirve el visor
		lowPriorityPrefix().ifPresent( command::addAll );
		command.add( javaExecutable() );
		command.add( "-jar" );
		command.add( renderer.toAbsolutePath().toString() );
		command.add( redrawEverything ? "-ruwf" : "-ruw" );

		ProcessBuilder builder = new ProcessBuilder( command );
		builder.directory( mapDirectory.toFile() );
		builder.redirectErrorStream( true );
		Process process = builder.start();

		String url = "http://127.0.0.1:" + port + "/";
		runningProcess.set( process );
		runningUrl.set( url );
		runningWorld = worldRepository;
		watchingOnly = false;
		TransferProgress.publish( "Building map", "Starting renderer", -1 );
		followProgress( process, mapDirectory );
		return url;
	}

	public static void stopRendering()
	{
		Process process = runningProcess.getAndSet( null );
		runningUrl.set( null );
		runningWorld = null;
		watchingOnly = false;
		if( process == null || !process.isAlive() )
			return;
		process.destroy();
		try
		{
			if( !process.waitFor( 5, java.util.concurrent.TimeUnit.SECONDS ) )
				process.destroyForcibly();
		}
		catch( InterruptedException interrupted )
		{
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
		TransferProgress.done();
	}

	/** Descarga el renderizador si aun no esta; devuelve donde ha quedado. */
	static Path ensureRenderer() throws IOException
	{
		Path jar = AppPaths.data().resolve( "maps" ).resolve( "bluemap-" + RENDERER_VERSION + "-cli.jar" );
		if( Files.isRegularFile( jar ) && Files.size( jar ) >= MINIMUM_RENDERER_BYTES )
			return jar;

		Files.createDirectories( jar.getParent() );
		Path partial = jar.resolveSibling( jar.getFileName() + ".part" );
		TransferProgress.publish( "Building map", "Downloading renderer", -1 );
		try (HttpClient client = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.ALWAYS )
				.connectTimeout( Duration.ofSeconds( 30 ) ).build())
		{
			HttpRequest request = HttpRequest.newBuilder( URI.create( RENDERER_URL ) ).timeout( DOWNLOAD_TIMEOUT )
					.GET().build();
			HttpResponse<Path> response = client.send( request, HttpResponse.BodyHandlers.ofFile( partial ) );
			if( response.statusCode() != 200 )
				throw new IOException( "The renderer could not be downloaded (HTTP " + response.statusCode() + ")." );
		}
		catch( InterruptedException interrupted )
		{
			Thread.currentThread().interrupt();
			throw new IOException( "The renderer download was interrupted." );
		}

		if( Files.size( partial ) < MINIMUM_RENDERER_BYTES )
		{
			Files.deleteIfExists( partial );
			throw new IOException( "The renderer download came back incomplete." );
		}
		Files.move( partial, jar, StandardCopyOption.REPLACE_EXISTING );
		return jar;
	}

	/**
	 * Hilo suelto que traduce la salida del renderizador a la barra de progreso,
	 * y de paso la deja escrita.
	 *
	 * <p>El registro no es un lujo: cuando el mapa sale raro, lo que dijo el
	 * renderizador es la unica pista, y sin guardarlo se pierde en cuanto se
	 * cierra el proceso.</p>
	 */
	private static void followProgress( Process process, Path mapDirectory )
	{
		Thread reader = new Thread( () ->
		{
			Path logFile = mapDirectory.resolve( "render.log" );
			try (BufferedReader lines = new BufferedReader(
					new InputStreamReader( process.getInputStream(), StandardCharsets.UTF_8 ) );
					java.io.BufferedWriter log = Files.newBufferedWriter( logFile ))
			{
				String line;
				while( (line = lines.readLine()) != null )
				{
					final String current = line;
					try
					{
						log.write( current );
						log.newLine();
						log.flush();
					}
					catch( IOException notLogged )
					{
						// Sin registro se sigue: la barra de progreso importa mas
					}
					WorldMapProgress.parse( current ).ifPresent( step ->
					{
						if( step.finished() )
						{
							// Fin de la pasada: de aqui en adelante el proceso solo
							// vigila, y la pantalla no puede seguir diciendo "construyendo"
							watchingOnly = true;
							TransferProgress.done();
							announceStateChange();
						}
						else
						{
							if( watchingOnly )
							{
								// Algo ha cambiado en el mundo y se esta redibujando esa zona
								watchingOnly = false;
								announceStateChange();
							}
							TransferProgress.publish( "Building map", step.detail(), step.percent() );
						}
					} );
				}
			}
			catch( IOException closed )
			{
				// El proceso ha terminado o lo hemos parado nosotros: nada que hacer
			}
			finally
			{
				TransferProgress.done();
			}
		}, "world-map-progress" );
		reader.setDaemon( true );
		reader.start();
	}

	private static String javaExecutable()
	{
		Path java = Path.of( System.getProperty( "java.home" ), "bin",
				System.getProperty( "os.name", "" ).toLowerCase( Locale.ROOT ).contains( "win" ) ? "java.exe"
						: "java" );
		return Files.isExecutable( java ) ? java.toAbsolutePath().toString() : "java";
	}

	/** En mac y Linux se baja la prioridad con nice; en Windows no existe. */
	private static Optional<List<String>> lowPriorityPrefix()
	{
		Path nice = Path.of( "/usr/bin/nice" );
		return Files.isExecutable( nice ) ? Optional.of( List.of( nice.toString(), "-n", "10" ) ) : Optional.empty();
	}

	private static int freePort() throws IOException
	{
		try (ServerSocket socket = new ServerSocket( 0 ))
		{
			return socket.getLocalPort();
		}
	}
}
