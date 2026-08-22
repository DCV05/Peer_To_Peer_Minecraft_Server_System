package app;

import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Notificaciones de escritorio de eventos de mundos (alguien empieza a hostear,
 * un mundo queda libre). El backend real depende del sistema: en macOS el
 * TrayIcon va regular, asi que se usa osascript; en Windows/Linux, SystemTray.
 * En tests se inyecta un backend capturador. El interruptor del usuario se
 * persiste en el data dir y por defecto esta ENCENDIDO.
 */
public final class Notifier
{
	public interface Backend
	{
		void show( String title, String message );
	}

	private static final String SETTINGS_FILE = "notifications.properties";
	private static final String ENABLED_KEY = "world_events";

	private static volatile Backend backend = Notifier::showWithOperatingSystem;
	private static volatile TrayIcon trayIcon;

	private Notifier()
	{
	}

	/** Solo para tests: sustituye el backend real por uno capturador. */
	static void setBackendForTests( Backend replacement )
	{
		backend = replacement != null ? replacement : Notifier::showWithOperatingSystem;
	}

	public static boolean isEnabled()
	{
		boolean result = true;
		Path settings = AppPaths.dataFile( SETTINGS_FILE );
		if( Files.isRegularFile( settings ) )
		{
			Properties properties = new Properties();
			try (FileInputStream input = new FileInputStream( settings.toFile() ))
			{
				properties.load( input );
				result = Boolean.parseBoolean( properties.getProperty( ENABLED_KEY, "true" ) );
			}
			catch( Exception readFailure )
			{
				// Ilegible = valor por defecto: mejor una notificacion de mas
			}
		}
		return result;
	}

	public static void setEnabled( boolean enabled )
	{
		Properties properties = new Properties();
		properties.setProperty( ENABLED_KEY, Boolean.toString( enabled ) );
		Path settings = AppPaths.dataFile( SETTINGS_FILE );
		try
		{
			Files.createDirectories( settings.getParent() );
			try (FileOutputStream output = new FileOutputStream( settings.toFile() ))
			{
				properties.store( output, "Endershare desktop notifications" );
			}
		}
		catch( Exception writeFailure )
		{
			Log.event( "NOTIFIER", "No se pudo guardar el ajuste de notificaciones", writeFailure );
		}
	}

	/** Notifica un evento de mundo si el usuario no lo tiene apagado. Nunca lanza. */
	public static void notifyWorldEvent( String title, String message )
	{
		try
		{
			if( isEnabled() )
				backend.show( title, message );
		}
		catch( Exception notifyFailure )
		{
			Log.event( "NOTIFIER", "No se pudo mostrar una notificacion", notifyFailure );
		}
	}

	private static void showWithOperatingSystem( String title, String message )
	{
		try
		{
			String osName = System.getProperty( "os.name", "" ).toLowerCase();
			if( osName.contains( "mac" ) )
			{
				new ProcessBuilder( "osascript", "-e",
						"display notification \"" + escapeForAppleScript( message )
								+ "\" with title \"" + escapeForAppleScript( title ) + "\"" )
						.start();
			}
			else if( SystemTray.isSupported() )
			{
				TrayIcon icon = trayIcon;
				if( icon == null )
				{
					icon = new TrayIcon( Toolkit.getDefaultToolkit()
							.createImage( Notifier.class.getResource( "/icons/EndershareIcon-16.png" ) ), "Endershare" );
					icon.setImageAutoSize( true );
					SystemTray.getSystemTray().add( icon );
					trayIcon = icon;
				}
				icon.displayMessage( title, message, TrayIcon.MessageType.INFO );
			}
		}
		catch( Exception backendFailure )
		{
			Log.event( "NOTIFIER", "El backend de notificaciones del sistema fallo", backendFailure );
		}
	}

	private static String escapeForAppleScript( String value )
	{
		return value == null ? "" : value.replace( "\\", "\\\\" ).replace( "\"", "\\\"" );
	}
}
