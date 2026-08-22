package app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lee la base del mod Ledger, que apunta cada bloque puesto o roto con quien lo
 * hizo, donde y cuando.
 *
 * <p><b>Solo lectura, siempre.</b> Quien escribe ahi es el servidor de
 * Minecraft mientras se juega; esta clase se limita a mirar. La conexion se
 * abre en modo lectura y con un tiempo de espera corto para que, si el servidor
 * esta escribiendo justo en ese momento, se ceda el paso en vez de bloquearse.</p>
 *
 * <p>La lectura es incremental por identificador: cada consulta pide solo lo
 * que haya despues del ultimo visto, que es la clave primaria y por tanto una
 * busqueda directa, no un repaso de toda la tabla. Sin eso, mirar cada pocos
 * segundos una base de cientos de miles de filas se comeria el disco.</p>
 */
public final class LedgerDatabase
{
	/** Nombre del fichero que crea Ledger dentro de la carpeta del mundo. */
	public static final String DATABASE_FILE = "ledger.sqlite";
	/** Techo por consulta: una racha de minado no puede traerse media base de golpe. */
	static final int MAX_ROWS_PER_READ = 500;

	// Las tablas con nombre explicito van en minuscula; las otras las nombro
	// Exposed con el nombre de la clase, de ahi las mayusculas
	private static final String QUERY = """
			SELECT a.id, a.time, a.x, a.y, a.z,
			       ai.action_identifier AS action,
			       oi.identifier        AS block,
			       w.identifier         AS world,
			       p.player_name        AS player
			FROM actions a
			JOIN ActionIdentifiers ai ON ai.id = a.action_id
			JOIN ObjectIdentifiers oi ON oi.id = a.object_id
			JOIN worlds w             ON w.id  = a.world_id
			LEFT JOIN players p       ON p.id  = a.player_id
			WHERE a.id > ? AND ai.action_identifier IN ('block-place', 'block-break')
			ORDER BY a.id
			LIMIT ?
			""";

	/** Calendario en UTC para leer las horas tal y como las guarda el mod. */
	private static final ThreadLocal<java.util.Calendar> UTC_CALENDAR = ThreadLocal
			.withInitial( () -> java.util.Calendar.getInstance( java.util.TimeZone.getTimeZone( "UTC" ) ) );

	private LedgerDatabase()
	{
	}

	/** Donde deja Ledger su base: dentro de la carpeta del mundo. */
	public static Path databaseIn( Path worldDirectory )
	{
		return worldDirectory.resolve( DATABASE_FILE );
	}

	public static boolean isInstalledIn( Path worldDirectory )
	{
		return Files.isRegularFile( databaseIn( worldDirectory ) );
	}

	/**
	 * Lo ocurrido despues de {@code afterId}, en orden.
	 *
	 * @return lista vacia si no hay nada nuevo, si el mod no esta instalado, o
	 *         si la base esta ocupada en ese instante
	 */
	public static List<BlockActivity> readNew( Path worldDirectory, long afterId )
	{
		List<BlockActivity> found = new ArrayList<>();
		Path database = databaseIn( worldDirectory );
		if( !Files.isRegularFile( database ) )
			return found;

		String url = "jdbc:sqlite:file:" + database.toAbsolutePath().toString().replace( '\\', '/' )
				+ "?mode=ro&immutable=0";
		try (Connection connection = DriverManager.getConnection( url ))
		{
			// Si el servidor esta escribiendo, se espera un poco y se abandona:
			// lo que no se lea ahora se leera en la siguiente pasada
			connection.createStatement().execute( "PRAGMA busy_timeout = 750" );
			try (PreparedStatement statement = connection.prepareStatement( QUERY ))
			{
				statement.setLong( 1, afterId );
				statement.setInt( 2, MAX_ROWS_PER_READ );
				try (ResultSet rows = statement.executeQuery())
				{
					while( rows.next() )
						found.add( toActivity( rows ) );
				}
			}
		}
		catch( SQLException unavailable )
		{
			// Base ocupada, a medio crear o de una version que no entendemos: no es
			// motivo para molestar a nadie, se reintenta en la siguiente pasada
			Log.event( "LEDGER", "No se pudo leer " + database, unavailable );
		}
		return found;
	}

	/** El identificador mas alto que hay ahora mismo, para empezar a mirar desde ahi. */
	public static Optional<Long> lastId( Path worldDirectory )
	{
		Path database = databaseIn( worldDirectory );
		if( !Files.isRegularFile( database ) )
			return Optional.empty();
		String url = "jdbc:sqlite:file:" + database.toAbsolutePath().toString().replace( '\\', '/' ) + "?mode=ro";
		try (Connection connection = DriverManager.getConnection( url );
				ResultSet rows = connection.createStatement().executeQuery( "SELECT MAX(id) FROM actions" ))
		{
			return rows.next() ? Optional.of( rows.getLong( 1 ) ) : Optional.empty();
		}
		catch( SQLException unavailable )
		{
			Log.event( "LEDGER", "No se pudo leer el ultimo id de " + database, unavailable );
			return Optional.empty();
		}
	}

	private static BlockActivity toActivity( ResultSet rows ) throws SQLException
	{
		// Ledger guarda la hora en UTC, pero el driver la interpreta en la zona
		// del ordenador si no se le dice lo contrario: medido, eso restaba dos
		// horas en España y dejaba toda la actividad fuera de la ventana de lo
		// reciente, o sea, el mapa nunca pintaba nada. Se lee en UTC explicito
		Instant at;
		try
		{
			java.sql.Timestamp stamp = rows.getTimestamp( "time", UTC_CALENDAR.get() );
			at = stamp != null ? stamp.toInstant() : Instant.now();
		}
		catch( SQLException notATimestamp )
		{
			at = Instant.now();
		}
		String player = rows.getString( "player" );
		return new BlockActivity( rows.getLong( "id" ), at, player == null ? "" : player, rows.getString( "action" ),
				rows.getString( "block" ), rows.getString( "world" ), rows.getInt( "x" ), rows.getInt( "y" ),
				rows.getInt( "z" ) );
	}
}
