package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import jgit.HostLock;

class WorldStatusScannerTest
{

	private static HostLock.Status hostedBy( String nickname )
	{
		return new HostLock.Status( true, false, false, nickname, Instant.now(), 900, "sha",
				new HostLock.HostDetails( "farm.ply.gg:123", 2, 4, "1.19" ) );
	}

	private static HostLock.Status free()
	{
		return new HostLock.Status( false, false, false, null, null, 900, null, HostLock.HostDetails.empty() );
	}

	@Test
	void refreshesOneWorldPerTickSoApiCallsStayStaggered()
	{
		List<String> worlds = List.of( "a/uno", "b/dos", "c/tres" );
		AtomicInteger reads = new AtomicInteger();
		WorldStatusScanner scanner = new WorldStatusScanner( () -> worlds, repo ->
		{
			reads.incrementAndGet();
			return free();
		}, null );

		scanner.tick();
		assertEquals( 1, reads.get() );
		scanner.tick();
		scanner.tick();
		assertEquals( 3, reads.get() );
		assertEquals( 3, scanner.snapshot().size() );

		// Con las tres fotos frescas, el siguiente tick no gasta llamada alguna
		scanner.tick();
		assertEquals( 3, reads.get() );
	}

	@Test
	void exposesWhoHostsEachWorldAndNotifiesTheListener()
	{
		List<WorldStatusScanner.WorldStatus> notified = new ArrayList<>();
		WorldStatusScanner scanner = new WorldStatusScanner( () -> List.of( "DCV05/farmland_mc" ),
				repo -> hostedBy( "Vikkavv" ), notified::add );

		scanner.tick();

		WorldStatusScanner.WorldStatus status = scanner.statusOf( "DCV05/farmland_mc" ).orElseThrow();
		assertTrue( status.hosted() );
		assertEquals( "Vikkavv", status.hostNickname() );
		// La foto arrastra los detalles publicados por el host en el lease
		assertEquals( "farm.ply.gg:123", status.details().tunnelAddress() );
		assertEquals( 2, status.details().onlinePlayers() );
		assertEquals( "1.19", status.details().minecraftVersion() );
		assertEquals( 1, notified.size() );
	}

	@Test
	void staleLeaseCountsAsNotHosted()
	{
		WorldStatusScanner scanner = new WorldStatusScanner( () -> List.of( "a/uno" ),
				repo -> new HostLock.Status( true, false, true, "Vikkavv", Instant.now(), 900, "sha",
						HostLock.HostDetails.empty() ),
				null );

		scanner.tick();

		WorldStatusScanner.WorldStatus status = scanner.statusOf( "a/uno" ).orElseThrow();
		// Un lease caducado es un host muerto: la tarjeta debe decir libre
		assertTrue( !status.hosted() && status.stale() );
	}

	@Test
	void refreshNowForcesAFullRescan()
	{
		AtomicInteger reads = new AtomicInteger();
		WorldStatusScanner scanner = new WorldStatusScanner( () -> List.of( "a/uno" ), repo ->
		{
			reads.incrementAndGet();
			return free();
		}, null );

		scanner.tick();
		scanner.tick();
		assertEquals( 1, reads.get() );

		scanner.refreshNow();
		scanner.tick();
		assertEquals( 2, reads.get() );
	}

	@Test
	void aFailingReaderNeverKillsTheScanner()
	{
		AtomicInteger calls = new AtomicInteger();
		WorldStatusScanner scanner = new WorldStatusScanner( () -> List.of( "a/uno" ), repo ->
		{
			calls.incrementAndGet();
			throw new IllegalStateException( "red caida" );
		}, null );

		scanner.tick();
		scanner.tick();

		// El fallo no deja foto, asi que cada tick reintenta y ninguno propaga
		assertEquals( 2, calls.get() );
		assertTrue( scanner.snapshot().isEmpty() );
	}

	@Test
	void firesTransitionsOnlyOnRealChanges()
	{
		java.util.concurrent.atomic.AtomicReference<HostLock.Status> current = new java.util.concurrent.atomic.AtomicReference<>(
				free() );
		List<WorldStatusScanner.Transition> transitions = new ArrayList<>();
		WorldStatusScanner scanner = new WorldStatusScanner( () -> List.of( "DCV05/farmland_mc" ),
				repo -> current.get(), null );
		scanner.setTransitionListener( transitions::add );

		// Primer avistamiento: nunca es transicion (evita rafagas al arrancar)
		scanner.tick();
		assertTrue( transitions.isEmpty() );

		// Refresco sin cambio real: silencio
		scanner.ageStatusForTests( "DCV05/farmland_mc", 120 );
		scanner.tick();
		assertTrue( transitions.isEmpty() );

		// Libre -> hosteado
		current.set( hostedBy( "Vikkavv" ) );
		scanner.ageStatusForTests( "DCV05/farmland_mc", 120 );
		scanner.tick();
		assertEquals( 1, transitions.size() );
		assertTrue( !transitions.get( 0 ).previous().hosted() && transitions.get( 0 ).current().hosted() );

		// Cambio de manos
		current.set( hostedBy( "OtherPeer" ) );
		scanner.ageStatusForTests( "DCV05/farmland_mc", 120 );
		scanner.tick();
		assertEquals( 2, transitions.size() );
		assertEquals( "OtherPeer", transitions.get( 1 ).current().hostNickname() );

		// Hosteado -> libre
		current.set( free() );
		scanner.ageStatusForTests( "DCV05/farmland_mc", 120 );
		scanner.tick();
		assertEquals( 3, transitions.size() );
	}

	@Test
	void pollsTheActiveWorldEventsOnEveryTickAndDeliversThem()
	{
		AtomicInteger polls = new AtomicInteger();
		List<String> delivered = new ArrayList<>();
		WorldStatusScanner scanner = new WorldStatusScanner( () -> List.of( "a/uno" ), repo -> free(), null );
		scanner.setEventsReader( repo ->
		{
			polls.incrementAndGet();
			return List.of( new jgit.WorldEvents.WorldEvent( 1L, "guest", "want_to_play", "1-guest-want_to_play.json", "s" ) );
		} );
		scanner.setEventListener( ( repo, event ) -> delivered.add( repo + ":" + event.type() ) );

		// Sin repo activo: solo el refresco normal del mundo recoge sus eventos
		scanner.tick();
		assertEquals( 1, polls.get() );
		assertEquals( List.of( "a/uno:want_to_play" ), delivered );

		// Foto fresca y sin activo: ningun poll extra
		scanner.tick();
		assertEquals( 1, polls.get() );

		// Con el mundo ACTIVO, sus eventos se consultan en cada tick
		scanner.setActiveRepo( "a/uno" );
		scanner.tick();
		scanner.tick();
		assertEquals( 3, polls.get() );
	}
}
