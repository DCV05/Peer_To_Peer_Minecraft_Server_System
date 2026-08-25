package jgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitBackupPreflightTest
{
	@TempDir
	Path temporaryDirectory;

	@AfterEach
	void clearBatchOverride()
	{
		System.clearProperty( "endershare.gitCommitBatchBytes" );
	}

	@Test
	void rejectsAFileGitHubCannotStoreAndIgnoresRuntimeOutput() throws Exception
	{
		Path world = Files.createDirectories( temporaryDirectory.resolve( "world/region" ) );
		Files.writeString( world.resolve( "small.mca" ), "region data" );
		Path logs = Files.createDirectories( temporaryDirectory.resolve( "logs" ) );
		Files.writeString( logs.resolve( "latest.log" ), "not part of backup" );
		Files.writeString( temporaryDirectory.resolve( "world/session.lock" ), "runtime lock" );

		Path oversized = world.resolve( "oversized.mca" );
		try (RandomAccessFile sparse = new RandomAccessFile( oversized.toFile(), "rw" ))
		{
			sparse.setLength( GitBackupPreflight.MAX_GITHUB_FILE_BYTES + 1 );
		}

		GitBackupPreflight.Result result = GitBackupPreflight.inspect( temporaryDirectory );

		assertFalse( result.safe() );
		assertEquals( 2, result.fileCount() );
		assertEquals( List.of( Path.of( "world/region/oversized.mca" ) ),
				result.blockedFiles().stream().map( GitBackupPreflight.FileEntry::relativePath ).toList() );
		assertTrue( result.message().contains( "over 100 MiB" ) );
	}

	@Test
	void runtimeRenderAndCacheDirectoriesNeverCountTowardTheSizeCap() throws Exception
	{
		Path world = Files.createDirectories( temporaryDirectory.resolve( "world/region" ) );
		Files.writeString( world.resolve( "small.mca" ), "region data" );

		// El render de BlueMap y la cache de Fabric no viajan en el backup
		// (.gitignore gestionado): si contaran aqui, un mundo pequeno con un
		// render local de varios GiB se rechazaria por el tope de 10 GiB
		Path bluemap = Files.createDirectories( temporaryDirectory.resolve( "bluemap/web/maps" ) );
		Path fabricCache = Files.createDirectories( temporaryDirectory.resolve( ".fabric/remappedJars" ) );
		try (RandomAccessFile sparse = new RandomAccessFile( bluemap.resolve( "tiles.bin" ).toFile(), "rw" ))
		{
			sparse.setLength( GitBackupPreflight.MAX_RECOMMENDED_REPOSITORY_BYTES + 1 );
		}
		try (RandomAccessFile sparse = new RandomAccessFile( fabricCache.resolve( "mod.jar" ).toFile(), "rw" ))
		{
			sparse.setLength( GitBackupPreflight.MAX_GITHUB_FILE_BYTES + 1 );
		}

		GitBackupPreflight.Result result = GitBackupPreflight.inspect( temporaryDirectory );

		assertTrue( result.safe() );
		assertEquals( 1, result.fileCount() );
		assertTrue( result.blockedFiles().isEmpty() );
	}

	@Test
	void perMachineRamFileStaysOutOfTheBackup() throws Exception
	{
		Path world = Files.createDirectories( temporaryDirectory.resolve( "world" ) );
		Files.writeString( world.resolve( "level.dat" ), "world data" );
		// La RAM es por-maquina: compartirla via backup causo conflictos reales
		Files.writeString( temporaryDirectory.resolve( "user_jvm_args.txt" ), "-Xmx5G" );

		GitBackupPreflight.Result result = GitBackupPreflight.inspect( temporaryDirectory );

		assertTrue( result.safe() );
		assertEquals( 1, result.fileCount() );
	}

	@Test
	void rejectsRepositoriesBeyondGithubRecommendedSizeWithoutOneOversizedFile()
	{
		List<GitBackupPreflight.FileEntry> files = new ArrayList<>();
		for( int index = 0; index < 120; index++ )
		{
			files.add( new GitBackupPreflight.FileEntry( Path.of( "world/region/r." + index + ".mca" ),
					90L * GitBackupPreflight.MEBIBYTE ) );
		}

		GitBackupPreflight.Result result = GitBackupPreflight.evaluate( files );

		assertFalse( result.safe() );
		assertTrue( result.blockedFiles().isEmpty() );
		assertTrue( result.message().contains( "10 GiB" ) );
	}

	@Test
	void dividesSelectedFilesIntoBoundedCommitBatches()
	{
		System.setProperty( "endershare.gitCommitBatchBytes", "10" );
		List<GitBackupPreflight.FileEntry> files = List.of(
				new GitBackupPreflight.FileEntry( Path.of( "a" ), 6 ),
				new GitBackupPreflight.FileEntry( Path.of( "b" ), 4 ),
				new GitBackupPreflight.FileEntry( Path.of( "c" ), 7 ),
				new GitBackupPreflight.FileEntry( Path.of( "d" ), 3 ) );

		List<List<GitBackupPreflight.FileEntry>> batches = GitBackupPreflight.batches( files );

		assertEquals( 2, batches.size() );
		assertEquals( List.of( Path.of( "a" ), Path.of( "b" ) ),
				batches.get( 0 ).stream().map( GitBackupPreflight.FileEntry::relativePath ).toList() );
		assertEquals( List.of( Path.of( "c" ), Path.of( "d" ) ),
				batches.get( 1 ).stream().map( GitBackupPreflight.FileEntry::relativePath ).toList() );
	}

	/**
	 * Las reglas gestionadas viven en .git/info/exclude, que es local y no viaja.
	 * Mientras vivieron en el .gitignore versionado, cada peer lo reescribia en
	 * cada arranque segun su version de la aplicacion y el conflicto era seguro.
	 */
	@Test
	void managedRulesGoToTheLocalExcludeAndAreIdempotent() throws Exception
	{
		try (org.eclipse.jgit.api.Git ignored = org.eclipse.jgit.api.Git.init()
				.setDirectory( temporaryDirectory.toFile() ).call())
		{
			Path ignore = temporaryDirectory.resolve( ".gitignore" );
			Files.writeString( ignore, "custom-plugin-cache/\n" );

			assertTrue( GitUtils.ensureBackupIgnoreFile( temporaryDirectory ) );

			Path exclude = temporaryDirectory.resolve( ".git" ).resolve( "info" ).resolve( "exclude" );
			String first = Files.readString( exclude );
			assertTrue( first.contains( "/logs/" ), first );
			assertTrue( first.contains( "/config/bluemap/" ), first );
			assertTrue( first.contains( "**/*.dat_old" ), first );

			// El .gitignore vuelve a ser solo del duenyo del servidor
			String ownerRules = Files.readString( ignore );
			assertTrue( ownerRules.contains( "custom-plugin-cache/" ) );
			assertFalse( ownerRules.contains( "/logs/" ), ownerRules );

			assertTrue( GitUtils.ensureBackupIgnoreFile( temporaryDirectory ) );
			assertEquals( first, Files.readString( exclude ) );
			assertEquals( 1, Files.readString( exclude ).split( "BEGIN Endershare", -1 ).length - 1 );
		}
	}

	/**
	 * El caso que bloqueo un servidor entero: el bloque de la version anterior se
	 * llamaba P2PMSS, el marcador literal no casaba, se conservaba como regla del
	 * usuario y dos peers acababan con .gitignore distintos.
	 */
	@Test
	void aManagedBlockFromAnyOlderVersionIsCleanedUp() throws Exception
	{
		try (org.eclipse.jgit.api.Git ignored = org.eclipse.jgit.api.Git.init()
				.setDirectory( temporaryDirectory.toFile() ).call())
		{
			Path ignore = temporaryDirectory.resolve( ".gitignore" );
			Files.writeString( ignore, """
					mi-regla/
					# BEGIN P2PMSS MANAGED BACKUP EXCLUDES
					/logs/
					/.p2pmss-import-*/
					# END P2PMSS MANAGED BACKUP EXCLUDES

					# BEGIN Endershare MANAGED BACKUP EXCLUDES
					/logs/
					# END Endershare MANAGED BACKUP EXCLUDES
					""" );

			assertTrue( GitUtils.ensureBackupIgnoreFile( temporaryDirectory ) );

			String ownerRules = Files.readString( ignore );
			assertEquals( "mi-regla/", ownerRules.strip() );
			assertFalse( ownerRules.contains( "MANAGED BACKUP EXCLUDES" ), ownerRules );
		}
	}

	/** Sin reglas propias no se deja un .gitignore vacio que ademas viajaria. */
	@Test
	void aGitignoreThatWasOnlyOursIsRemoved() throws Exception
	{
		try (org.eclipse.jgit.api.Git ignored = org.eclipse.jgit.api.Git.init()
				.setDirectory( temporaryDirectory.toFile() ).call())
		{
			Path ignore = temporaryDirectory.resolve( ".gitignore" );
			Files.writeString( ignore, """
					# BEGIN P2PMSS MANAGED BACKUP EXCLUDES
					/logs/
					# END P2PMSS MANAGED BACKUP EXCLUDES
					""" );

			assertTrue( GitUtils.ensureBackupIgnoreFile( temporaryDirectory ) );

			assertFalse( Files.exists( ignore ), "el .gitignore era enteramente nuestro" );
		}
	}

	/** El alta llama a esto antes de crear el repositorio: no puede reventar. */
	@Test
	void withoutARepositoryYetItStillCleansTheGitignore() throws Exception
	{
		Path ignore = temporaryDirectory.resolve( ".gitignore" );
		Files.writeString( ignore, """
				mi-regla/
				# BEGIN Endershare MANAGED BACKUP EXCLUDES
				/logs/
				# END Endershare MANAGED BACKUP EXCLUDES
				""" );

		assertTrue( GitUtils.ensureBackupIgnoreFile( temporaryDirectory ) );

		assertEquals( "mi-regla/", Files.readString( ignore ).strip() );
	}

	/** Espejo del exclude: si discrepan, el commit mete lo que el ignore excluye. */
	@Test
	void theNewExclusionsAreAlsoRuntimeOnlyForThePreflight()
	{
		assertTrue( GitBackupPreflight.isRuntimeOnly( Path.of( "config", "bluemap", "plugin.conf" ) ) );
		assertTrue( GitBackupPreflight.isRuntimeOnly( Path.of( "world", "level.dat_old" ) ) );
		assertTrue( GitBackupPreflight.isRuntimeOnly( Path.of( "world", "playerdata", "abc.dat_old" ) ) );
		assertFalse( GitBackupPreflight.isRuntimeOnly( Path.of( "world", "level.dat" ) ) );
		assertFalse( GitBackupPreflight.isRuntimeOnly( Path.of( "config", "otro-mod", "ajustes.conf" ) ) );
	}
}
