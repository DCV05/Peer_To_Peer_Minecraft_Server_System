package minecraftServerManagement;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports a Minecraft world without deleting the previous one. The candidate is
 * fully staged and validated before the configured world directory is moved to
 * a timestamped, recoverable backup. A failed final move triggers rollback.
 */
public final class WorldImportService
{
	private static final int MAX_ARCHIVE_ENTRIES = 250_000;
	private static final long MAX_UNCOMPRESSED_BYTES = 128L * 1024 * 1024 * 1024;
	private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern( "yyyyMMdd-HHmmss" );

	private WorldImportService()
	{
	}

	// ---- FASE 1 — Importacion con red de seguridad -------------------------

	public static ImportResult importWorld( Path source, Path serverDirectory )
	{
		ImportResult result;
		Path staging = null;
		Path backup = null;
		Path target = null;
		boolean targetMoved = false;
		try
		{
			do
			{
				Path server = requireDirectory( serverDirectory, "The selected Forge server folder does not exist." );
				Path candidate = requireExisting( source, "The selected world source does not exist." );
				target = configuredWorldDirectory( server );
				if( candidate.equals( target ) || candidate.startsWith( target ) )
				{
					result = ImportResult.failure( target, "The active server world cannot be imported into itself." );
					break;
				}
				if( Files.isDirectory( candidate ) && server.startsWith( candidate ) )
				{
					result = ImportResult.failure( target,
							"Select the world folder, not the Forge server folder or one of its parents." );
					break;
				}

				// Todo se prepara en una carpeta de staging dentro del propio servidor:
				// asi el movimiento final es en el mismo volumen y puede ser atomico
				staging = server.resolve( ".endershare-import-" + UUID.randomUUID() ).normalize();
				Files.createDirectory( staging );
				Path extracted = staging.resolve( "source" );
				if( Files.isDirectory( candidate ) )
				{
					copyDirectory( candidate, extracted );
				}
				else
				{
					String candidateName = candidate.getFileName().toString().toLowerCase();
					if( !candidateName.endsWith( ".zip" ) )
					{
						result = ImportResult.failure( target, "Select a Minecraft world folder or a .zip archive." );
						break;
					}
					extractZip( candidate, extracted );
				}

				Path worldRoot = findWorldRoot( extracted );
				if( worldRoot == null )
				{
					result = ImportResult.failure( target, "No unique level.dat was found in the selected source." );
					break;
				}

				Path prepared = staging.resolve( "prepared-world" );
				move( worldRoot, prepared );
				if( Files.exists( target ) )
				{
					Path backupRoot = server.resolve( "world-import-backups" );
					if( target.equals( backupRoot ) )
						backupRoot = server.resolve( ".endershare-world-import-backups" );
					Files.createDirectories( backupRoot );
					backup = uniqueBackupPath( backupRoot, target.getFileName().toString() );
					move( target, backup );
					targetMoved = true;
				}

				try
				{
					move( prepared, target );
				}
				catch( IOException finalMoveFailure )
				{
					// El mundo anterior ya no esta en su sitio: se devuelve antes de
					// propagar el fallo, para no dejar al servidor sin mundo ninguno
					if( targetMoved && backup != null && !Files.exists( target ) )
					{
						try
						{
							move( backup, target );
							backup = null;
						}
						catch( IOException rollbackFailure )
						{
							throw new IOException( "Import failed; the previous world remains safely at " + backup + ".",
									rollbackFailure );
						}
					}
					throw finalMoveFailure;
				}
				result = ImportResult.success( target, backup );
			} while( false );
		}
		catch( Exception anyImportFailure )
		{
			String message = anyImportFailure.getMessage() == null
					? "The world could not be imported."
					: anyImportFailure.getMessage();
			result = new ImportResult( false, target, backup, message );
		}
		finally
		{
			cleanupStaging( staging );
		}
		return result;
	}

	// ---- FASE 2 — Rutas y validaciones del mundo destino -------------------

	public static Path configuredWorldDirectory( Path serverDirectory ) throws IOException
	{
		Path server = requireDirectory( serverDirectory, "The selected Forge server folder does not exist." );
		Properties properties = new Properties();
		Path propertiesFile = server.resolve( "server.properties" );
		if( Files.isRegularFile( propertiesFile ) )
		{
			try (InputStream input = Files.newInputStream( propertiesFile ))
			{
				properties.load( input );
			}
		}
		String levelName = properties.getProperty( "level-name", "world" ).trim();
		if( levelName.isBlank() )
			levelName = "world";
		Path target = server.resolve( levelName ).normalize();
		if( !target.startsWith( server ) || target.equals( server ) )
		{
			throw new IOException( "The configured level-name points outside the server folder." );
		}
		return target;
	}

	private static Path requireDirectory( Path path, String message ) throws IOException
	{
		Path normalized = requireExisting( path, message );
		if( !Files.isDirectory( normalized ) )
			throw new IOException( message );
		return normalized;
	}

	private static Path requireExisting( Path path, String message ) throws IOException
	{
		if( path == null || !Files.exists( path ) )
			throw new IOException( message );
		return path.toRealPath().normalize();
	}

	// ---- FASE 3 — Copia y extraccion acotadas ------------------------------

	private static void copyDirectory( Path source, Path destination ) throws IOException
	{
		AtomicLong files = new AtomicLong();
		AtomicLong bytes = new AtomicLong();
		Files.walkFileTree( source, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory( Path directory, BasicFileAttributes attributes ) throws IOException
			{
				if( Files.isSymbolicLink( directory ) )
					throw new IOException( "Symbolic links are not allowed in imported worlds." );
				Files.createDirectories( destination.resolve( source.relativize( directory ).toString() ) );
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile( Path file, BasicFileAttributes attributes ) throws IOException
			{
				if( Files.isSymbolicLink( file ) )
					throw new IOException( "Symbolic links are not allowed in imported worlds." );
				enforceSizeLimits( files.incrementAndGet(), bytes.addAndGet( attributes.size() ) );
				Path output = destination.resolve( source.relativize( file ).toString() );
				Files.copy( file, output, StandardCopyOption.COPY_ATTRIBUTES );
				return FileVisitResult.CONTINUE;
			}
		} );
	}

	private static void extractZip( Path archive, Path destination ) throws IOException
	{
		Files.createDirectories( destination );
		long entries = 0;
		long bytes = 0;
		byte[] buffer = new byte[64 * 1024];
		try (ZipInputStream zip = new ZipInputStream( Files.newInputStream( archive ) ))
		{
			ZipEntry entry;
			while( (entry = zip.getNextEntry()) != null )
			{
				entries++;
				enforceSizeLimits( entries, bytes );
				String portableName = entry.getName().replace( '\\', '/' );
				Path output = destination.resolve( portableName ).normalize();
				if( !output.startsWith( destination ) )
					throw new IOException( "The ZIP contains an unsafe path." );
				if( entry.isDirectory() )
				{
					Files.createDirectories( output );
				}
				else
				{
					if( output.getParent() != null )
						Files.createDirectories( output.getParent() );
					try (var file = Files.newOutputStream( output ))
					{
						int read;
						while( (read = zip.read( buffer )) != -1 )
						{
							bytes += read;
							enforceSizeLimits( entries, bytes );
							file.write( buffer, 0, read );
						}
					}
				}
				zip.closeEntry();
			}
		}
	}

	private static void enforceSizeLimits( long entries, long bytes ) throws IOException
	{
		if( entries > MAX_ARCHIVE_ENTRIES || bytes > MAX_UNCOMPRESSED_BYTES )
		{
			throw new IOException( "The selected world exceeds the safe import limits." );
		}
	}

	private static Path findWorldRoot( Path extracted ) throws IOException
	{
		List<Path> candidates = new ArrayList<>();
		try (var paths = Files.find( extracted, 6,
				( path, attributes ) -> attributes.isRegularFile() && path.getFileName().toString().equals( "level.dat" ) ))
		{
			paths.map( Path::getParent ).forEach( candidates::add );
		}
		return candidates.size() == 1 ? candidates.get( 0 ) : null;
	}

	// ---- FASE 4 — Movimiento, copias de seguridad y limpieza ---------------

	private static Path uniqueBackupPath( Path backupRoot, String worldName )
	{
		String base = worldName + "-" + BACKUP_TIMESTAMP.format( LocalDateTime.now() );
		Path candidate = backupRoot.resolve( base );
		int suffix = 2;
		while( Files.exists( candidate ) )
			candidate = backupRoot.resolve( base + "-" + suffix++ );
		return candidate;
	}

	private static void move( Path source, Path destination ) throws IOException
	{
		try
		{
			Files.move( source, destination, StandardCopyOption.ATOMIC_MOVE );
		}
		catch( AtomicMoveNotSupportedException crossVolumeMove )
		{
			// Origen y destino en volumenes distintos: se acepta el movimiento no
			// atomico porque la alternativa seria no poder importar nada
			Files.move( source, destination );
		}
	}

	private static void cleanupStaging( Path staging )
	{
		if( staging == null || !Files.exists( staging )
				|| !staging.getFileName().toString().startsWith( ".endershare-import-" ) )
			return;
		try
		{
			Files.walkFileTree( staging, new SimpleFileVisitor<>()
			{
				@Override
				public FileVisitResult visitFile( Path file, BasicFileAttributes attributes ) throws IOException
				{
					Files.deleteIfExists( file );
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory( Path directory, IOException error ) throws IOException
				{
					if( error != null )
						throw error;
					Files.deleteIfExists( directory );
					return FileVisitResult.CONTINUE;
				}
			} );
		}
		catch( IOException cleanupFailure )
		{
			// Una limpieza fallida solo deja una carpeta de staging con nombre
			// reconocible; el mundo activo no se ve afectado, asi que no se propaga
			app.Log.event( "WORLD_IMPORT", "Import staging folder could not be removed: " + staging, cleanupFailure );
		}
	}

	public record ImportResult( boolean success, Path worldDirectory, Path backupDirectory, String message )
	{
		private static ImportResult success( Path world, Path backup )
		{
			return new ImportResult( true, world, backup, backup == null
					? "World imported successfully."
					: "World imported successfully; the previous world is preserved at " + backup + "." );
		}

		private static ImportResult failure( Path world, String message )
		{
			return new ImportResult( false, world, null, message );
		}
	}
}
