package e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import app.WorldStatusScanner;
import jgit.GitUtils;
import jgit.HostLock;
import jgit.TokenStore;
import view.MainFrame;

/**
 * El flujo real de dos amigos, entero y sin red: el host enlaza el mundo,
 * coge el candado, publica su direccion y su aforo; el invitado lo ve con el
 * escaner, no puede arrancar mientras tanto, y hereda el rol cuando el host
 * cierra. GitHub es {@link MockGitHub}; el repo del mundo es un bare local.
 *
 * <p>Cada peer es un data-dir distinto (la identidad y la sesion viven ahi),
 * cambiado via la system property {@code p2pmss.dataDirectory} que ya usan el
 * resto de tests.</p>
 */
class TwoPeerFlowTest
{
	@TempDir
	Path temporaryDirectory;

	private MockGitHub github;

	@AfterEach
	void tearDownSession()
	{
		TokenStore.invalidateSession();
		System.clearProperty( "p2pmss.dataDirectory" );
		System.clearProperty( "p2pmss.githubApiBase" );
		HostLock.clearPublishedDetails();
		MainFrame.serverOpenedDirectory = null;
		if( github != null )
			github.close();
	}

	/** Cambia de peer: su data-dir y su sesion de GitHub propios. */
	private void actAs( String nickname, Path dataDirectory )
	{
		System.setProperty( "p2pmss.dataDirectory", dataDirectory.toString() );
		HostLock.clearPublishedDetails();
		assertTrue( TokenStore.saveUserData( nickname, nickname + "@example.test", "token-" + nickname ) );
	}

	@Test
	void hostPublishesGuestSeesAndInheritsTheWorld() throws Exception
	{
		github = MockGitHub.start();
		System.setProperty( "p2pmss.githubApiBase", github.baseUrl() );
		String worldRepo = "dcv/farmland";
		github.registerRepository( worldRepo );

		Path remote = temporaryDirectory.resolve( "remote.git" );
		try (Git ignored = Git.init().setBare( true ).setDirectory( remote.toFile() ).call())
		{
		}

		// ---- El HOST enlaza su mundo y lo sube -----------------------------
		Path hostData = Files.createDirectories( temporaryDirectory.resolve( "data-host" ) );
		Path hostServer = Files.createDirectories( temporaryDirectory.resolve( "host-server" ) );
		actAs( "hostA", hostData );
		Files.writeString( hostServer.resolve( "world.txt" ), "world-v1\n" );
		Files.createDirectories( hostServer.resolve( "world" ) );
		Files.writeString( hostServer.resolve( "world/level.dat" ), "level-v1\n" );

		assertTrue( GitUtils.createRepoInPath( hostServer ) );
		assertTrue( GitUtils.linkLocalRepoToExternal( remote.toUri().toString(), "unused", hostServer ) );

		// ---- El HOST arbitra el candado y publica su foto ------------------
		HostLock.AcquireResult acquired = HostLock.acquire( worldRepo );
		assertTrue( acquired.acquired(), acquired.message() );

		HostLock.publishDetails( new HostLock.HostDetails( "farm.craft.ply.gg:25565", 1, 4, "1.21.7" ) );
		assertTrue( HostLock.heartbeat( worldRepo ) );
		assertEquals( "farm.craft.ply.gg:25565", github.currentLease( worldRepo ).path( "tunnel_address" ).asText() );

		// ---- El INVITADO ve el mundo vivo con el escaner -------------------
		Path guestData = Files.createDirectories( temporaryDirectory.resolve( "data-guest" ) );
		actAs( "guestB", guestData );

		WorldStatusScanner scanner = new WorldStatusScanner( () -> List.of( worldRepo ), HostLock::readStatus, null );
		scanner.tick();
		WorldStatusScanner.WorldStatus seen = scanner.statusOf( worldRepo ).orElseThrow();
		assertTrue( seen.hosted() );
		assertFalse( seen.mine() );
		assertEquals( "hostA", seen.hostNickname() );
		assertEquals( "farm.craft.ply.gg:25565", seen.details().tunnelAddress() );
		assertEquals( 1, seen.details().onlinePlayers() );
		assertEquals( "1.21.7", seen.details().minecraftVersion() );

		// ---- El candado impide el doble hosting ----------------------------
		HostLock.AcquireResult refused = HostLock.acquire( worldRepo );
		assertFalse( refused.acquired() );
		assertTrue( refused.blockedByPeer() );
		assertTrue( refused.message().contains( "hostA" ) );

		// ---- El invitado clona el mundo y esta al dia ----------------------
		Path guestServer = temporaryDirectory.resolve( "guest-server" );
		try (Git clone = Git.cloneRepository().setURI( remote.toUri().toString() )
				.setDirectory( guestServer.toFile() ).call())
		{
		}
		assertEquals( "world-v1\n", Files.readString( guestServer.resolve( "world.txt" ) ) );

		// ---- El HOST juega, guarda y cierra --------------------------------
		actAs( "hostA", hostData );
		Files.writeString( hostServer.resolve( "world.txt" ), "world-v2\n" );
		MainFrame.serverOpenedDirectory = hostServer.toFile();
		assertTrue( GitUtils.autoCommitAndPush( true ) );
		assertTrue( HostLock.release( worldRepo ) );

		// ---- El INVITADO hereda: pull del mundo nuevo y candado propio -----
		actAs( "guestB", guestData );
		scanner.refreshNow();
		scanner.tick();
		assertFalse( scanner.statusOf( worldRepo ).orElseThrow().hosted() );

		assertTrue( GitUtils.pull( guestServer ) );
		assertEquals( "world-v2\n", Files.readString( guestServer.resolve( "world.txt" ) ) );

		HostLock.AcquireResult inherited = HostLock.acquire( worldRepo );
		assertTrue( inherited.acquired(), inherited.message() );
		HostLock.Status mine = HostLock.readStatus( worldRepo );
		assertTrue( mine.locked() && mine.mine() );
		assertNotNull( github.currentLease( worldRepo ) );
		assertTrue( HostLock.release( worldRepo ) );
	}

	@Test
	void aStaleLeaseFromACrashedHostCanBeTakenOver() throws Exception
	{
		github = MockGitHub.start();
		System.setProperty( "p2pmss.githubApiBase", github.baseUrl() );
		String worldRepo = "dcv/farmland";
		github.registerRepository( worldRepo );

		Path hostData = Files.createDirectories( temporaryDirectory.resolve( "data-host" ) );
		actAs( "hostA", hostData );
		assertTrue( HostLock.acquire( worldRepo ).acquired() );

		// El host "muere" sin liberar. Para el otro peer el lease sigue fresco…
		Path guestData = Files.createDirectories( temporaryDirectory.resolve( "data-guest" ) );
		actAs( "guestB", guestData );
		assertFalse( HostLock.acquire( worldRepo ).acquired() );

		// …hasta que caduca: el escaner lo marca stale y acquire hace el relevo.
		// La caducidad se simula envejeciendo el commit del lease en el mock
		agePublishedLease( worldRepo, HostLock.DEFAULT_LEASE_SECONDS + 60 );
		HostLock.Status stale = HostLock.readStatus( worldRepo );
		assertTrue( stale.locked() && stale.stale() );

		HostLock.AcquireResult takeover = HostLock.acquire( worldRepo );
		assertTrue( takeover.acquired(), takeover.message() );
		assertTrue( takeover.message().contains( "stale" ) );
	}

	/** Retrocede la fecha de commit del lease para simular latidos perdidos. */
	private void agePublishedLease( String repoFullName, long seconds ) throws Exception
	{
		java.lang.reflect.Field repositories = MockGitHub.class.getDeclaredField( "repositories" );
		repositories.setAccessible( true );
		Object state = ((java.util.Map<?, ?>) repositories.get( github )).get( repoFullName );
		java.lang.reflect.Field committedAt = state.getClass().getDeclaredField( "lockCommittedAt" );
		committedAt.setAccessible( true );
		committedAt.set( state, java.time.Instant.now().minusSeconds( seconds ) );
	}
}
