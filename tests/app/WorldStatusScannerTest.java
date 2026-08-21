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
		return new HostLock.Status( true, false, false, nickname, Instant.now(), 900, "sha" );
	}

	private static HostLock.Status free()
	{
		return new HostLock.Status( false, false, false, null, null, 900, null );
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
		assertEquals( 1, notified.size() );
	}

	@Test
	void staleLeaseCountsAsNotHosted()
	{
		WorldStatusScanner scanner = new WorldStatusScanner( () -> List.of( "a/uno" ),
				repo -> new HostLock.Status( true, false, true, "Vikkavv", Instant.now(), 900, "sha" ), null );

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
}
