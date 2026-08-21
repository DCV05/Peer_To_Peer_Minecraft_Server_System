package minecraftServerManagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForgeUtilsTest
{
	@TempDir
	Path temporaryDirectory;

	@Test
	void buildsNativeCommandsForWindowsAndUnix() throws Exception
	{
		Path server = Files.createDirectories( temporaryDirectory.resolve( "scripts" ) );
		Files.writeString( server.resolve( "run.bat" ), "@echo off\r\n" );
		Files.writeString( server.resolve( "run.sh" ), "#!/bin/sh\n" );

		assertEquals( List.of( "cmd.exe", "/c", "run.bat", "nogui" ), ForgeUtils.buildStartupCommand( server, true ) );
		assertEquals( List.of( "/bin/sh", "run.sh", "nogui" ), ForgeUtils.buildStartupCommand( server, false ) );
	}

	@Test
	void executesUnixScriptAndForwardsNogui() throws Exception
	{
		Assumptions.assumeFalse( System.getProperty( "os.name", "" ).toLowerCase().contains( "win" ) );
		Path server = Files.createDirectories( temporaryDirectory.resolve( "unix-server" ) );
		Files.writeString( server.resolve( "run.sh" ), "printf '%s' \"$1\" > launched.txt\n" );

		Process process = ForgeUtils.executeMinecraftServer( server );
		assertNotNull( process );
		assertEquals( 0, process.waitFor() );
		assertEquals( "nogui", Files.readString( server.resolve( "launched.txt" ) ) );
	}

	@Test
	void fallsBackToLegacyJarCommand() throws Exception
	{
		Path server = Files.createDirectories( temporaryDirectory.resolve( "legacy-server" ) );
		Files.writeString( server.resolve( "forge-legacy.jar" ), "placeholder" );
		Files.writeString( server.resolve( "user_jvm_args.txt" ), "-Xmx2G\n" );

		assertEquals(
				List.of( "java", "-Xmx2G", "-Xms2G", "-jar", "forge-legacy.jar", "nogui" ),
				ForgeUtils.buildStartupCommand( server, false ) );
	}

	@Test
	void recreatesTheJvmArgsFileOnlyWhenMissing() throws Exception
	{
		Path server = Files.createDirectories( temporaryDirectory.resolve( "args-server" ) );

		ForgeUtils.ensureUserJvmArgsFile( server );
		assertTrue( Files.readString( server.resolve( "user_jvm_args.txt" ) ).contains( "-Xmx4G" ) );

		// Un fichero existente (la RAM elegida por esta maquina) jamas se pisa
		Files.writeString( server.resolve( "user_jvm_args.txt" ), "-Xmx5G" );
		ForgeUtils.ensureUserJvmArgsFile( server );
		assertEquals( "-Xmx5G", Files.readString( server.resolve( "user_jvm_args.txt" ) ) );
	}

	@Test
	void detectsTheMinecraftVersionFromTheVersionsDirectory() throws Exception
	{
		Path server = Files.createDirectories( temporaryDirectory.resolve( "versioned-server" ) );
		Files.createDirectories( server.resolve( "versions/1.19" ) );
		// Los directorios que no son una version numerica no cuentan
		Files.createDirectories( server.resolve( "versions/backup-old" ) );

		assertEquals( "1.19", ForgeUtils.getMinecraftVersion( server ) );
		// Sin carpeta versions/ no se inventa nada
		assertEquals( null, ForgeUtils.getMinecraftVersion( temporaryDirectory.resolve( "no-existe" ) ) );
	}

	@Test
	void readsAndUpdatesRamWithoutDependingOnCurrentlyFreeMemory() throws Exception
	{
		Path server = Files.createDirectories( temporaryDirectory.resolve( "ram-server" ) );
		Path arguments = server.resolve( "user_jvm_args.txt" );
		Files.writeString( arguments, "# local arguments\n-Xmx2G\n" );

		assertEquals( "-Xmx2G", ForgeUtils.getServerRAMAlloc( server ) );
		ForgeUtils.setServerRAMAlloc( server, 1 );
		assertEquals( "-Xmx1G", ForgeUtils.getServerRAMAlloc( server ) );
	}

	@Test
	void ignoresCommentedForgeRamExamplesAndAddsAnActiveValue() throws Exception
	{
		Path server = Files.createDirectories( temporaryDirectory.resolve( "official-ram-server" ) );
		Path arguments = server.resolve( "user_jvm_args.txt" );
		Files.writeString( arguments, "# Example only: -Xmx3G\n# -Xmx4G\n" );

		assertEquals( "-Xmx4G", ForgeUtils.getServerRAMAlloc( server ) );
		ForgeUtils.setServerRAMAlloc( server, 1 );
		assertEquals( "-Xmx1G", ForgeUtils.getServerRAMAlloc( server ) );
		assertTrue( Files.readAllLines( arguments ).stream().anyMatch( line -> line.equals( "-Xmx1G" ) ) );
	}

	@Test
	void createsModsDirectoryWithPlatformIndependentPath() throws Exception
	{
		Path server = Files.createDirectories( temporaryDirectory.resolve( "mods-server" ) );
		ForgeUtils.openModsFolder( server );
		assertTrue( Files.isDirectory( server.resolve( "mods" ) ) );
	}

	@Test
	void writesExactInlineSettingsWithoutTouchingWorldData() throws Exception
	{
		Path server = Files.createDirectories( temporaryDirectory.resolve( "settings-server" ) );
		Files.writeString( server.resolve( "server.properties" ), "server-port=25565\nmax-players=20\nlevel-name=world\n" );
		Files.writeString( server.resolve( "user_jvm_args.txt" ), "-Xmx1G\n" );
		Path world = Files.createDirectories( server.resolve( "world" ) );
		Files.writeString( world.resolve( "level.dat" ), "world-sentinel" );

		ForgeUtils.setServerPortChecked( server, 25570 );
		ForgeUtils.setMaxPlayers( server, 12 );
		ForgeUtils.setServerRAMAlloc( server, "2048M" );

		assertEquals( 25570, ForgeUtils.getServerPort( server ) );
		assertEquals( 12, ForgeUtils.getMaxPlayers( server ) );
		assertEquals( "-Xmx2048M", ForgeUtils.getServerRAMAlloc( server ) );
		assertEquals( "world-sentinel", Files.readString( world.resolve( "level.dat" ) ) );
	}

	@Test
	void serverReadyLineMatchesOnlyTheRealDoneBanner()
	{
		assertTrue( ForgeUtils.isServerReadyLine( "[16:47:07] [Server thread/INFO]: Done (1.614s)! For help, type \"help\"" ) );
		assertTrue( ForgeUtils.isServerReadyLine( "[Server thread/INFO]: Done (12.345s)!" ) );
		org.junit.jupiter.api.Assertions.assertFalse( ForgeUtils.isServerReadyLine( "[16:50:00] [Server thread/INFO]: <Victor> Done" ) );
		org.junit.jupiter.api.Assertions.assertFalse( ForgeUtils.isServerReadyLine( "Done deal, moving on" ) );
		org.junit.jupiter.api.Assertions.assertFalse( ForgeUtils.isServerReadyLine( null ) );
	}

	@Test
	void presencePollResponsesAreConsoleNoise()
	{
		assertTrue( ForgeUtils.isPresencePollNoise( "[17:03:08] [Server thread/INFO]: There are 0 of a max of 20 players online: " ) );
		assertTrue( ForgeUtils.isPresencePollNoise( "There are 3 of a max of 20 players online: a, b, c" ) );
		org.junit.jupiter.api.Assertions
				.assertFalse( ForgeUtils.isPresencePollNoise( "[17:03:08] [Server thread/INFO]: Victor joined the game" ) );
		org.junit.jupiter.api.Assertions.assertFalse( ForgeUtils.isPresencePollNoise( null ) );
	}
}
