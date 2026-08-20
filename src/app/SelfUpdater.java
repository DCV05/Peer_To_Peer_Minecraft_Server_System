package app;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Descarga el instalador de una release y lo deja listo para lanzarse al salir
 * de la app. La instalacion no puede correr con la app abierta (en Windows los
 * ficheros de la app instalada estan bloqueados por el propio proceso), asi
 * que el flujo es: descargar del todo -> lanzar el instalador -> cerrar la app.
 */
public final class SelfUpdater
{

	private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes( 10 );

	private SelfUpdater()
	{
	}

	/** Carpeta propia para instaladores descargados, dentro del area de datos de la app. */
	public static Path updatesDirectory()
	{
		return AppPaths.dataFile( "updates" );
	}

	/**
	 * Descarga el instalador a disco de forma atomica (.part + move). Devuelve
	 * la ruta final o lanza IOException si la red falla: el llamador decide si
	 * degrada a abrir la descarga en el navegador.
	 */
	public static Path downloadInstaller( String downloadUrl, String fileName ) throws IOException, InterruptedException
	{
		Path result = null;
		do
		{
			if( downloadUrl == null || downloadUrl.isBlank() )
				throw new IOException( "The update has no download for this platform." );

			Files.createDirectories( updatesDirectory() );
			Path destination = updatesDirectory().resolve( fileName );
			Path partial = updatesDirectory().resolve( fileName + ".part" );

			HttpClient client = HttpClient.newBuilder()
					.connectTimeout( Duration.ofSeconds( 30 ) )
					.followRedirects( HttpClient.Redirect.NORMAL )
					.build();
			HttpRequest request = HttpRequest.newBuilder()
					.uri( URI.create( downloadUrl ) )
					.timeout( DOWNLOAD_TIMEOUT )
					.GET()
					.build();

			HttpResponse<InputStream> response = client.send( request, HttpResponse.BodyHandlers.ofInputStream() );
			if( response.statusCode() != 200 )
				throw new IOException( "The installer download answered HTTP " + response.statusCode() + "." );

			try (InputStream in = response.body())
			{
				Files.copy( in, partial, StandardCopyOption.REPLACE_EXISTING );
				Files.move( partial, destination, StandardCopyOption.REPLACE_EXISTING );
			}
			catch( IOException downloadFailure )
			{
				Files.deleteIfExists( partial );
				throw downloadFailure;
			}

			result = destination;
		} while( false );
		return result;
	}

	/**
	 * Lanza el instalador descargado como proceso independiente. Se llama justo
	 * ANTES de salir de la app: el instalador sobrevive al exit y para cuando
	 * copia ficheros la app ya ha muerto y no bloquea nada.
	 */
	public static boolean launchInstaller( Path installer )
	{
		boolean launched = false;
		do
		{
			if( installer == null || !Files.isRegularFile( installer ) )
				break;

			String name = installer.getFileName().toString().toLowerCase();
			List<String> command = new ArrayList<>();
			String osName = System.getProperty( "os.name", "" ).toLowerCase();
			if( name.endsWith( ".exe" ) || name.endsWith( ".msi" ) )
			{
				// Instalador de Windows: se lanza tal cual; si el usuario ve el
				// asistente, son dos clics — la descarga ya esta hecha
				command.add( "cmd" );
				command.add( "/c" );
				command.add( "start" );
				command.add( "\"P2PMSS update\"" );
				command.add( installer.toAbsolutePath().toString() );
			}
			else if( osName.contains( "mac" ) )
			{
				// El DMG (o cualquier otro artefacto) se abre con el visor del sistema
				command.add( "open" );
				command.add( installer.toAbsolutePath().toString() );
			}
			else
			{
				// Sin instalador nativo: se abre la carpeta para que el usuario
				// sustituya el jar a mano
				command.add( "xdg-open" );
				command.add( installer.toAbsolutePath().getParent().toString() );
			}

			try
			{
				new ProcessBuilder( command ).start();
				launched = true;
			}
			catch( IOException launchFailure )
			{
				Log.event( "UPDATER", "The downloaded installer could not be launched: " + installer, launchFailure );
			}
		} while( false );
		return launched;
	}

	/** Nombre de fichero local para la descarga, derivado de la URL del asset. */
	public static String installerFileName( String downloadUrl, String version )
	{
		String extension = ".jar";
		if( downloadUrl != null )
		{
			String lowered = downloadUrl.toLowerCase();
			int lastDot = lowered.lastIndexOf( '.' );
			if( lastDot >= 0 && lastDot > lowered.lastIndexOf( '/' ) )
				extension = lowered.substring( lastDot );
		}
		return "P2PMSS-" + version + extension;
	}
}
