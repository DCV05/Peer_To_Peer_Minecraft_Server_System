package cloud;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.swing.JOptionPane;

/**
 * Empaquetado y desempaquetado de las carpetas de servidor, mas los utilitarios
 * de disco que necesitan los proveedores de nube. Todo lo que falla aqui avisa
 * al usuario con un dialogo (es una accion que el ha pedido) y deja rastro en el
 * log con la causa completa, porque el fallo suele ser de permisos o de disco
 * lleno en la maquina del usuario y solo tenemos su consola para diagnosticar.
 */
public final class ZipUtils
{

	public static final Path BACKUPS_ZIPS_FOLDER = app.AppPaths.dataFile( "backups_zips" );
	public static final Path DOWNLOADS_BACKUPS_ZIPS_FOLDER = app.AppPaths.dataFile( "download_backups_zips" );

	// ---- FASE 1 — Empaquetado y desempaquetado ------------------------------

	public static void createZip( Path sourceDir, Path zipFilePath )
	{
		deleteDirectory( BACKUPS_ZIPS_FOLDER );
		createFie( BACKUPS_ZIPS_FOLDER );
		try (ZipOutputStream zipOutput = new ZipOutputStream( new BufferedOutputStream( Files.newOutputStream( zipFilePath ) ) ))
		{
			Files.walk( sourceDir ).forEach( path ->
			{
				try
				{
					if( Files.isDirectory( path ) )
						return;
					Path relativePath = sourceDir.relativize( path );
					if( shouldExclude( relativePath ) )
						return;

					// Separador '/' siempre: un zip creado en Windows tiene que abrirse
					// tal cual en el Linux del que clona el servidor
					ZipEntry zipEntry = new ZipEntry( relativePath.toString().replace( "\\", "/" ) );
					zipOutput.putNextEntry( zipEntry );
					Files.copy( path, zipOutput );
					zipOutput.closeEntry();
				}
				catch( IOException entryFailure )
				{
					throw new UncheckedIOException( entryFailure );
				}
			} );
		}
		catch( IOException zipFailure )
		{
			JOptionPane.showMessageDialog( null,
					"File " + sourceDir + " or " + zipFilePath + " not found or inaccessible or another thing went wrong, try again.",
					"Error", JOptionPane.ERROR_MESSAGE );
			app.Log.event( "CLOUD_BACKUP", "No se pudo comprimir " + sourceDir + " en " + zipFilePath, zipFailure );
		}
	}

	public static void unzip( Path zipFilePath, Path targetDir )
	{
		if( !(Files.exists( zipFilePath )) || !(Files.exists( targetDir )) )
		{
			JOptionPane.showMessageDialog( null, "File " + targetDir + " or " + zipFilePath + " not found or inaccessible, try again.",
					"Error", JOptionPane.ERROR_MESSAGE );
			return;
		}

		try (ZipInputStream zipInput = new ZipInputStream( new BufferedInputStream( Files.newInputStream( zipFilePath ) ) ))
		{

			ZipEntry zipEntry;

			while( (zipEntry = zipInput.getNextEntry()) != null )
			{
				Path resolvePath = targetDir.resolve( zipEntry.getName() ).normalize();

				// Zip slip: una entrada con ../ escribiria fuera de la carpeta destino
				if( !resolvePath.startsWith( targetDir ) )
					throw new IOException( "Zip enty out of destinatary folder: " + zipEntry.getName() );

				if( zipEntry.isDirectory() )
					Files.createDirectories( resolvePath );
				else
				{
					Files.createDirectories( resolvePath.getParent() );

					try (OutputStream entryOutput = Files.newOutputStream( resolvePath ))
					{
						zipInput.transferTo( entryOutput );
					}
				}

				zipInput.closeEntry();
			}
		}
		catch( IOException unzipFailure )
		{
			JOptionPane.showMessageDialog( null,
					"File " + targetDir + " or " + zipFilePath + " not found or inaccessible or another thing went wrong, try again.",
					"Error", JOptionPane.ERROR_MESSAGE );
			app.Log.event( "CLOUD_BACKUP", "No se pudo descomprimir " + zipFilePath + " en " + targetDir, unzipFailure );
		}

	}

	// ---- FASE 2 — Utilitarios de disco --------------------------------------

	public static void createDirectory( Path dir )
	{
		try
		{
			Files.createDirectory( dir );
		}
		catch( IOException creationFailure )
		{
			JOptionPane.showMessageDialog( null, "Path " + dir + " not found or inaccessible or another thing went wrong, try again.",
					"Error", JOptionPane.ERROR_MESSAGE );
		}
	}

	public static void createFie( Path file )
	{
		try
		{
			Files.createFile( file );
		}
		catch( IOException creationFailure )
		{
			JOptionPane.showMessageDialog( null, "Path " + file + " not found or inaccessible or another thing went wrong, try again.",
					"Error", JOptionPane.ERROR_MESSAGE );
		}
	}

	public static boolean deleteDirectory( Path dir )
	{
		boolean result = false;
		do
		{
			if( !Files.exists( dir ) )
				break;

			try
			{
				// Orden inverso: los hijos antes que el padre, o el delete del padre falla
				Files.walk( dir )
						.sorted( Comparator.reverseOrder() )
						.forEach( path ->
						{
							try
							{
								Files.delete( path );
							}
							catch( IOException deleteFailure )
							{
								throw new UncheckedIOException( deleteFailure );
							}
						} );
			}
			catch( IOException walkFailure )
			{
				app.Log.event( "CLOUD_BACKUP", "No se pudo recorrer " + dir + " para borrarla", walkFailure );
				break;
			}

			result = true;
		} while( false );
		return result;
	}

	public static boolean existsDirectory( Path directory )
	{
		return Files.exists( directory );
	}

	// ---- FASE 3 — Ficheros de propiedades -----------------------------------

	public static void createOrModiFyPropertiesFile( String property, String data, Path propertiesFilePath )
	{
		do
		{
			if( !Files.exists( propertiesFilePath ) )
			{
				try
				{
					Files.createFile( propertiesFilePath );
				}
				catch( IOException creationFailure )
				{
					JOptionPane.showMessageDialog( null, "Something went wrong, try again.", "Error", JOptionPane.ERROR_MESSAGE );
					break;
				}
			}

			Properties properties = new Properties();
			try (FileInputStream propertiesInput = new FileInputStream( propertiesFilePath.toFile() ))
			{
				properties.load( propertiesInput );

				properties.setProperty( property, data );
				// La escritura va anidada dentro de la lectura a proposito: se relee y
				// se reescribe el fichero entero para no perder las otras propiedades
				try (FileOutputStream propertiesOutput = new FileOutputStream( propertiesFilePath.toFile() ))
				{
					properties.store( propertiesOutput, "Modify property:" + property + " with data: '" + data + "'." );
				}
			}
			catch( IOException writeFailure )
			{
				JOptionPane.showMessageDialog( null,
						"File " + propertiesFilePath + " not found or inaccessible or another thing went wrong, try again.", "Error",
						JOptionPane.ERROR_MESSAGE );
			}
		} while( false );
	}

	public static String getDataFromPropertiesFile( String property, Path propertiesFilePath )
	{
		String result = null;
		do
		{
			if( !Files.exists( propertiesFilePath ) )
				break;

			Properties properties = new Properties();
			try (FileInputStream propertiesInput = new FileInputStream( propertiesFilePath.toFile() ))
			{
				properties.load( propertiesInput );
				if( !properties.containsKey( property ) )
					break;
				result = properties.getProperty( property );
			}
			catch( IOException readFailure )
			{
				JOptionPane.showMessageDialog( null,
						"File " + propertiesFilePath + " not found or inaccessible or another thing went wrong, try again.", "Error",
						JOptionPane.ERROR_MESSAGE );
			}
		} while( false );
		return result;
	}

	/** Lo que no vale la pena subir: se regenera solo o multiplica el tamano del zip. */
	private static boolean shouldExclude( Path relativePath )
	{
		String normalizedPath = relativePath.toString().replace( "\\", "/" ).toLowerCase();

		return normalizedPath.startsWith( "logs/" )
				|| normalizedPath.startsWith( "crash-reports/" )
				|| normalizedPath.startsWith( "versions/" )
				|| normalizedPath.endsWith( ".log" )
				|| normalizedPath.contains( "/cache/" )
				|| normalizedPath.contains( "/.git/" );
	}
}
