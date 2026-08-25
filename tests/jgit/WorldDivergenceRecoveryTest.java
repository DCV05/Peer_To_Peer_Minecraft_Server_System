package jgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import view.MainFrame;

/**
 * Simulación del incidente del 24-ago-2026, extremo a extremo y con la
 * secuencia real de los hechos.
 *
 * <p>El invitado respaldó por última vez el 21 a las 15:54. El host siguió
 * jugando los días 22 y 23 y subió su mundo. El 24, el invitado arrancó: la
 * aplicación commiteó su disco ANTES de mirar si iba por detrás, GitHub
 * rechazó el envío por non-fast-forward y, con ese commit ya creado, el árbol
 * quedó limpio pero con historia propia. A partir de ahí ningún botón de la
 * aplicación podía arreglarlo: PULL WORLD sólo se rescataba con ficheros
 * sucios, y no los había.</p>
 *
 * <p>Este test recorre esa misma secuencia y exige que ahora termine sola.</p>
 */
class WorldDivergenceRecoveryTest
{
	@TempDir
	Path temporaryDirectory;

	private Path gitHub;
	private Path hostWorld;
	private Path guestWorld;

	@BeforeEach
	void openSession() throws Exception
	{
		System.setProperty( "endershare.dataDirectory", temporaryDirectory.resolve( "data" ).toString() );
		assertTrue( TokenStore.saveUserData( "host", "host@example.test", "local-token" ) );

		gitHub = temporaryDirectory.resolve( "github.git" );
		Git.init().setBare( true ).setDirectory( gitHub.toFile() ).call().close();
	}

	@AfterEach
	void closeSession()
	{
		TokenStore.invalidateSession();
		System.clearProperty( "endershare.dataDirectory" );
		MainFrame.serverOpenedDirectory = null;
	}

	@Test
	void aGuestLeftBehindForDaysRecoversWithoutLosingAnything() throws Exception
	{
		// --- 21-ago 15:54: los dos juegan y el invitado respalda por ultima vez
		hostWorld = worldFor( "host" );
		writeWorld( hostWorld, "mundo del 21 por la tarde" );
		backup( hostWorld );
		guestWorld = joinFrom( "guest" );
		assertEquals( "mundo del 21 por la tarde", Files.readString( guestWorld.resolve( "world/r.0.0.mca" ) ) );

		// --- 22 y 23-ago: el host sigue jugando solo y sube dos sesiones mas
		writeWorld( hostWorld, "mundo del 22 por la noche" );
		backup( hostWorld );
		writeWorld( hostWorld, "mundo del 23 a las 10:30" );
		backup( hostWorld );

		// --- 24-ago: el invitado arranca con una copia de tres dias atras.
		// Su disco trae cambios: los deja cualquier arranque, aunque no se juegue
		Files.writeString( guestWorld.resolve( "usercache.json" ), "[]" );
		Files.writeString( guestWorld.resolve( "world/level.dat" ), "estado tras el arranque del 24" );

		assertEquals( GitUtils.SyncState.BEHIND, GitUtils.syncState( guestWorld ),
				"antes de respaldar, la copia solo va por DETRAS: todavia no hay divergencia" );

		// Aqui estaba el fallo: respaldar creaba el commit que separaba las
		// historias para siempre. Ahora se alinea primero y el desfase se cierra
		GitUtils.AlignmentResult alignment = GitUtils.alignWithRemote( guestWorld );

		assertTrue( alignment.ready(), alignment.message() );
		assertEquals( GitUtils.SyncState.BEHIND, alignment.state() );
		assertEquals( "mundo del 23 a las 10:30", Files.readString( guestWorld.resolve( "world/r.0.0.mca" ) ) );
		assertEquals( GitUtils.SyncState.UP_TO_DATE, GitUtils.syncState( guestWorld ) );

		// Y su estado del 24 no se ha tirado a la basura: esta en la rama de snapshot
		assertFalse( alignment.snapshotBranch().isEmpty(), "tenia cambios en disco: hay que conservarlos" );
		assertTrue( branchNames( guestWorld ).contains( alignment.snapshotBranch() ) );
	}

	/** Ir por detrás sin nada propio en disco es el caso fácil: basta con traer el mundo. */
	@Test
	void aCleanGuestLeftBehindJustDownloadsTheWorld() throws Exception
	{
		hostWorld = worldFor( "host" );
		writeWorld( hostWorld, "mundo del 21 por la tarde" );
		backup( hostWorld );
		guestWorld = joinFrom( "guest" );

		writeWorld( hostWorld, "mundo del 23 a las 10:30" );
		backup( hostWorld );

		GitUtils.AlignmentResult alignment = GitUtils.alignWithRemote( guestWorld );

		assertTrue( alignment.ready(), alignment.message() );
		assertEquals( "", alignment.snapshotBranch(), "sin nada propio no hace falta rama" );
		assertEquals( "mundo del 23 a las 10:30", Files.readString( guestWorld.resolve( "world/r.0.0.mca" ) ) );
	}

	/** Sin red no se toca el mundo de quien juega: se para y se explica. */
	@Test
	void withoutNetworkNothingIsTouched() throws Exception
	{
		hostWorld = worldFor( "host" );
		writeWorld( hostWorld, "mundo del 21" );
		backup( hostWorld );
		guestWorld = joinFrom( "guest" );
		try (Git git = Git.open( guestWorld.toFile() ))
		{
			StoredConfig config = git.getRepository().getConfig();
			config.setString( "remote", "origin", "url", temporaryDirectory.resolve( "no-existe.git" ).toString() );
			config.save();
		}

		GitUtils.AlignmentResult alignment = GitUtils.alignWithRemote( guestWorld );

		assertFalse( alignment.ready() );
		assertEquals( GitUtils.SyncState.UNREACHABLE, alignment.state() );
		assertEquals( "mundo del 21", Files.readString( guestWorld.resolve( "world/r.0.0.mca" ) ),
				"el mundo local no se toca cuando no se puede comparar" );
	}

	/**
	 * La misma historia, pero partiendo del daño ya hecho: un peer que YA tiene
	 * el commit envenenado. Es el estado en el que se quedó atascado, y del que
	 * no se salía con ningún botón.
	 */
	@Test
	void aWorldAlreadyStuckIsRecoveredAutomaticallyAndKeepsTheOldCopy() throws Exception
	{
		hostWorld = worldFor( "host" );
		writeWorld( hostWorld, "mundo del 21 por la tarde" );
		backup( hostWorld );
		guestWorld = joinFrom( "guest" );

		writeWorld( hostWorld, "mundo del 23 a las 10:30" );
		backup( hostWorld );

		// El commit que la version anterior creaba sin preguntar
		writeWorld( guestWorld, "lo que el invitado tenia en su disco" );
		commitEverything( guestWorld, "World backup 1/1 by guest" );
		assertEquals( GitUtils.SyncState.DIVERGED, GitUtils.syncState( guestWorld ) );
		assertTrue( isCleanTree( guestWorld ),
				"el arbol queda LIMPIO: por eso el rescate por ficheros sucios nunca se disparaba" );

		// Respaldar ya no empeora las cosas: no se crea ningun commit mas
		int commitsBefore = commitCount( guestWorld );
		GitUtils.BackupPushResult blocked = GitUtils.commitAndPush( guestWorld, false );
		assertFalse( blocked.success() );
		assertEquals( commitsBefore, commitCount( guestWorld ) );

		// Y el rescate saca al peer del atasco sin perder su copia
		String snapshotBranch = GitUtils.snapshotLocalChangesAndTakeRemote( guestWorld );

		assertNotNull( snapshotBranch );
		assertFalse( snapshotBranch.isEmpty(), "su trabajo tiene que quedar en una rama" );
		assertTrue( branchNames( guestWorld ).contains( snapshotBranch ) );
		assertEquals( "mundo del 23 a las 10:30", Files.readString( guestWorld.resolve( "world/r.0.0.mca" ) ) );
		assertEquals( GitUtils.SyncState.UP_TO_DATE, GitUtils.syncState( guestWorld ) );

		// Nada se ha borrado: la copia vieja sigue recuperable desde la rama
		assertEquals( "lo que el invitado tenia en su disco", fileInBranch( guestWorld, snapshotBranch, "world/r.0.0.mca" ) );
	}

	/**
	 * El conflicto que de verdad les saltó: dos versiones de la aplicación
	 * reescribían el .gitignore versionado con bloques distintos. Ahora las
	 * reglas son locales y ese fichero deja de viajar.
	 */
	@Test
	void twoAppVersionsNoLongerFightOverTheIgnoreFile() throws Exception
	{
		hostWorld = worldFor( "host" );
		// El host trae el .gitignore contaminado por la version anterior
		Files.writeString( hostWorld.resolve( ".gitignore" ), """
				# BEGIN P2PMSS MANAGED BACKUP EXCLUDES
				/logs/
				# END P2PMSS MANAGED BACKUP EXCLUDES
				""" );
		writeWorld( hostWorld, "mundo del 21" );
		backup( hostWorld );

		guestWorld = joinFrom( "guest" );
		GitUtils.ensureBackupIgnoreFile( guestWorld );

		assertFalse( Files.exists( guestWorld.resolve( ".gitignore" ) ),
				"el .gitignore era enteramente nuestro: deja de viajar" );
		String localExclude = Files.readString( guestWorld.resolve( ".git/info/exclude" ) );
		assertTrue( localExclude.contains( "/logs/" ), localExclude );
		assertTrue( localExclude.contains( "/config/bluemap/" ), localExclude );

		// Y arrancar dos veces seguidas no deja nada que respaldar
		GitUtils.ensureBackupIgnoreFile( guestWorld );
		assertTrue( isCleanTree( guestWorld ), "dos arranques seguidos no deben ensuciar el arbol" );
	}

	/** La configuración de esta máquina no puede generar trabajo para el backup. */
	@Test
	void machineOnlySettingsDoNotDirtyTheWorld() throws Exception
	{
		hostWorld = worldFor( "host" );
		Files.writeString( hostWorld.resolve( "server.properties" ), "max-players=20" );
		Files.writeString( hostWorld.resolve( "user_jvm_args.txt" ), "-Xmx4G" );
		writeWorld( hostWorld, "mundo del 21" );
		backup( hostWorld );

		// Alguien cambia su RAM y su numero de jugadores, como haria desde el panel
		Files.writeString( hostWorld.resolve( "user_jvm_args.txt" ), "-Xmx8G" );
		Files.writeString( hostWorld.resolve( "server.properties" ), "max-players=10" );
		Files.createDirectories( hostWorld.resolve( "config/bluemap" ) );
		Files.writeString( hostWorld.resolve( "config/bluemap/plugin.conf" ), "player-render-limit: 1" );

		assertTrue( isCleanTree( hostWorld ),
				"la configuracion de esta maquina no debe aparecer como cambio del mundo" );
	}

	// ---- Utilidades --------------------------------------------------------

	/** Una carpeta de servidor ya enlazada al "GitHub" de la prueba. */
	private Path worldFor( String name ) throws Exception
	{
		Path world = temporaryDirectory.resolve( name );
		Files.createDirectories( world.resolve( "world" ) );
		Git.init().setDirectory( world.toFile() ).call().close();
		try (Git git = Git.open( world.toFile() ))
		{
			StoredConfig config = git.getRepository().getConfig();
			config.setString( "remote", "origin", "url", gitHub.toUri().toString() );
			config.setString( "remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*" );
			config.setString( "user", null, "name", name );
			config.setString( "user", null, "email", name + "@example.test" );
			config.save();
		}
		GitUtils.ensureBackupIgnoreFile( world );
		GitUtils.protectMachineLocalFiles( world );
		return world;
	}

	/** Lo que hace un invitado al unirse: clonar el mundo confirmado. */
	private Path joinFrom( String name ) throws Exception
	{
		Path world = temporaryDirectory.resolve( name );
		Git.cloneRepository().setURI( gitHub.toUri().toString() ).setDirectory( world.toFile() ).call().close();
		try (Git git = Git.open( world.toFile() ))
		{
			StoredConfig config = git.getRepository().getConfig();
			config.setString( "user", null, "name", name );
			config.setString( "user", null, "email", name + "@example.test" );
			config.save();
		}
		GitUtils.ensureBackupIgnoreFile( world );
		GitUtils.protectMachineLocalFiles( world );
		return world;
	}

	private void writeWorld( Path world, String content ) throws Exception
	{
		Files.createDirectories( world.resolve( "world" ) );
		Files.writeString( world.resolve( "world/r.0.0.mca" ), content );
	}

	private void backup( Path world ) throws Exception
	{
		GitUtils.BackupPushResult result = GitUtils.commitAndPush( world, false );
		assertTrue( result.success(), result.message() );
	}

	private void commitEverything( Path world, String message ) throws Exception
	{
		try (Git git = Git.open( world.toFile() ))
		{
			git.add().addFilepattern( "." ).call();
			git.add().addFilepattern( "." ).setUpdate( true ).call();
			git.commit().setMessage( message ).call();
		}
	}

	private boolean isCleanTree( Path world ) throws Exception
	{
		try (Git git = Git.open( world.toFile() ))
		{
			return git.status().call().isClean();
		}
	}

	private int commitCount( Path world ) throws Exception
	{
		try (Git git = Git.open( world.toFile() ))
		{
			int commits = 0;
			for( var ignored : git.log().call() )
				commits++;
			return commits;
		}
	}

	private List<String> branchNames( Path world ) throws Exception
	{
		try (Git git = Git.open( world.toFile() ))
		{
			return git.branchList().call().stream()
					.map( branch -> branch.getName().replace( "refs/heads/", "" ) )
					.toList();
		}
	}

	private String fileInBranch( Path world, String branch, String file ) throws Exception
	{
		try (Git git = Git.open( world.toFile() ))
		{
			var repo = git.getRepository();
			var commit = repo.resolve( branch );
			try (var walk = new org.eclipse.jgit.revwalk.RevWalk( repo ))
			{
				var tree = walk.parseCommit( commit ).getTree();
				try (var treeWalk = org.eclipse.jgit.treewalk.TreeWalk.forPath( repo, file, tree ))
				{
					assertNotNull( treeWalk, "el fichero deberia estar en la rama de snapshot" );
					return new String( repo.open( treeWalk.getObjectId( 0 ) ).getBytes() );
				}
			}
		}
	}
}
