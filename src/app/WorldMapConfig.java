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
 *   <li><b>Hilos</b>: la mitad de los del equipo, nunca mas de seis, para que
 *       renderizar no deje el ordenador inservible mientras tanto.</li>
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

	private static final int MAX_RENDER_THREADS = 6;

	private WorldMapConfig()
	{
	}

	/** Mitad de los procesadores: renderizar no puede dejar el equipo inservible. */
	public static int defaultThreadCount()
	{
		return threadCountFor( false );
	}

	/**
	 * Cuantos hilos usar para renderizar.
	 *
	 * <p>Con una partida en marcha en este mismo equipo se baja a un par de
	 * hilos: el juego va primero, y un mapa que tarda el doble pero no da tirones
	 * es mejor que uno rapido que hace injugable la partida.</p>
	 */
	public static int threadCountFor( boolean gameRunningHere )
	{
		int available = Runtime.getRuntime().availableProcessors();
		if( gameRunningHere )
			return Math.max( 1, Math.min( 2, available / 4 ) );
		return Math.max( 1, Math.min( MAX_RENDER_THREADS, available / 2 ) );
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
