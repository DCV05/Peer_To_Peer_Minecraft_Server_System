package app;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import jgit.HostLock;

/**
 * Escaner periodico de los mundos suscritos: consulta el candado de GitHub de
 * cada repositorio y mantiene una foto del estado (libre / hosteado por quien).
 *
 * <p>Decisiones de diseño: un solo hilo daemon y UN repositorio refrescado por
 * tick, de modo que las llamadas a la API salen escalonadas y nunca en rafaga.
 * Con el tick de 5 segundos y el refresco de 60, hay margen para una docena de
 * mundos con datos frescos; con mas mundos los datos envejecen en proporcion,
 * pero la cuota de la API no se compromete jamas.</p>
 */
public final class WorldStatusScanner
{

	// ---- FASE 1 — Contratos y estado ---------------------------------------

	public static final long REFRESH_SECONDS = 60;
	static final long TICK_SECONDS = 5;

	/** Foto inmutable del estado de un mundo en el momento de consultarlo. */
	public record WorldStatus( String repoFullName, boolean hosted, boolean mine, boolean stale,
			String hostNickname, Instant checkedAt, HostLock.HostDetails details )
	{
	}

	private final Supplier<List<String>> subscriptions;
	private final Function<String, HostLock.Status> statusReader;
	private final Consumer<WorldStatus> listener;
	private final Map<String, WorldStatus> statuses = new ConcurrentHashMap<>();
	private ScheduledExecutorService scheduler;

	/**
	 * @param subscriptions lista viva de repos a vigilar (se relee en cada tick)
	 * @param statusReader lector del candado; en produccion {@code HostLock::readStatus},
	 *        en tests un stub sin red
	 * @param listener avisado tras cada refresco, desde el hilo del escaner: la
	 *        interfaz debe envolver en invokeLater lo que toque Swing
	 */
	public WorldStatusScanner( Supplier<List<String>> subscriptions,
			Function<String, HostLock.Status> statusReader, Consumer<WorldStatus> listener )
	{
		this.subscriptions = subscriptions;
		this.statusReader = statusReader;
		this.listener = listener;
	}

	// ---- FASE 2 — Ciclo de vida --------------------------------------------

	public synchronized void start()
	{
		if( scheduler != null )
			return;
		scheduler = Executors.newSingleThreadScheduledExecutor( runnable ->
		{
			Thread worker = new Thread( runnable, "p2pmss-world-scanner" );
			worker.setDaemon( true );
			return worker;
		} );
		scheduler.scheduleAtFixedRate( this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS );
	}

	public synchronized void stop()
	{
		if( scheduler != null )
		{
			scheduler.shutdownNow();
			scheduler = null;
		}
	}

	/** Invalida todas las fotos: los siguientes ticks refrescan todo cuanto antes. */
	public void refreshNow()
	{
		statuses.clear();
	}

	public Optional<WorldStatus> statusOf( String repoFullName )
	{
		return Optional.ofNullable( statuses.get( repoFullName ) );
	}

	public Map<String, WorldStatus> snapshot()
	{
		return Map.copyOf( statuses );
	}

	// ---- FASE 3 — Refresco escalonado --------------------------------------

	/**
	 * Refresca COMO MUCHO un mundo por llamada: el mas caducado de los que ya
	 * superaron el intervalo. Visible para que los tests avancen el escaner a
	 * mano sin depender de temporizadores reales.
	 */
	void tick()
	{
		try
		{
			String candidate = null;
			Instant oldest = null;
			for( String repo : subscriptions.get() )
			{
				WorldStatus known = statuses.get( repo );
				if( known == null )
				{
					// Un mundo nunca consultado va siempre primero
					candidate = repo;
					break;
				}
				boolean expired = known.checkedAt().isBefore( Instant.now().minusSeconds( REFRESH_SECONDS ) );
				if( expired && (oldest == null || known.checkedAt().isBefore( oldest )) )
				{
					candidate = repo;
					oldest = known.checkedAt();
				}
			}
			if( candidate == null )
				return;

			HostLock.Status lock = statusReader.apply( candidate );
			WorldStatus refreshed = new WorldStatus( candidate,
					lock.locked() && !lock.stale(), lock.mine(), lock.stale(), lock.hostNickname(), Instant.now(),
					lock.details() );
			statuses.put( candidate, refreshed );
			if( listener != null )
				listener.accept( refreshed );
		}
		catch( Exception scanFailure )
		{
			// Un fallo puntual (red, API) no puede matar el hilo programado: el
			// siguiente tick lo reintenta de forma natural
			Log.event( "WORLD_SCANNER", "Fallo refrescando el estado de un mundo", scanFailure );
		}
	}
}
