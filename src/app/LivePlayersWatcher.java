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
		// Las caras de quien ya ha jugado aqui se van bajando desde ahora, para que
		// cuando alguien entre aparezca ya con la suya y no con el muñeco generico
		PlayerSkins.prefetch( WorldMap.directoryFor( worldRepository ), PlayerSkins.knownPlayersIn( worldRepository ) );
	}

	public synchronized void stop()
	{
		if( scheduler != null )
		{
			scheduler.shutdownNow();
			scheduler = null;
		}
		// Se limpia SIEMPRE, aunque no llegara a arrancar: si el mapa quedo con
		// jugadores pintados de una vez anterior, se quedarian ahi clavados para
		// siempre dando a entender que hay alguien conectado
		LivePlayers.write( WorldMap.directoryFor( worldRepository ), List.of() );
	}

	public synchronized boolean isRunning()
	{
		return scheduler != null;
	}

	/**
	 * Una vuelta. Visible para los tests.
	 *
	 * <p>Se reescribe SIEMPRE, aunque no haya novedades. No es por capricho: el
	 * renderizador guarda su estado cada dos minutos y al hacerlo deja el fichero
	 * de jugadores en <code>{}</code>. Y ese valor no solo borra los muñecos: si
	 * el visor se abre justo en ese momento, apaga los jugadores y no vuelve a
	 * pedirlos hasta que alguien recargue la pagina a mano. Reescribiendo cada
	 * segundo, lo que nos pisan dura como mucho un segundo.</p>
	 */
	void tick()
	{
		try
		{
			Path published = worldDirectory.resolve( "scripts" ).resolve( LivePlayers.SCRIPT_NAME + ".data" )
					.resolve( "players.json" );
			long changedAt = java.nio.file.Files.isRegularFile( published )
					? java.nio.file.Files.getLastModifiedTime( published ).toMillis()
					: 0;

			List<LivePlayers.Snapshot> players = changedAt == 0 ? List.of() : LivePlayers.read( worldDirectory );
			LivePlayers.write( WorldMap.directoryFor( worldRepository ), players );

			// El aviso a la interfaz si va solo cuando cambia algo de verdad
			if( changedAt != lastPublishedAt )
			{
				lastPublishedAt = changedAt;
				if( listener != null )
					listener.accept( players );
			}
		}
		catch( java.io.IOException | RuntimeException unexpected )
		{
			// Un fallo aqui no puede matar el temporizador
			Log.event( "LIVE_PLAYERS", "Fallo publicando las posiciones", unexpected );
		}
	}
}
