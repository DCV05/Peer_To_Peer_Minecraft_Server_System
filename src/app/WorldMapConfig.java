package app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Escribe la configuracion que necesita el renderizador de mapas.
 *
 * <p>Los valores no son los de fabrica: se midieron sobre un mundo real de 1454
 * regiones. Los importantes:</p>
 *
 * <ul>
 *   <li><b>Calidad</b>: la capa de detalle es el 99 % del peso (112 MB frente a
 *       1,6 MB en las mismas 9 regiones). Por eso es una opcion y no algo fijo.</li>
 *   <li><b>Distancias de vision</b>: de fabrica vienen en 100 y 2000 bloques.
 *       Medido a 350 y 7000 el visor seguia a 120 fps, o sea que lo de fabrica
 *       era conservador y se estaba desaprovechando la maquina.</li>
 *   <li><b>Hilos</b>: todos los del equipo menos dos. Era la mitad y nunca mas
 *       de seis, cifra puesta a ojo que en un equipo de catorce nucleos dejaba
 *       ocho parados: medido, doce hilos dibujan casi cuatro veces mas rapido
 *       que dos y sacan un mapa identico tile a tile.</li>
 * </ul>
 */
public final class WorldMapConfig
{
	/** Como se quiere el mapa. */
	public record Options( boolean fullDetail, int renderThreads, int webPort )
	{
		public static Options standard( int webPort )
		{
			return new Options( true, defaultThreadCount(), webPort );
		}
	}

	/**
	 * Una dimension del mundo y como hay que dibujarla.
	 *
	 * <p>Cada dimension necesita ajustes propios, y equivocarlos no da ningun
	 * error: sale un mapa vacio o negro. Los valores son los que usa BlueMap por
	 * defecto para cada una.</p>
	 *
	 * @param removeCavesBelowY altura por debajo de la cual se tapan las cuevas.
	 *        En el Nether hay que desactivarlo (-10000): el Nether ENTERO es una
	 *        cueva, y con el valor del overworld se borra casi todo el mapa.
	 * @param maxY techo del render, o null para no cortar. El Nether tiene una
	 *        plancha de piedra base arriba: sin cortarla solo se ve eso.
	 * @param skyLight luz de cielo. Nether y End no tienen cielo, y con el valor
	 *        del overworld se ven mal iluminados.
	 */
	private record Dimension( String id, String displayName, String relativePath, int sorting, String skyColor,
			double ambientLight, int skyLight, int removeCavesBelowY, Integer maxY )
	{
	}

	private static final List<Dimension> DIMENSIONS = List.of(
			new Dimension( "overworld", "Overworld", "", 0, "#7dabff", 0.1, 15, 55, null ),
			new Dimension( "nether", "Nether", "DIM-1", 1, "#290000", 0.6, 0, -10000, 90 ),
			new Dimension( "end", "End", "DIM1", 2, "#080010", 0.6, 0, -10000, null ) );

	/**
	 * Tope de hilos. A partir de doce lo que se gana ya casi no se nota (de 8 a 12
	 * hilos, un 50 % mas de hilos compra un 26 % de tiempo), y el equipo empieza a
	 * ir a trompicones.
	 */
	private static final int MAX_RENDER_THREADS = 12;
	/** Nucleos que se dejan libres para que el equipo siga usable mientras dibuja. */
	private static final int CORES_LEFT_FOR_THE_REST = 2;

	private WorldMapConfig()
	{
	}

	/** Todos los procesadores menos dos: renderizar no puede dejar el equipo inservible. */
	public static int defaultThreadCount()
	{
		return threadCountFor( false );
	}

	/**
	 * Cuantos hilos usar para renderizar.
	 *
	 * <p>Con una partida en marcha en este mismo equipo, <b>uno</b>. Eran dos y se
	 * quedo corto: cada hilo no es solo CPU, es otro que escribe al disco a la
	 * vez, y con el disco ocupado el guardado del mundo paso de 4 a 45 segundos
	 * hasta que el vigilante de Minecraft tumbo el servidor. Un mapa que tarda el
	 * doble pero deja jugar es mejor que uno rapido que tira la partida.</p>
	 *
	 * <p><b>Sin partida, todo lo que la maquina pueda menos dos nucleos.</b> Antes
	 * era la mitad y nunca mas de seis, que era una cifra puesta a ojo. Medido
	 * sobre 3894 tiles del mundo real, en un equipo de catorce nucleos:</p>
	 *
	 * <ul>
	 *   <li>2 hilos &rarr; 208 s</li>
	 *   <li>4 hilos &rarr; 116 s</li>
	 *   <li>8 hilos &rarr; 68 s</li>
	 *   <li>12 hilos &rarr; 54 s (<b>casi cuatro veces</b> mas rapido que con dos)</li>
	 * </ul>
	 *
	 * <p>Y el mapa que sale es el mismo: se compararon los 3849 tiles de las dos
	 * pasadas y coinciden todos salvo un identificador aleatorio que el
	 * renderizador estampa en cada uno y que no pinta nada.</p>
	 *
	 * <p>Los dos nucleos que se dejan libres son para que el equipo siga usable
	 * mientras dibuja.</p>
	 */
	public static int threadCountFor( boolean gameRunningHere )
	{
		if( gameRunningHere )
			return 1;
		int available = Runtime.getRuntime().availableProcessors();
		return Math.max( 1, Math.min( MAX_RENDER_THREADS, available - CORES_LEFT_FOR_THE_REST ) );
	}

	/**
	 * Deja la configuracion escrita en {@code mapDirectory/config}.
	 *
	 * @param worldDirectory carpeta del mundo (la que tiene level.dat)
	 * @return las dimensiones para las que se ha generado mapa
	 */
	public static List<String> write( Path mapDirectory, Path worldDirectory, Options options ) throws IOException
	{
		Path config = mapDirectory.resolve( "config" );
		Files.createDirectories( config.resolve( "maps" ) );
		Files.createDirectories( config.resolve( "storages" ) );

		write( config.resolve( "core.conf" ), core( options ) );
		write( config.resolve( "webapp.conf" ), webapp() );
		write( config.resolve( "webserver.conf" ), webserver( options ) );
		write( config.resolve( "storages" ).resolve( "file.conf" ), storage() );

		List<String> generated = new ArrayList<>();
		for( Dimension dimension : DIMENSIONS )
		{
			Path dimensionDirectory = dimension.relativePath().isEmpty()
					? worldDirectory
					: worldDirectory.resolve( dimension.relativePath() );
			// Un mundo sin Nether ni End no tiene por que generar mapas vacios
			if( !Files.isDirectory( dimensionDirectory.resolve( "region" ) ) )
				continue;
			write( config.resolve( "maps" ).resolve( dimension.id() + ".conf" ),
					map( dimension, dimensionDirectory, options ) );
			generated.add( dimension.id() );
		}
		return generated;
	}

	private static void write( Path file, String content ) throws IOException
	{
		Files.writeString( file, content, StandardCharsets.UTF_8 );
	}

	private static String core( Options options )
	{
		return """
				accept-download: true
				data: "data"
				render-thread-count: %d
				scan-for-mod-resources: true
				metrics: false
				""".formatted( options.renderThreads() );
	}

	/**
	 * Distancias de vision al maximo medido. De fabrica: 100 y 2000. Medido a
	 * 350 y 7000 el visor seguia dando 120 fps.
	 */
	private static String webapp()
	{
		return """
				enabled: true
				webroot: "web"
				update-settings-file: true
				use-cookies: true
				enable-free-flight: true
				min-zoom-distance: 5
				max-zoom-distance: 100000
				resolution-default: 1
				hires-slider-max: 1000
				hires-slider-default: 350
				hires-slider-min: 0
				lowres-slider-max: 7000
				lowres-slider-default: 7000
				lowres-slider-min: 500
				scripts: [
				    "%s"
				]
				""".formatted( WorldMapViewer.SCRIPT_PATH );
	}

	private static String webserver( Options options )
	{
		// Solo escucha en local: el mapa se comparte publicando la direccion, no
		// abriendo un puerto al mundo sin que nadie lo haya pedido
		return """
				enabled: true
				webroot: "web"
				ip: "127.0.0.1"
				port: %d
				""".formatted( options.webPort() );
	}

	private static String storage()
	{
		return """
				storage-type: FILE
				root: "web/maps"
				compression: GZIP
				""";
	}

	private static String map( Dimension dimension, Path dimensionDirectory, Options options )
	{
		String ceiling = dimension.maxY() == null ? "" : "max-y: " + dimension.maxY() + "\n";
		return """
				name: "%s"
				world: "%s"
				sorting: %d
				sky-color: "%s"
				ambient-light: %s
				world-sky-light: %d
				remove-caves-below-y: %d
				cave-detection-ocean-floor: -5
				cave-detection-uses-block-light: false
				min-inhabited-time: 0
				render-edges: true
				save-hires-layer: %s
				storage: "file"
				ignore-missing-light-data: false
				%smarker-sets: {
				}
				""".formatted( dimension.displayName(),
				dimensionDirectory.toAbsolutePath().toString().replace( "\\", "\\\\" ),
				dimension.sorting(),
				dimension.skyColor(),
				dimension.ambientLight(),
				dimension.skyLight(),
				dimension.removeCavesBelowY(),
				options.fullDetail(),
				ceiling );
	}
}
