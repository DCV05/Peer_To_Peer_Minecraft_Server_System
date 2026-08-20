package jgit;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Revisa un servidor de Minecraft ANTES de que Git lo prepare para subir.
 *
 * <p>GitHub rechaza objetos Git sueltos de más de 100 MiB y limita cada push a
 * 2 GiB. P2PMSS se queda muy por debajo del límite de push construyendo commits
 * de 256 MiB; este preflight rechaza además los ficheros que Git normal no puede
 * representar en absoluto. Los ficheros generados en tiempo de ejecución se
 * ignoran tanto aquí como en el {@code .gitignore} gestionado por
 * {@link GitUtils}, para que el preflight y el commit vean el mismo árbol.</p>
 *
 * <p>Decisión de diseño: el preflight NO toca la red ni el repositorio. Solo
 * mira el disco y devuelve un veredicto, de modo que un mundo inviable se
 * detecta antes de crear nada remoto.</p>
 */
public final class GitBackupPreflight
{

	// ---- FASE 1 — Limites de GitHub y contratos de resultado ---------------

	public static final long MEBIBYTE = 1024L * 1024L;
	public static final long LARGE_FILE_WARNING_BYTES = 50L * MEBIBYTE;
	public static final long MAX_GITHUB_FILE_BYTES = 100L * MEBIBYTE;
	public static final long COMMIT_BATCH_BYTES = 256L * MEBIBYTE;
	public static final long MAX_RECOMMENDED_REPOSITORY_BYTES = 10L * 1024L * MEBIBYTE;
	private static final String BATCH_SIZE_PROPERTY = "p2pmss.gitCommitBatchBytes";

	/** A regular file selected for backup, relative to the server root. */
	public record FileEntry( Path relativePath, long size )
	{
	}

	/** Immutable result used by both initial setup and every later backup. */
	public record Result(
			boolean safe,
			long totalBytes,
			long fileCount,
			List<FileEntry> files,
			List<FileEntry> largeFiles,
			List<FileEntry> blockedFiles,
			String message )
	{
		/** Veredicto negativo sin inventario: el arbol ni siquiera se pudo recorrer. */
		public static Result failure( String message )
		{
			return new Result( false, 0, 0, List.of(), List.of(), List.of(), message );
		}

		/** Veredicto negativo CON inventario: se recorrio todo, pero algo no es subible. */
		public static Result rejected( long totalBytes, List<FileEntry> files, List<FileEntry> largeFiles,
				List<FileEntry> blockedFiles, String message )
		{
			return new Result( false, totalBytes, files.size(), files, largeFiles, blockedFiles, message );
		}

		/** Veredicto positivo: el mundo cabe en GitHub tal y como esta. */
		public static Result ok( long totalBytes, List<FileEntry> files, List<FileEntry> largeFiles,
				List<FileEntry> blockedFiles, String message )
		{
			return new Result( true, totalBytes, files.size(), files, largeFiles, blockedFiles, message );
		}
	}

	private GitBackupPreflight()
	{
	}

	// ---- FASE 2 — Recorrido del arbol del servidor -------------------------

	/**
	 * Recorre la carpeta del servidor y emite el veredicto de si puede subirse.
	 * Cualquier fallo de lectura degrada a "no seguro" con un mensaje concreto:
	 * es preferible no crear nada remoto a subir un mundo incompleto.
	 */
	public static Result inspect( Path serverRoot )
	{
		Result result;
		do
		{
			if( serverRoot == null || !Files.isDirectory( serverRoot ) )
			{
				result = Result.failure( "The selected server folder is not accessible." );
				break;
			}

			List<FileEntry> files = new ArrayList<>();
			try
			{
				Files.walkFileTree( serverRoot, new SimpleFileVisitor<>()
				{
					@Override
					public FileVisitResult preVisitDirectory( Path directory, BasicFileAttributes attributes )
					{
						if( directory.equals( serverRoot ) )
							return FileVisitResult.CONTINUE;
						// Podar el subarbol entero es lo que hace barato el preflight en
						// mundos con miles de logs y crash-reports
						return isRuntimeOnly( serverRoot.relativize( directory ) )
								? FileVisitResult.SKIP_SUBTREE
								: FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFile( Path file, BasicFileAttributes attributes )
					{
						Path relative = serverRoot.relativize( file );
						if( attributes.isRegularFile() && !isRuntimeOnly( relative ) )
						{
							files.add( new FileEntry( relative, attributes.size() ) );
						}
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFileFailed( Path file, IOException failure )
					{
						// El visitor no puede devolver un Result: se sube el fallo como
						// excepcion propia para nombrar el fichero exacto ahi fuera
						throw new UnreadableBackupFile( serverRoot.relativize( file ), failure );
					}
				} );
			}
			catch( UnreadableBackupFile unreadable )
			{
				result = Result.failure( "The backup cannot read " + portable( unreadable.relativePath ) + "." );
				break;
			}
			catch( IOException walkFailure )
			{
				result = Result.failure( "The server folder could not be inspected before backup." );
				break;
			}

			// Orden estable: los lotes deben salir iguales en cada maquina, si no
			// dos peers parten el mismo mundo en commits distintos
			files.sort( Comparator.comparing( entry -> portable( entry.relativePath() ) ) );
			result = evaluate( files );
		} while( false );
		return result;
	}

	// ---- FASE 3 — Evaluacion y lotes de commit -----------------------------

	static Result evaluate( List<FileEntry> selectedFiles )
	{
		Result result;
		do
		{
			List<FileEntry> files = List.copyOf( selectedFiles );
			long totalBytes = files.stream().mapToLong( FileEntry::size ).sum();
			List<FileEntry> largeFiles = files.stream()
					.filter( file -> file.size() > LARGE_FILE_WARNING_BYTES )
					.toList();
			List<FileEntry> blockedFiles = files.stream()
					.filter( file -> file.size() > MAX_GITHUB_FILE_BYTES )
					.toList();

			if( !blockedFiles.isEmpty() )
			{
				result = Result.rejected( totalBytes, files, largeFiles, blockedFiles,
						"GitHub blocks files over 100 MiB. Move or reduce " + summarize( blockedFiles )
								+ " before retrying; no remote repository was created." );
				break;
			}
			if( totalBytes > MAX_RECOMMENDED_REPOSITORY_BYTES )
			{
				result = Result.rejected( totalBytes, files, largeFiles, blockedFiles,
						"This server needs " + humanSize( totalBytes )
								+ ". P2PMSS caps GitHub world repositories at the recommended 10 GiB size; no remote repository was created." );
				break;
			}

			String message = "Backup preflight passed: " + files.size() + " files, " + humanSize( totalBytes ) + ".";
			if( !largeFiles.isEmpty() )
			{
				// Aviso, no bloqueo: por encima de 50 MiB GitHub protesta pero acepta
				message += " " + largeFiles.size() + " file(s) exceed GitHub's 50 MiB warning threshold but remain uploadable.";
			}
			result = Result.ok( totalBytes, files, largeFiles, blockedFiles, message );
		} while( false );
		return result;
	}

	/**
	 * Parte los ficheros en lotes conservadores para que cada commit y cada push
	 * queden muy por debajo del limite de 2 GiB de GitHub.
	 */
	public static List<List<FileEntry>> batches( List<FileEntry> files )
	{
		long configuredBatchSize = Long.getLong( BATCH_SIZE_PROPERTY, COMMIT_BATCH_BYTES );
		long batchSize = configuredBatchSize > 0 ? configuredBatchSize : COMMIT_BATCH_BYTES;
		List<List<FileEntry>> batches = new ArrayList<>();
		List<FileEntry> current = new ArrayList<>();
		long currentBytes = 0;

		for( FileEntry file : files )
		{
			// Un fichero nunca se parte: un lote puede pasarse de tamano si el
			// fichero solo ya lo excede, y aun asi cabe en el push
			if( !current.isEmpty() && currentBytes + file.size() > batchSize )
			{
				batches.add( List.copyOf( current ) );
				current.clear();
				currentBytes = 0;
			}
			current.add( file );
			currentBytes += file.size();
		}
		if( !current.isEmpty() )
			batches.add( List.copyOf( current ) );
		return List.copyOf( batches );
	}

	/**
	 * Debe reflejar exactamente las entradas del bloque gestionado del .gitignore:
	 * si preflight y .gitignore discrepan, el commit incluye ficheros que el
	 * preflight nunca peso y el lote se pasa de tamano.
	 */
	static boolean isRuntimeOnly( Path relativePath )
	{
		if( relativePath == null || relativePath.getNameCount() == 0 )
			return false;
		String portable = portable( relativePath );
		String first = relativePath.getName( 0 ).toString();
		String fileName = relativePath.getFileName().toString();
		return ".git".equals( first )
				|| "logs".equals( first )
				|| "crash-reports".equals( first )
				|| "world-import-backups".equals( first )
				|| first.startsWith( ".p2pmss-import-" )
				|| ".DS_Store".equals( fileName )
				|| "session.lock".equals( fileName )
				|| portable.endsWith( ".tmp" );
	}

	// ---- FASE 4 — Utilidades de formato ------------------------------------

	/** Formatea un tamano en la unidad binaria mas legible (KiB/MiB/GiB/TiB). */
	public static String humanSize( long bytes )
	{
		if( bytes < 1024 )
			return bytes + " B";
		double value = bytes;
		String[] units = {"KiB", "MiB", "GiB", "TiB"};
		int unit = -1;
		do
		{
			value /= 1024.0;
			unit++;
		} while( value >= 1024.0 && unit < units.length - 1 );
		return String.format( Locale.ROOT, "%.1f %s", value, units[unit] );
	}

	/** Nombra como mucho tres culpables: la lista entera no cabe en un dialogo. */
	private static String summarize( List<FileEntry> files )
	{
		return files.stream().limit( 3 )
				.map( file -> portable( file.relativePath() ) + " (" + humanSize( file.size() ) + ")" )
				.reduce( ( left, right ) -> left + ", " + right )
				.orElse( "the oversized file" );
	}

	/** Git habla siempre con barras normales, tambien en Windows. */
	private static String portable( Path path )
	{
		return path.toString().replace( '\\', '/' );
	}

	/** Interrumpe el recorrido conservando QUE fichero fallo, no solo que fallo alguno. */
	private static final class UnreadableBackupFile extends RuntimeException
	{
		private static final long serialVersionUID = 1L;
		private final Path relativePath;

		private UnreadableBackupFile( Path relativePath, IOException cause )
		{
			super( cause );
			this.relativePath = relativePath;
		}
	}
}
