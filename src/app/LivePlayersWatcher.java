package app;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Lleva las posiciones de los jugadores del mundo al mapa, una vez por segundo.
 *
 * <p>Solo trabaja cuando hay algo que hacer: si el guion no ha publicado nada
 * nuevo (el fichero no ha cambiado de fecha) no se lee ni se escribe. Con el
 * servidor parado no cuesta absolutamente nada.</p>
 */
public final class LivePlayersWatcher
{
	/** Cada cuanto se mira. Con mas, el movimiento deja de parecer en vivo. */
	public static final int TICK_SECONDS = 1;

	private final Path worldRepository;
	private final Path worldDirectory;
	private final Consumer<List<LivePlayers.Snapshot>> listener;
	private ScheduledExecutorService scheduler;
	private long lastPublishedAt = 0;

	public LivePlayersWatcher( Path worldRepository, Path worldDirectory,
			Consumer<List<LivePlayers.Snapshot>> listener )
	{
		this.worldRepository = worldRepository;
		this.worldDirectory = worldDirectory;
		this.listener = listener;
	}

	public synchronized void start()
	{
		if( scheduler != null )
			return;
		scheduler = Executors.newSingleThreadScheduledExecutor( runnable ->
		{
			Thread thread = new Thread( runnable, "endershare-live-players" );
			thread.setDaemon( true );
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
		// Al parar se deja el mapa sin jugadores, o se quedarian ahi clavados
		LivePlayers.write( WorldMap.directoryFor( worldRepository ), List.of() );
	}

	public synchronized boolean isRunning()
	{
		return scheduler != null;
	}

	/** Una vuelta. Visible para los tests. */
	void tick()
	{
		try
		{
			Path published = worldDirectory.resolve( "scripts" ).resolve( LivePlayers.SCRIPT_NAME + ".data" )
					.resolve( "players.json" );
			long changedAt = java.nio.file.Files.isRegularFile( published )
					? java.nio.file.Files.getLastModifiedTime( published ).toMillis()
					: 0;
			// Sin novedades no se toca nada: ni leer, ni escribir, ni avisar
			if( changedAt == 0 || changedAt == lastPublishedAt )
				return;
			lastPublishedAt = changedAt;

			List<LivePlayers.Snapshot> players = LivePlayers.read( worldDirectory );
			LivePlayers.write( WorldMap.directoryFor( worldRepository ), players );
			if( listener != null )
				listener.accept( players );
		}
		catch( java.io.IOException | RuntimeException unexpected )
		{
			// Un fallo aqui no puede matar el temporizador
			Log.event( "LIVE_PLAYERS", "Fallo publicando las posiciones", unexpected );
		}
	}
}
