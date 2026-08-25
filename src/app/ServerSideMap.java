package app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * El renderizador de mapas que algunos mundos llevan dentro, como mod.
 *
 * <p>El mapa de la aplicacion lo dibuja un proceso aparte
 * ({@link WorldMap}). Pero si el mundo trae ademas el mod de BlueMap en su
 * carpeta de mods, dentro del servidor arranca un <b>segundo</b> renderizador
 * que dibuja lo mismo otra vez, en la misma maquina y sobre el mismo disco.</p>
 *
 * <p>Eso no es solo trabajo repetido: ese segundo renderizador vive <b>dentro
 * del proceso del servidor</b>. Cuando le disputa el disco al guardado del
 * mundo, el guardado pasa de cuatro segundos a cuarenta y cinco, y el vigilante
 * de Minecraft da el servidor por colgado y lo mata con la gente dentro. Paso de
 * verdad.</p>
 *
 * <p>La solucion no puede ser borrar sus ficheros de configuracion: el mod los
 * vuelve a escribir en cada arranque si no los encuentra. Lo que si respeta es
 * un valor que ya este puesto, y tiene justo el que hace falta:
 * {@code player-render-limit}, el numero de jugadores a partir del cual deja de
 * dibujar. Puesto a uno, en cuanto alguien entra a jugar se para solo.</p>
 */
public final class ServerSideMap
{
	/** Con uno basta: en cuanto entra alguien a jugar, el mod deja de dibujar. */
	static final String SETTING = "player-render-limit";
	private static final String WANTED_LINE = SETTING + ": 1";
	private static final String EXPLANATION = """
			# Puesto por Endershare: el mapa lo dibuja la aplicacion en un proceso
			# aparte, y este mod dibujandolo otra vez dentro del servidor le quita el
			# disco al guardado del mundo hasta tumbarlo. Con 1, mientras haya alguien
			# jugando este no dibuja.
			""";

	private ServerSideMap()
	{
	}

	/**
	 * Deja el mod (si lo hay) configurado para no dibujar mientras se juega.
	 *
	 * @return true si ha habido que cambiar algo
	 */
	public static boolean pauseWhileAnyonePlays( Path serverDirectory )
	{
		boolean changed = false;
		do
		{
			if( !hasMapMod( serverDirectory ) )
				break;
			Path configuration = serverDirectory.resolve( "config" ).resolve( "bluemap" ).resolve( "plugin.conf" );
			String before = read( configuration );
			String after = withRenderPaused( before );
			if( after.equals( before ) )
				break;
			try
			{
				Files.createDirectories( configuration.getParent() );
				Files.writeString( configuration, after, StandardCharsets.UTF_8 );
				Log.event( "WORLD_MAP", "El mapa que el mundo lleva dentro ya no dibuja mientras se juega" );
				changed = true;
			}
			catch( IOException notWritten )
			{
				// Sin esto solo se pierde la optimizacion: el servidor arranca igual
				Log.event( "WORLD_MAP", "No se pudo ajustar " + configuration, notWritten );
			}
		}
		while( false );
		return changed;
	}

	/** Cierto cuando el mundo trae el mod de mapas en su carpeta de mods. */
	public static boolean hasMapMod( Path serverDirectory )
	{
		Path mods = serverDirectory.resolve( "mods" );
		if( !Files.isDirectory( mods ) )
			return false;
		try (Stream<Path> jars = Files.list( mods ))
		{
			return jars.anyMatch( jar -> jar.getFileName().toString().toLowerCase( java.util.Locale.ROOT )
					.startsWith( "bluemap" ) );
		}
		catch( IOException unreadable )
		{
			return false;
		}
	}

	/**
	 * El contenido de plugin.conf con el ajuste puesto.
	 *
	 * <p>Se respeta todo lo demas del fichero, incluidos los comentarios: es
	 * configuracion de la persona que juega, no nuestra. Y si el ajuste ya estaba
	 * como lo queremos se devuelve el texto tal cual, para no reescribir el
	 * fichero en cada arranque.</p>
	 *
	 * @param current contenido actual, o null si el fichero todavia no existe
	 */
	static String withRenderPaused( String current )
	{
		if( current == null || current.isBlank() )
			return EXPLANATION + WANTED_LINE + "\n";

		List<String> result = new ArrayList<>();
		boolean found = false;
		for( String line : current.split( "\n", -1 ) )
		{
			// Una linea comentada no es el ajuste: es la explicacion de que existe
			if( line.strip().startsWith( SETTING ) )
			{
				found = true;
				result.add( WANTED_LINE );
			}
			else
			{
				result.add( line );
			}
		}
		String rewritten = String.join( "\n", result );
		if( found )
			return rewritten;
		String separator = rewritten.endsWith( "\n" ) ? "" : "\n";
		return rewritten + separator + "\n" + EXPLANATION + WANTED_LINE + "\n";
	}

	private static String read( Path configuration )
	{
		try
		{
			return Files.isRegularFile( configuration ) ? Files.readString( configuration, StandardCharsets.UTF_8 )
					: null;
		}
		catch( IOException unreadable )
		{
			return null;
		}
	}
}
