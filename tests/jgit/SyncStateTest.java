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
 * Reproduce sobre repositorios reales el bloqueo que dejó un servidor entero
 * fuera de juego: una copia que llevaba días sin abrirse respaldaba su disco
 * ANTES de comprobar si iba por detrás, GitHub rechazaba el envío por
 * non-fast-forward y a partir de ese commit ya no había forma de volver.
 *
 * <p>El "GitHub" de estos tests es un repositorio bare en disco: basta para
 * fijar la clasificación y el rescate, que es donde estaba el fallo.</p>
 */
class SyncStateTest
{
	@TempDir
	Path temporaryDirectory;

	private Path remote;
	private Path hostWorld;

	@BeforeEach
	void configureSession() throws Exception
	{
		System.setProperty( "endershare.dataDirectory", temporaryDirectory.resolve( "data" ).toString() );
		assertTrue( TokenStore.saveUserData( "hoster", "hoster@example.test", "local-token" ) );

		remote = temporaryDirectory.resolve( "github.git" );
		Git.init().setBare( true ).setDirectory( remote.toFile() ).call().close();

		hostWorld = worldClonedFromRemote( "host" );
		commitWorld( hostWorld, "r.0.0.mca", "mundo del dia 1" );
		push( hostWorld );
	}

	@AfterEach
	void clearConfiguration()
	{
		TokenStore.invalidateSession();
		System.clearProperty( "endershare.dataDirectory" );
		MainFrame.serverOpenedDirectory = null;
	}

	// ---- Clasificación -----------------------------------------------------

	@Test
	void aWorldThatMatchesGitHubIsUpToDate()
	{
		assertEquals( GitUtils.SyncState.UP_TO_DATE, GitUtils.syncState( hostWorld ) );
	}

	@Test
	void aWorldWithUnpushedCommitsIsAhead() throws Exception
	{
		commitWorld( hostWorld, "r.0.0.mca", "una tarde mas de juego" );

		assertEquals( GitUtils.SyncState.AHEAD, GitUtils.syncState( hostWorld ) );
	}

	@Test
	void aCopyLeftBehindByAnotherPeerIsBehind() throws Exception
	{
		Path guest = worldClonedFromRemote( "guest" );
		commitWorld( hostWorld, "r.0.0.mca", "el host sigue jugando" );
		push( hostWorld );

		assertEquals( GitUtils.SyncState.BEHIND, GitUtils.syncState( guest ) );
	}

	/** El caso de Victor: su copia y la del host crecieron por separado. */
	@Test
	void twoWorldsGrownApartAreDiverged() throws Exception
	{
		Path guest = worldClonedFromRemote( "guest" );

		commitWorld( hostWorld, "r.0.0.mca", "el host juega el dia 3" );
		push( hostWorld );
		commitWorld( guest, "r.0.0.mca", "el invitado arranca sobre el mundo del dia 1" );

		assertEquals( GitUtils.SyncState.DIVERGED, GitUtils.syncState( guest ) );
	}

	/**
	 * Sin red no se rescata nada. Confundir "no lo sé" con "divergido" haría que
	 * una caída de conexión disparase un reset del mundo de quien está jugando.
	 */
	@Test
	void anUnreachableRemoteIsNeverReportedAsDiverged() throws Exception
	{
		Path orphan = worldClonedFromRemote( "orphan" );
		try (Git git = Git.open( orphan.toFile() ))
		{
			StoredConfig config = git.getRepository().getConfig();
			config.setString( "remote", "origin", "url", temporaryDirectory.resolve( "no-existe.git" ).toString() );
			config.save();
		}

		assertEquals( GitUtils.SyncState.UNREACHABLE, GitUtils.syncState( orphan ) );
	}

	// ---- La puerta del respaldo -------------------------------------------

	/**
	 * El commit envenenado: con historias ya separadas, respaldar creaba un
	 * commit que GitHub jamás iba a aceptar y que dejaba al peer fuera para
	 * siempre. Ahora no se crea.
	 */
	@Test
	void aDivergedWorldIsNotCommittedAtAll() throws Exception
	{
		Path guest = worldClonedFromRemote( "guest" );
		commitWorld( hostWorld, "r.0.0.mca", "el host juega" );
		push( hostWorld );
		commitWorld( guest, "r.0.0.mca", "el invitado juega por su cuenta" );
		int commitsBefore = commitCount( guest );

		// Cambios en disco sin respaldar, como los deja una sesion cualquiera
		Files.writeString( guest.resolve( "level.dat" ), "estado nuevo sin guardar" );

		GitUtils.BackupPushResult backup = GitUtils.commitAndPush( guest, false );

		assertFalse( backup.success() );
		assertEquals( 0, backup.committedBatches() );
		assertEquals( commitsBefore, commitCount( guest ), "no debe haberse creado ningun commit" );
		assertTrue( backup.message().contains( "grown apart" ), backup.message() );
	}

	// ---- El rescate --------------------------------------------------------

	/**
	 * El agujero que dejaba a Victor sin salida: tras un respaldo rechazado el
	 * árbol queda LIMPIO con commits por delante. El rescate sólo miraba si había
	 * ficheros sucios, así que no creaba rama y el reset se llevaba por delante
	 * esos commits sin que nadie se enterara.
	 */
	@Test
	void theRescueKeepsUnpushedCommitsEvenWithACleanTree() throws Exception
	{
		Path guest = worldClonedFromRemote( "guest" );
		commitWorld( hostWorld, "r.0.0.mca", "el host juega" );
		push( hostWorld );
		commitWorld( guest, "r.0.0.mca", "trabajo del invitado que nunca subio" );
		assertTrue( isCleanTree( guest ), "el escenario exige un arbol limpio" );

		String snapshotBranch = GitUtils.snapshotLocalChangesAndTakeRemote( guest );

		assertNotNull( snapshotBranch );
		assertFalse( snapshotBranch.isEmpty(), "con commits sin subir tiene que crearse una rama" );
		assertTrue( branchNames( guest ).contains( snapshotBranch ) );
		assertEquals( "el host juega", Files.readString( guest.resolve( "r.0.0.mca" ) ) );
		assertEquals( GitUtils.SyncState.UP_TO_DATE, GitUtils.syncState( guest ) );
	}

	@Test
	void theRescueAlsoKeepsUncommittedWork() throws Exception
	{
		Path guest = worldClonedFromRemote( "guest" );
		commitWorld( hostWorld, "r.0.0.mca", "el host juega" );
		push( hostWorld );
		Files.writeString( guest.resolve( "construccion.dat" ), "lo que estaba a medias" );

		String snapshotBranch = GitUtils.snapshotLocalChangesAndTakeRemote( guest );

		assertNotNull( snapshotBranch );
		assertFalse( snapshotBranch.isEmpty() );
		assertTrue( branchNames( guest ).contains( snapshotBranch ) );
	}

	/** Sin nada propio que guardar no se ensucia el repositorio con ramas. */
	@Test
	void anAlignedWorldNeedsNoSnapshotBranch()
	{
		String snapshotBranch = GitUtils.snapshotLocalChangesAndTakeRemote( hostWorld );

		assertEquals( "", snapshotBranch );
	}

	// ---- Clon superficial --------------------------------------------------

	/**
	 * Quien se une descarga sólo el mundo de ahora, sin la historia. Con la
	 * historia cortada, {@code isMergedInto} podría no encontrar el antepasado
	 * común y dar una divergencia FALSA, que dispararía un rescate y resetearía
	 * el mundo de alguien que no tenía ningún problema.
	 */
	@Test
	void aShallowGuestIsClassifiedAsBehindAndNotAsDiverged() throws Exception
	{
		Path shallowGuest = shallowCloneOfRemote( "shallow-guest" );
		commitWorld( hostWorld, "r.0.0.mca", "el host juega el dia 2" );
		commitWorld( hostWorld, "r.0.0.mca", "el host juega el dia 3" );
		push( hostWorld );

		assertEquals( GitUtils.SyncState.BEHIND, GitUtils.syncState( shallowGuest ) );
	}

	/** Y cuando la divergencia es real, el corte de historia no la esconde. */
	@Test
	void aShallowGuestStillDetectsARealDivergence() throws Exception
	{
		Path shallowGuest = shallowCloneOfRemote( "shallow-guest" );
		commitWorld( hostWorld, "r.0.0.mca", "el host juega" );
		push( hostWorld );
		commitWorld( shallowGuest, "r.0.0.mca", "el invitado juega por su cuenta" );

		assertEquals( GitUtils.SyncState.DIVERGED, GitUtils.syncState( shallowGuest ) );
	}

	/** Un clon superficial tiene que poder respaldar: si no, no podría jugar. */
	@Test
	void aShallowGuestCanStillBackUpItsWorld() throws Exception
	{
		Path shallowGuest = shallowCloneOfRemote( "shallow-guest" );
		Files.writeString( shallowGuest.resolve( "r.0.0.mca" ), "la tarde del invitado" );

		GitUtils.BackupPushResult backup = GitUtils.commitAndPush( shallowGuest, false );

		assertTrue( backup.success(), backup.message() );
		assertEquals( GitUtils.SyncState.UP_TO_DATE, GitUtils.syncState( shallowGuest ) );
	}

	// ---- Utilidades --------------------------------------------------------

	/** Igual que worldClonedFromRemote, pero como clona de verdad la aplicación. */
	private Path shallowCloneOfRemote( String name ) throws Exception
	{
		Path world = temporaryDirectory.resolve( name );
		Git.cloneRepository()
				.setURI( remote.toUri().toString() )
				.setDirectory( world.toFile() )
				.setDepth( 1 )
				.call()
				.close();
		try (Git git = Git.open( world.toFile() ))
		{
			StoredConfig config = git.getRepository().getConfig();
			config.setString( "user", null, "name", name );
			config.setString( "user", null, "email", name + "@example.test" );
			config.save();
		}
		return world;
	}

	private Path worldClonedFromRemote( String name ) throws Exception
	{
		Path world = temporaryDirectory.resolve( name );
		Git.cloneRepository()
				.setURI( remote.toUri().toString() )
				.setDirectory( world.toFile() )
				.call()
				.close();
		try (Git git = Git.open( world.toFile() ))
		{
			StoredConfig config = git.getRepository().getConfig();
			config.setString( "user", null, "name", name );
			config.setString( "user", null, "email", name + "@example.test" );
			config.save();
		}
		return world;
	}

	private void commitWorld( Path world, String file, String content ) throws Exception
	{
		Files.writeString( world.resolve( file ), content );
		try (Git git = Git.open( world.toFile() ))
		{
			git.add().addFilepattern( "." ).call();
			git.commit().setMessage( "World backup: " + content ).call();
		}
	}

	private void push( Path world ) throws Exception
	{
		try (Git git = Git.open( world.toFile() ))
		{
			git.push().call();
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

	private boolean isCleanTree( Path world ) throws Exception
	{
		try (Git git = Git.open( world.toFile() ))
		{
			return git.status().call().isClean();
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
}
