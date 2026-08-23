package app;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Mira cada pocos segundos que bloques se han puesto o roto, lo guarda y lo
 * pinta en el mapa.
 *
 * <p>Lo caro de esto seria consultar la base del mod a lo bruto. No se hace:
 * se pregunta solo por lo ocurrido despues del ultimo identificador visto, que
 * es la clave primaria, y si no hay nada nuevo la consulta no devuelve filas y
 * no se toca ni el disco del mapa ni la interfaz. Un mundo parado cuesta lo
 * mismo que no tener esto encendido.</p>
 */
public final class BlockActivityWatcher
{
	/** Cada cuanto se pregunta. Con menos no se nota y con mas deja de parecer en vivo. */
	public static final int TICK_SECONDS = 5;

	private final Path worldRepository;
	private final Path worldDirectory;
	private final BlockActivityLog log;
	private final Consumer<List<BlockActivity>> listener;
	private ScheduledExecutorService scheduler;

	public BlockActivityWatcher( Path worldRepository, Path worldDirectory, Consumer<List<BlockActivity>> listener )
	{
		this.worldRepository = worldRepository;
		this.worldDirectory = worldDirectory;
		this.log = new BlockActivityLog( worldRepository );
		this.listener = listener;
	}

	public BlockActivityLog activityLog()
	{
		return log;
	}

	/** Cierto si el mod que registra los bloques esta puesto en ese mundo. */
	public boolean detectorInstalled()
	{
		return LedgerDatabase.isInstalledIn( worldDirectory );
	}

	public synchronized void start()
	{
		if( scheduler != null )
			return;
		// Al arrancar por primera vez se salta lo ya ocurrido: interesa lo que pase
		// a partir de ahora, no reproducir el historial entero de golpe
		if( log.lastSeenId() == 0 )
			LedgerDatabase.lastId( worldDirectory ).ifPresent( log::rememberCursor );

		scheduler = Executors.newSingleThreadScheduledExecutor( runnable ->
		{
			Thread thread = new Thread( runnable, "endershare-block-activity" );
			thread.setDaemon( true );
			// Por debajo de lo normal: esto nunca puede quitarle turno al juego
			thread.setPriority( Thread.MIN_PRIORITY );
			return thread;
		} );
		scheduler.scheduleWithFixedDelay( this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS );
	}

	public synchronized void stop()
	{
		if( scheduler == null )
			return;
		scheduler.shutdownNow();
		scheduler = null;
	}

	public synchronized boolean isRunning()
	{
		return scheduler != null;
	}

	/**
	 * Una pasada: leer lo nuevo, guardarlo, pintarlo y avisar. Visible para los
	 * tests.
	 *
	 * <p>Los marcadores se reescriben aunque no haya novedades. El renderizador
	 * guarda su estado cada dos minutos y al hacerlo pisa el fichero de
	 * marcadores con los suyos, que estan vacios: sin reescribir, la actividad
	 * desapareceria del mapa cada dos minutos sin motivo aparente.</p>
	 */
	void tick()
	{
		try
		{
			List<BlockActivity> incoming = LedgerDatabase.readNew( worldDirectory, log.lastSeenId() );
			List<BlockActivity> accepted = incoming.isEmpty() ? List.of() : log.add( incoming );
			// Se repintan con TODA la ventana visible, no solo con lo que acaba de
			// llegar: el fichero se reemplaza entero cada vez
			List<BlockActivity> visible = log.recent( WorldMapMarkers.MAX_MARKERS );
			if( !visible.isEmpty() || !accepted.isEmpty() )
				WorldMapMarkers.write( WorldMap.directoryFor( worldRepository ), visible );
			if( !accepted.isEmpty() && listener != null )
				listener.accept( accepted );
		}
		catch( RuntimeException unexpected )
		{
			// Un fallo aqui no puede matar el temporizador: se reintenta en la
			// siguiente pasada
			Log.event( "ACTIVITY", "Fallo mirando la actividad del mundo", unexpected );
		}
	}
}
