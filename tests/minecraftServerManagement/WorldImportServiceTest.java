package minecraftServerManagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldImportServiceTest
{
	@TempDir
	Path temporaryDirectory;

	@Test
	void folderImportPreservesPreviousWorldAsRecoverableBackup() throws IOException
	{
		Path server = serverWithWorld( "old-level" );
		Path source = temporaryDirectory.resolve( "single-player-world" );
		Files.createDirectories( source.resolve( "region" ) );
		Files.writeString( source.resolve( "level.dat" ), "new-level" );
		Files.writeString( source.resolve( "region/r.0.0.mca" ), "region-data" );

		WorldImportService.ImportResult result = WorldImportService.importWorld( source, server );

		assertTrue( result.success(), result.message() );
		assertEquals( "new-level", Files.readString( server.resolve( "world/level.dat" ) ) );
		assertEquals( "region-data", Files.readString( server.resolve( "world/region/r.0.0.mca" ) ) );
		assertNotNull( result.backupDirectory() );
		assertEquals( "old-level", Files.readString( result.backupDirectory().resolve( "level.dat" ) ) );
	}

	@Test
	void zipImportAcceptsOneNestedMinecraftWorld() throws IOException
	{
		Path server = serverWithWorld( null );
		Path archive = temporaryDirectory.resolve( "world.zip" );
		try (ZipOutputStream zip = new ZipOutputStream( Files.newOutputStream( archive ) ))
		{
			addEntry( zip, "Exported World/level.dat", "level" );
			addEntry( zip, "Exported World/data/raids.dat", "raids" );
		}

		WorldImportService.ImportResult result = WorldImportService.importWorld( archive, server );

		assertTrue( result.success(), result.message() );
		assertNull( result.backupDirectory() );
		assertEquals( "level", Files.readString( server.resolve( "world/level.dat" ) ) );
		assertEquals( "raids", Files.readString( server.resolve( "world/data/raids.dat" ) ) );
	}

	@Test
	void unsafeZipIsRejectedWithoutTouchingCurrentWorld() throws IOException
	{
		Path server = serverWithWorld( "old-level" );
		Path archive = temporaryDirectory.resolve( "unsafe.zip" );
		try (ZipOutputStream zip = new ZipOutputStream( Files.newOutputStream( archive ) ))
		{
			addEntry( zip, "world/level.dat", "new-level" );
			addEntry( zip, "../escaped.txt", "unsafe" );
		}

		WorldImportService.ImportResult result = WorldImportService.importWorld( archive, server );

		assertFalse( result.success() );
		assertEquals( "old-level", Files.readString( server.resolve( "world/level.dat" ) ) );
		assertFalse( Files.exists( server.resolve( "escaped.txt" ) ) );
		assertFalse( Files.exists( temporaryDirectory.resolve( "escaped.txt" ) ) );
	}

	@Test
	void missingLevelDatIsRejectedWithoutTouchingCurrentWorld() throws IOException
	{
		Path server = serverWithWorld( "old-level" );
		Path source = temporaryDirectory.resolve( "not-a-world" );
		Files.createDirectories( source );
		Files.writeString( source.resolve( "readme.txt" ), "not a world" );

		WorldImportService.ImportResult result = WorldImportService.importWorld( source, server );

		assertFalse( result.success() );
		assertEquals( "old-level", Files.readString( server.resolve( "world/level.dat" ) ) );
	}

	@Test
	void serverFolderCannotBeSelectedAsItsOwnWorldSource() throws IOException
	{
		Path server = serverWithWorld( "old-level" );

		WorldImportService.ImportResult result = WorldImportService.importWorld( server, server );

		assertFalse( result.success() );
		assertEquals( "old-level", Files.readString( server.resolve( "world/level.dat" ) ) );
		try (var paths = Files.list( server ))
		{
			assertFalse( paths.anyMatch( path -> path.getFileName().toString().startsWith( ".p2pmss-import-" ) ) );
		}
	}

	private Path serverWithWorld( String levelContents ) throws IOException
	{
		Path server = Files.createDirectory( temporaryDirectory.resolve( "server-" + System.nanoTime() ) );
		Files.writeString( server.resolve( "server.properties" ), "level-name=world\nmax-players=20\n" );
		if( levelContents != null )
		{
			Files.createDirectories( server.resolve( "world" ) );
			Files.writeString( server.resolve( "world/level.dat" ), levelContents );
		}
		return server;
	}

	private static void addEntry( ZipOutputStream zip, String name, String value ) throws IOException
	{
		zip.putNextEntry( new ZipEntry( name ) );
		zip.write( value.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
		zip.closeEntry();
	}
}
