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
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import view.MainFrame;

/**
 * El incidente del 24-ago-2026 contra GitHub DE VERDAD, no contra un
 * repositorio local.
 *
 * <p>Los ensayos con un bare en disco fijan la lógica, pero no ven lo que
 * distingue a GitHub: el rechazo real por non-fast-forward, la latencia y el
 * clon superficial hablando con un servidor que negocia de otra manera. Este
 * recorre la secuencia completa contra un repositorio desechable.</p>
 *
 * <p>Variables: {@code Endershare_E2E_REPO} (owner/repo desechable),
 * {@code Endershare_E2E_NICK} y {@code Endershare_E2E_TOKEN}.</p>
 */
@Tag("e2e-real")
class RealGitHubWorldRecoveryTest
{
	@TempDir
	Path temporaryDirectory;

	private String cloneUrl;
	private UsernamePasswordCredentialsProvider credentials;
	private Path hostWorld;

	@BeforeEach
	void openSession() throws Exception
	{
		System.setProperty( "endershare.dataDirectory",
				Files.createDirectories( temporaryDirectory.resolve( "data" ) ).toString() );

		String repo = System.getenv( "Endershare_E2E_REPO" );
		String nickname = System.getenv( "Endershare_E2E_NICK" );
		String token = System.getenv( "Endershare_E2E_TOKEN" );
		Assumptions.assumeTrue( repo != null && nickname != null && token != null,
				"Sin Endershare_E2E_REPO/NICK/TOKEN este ensayo no corre" );

		assertTrue( TokenStore.saveUserData( nickname, nickname + "@example.test", token ) );
		cloneUrl = "https://github.com/" + repo + ".git";
		credentials = new UsernamePasswordCredentialsProvider( nickname, token );

		// Cada pasada arranca de un mundo conocido: el sandbox se reescribe entero
		hostWorld = cloneAs( "host", false );
		Files.createDirectories( hostWorld.resolve( "world" ) );
		Files.writeString( hostWorld.resolve( "world/r.0.0.mca" ), "mundo del 21 por la tarde" );
		commitAndPush( hostWorld, "Reset del sandbox" );
	}

	@AfterEach
	void closeSession()
	{
		TokenStore.invalidateSession();
		System.clearProperty( "endershare.dataDirectory" );
		MainFrame.serverOpenedDirectory = null;
	}

	/**
	 * La secuencia entera: el invitado se une, se queda tres días atrás, arranca
	 * con cambios en su disco y tiene que recuperarse solo, sin perder nada.
	 */
	@Test
	void aGuestLeftBehindRecoversByItselfAgainstRealGitHub() throws Exception
	{
		Path guest = cloneAs( "guest", true );
		assertEquals( "mundo del 21 por la tarde", Files.readString( guest.resolve( "world/r.0.0.mca" ) ) );
		assertTrue( Files.exists( guest.resolve( ".git/shallow" ) ), "la aplicacion clona en superficial" );

		// El host juega los dias 22 y 23 y respalda
		Files.writeString( hostWorld.resolve( "world/r.0.0.mca" ), "mundo del 22 por la noche" );
		commitAndPush( hostWorld, "World backup del 22" );
		Files.writeString( hostWorld.resolve( "world/r.0.0.mca" ), "mundo del 23 a las 10:30" );
		commitAndPush( hostWorld, "World backup del 23" );

		// El invitado arranca el 24 con cambios en disco, como los deja cualquier sesion
		Files.writeString( guest.resolve( "usercache.json" ), "[]" );
		assertEquals( GitUtils.SyncState.BEHIND, GitUtils.syncState( guest ),
				"un clon superficial tres dias atras NO puede leerse como divergido" );

		GitUtils.AlignmentResult alignment = GitUtils.alignWithRemote( guest );

		assertTrue( alignment.ready(), alignment.message() );
		assertEquals( "mundo del 23 a las 10:30", Files.readString( guest.resolve( "world/r.0.0.mca" ) ) );
		assertEquals( GitUtils.SyncState.UP_TO_DATE, GitUtils.syncState( guest ) );
		assertFalse( alignment.snapshotBranch().isEmpty(), "tenia cambios en disco: hay que conservarlos" );
		assertTrue( branchNames( guest ).contains( alignment.snapshotBranch() ) );
	}

	/**
	 * El estado exacto en el que se quedó atascado: un commit propio que GitHub
	 * rechaza. Aquí es donde el ensayo local no llega — el non-fast-forward lo
	 * decide GitHub.
	 */
	@Test
	void anAlreadyDivergedGuestIsBlockedAndThenRescued() throws Exception
	{
		Path guest = cloneAs( "guest", true );

		Files.writeString( hostWorld.resolve( "world/r.0.0.mca" ), "mundo del 23 a las 10:30" );
		commitAndPush( hostWorld, "World backup del 23" );

		// El commit que la version anterior creaba sin mirar nada
		Files.writeString( guest.resolve( "world/r.0.0.mca" ), "lo que el invitado tenia" );
		commitLocally( guest, "World backup 1/1 by guest" );
		assertEquals( GitUtils.SyncState.DIVERGED, GitUtils.syncState( guest ) );
		assertTrue( isCleanTree( guest ), "el arbol queda LIMPIO: por eso no habia rescate posible" );

		// GitHub rechazaria este envio: ahora ni se intenta ni se commitea de mas
		int commitsBefore = commitCount( guest );
		GitUtils.BackupPushResult blocked = GitUtils.commitAndPush( guest, false );
		assertFalse( blocked.success() );
		assertEquals( commitsBefore, commitCount( guest ) );

		// Y el rescate lo devuelve al redil conservando su copia
		GitUtils.AlignmentResult alignment = GitUtils.alignWithRemote( guest );

		assertTrue( alignment.ready(), alignment.message() );
		assertEquals( GitUtils.SyncState.DIVERGED, alignment.state() );
		assertFalse( alignment.snapshotBranch().isEmpty() );
		assertEquals( "mundo del 23 a las 10:30", Files.readString( guest.resolve( "world/r.0.0.mca" ) ) );
		assertEquals( GitUtils.SyncState.UP_TO_DATE, GitUtils.syncState( guest ) );
	}

	/** Alineado, el invitado respalda contra GitHub desde su clon superficial. */
	@Test
	void anAlignedShallowGuestCanBackUpAgainstRealGitHub() throws Exception
	{
		Path guest = cloneAs( "guest", true );
		GitUtils.protectMachineLocalFiles( guest );
		Files.writeString( guest.resolve( "world/r.0.0.mca" ), "la tarde del invitado" );

		GitUtils.BackupPushResult backup = GitUtils.commitAndPush( guest, false );

		assertTrue( backup.success(), backup.message() );
		assertEquals( GitUtils.SyncState.UP_TO_DATE, GitUtils.syncState( guest ) );

		// Y el host lo ve: el mundo del invitado llego de verdad a GitHub
		assertEquals( GitUtils.SyncState.BEHIND, GitUtils.syncState( hostWorld ) );
		assertTrue( GitUtils.pull( hostWorld ) );
		assertEquals( "la tarde del invitado", Files.readString( hostWorld.resolve( "world/r.0.0.mca" ) ) );
	}

	// ---- Utilidades --------------------------------------------------------

	private Path cloneAs( String name, boolean shallow ) throws Exception
	{
		Path world = temporaryDirectory.resolve( name );
		var clone = Git.cloneRepository()
				.setURI( cloneUrl )
				.setDirectory( world.toFile() )
				.setCredentialsProvider( credentials );
		if( shallow )
			clone.setDepth( 1 );
		clone.call().close();
		try (Git git = Git.open( world.toFile() ))
		{
			StoredConfig config = git.getRepository().getConfig();
			config.setString( "user", null, "name", name );
			config.setString( "user", null, "email", name + "@example.test" );
			config.save();
		}
		GitUtils.ensureBackupIgnoreFile( world );
		return world;
	}

	private void commitAndPush( Path world, String message ) throws Exception
	{
		commitLocally( world, message );
		try (Git git = Git.open( world.toFile() ))
		{
			git.push().setCredentialsProvider( credentials ).call();
		}
	}

	private void commitLocally( Path world, String message ) throws Exception
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
}
