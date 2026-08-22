package app;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee lo que BlueMap escribe por su salida y lo traduce a algo que la barra de
 * progreso entienda. El renderizador corre como proceso aparte (asi no puede
 * llevarse la aplicacion por delante si se atraganta), y su unica forma de
 * contar como va son lineas de texto como estas:
 *
 * <pre>
 * [INFO] Start updating 3 maps (1448 regions, ~1482752 chunks)...
 * [INFO] Update map 'overworld': 26.431% (ETA: 00:47:19)
 * [INFO] Your maps are now all up-to-date!
 * </pre>
 */
public final class WorldMapProgress
{
	/** Lo leido en una linea. {@code percent} -1 = aun no se sabe. */
	public record Step( String detail, int percent, boolean finished )
	{
	}

	private static final Pattern UPDATE_LINE = Pattern
			.compile( "Update map '([^']+)':\\s*([0-9]+(?:\\.[0-9]+)?)%(?:\\s*\\(ETA:\\s*([0-9:]+)\\))?" );
	private static final Pattern START_LINE = Pattern
			.compile( "Start updating\\s+(\\d+)\\s+maps?\\s*\\((\\d+)\\s+regions" );
	private static final String FINISHED_MARK = "up-to-date";

	private WorldMapProgress()
	{
	}

	/** Vacio si la linea no dice nada del progreso (avisos, arranque del servidor web...). */
	public static Optional<Step> parse( String line )
	{
		Optional<Step> result = Optional.empty();
		do
		{
			if( line == null || line.isBlank() )
				break;

			if( line.contains( FINISHED_MARK ) )
			{
				result = Optional.of( new Step( "Map ready", 100, true ) );
				break;
			}

			Matcher update = UPDATE_LINE.matcher( line );
			if( update.find() )
			{
				int percent = (int) Math.min( 100, Math.round( Double.parseDouble( update.group( 2 ) ) ) );
				String eta = update.group( 3 );
				// El nombre interno del mapa no le dice nada a nadie: se enseña la ETA,
				// que es lo unico que el usuario quiere saber mientras espera
				String detail = eta == null || eta.isBlank() ? "Rendering world" : "Rendering world · " + eta + " left";
				result = Optional.of( new Step( detail, percent, false ) );
				break;
			}

			Matcher start = START_LINE.matcher( line );
			if( start.find() )
			{
				result = Optional.of( new Step( "Reading " + start.group( 2 ) + " regions", -1, false ) );
				break;
			}
		}
		while( false );
		return result;
	}
}
