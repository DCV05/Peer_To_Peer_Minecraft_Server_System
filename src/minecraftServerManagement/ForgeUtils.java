package minecraftServerManagement;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.sun.management.OperatingSystemMXBean;

import cloud.ZipUtils;
import view.GeneralConfigurationsWindows;
import view.MainFrame;
import vpn.DiscoveryResponder;


/**
 * Everything the app needs from a Forge server folder: installer download,
 * version catalogue, launch command, console pump and the server.properties
 * settings surfaced in the dashboard. Each operation comes in two flavours: a
 * checked variant that throws with an actionable message, and a legacy variant
 * that shows a dialog and degrades to a safe default for the older call sites.
 */
public final class ForgeUtils
{

	public static final Path DIR_INSTALLERS = app.AppPaths.dataFile( "forge_installers" );
	private static final String FORGE_METADATA_URL = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
	private static final int DOWNLOAD_TIMEOUT_MILLIS = 30_000;
	/** Orden de busqueda del -Xmx; getServerJarName usa el suyo propio a proposito. */
	private static final String[] MEMORY_SCRIPT_NAMES = {"run.bat", "start.bat", "run.sh", "start.sh"};

	// ---- FASE 1 — Descarga e instalacion de Forge --------------------------

	public static Path downloadForgeInstaller( String version )
	{
		try
		{
			return downloadForgeInstallerChecked( version );
		}
		catch( IOException failure )
		{
			JOptionPane.showMessageDialog( null, failure.getMessage(), "Forge download failed", JOptionPane.ERROR_MESSAGE );
			return null;
		}
	}

	/** Downloads a Forge installer with finite network timeouts and an atomic final path. */
	public static Path downloadForgeInstallerChecked( String version ) throws IOException
	{
		if( version == null || !version.matches( "[0-9A-Za-z._+-]+" ) )
		{
			throw new IOException( "Select a valid Forge version." );
		}
		Files.createDirectories( DIR_INSTALLERS );
		String url = String.format( "https://maven.minecraftforge.net/net/minecraftforge/forge/%s/forge-%s-installer.jar", version,
				version );
		Path destination = DIR_INSTALLERS.resolve( String.format( "forge-%s-installer.jar", version ) );
		Path partial = DIR_INSTALLERS.resolve( destination.getFileName() + ".part" );
		URLConnection connection = openConnection( url );
		// Se baja a .part y se mueve al final: una descarga cortada no puede dejar
		// un instalador a medias que luego se ejecutaria como si estuviera completo
		try (InputStream download = connection.getInputStream())
		{
			Files.copy( download, partial, StandardCopyOption.REPLACE_EXISTING );
			Files.move( partial, destination, StandardCopyOption.REPLACE_EXISTING );
		}
		catch( IOException downloadFailure )
		{
			Files.deleteIfExists( partial );
			throw new IOException( "Forge installer could not be downloaded. Check the connection and retry.", downloadFailure );
		}
		return destination;
	}

	public static void installForgeServer( Path forgeInstallerFile, Path forgeServerInstalationDirectory )
	{
		try
		{
			installForgeServerChecked( forgeInstallerFile, forgeServerInstalationDirectory );
		}
		catch( InterruptedException interrupted )
		{
			Thread.currentThread().interrupt();
			JOptionPane.showMessageDialog( null, "Forge installation was interrupted.", "Error", JOptionPane.ERROR_MESSAGE );
		}
		catch( IOException failure )
		{
			JOptionPane.showMessageDialog( null, failure.getMessage(), "Error", JOptionPane.ERROR_MESSAGE );
		}
	}

	/** Runs the Forge installer and only succeeds when its process exits cleanly. */
	public static void installForgeServerChecked( Path forgeInstallerFile, Path forgeServerInstalationDirectory )
			throws IOException, InterruptedException
	{
		if( forgeInstallerFile == null || !Files.isRegularFile( forgeInstallerFile ) )
		{
			throw new IOException( "The downloaded Forge installer is missing." );
		}
		if( forgeServerInstalationDirectory == null || !Files.isDirectory( forgeServerInstalationDirectory ) )
		{
			throw new IOException( "The selected server folder is not accessible." );
		}
		ProcessBuilder installerProcess = new ProcessBuilder(
				"java",
				"-jar",
				forgeInstallerFile.toAbsolutePath().toString(),
				"--installServer" );

		installerProcess.directory( forgeServerInstalationDirectory.toFile() );
		installerProcess.inheritIO();

		try
		{
			Process process = installerProcess.start();
			int exitCode = process.waitFor();
			if( exitCode != 0 )
				throw new IOException( "Forge installer exited with code " + exitCode + "." );
		}
		finally
		{
			// El instalador pesa cientos de MB y ya no sirve de nada: se borra pase
			// lo que pase para no acumular uno por cada intento de instalacion
			Files.deleteIfExists( forgeInstallerFile );
		}
	}

	/** Sin eula=true el servidor arranca y se apaga solo, sin decir por que. */
	public static boolean acceptEULA( Path forgeServerInstalationDirectory )
	{
		boolean result = true;
		Path eula = forgeServerInstalationDirectory.resolve( "eula.txt" );
		if( !Files.exists( eula ) )
		{
			try
			{
				Files.createFile( eula );
			}
			catch( IOException creationFailure )
			{
				JOptionPane.showMessageDialog( null, "File not found or inaccessible (eula.txt)", "Error", JOptionPane.ERROR_MESSAGE );
			}
		}

		try
		{
			Files.writeString( eula,
					"#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\r\n"
							+ "eula=true" );
		}
		catch( IOException writeFailure )
		{
			app.Log.event( "FORGE", "eula.txt could not be written at " + eula, writeFailure );
			result = false;
		}
		return result;
	}

	// ---- FASE 2 — Catalogo de versiones de Forge ---------------------------

	public static String downloadForgeMetadata()
	{
		try
		{
			return downloadForgeMetadataChecked();
		}
		catch( IOException failure )
		{
			JOptionPane.showMessageDialog( null, failure.getMessage(), "Forge catalogue failed", JOptionPane.ERROR_MESSAGE );
			return null;
		}
	}

	public static String downloadForgeMetadataChecked() throws IOException
	{
		URLConnection connection = openConnection( FORGE_METADATA_URL );
		try (InputStream metadataBody = connection.getInputStream())
		{
			return new String( metadataBody.readAllBytes(), StandardCharsets.UTF_8 );
		}
		catch( IOException requestFailure )
		{
			throw new IOException( "Forge versions could not be loaded. Check the connection and retry.", requestFailure );
		}
	}

	private static URLConnection openConnection( String url ) throws IOException
	{
		URLConnection connection = URI.create( url ).toURL().openConnection();
		connection.setConnectTimeout( DOWNLOAD_TIMEOUT_MILLIS );
		connection.setReadTimeout( DOWNLOAD_TIMEOUT_MILLIS );
		connection.setRequestProperty( "User-Agent", "Peer_To_Peer_Minecraft_Server_System/1.6" );
		return connection;
	}

	public static List<String> getForgeVersionsList( String forgeMetadata )
	{
		List<String> forgeVersions = new ArrayList<>();
		do
		{
			if( forgeMetadata == null || forgeMetadata.isBlank() )
				break;

			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			Document document = null;

			try
			{
				DocumentBuilder builder = factory.newDocumentBuilder();
				document = builder.parse( new ByteArrayInputStream( forgeMetadata.getBytes() ) );
			}
			catch( ParserConfigurationException | SAXException parserFailure )
			{
				app.Log.event( "FORGE", "Forge maven-metadata.xml could not be parsed", parserFailure );
				JOptionPane.showMessageDialog( null, "XML metadata parser error", "Error", JOptionPane.ERROR_MESSAGE );
			}
			catch( IOException readFailure )
			{
				app.Log.event( "FORGE", "Forge maven-metadata.xml could not be read", readFailure );
				JOptionPane.showMessageDialog( null, "File not found or inaccessible (forgeMetadata)", "Error", JOptionPane.ERROR_MESSAGE );
			}

			// Un catalogo vacio deja el desplegable sin versiones, pero la ventana
			// sigue viva: preferible a tumbar el asistente de instalacion
			if( document == null )
				break;

			NodeList versionNodes = document.getElementsByTagName( "version" );
			for( int i = 0; i < versionNodes.getLength(); i++ )
			{
				forgeVersions.add( versionNodes.item( i ).getTextContent() );
			}
		} while( false );

		return forgeVersions;
	}

	public static List<String> getMinecraftVersionsList( String forgeMetadata )
	{
		List<String> forgeVersions = getForgeVersionsList( forgeMetadata );
		List<String> minecraftVersions = new ArrayList<>();

		for( String forgeVersion : forgeVersions )
		{
			minecraftVersions.add( forgeVersion.split( "-" )[0] );
		}
		minecraftVersions = new ArrayList<>( minecraftVersions.stream().distinct().toList() );
		minecraftVersions.add( 0, "Select Minecraft version" );

		return minecraftVersions;
	}

	public static List<String> getForgeVersionsForMinecraftVersion( String minecraftVersion, List<String> forgeVersions )
	{
		List<String> forgeVersionsFilteredList = new ArrayList<>();
		Stream<String> forgeVersionsFilteredStream = forgeVersions.stream()
				.filter( forgeVersion -> forgeVersion.split( "-" )[0].equals( minecraftVersion ) );
		forgeVersionsFilteredList.addAll( forgeVersionsFilteredStream.toList() );
		forgeVersionsFilteredList.add( 0, "Select a Forge version" );

		return forgeVersionsFilteredList;
	}

	// ---- FASE 3 — Utilidades de escritorio ---------------------------------

	public static void openURL( String url )
	{
		try
		{
			Desktop desktop = Desktop.getDesktop();
			if( desktop.isSupported( Desktop.Action.BROWSE ) )
			{
				desktop.browse( new URI( url ) );
			}
		}
		catch( Exception browseFailure )
		{
			// Sin navegador (headless, Linux sin xdg-open) no se puede hacer mas que
			// dejar constancia: la URL ya esta visible en la interfaz para copiarla
			app.Log.event( "FORGE", "The browser could not be opened for " + url, browseFailure );
		}
	}

	public static void openModsFolder( Path serverDirectory )
	{
		File modsDirectory = serverDirectory.resolve( "mods" ).toFile();
		// Un servidor recien instalado no tiene carpeta mods: la primera pulsacion
		// la crea y la segunda ya la abre en el explorador de archivos
		if( !Files.exists( modsDirectory.toPath() ) )
		{
			try
			{
				Files.createDirectories( modsDirectory.toPath() );
			}
			catch( IOException creationFailure )
			{
				app.Log.event( "FORGE", "The mods folder could not be created at " + modsDirectory, creationFailure );
				JOptionPane.showMessageDialog( null, "Directory not found or inaccessible (mods folder)", "Error",
						JOptionPane.ERROR_MESSAGE );
			}
		}
		else
		{
			try
			{
				Desktop.getDesktop().open( modsDirectory );
			}
			catch( IOException openFailure )
			{
				app.Log.event( "FORGE", "The mods folder could not be opened at " + modsDirectory, openFailure );
				JOptionPane.showMessageDialog( null, "Directory not found or inaccessible (mods folder)", "Error",
						JOptionPane.ERROR_MESSAGE );
			}
		}
	}

	// ---- FASE 4 — Arranque del proceso del servidor ------------------------

	public static boolean hasServerStartupCommand( Path serverDirectory )
	{
		return buildStartupCommand( serverDirectory, isWindows() ) != null;
	}

	public static Process executeMinecraftServer( Path serverDirectory )
	{
		Process result = null;
		try
		{
			List<String> command = buildStartupCommand( serverDirectory, isWindows() );
			if( command != null )
			{
				ProcessBuilder serverLauncher = new ProcessBuilder( command );
				serverLauncher.directory( serverDirectory.toFile() );
				// stderr fundido con stdout: la consola de la app lee un unico pipe
				serverLauncher.redirectErrorStream( true );
				result = serverLauncher.start();
			}
		}
		catch( IOException startFailure )
		{
			// null significa "no se pudo arrancar" para toda la interfaz
			app.Log.event( "FORGE", "The Minecraft server process could not be started at " + serverDirectory, startFailure );
		}
		return result;
	}

	/** El script de arranque manda sobre el jar: trae los argumentos que Forge necesita. */
	static List<String> buildStartupCommand( Path serverDirectory, boolean windows )
	{
		List<String> result = null;
		do
		{
			Path startupScript = findStartupScript( serverDirectory, windows );
			if( startupScript != null )
			{
				String scriptName = startupScript.getFileName().toString();
				result = windows
						? List.of( "cmd.exe", "/c", scriptName, "nogui" )
						: List.of( "/bin/sh", scriptName, "nogui" );
				break;
			}

			String serverJarName = getServerJarName( serverDirectory );
			if( serverJarName == null )
				break;
			result = List.of( "java", getServerRAMAlloc( serverDirectory ), "-jar", serverJarName, "nogui" );
		} while( false );
		return result;
	}

	private static Path findStartupScript( Path serverDirectory, boolean windows )
	{
		String[] candidates = windows
				? new String[]{"run.bat", "start.bat"}
				: new String[]{"run.sh", "start.sh"};
		Path result = null;
		for( String candidate : candidates )
		{
			Path script = serverDirectory.resolve( candidate );
			if( Files.isRegularFile( script ) )
			{
				result = script;
				break;
			}
		}
		return result;
	}

	private static boolean isWindows()
	{
		return System.getProperty( "os.name", "" ).toLowerCase().contains( "win" );
	}

	// ---- FASE 5 — Consola del servidor y volcado del mundo -----------------

	public static Thread getServerOutputs( Process serverProcess, JTextArea consoleArea )
	{
		return getServerOutputs( serverProcess, consoleArea, line ->
		{
		} );
	}

	/** True only for the real server-ready line, e.g. Done (1.614s)! — chat containing "Done" must not match. */
	static boolean isServerReadyLine( String line )
	{
		return line != null && line.matches( ".*\\bDone \\(.*\\)!.*" );
	}

	/**
	 * Streams Forge output to both the console and an optional state observer.
	 * The whole loop body is exception-proof: if this thread dies, nobody reads
	 * the child's pipe, the buffer fills up and the server freezes for players.
	 */
	public static Thread getServerOutputs( Process serverProcess, JTextArea consoleArea, Consumer<String> outputObserver )
	{
		Thread consoleThread = new Thread( () ->
		{
			try (BufferedReader reader = new BufferedReader( new InputStreamReader( serverProcess.getInputStream() ) ))
			{

				String line;
				while( (line = reader.readLine()) != null )
				{
					String finalLine = line;
					try
					{
						if( outputObserver != null )
							outputObserver.accept( finalLine );
					}
					catch( RuntimeException observerFailure )
					{
						// Un observador roto no puede matar al lector: si este hilo
						// muere, nadie vacia el pipe y el servidor se congela
						app.Log.event( "FORGE", "A console output observer failed on one line", observerFailure );
					}
					noteConsoleLine( finalLine );
					try
					{
						if( finalLine.contains( "> \\" ) )
							CustomCommands.processCustomCommand( finalLine );
					}
					catch( RuntimeException commandFailure )
					{
						// Un comando de barra mal escrito no puede tumbar el lector
						app.Log.event( "FORGE", "A backslash command failed: " + finalLine, commandFailure );
					}
					if( isServerReadyLine( finalLine ) )
					{
						// El arranque de los servicios de host puede tardar segundos: fuera
						// del hilo lector, que su unico trabajo es vaciar el pipe del server
						new Thread( () ->
						{
							try
							{
								MainFrame.responder = new DiscoveryResponder( MainFrame.networkName,
										() -> MainFrame.window == null ? "" : MainFrame.window.playerDiscoveryPayload() )
										.listenAsync( MainFrame.actualServerPort );
								MainFrame.window.checkServerStatus();
								MainFrame.window.startHostServices();

								if( ZipUtils.existsDirectory( GeneralConfigurationsWindows.USER_OPS_PATH ) )
								{
									for( String nickname : ZipUtils
											.getDataFromPropertiesFile( "userOps", GeneralConfigurationsWindows.USER_OPS_PATH )
											.split( ", " ) )
									{
										ForgeUtils.sendCommand( "/op " + nickname, MainFrame.serverProcess, MainFrame.serverWriter );
									}
								}
							}
							catch( RuntimeException hostServicesFailure )
							{
								app.Log.event( "FORGE", "Host services could not be started after the server was ready",
										hostServicesFailure );
							}
						}, "p2pmss-host-services" ).start();
					}
					// La respuesta al sondeo de jugadores (un "list" cada pocos segundos)
					// alimenta al tracker pero no se pinta: solo ensucia la consola
					if( !isPresencePollNoise( finalLine ) )
					{
						SwingUtilities.invokeLater( () ->
						{
							consoleArea.append( finalLine + "\n" );
							trimConsole( consoleArea );
							consoleArea.setCaretPosition( consoleArea.getDocument().getLength() );
						} );
					}
				}

			}
			catch( IOException pipeClosed )
			{
				// El pipe se cierra en cada apagado del servidor: es el final normal
				// de este hilo, no un error que merezca ruido en el log
			}
		}, "ServerOutputReader" );
		consoleThread.start();
		return consoleThread;
	}

	private static final Pattern PRESENCE_POLL_NOISE = Pattern.compile(
			"There are\\s+\\d+\\s+of a max of\\s+\\d+\\s+players online", Pattern.CASE_INSENSITIVE );
	private static final int CONSOLE_MAX_LINES = 1200;
	private static final int CONSOLE_KEEP_LINES = 800;

	/** Roster-poll responses feed the presence tracker but should not reach the visible console. */
	static boolean isPresencePollNoise( String line )
	{
		return line != null && PRESENCE_POLL_NOISE.matcher( line ).find();
	}

	/** Caps the console document so hours of hosting cannot degrade the whole UI. */
	private static void trimConsole( JTextArea consoleArea )
	{
		int lines = consoleArea.getLineCount();
		if( lines <= CONSOLE_MAX_LINES )
			return;
		try
		{
			consoleArea.getDocument().remove( 0, consoleArea.getLineStartOffset( lines - CONSOLE_KEEP_LINES ) );
		}
		catch( javax.swing.text.BadLocationException unreachable )
		{
			// El offset sale del propio documento: si esto salta, recortar es lo de
			// menos, asi que se ignora antes que arriesgar la consola entera
		}
	}

	private static volatile java.util.concurrent.CountDownLatch savedTheGameLatch = null;

	/** Feeds console lines to whoever is waiting for a save confirmation. */
	public static void noteConsoleLine( String line )
	{
		java.util.concurrent.CountDownLatch latch = savedTheGameLatch;
		if( latch != null && line != null && line.contains( "Saved the game" ) )
			latch.countDown();
	}

	/**
	 * Sends "/save-all flush" and blocks until the server prints "Saved the game"
	 * (or the timeout expires). Committing before that confirmation copies region
	 * files mid-write and produces a corrupt snapshot — the reason naive live
	 * backups appear to "not work".
	 */
	public static boolean flushWorldToDisk( Process serverProcess, BufferedWriter serverWriter, long timeoutSeconds )
	{
		boolean result = false;
		java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch( 1 );
		savedTheGameLatch = latch;
		try
		{
			sendCommand( "/save-all flush", serverProcess, serverWriter );
			result = latch.await( timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS );
		}
		catch( InterruptedException waitInterrupted )
		{
			// Sin confirmacion no se puede prometer un mundo integro: false obliga a
			// quien llama a no subir el backup
			Thread.currentThread().interrupt();
		}
		finally
		{
			savedTheGameLatch = null;
		}
		return result;
	}

	public static BufferedWriter configureServerWriter( Process serverProcess, BufferedWriter serverWriter )
	{
		serverWriter = new BufferedWriter( new OutputStreamWriter( serverProcess.getOutputStream() ) );
		return serverWriter;
	}

	public static synchronized void sendCommand( String command, Process serverProcess, BufferedWriter serverWriter )
	{
		if( serverProcess != null && serverProcess.isAlive() && serverWriter != null )
		{
			try
			{
				serverWriter.write( command );
				serverWriter.newLine();
				serverWriter.flush();
			}
			catch( IOException writeFailure )
			{
				app.Log.event( "FORGE", "The command could not be sent to the server: " + command, writeFailure );
			}
		}
	}

	// ---- FASE 6 — Memoria y jar del servidor -------------------------------

	/** -Xmx efectivo: manda user_jvm_args.txt y, si no lo trae, los scripts de arranque. */
	public static String getServerRAMAlloc( Path serverDirectory )
	{
		Pattern memoryPattern = Pattern.compile( "-Xmx[0-9]+[GM]", Pattern.CASE_INSENSITIVE );
		Path jvmArgs = serverDirectory.resolve( "user_jvm_args.txt" );
		String result = findMemoryOption( jvmArgs, memoryPattern );
		if( result == null )
		{
			for( String scriptName : MEMORY_SCRIPT_NAMES )
			{
				result = findMemoryOption( serverDirectory.resolve( scriptName ), memoryPattern );
				if( result != null )
					break;
			}
		}
		// Default cuando aun no hay un -Xmx explicito (las instalaciones nuevas de
		// Forge lo traen comentado): 1G ahoga un mundo con mods, 4G es mas sensato
		return result == null ? "-Xmx4G" : result;
	}

	/** Returns the first uncommented -Xmx of the file, or null when it has none. */
	private static String findMemoryOption( Path file, Pattern memoryPattern )
	{
		String result = null;
		if( Files.isRegularFile( file ) )
		{
			try
			{
				for( String line : Files.readAllLines( file ) )
				{
					if( isCommentedLine( line ) )
						continue;
					Matcher matcher = memoryPattern.matcher( line );
					if( matcher.find() )
					{
						result = matcher.group();
						break;
					}
				}
			}
			catch( IOException unreadable )
			{
				// Un fichero ilegible no corta la busqueda: puede haber otro candidato
			}
		}
		return result;
	}

	public static void setServerRAMAlloc( Path serverDirectory, int gb ) throws Exception
	{
		String memoryUnit = "G";
		// Los valores hasta los GB instalados se leen como GB y los mayores como MB.
		// El limite se toma de la RAM total: la libre no es fiable en macOS ni Linux
		OperatingSystemMXBean operatingSystem = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
		long totalRamGb = (long) (operatingSystem.getTotalMemorySize() / (Math.pow( 1024L, 3 )));

		if( gb <= 0 )
			throw new Exception( "Ram exceeded" );
		if( gb > totalRamGb )
			memoryUnit = "M";
		if( memoryUnit.equals( "M" ) && gb > totalRamGb * 1024L )
			throw new Exception( "Ram exceeded" );

		setServerRAMAlloc( serverDirectory, gb + memoryUnit );
	}

	/** Sets an exact JVM maximum such as {@code 4G} or {@code 2048M}. */
	public static void setServerRAMAlloc( Path serverDirectory, String allocation ) throws Exception
	{
		String normalized = allocation == null ? "" : allocation.trim().toUpperCase();
		Matcher allocationMatcher = Pattern.compile( "^([1-9][0-9]*)([GM])$" ).matcher( normalized );
		if( !allocationMatcher.matches() )
			throw new IllegalArgumentException( "RAM must use a value such as 4G or 2048M." );
		long amount = Long.parseLong( allocationMatcher.group( 1 ) );
		long requestedBytes = "G".equals( allocationMatcher.group( 2 ) )
				? amount * 1024L * 1024L * 1024L
				: amount * 1024L * 1024L;
		OperatingSystemMXBean operatingSystem = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
		if( requestedBytes <= 0 || requestedBytes > operatingSystem.getTotalMemorySize() )
			throw new Exception( "Ram exceeded" );

		Path jvmArgs = serverDirectory.resolve( "user_jvm_args.txt" );
		Path path = Files.isRegularFile( jvmArgs ) ? jvmArgs : findMemoryConfigurationScript( serverDirectory );
		if( path == null )
			throw new IOException( "No JVM arguments or startup script found" );
		List<String> lines = Files.readAllLines( path );
		String replacement = "-Xmx" + normalized;
		Pattern memoryPattern = Pattern.compile( "-Xmx[0-9]+[GM]", Pattern.CASE_INSENSITIVE );
		boolean replaced = false;
		for( int i = 0; i < lines.size(); i++ )
		{
			String line = lines.get( i );
			if( !isCommentedLine( line ) && memoryPattern.matcher( line ).find() )
			{
				lines.set( i, line.replaceAll( "(?i)-Xmx[0-9]+[GM]", replacement ) );
				replaced = true;
			}
		}
		if( !replaced && path.equals( jvmArgs ) )
			lines.add( replacement );
		if( !replaced && !path.equals( jvmArgs ) )
			throw new IOException( "No -Xmx option found in startup script" );
		Files.write( path, lines );
	}

	private static boolean isCommentedLine( String line )
	{
		return line.trim().startsWith( "#" );
	}

	private static Path findMemoryConfigurationScript( Path serverDirectory )
	{
		Path result = null;
		for( String scriptName : MEMORY_SCRIPT_NAMES )
		{
			Path script = serverDirectory.resolve( scriptName );
			if( Files.isRegularFile( script ) )
			{
				result = script;
				break;
			}
		}
		return result;
	}


	/**
	 * Jar with which the server is launched. The startup scripts are the source
	 * of truth; only when none names a jar does the folder listing decide.
	 */
	public static String getServerJarName( Path serverDirectory )
	{
		// Orden propio: start.* antes que run.*, al reves que la busqueda del -Xmx
		String result = null;
		for( String scriptName : new String[]{"start.bat", "start.sh", "run.bat", "run.sh"} )
		{
			result = findJarNameInScript( serverDirectory.resolve( scriptName ) );
			if( result != null )
				break;
		}

		if( result == null )
		{
			try (Stream<Path> files = Files.list( serverDirectory ))
			{
				// El instalador queda descartado y los forge-* van primero: son los
				// unicos jars arrancables de una instalacion normal
				result = files.filter( Files::isRegularFile )
						.map( path -> path.getFileName().toString() )
						.filter( name -> name.endsWith( ".jar" ) && !name.contains( "installer" ) )
						.sorted( ( left, right ) -> Boolean.compare( right.startsWith( "forge-" ), left.startsWith( "forge-" ) ) )
						.findFirst()
						.orElse( null );
			}
			catch( IOException unreadableDirectory )
			{
				// Carpeta ilegible: se devuelve null y la interfaz avisa de que no
				// hay forma de arrancar el servidor
			}
		}
		return result;
	}

	/** Returns the jar named in a java line of the script, or null when there is none. */
	private static String findJarNameInScript( Path script )
	{
		String result = null;
		if( Files.isRegularFile( script ) )
		{
			Pattern jarPattern = Pattern.compile( "(?:^|\\s)([^\\s\"']+\\.jar)(?:\\s|$)" );
			try
			{
				for( String line : Files.readAllLines( script ) )
				{
					if( !line.contains( "java" ) )
						continue;
					Matcher matcher = jarPattern.matcher( line );
					if( matcher.find() )
					{
						result = matcher.group( 1 );
						break;
					}
				}
			}
			catch( IOException unreadable )
			{
				// Un script ilegible no corta la busqueda: puede haber otro candidato
			}
		}
		return result;
	}

	// ---- FASE 7 — Nombre de red y server.properties ------------------------

	/** False solo la primera vez, cuando el fichero acaba de crearse vacio. */
	public static boolean checkIfExistsNetworkNameFileAndCreateIfNot()
	{
		if( !Files.exists( app.AppPaths.dataFile( "networkName.properties" ) ) )
		{
			try
			{
				Files.createFile( app.AppPaths.dataFile( "networkName.properties" ) );
				return false;
			}
			catch( IOException creationFailure )
			{
				JOptionPane.showMessageDialog( null, "File not found or inaccessible (networkName.properties)", "Error",
						JOptionPane.ERROR_MESSAGE );
			}
		}
		return true;
	}

	public static String getNetworkName()
	{
		if( checkIfExistsNetworkNameFileAndCreateIfNot() )
		{
			Properties props = new Properties();
			File file = app.AppPaths.dataFile( "networkName.properties" ).toFile();
			try (FileInputStream in = new FileInputStream( file ))
			{
				props.load( in );
				if( !(props.containsKey( "networkName" )) )
				{
					props.setProperty( "networkName", "DefaultNetworkName" );
					FileOutputStream out = new FileOutputStream( file );
					props.store( out, "Network name updated" );
					out.close();
				}
				return props.getProperty( "networkName" );
			}
			catch( IOException readFailure )
			{
				JOptionPane.showMessageDialog( null, "File not found or inaccessible (networkName.properties)", "Error",
						JOptionPane.ERROR_MESSAGE );
			}
		}
		return "DefaultNetworkName";
	}

	public static void setNetworkName( String newNetworkName )
	{
		if( checkIfExistsNetworkNameFileAndCreateIfNot() )
		{
			Properties props = new Properties();
			File file = app.AppPaths.dataFile( "networkName.properties" ).toFile();
			try (FileInputStream in = new FileInputStream( file ))
			{
				props.load( in );
				props.setProperty( "networkName", newNetworkName );
				FileOutputStream out = new FileOutputStream( file );
				props.store( out, "Network name updated" );
				out.close();
			}
			catch( IOException writeFailure )
			{
				JOptionPane.showMessageDialog( null, "File not found or inaccessible (networkName.properties)", "Error",
						JOptionPane.ERROR_MESSAGE );
			}
		}
	}

	/** Checked variant used by the inline dashboard editor. */
	public static void setNetworkNameChecked( String newNetworkName ) throws IOException
	{
		String normalized = newNetworkName == null ? "" : newNetworkName.trim();
		if( normalized.isBlank() )
			throw new IllegalArgumentException( "Network name cannot be empty." );
		Path dataDirectory = app.AppPaths.data();
		Files.createDirectories( dataDirectory );
		Path file = dataDirectory.resolve( "networkName.properties" );
		Properties properties = new Properties();
		if( Files.isRegularFile( file ) )
		{
			try (InputStream input = Files.newInputStream( file ))
			{
				properties.load( input );
			}
		}
		properties.setProperty( "networkName", normalized );
		try (java.io.OutputStream output = Files.newOutputStream( file ))
		{
			properties.store( output, "Network name updated" );
		}
	}

	public static int getServerPort( Path serverDirectory )
	{
		Path serverProperties = serverDirectory.resolve( "server.properties" );
		if( Files.exists( serverProperties ) )
		{
			Properties props = new Properties();
			try (FileInputStream in = new FileInputStream( serverProperties.toFile() ))
			{
				props.load( in );
				return Integer.parseInt( props.getProperty( "server-port" ) );
			}
			catch( IOException readFailure )
			{
				JOptionPane.showMessageDialog( null, "File not found or inaccessible (server.properties)", "Error",
						JOptionPane.ERROR_MESSAGE );
			}
		}
		return getSavedServerPort();
	}

	public static int getMaxPlayers( Path serverDirectory )
	{
		Path serverProperties = serverDirectory.resolve( "server.properties" );
		if( Files.isRegularFile( serverProperties ) )
		{
			Properties properties = new Properties();
			try (FileInputStream input = new FileInputStream( serverProperties.toFile() ))
			{
				properties.load( input );
				return Math.max( 1, Integer.parseInt( properties.getProperty( "max-players", "20" ) ) );
			}
			catch( IOException | NumberFormatException unreadableSetting )
			{
				// 20 es el default de Minecraft: mejor un aforo plausible que romper
				// el panel por un server.properties corrupto
			}
		}
		return 20;
	}

	public static void setServerPort( Path serverDirectory, int newPort )
	{
		Path serverProperties = serverDirectory.resolve( "server.properties" );
		if( Files.exists( serverProperties ) )
		{
			Properties props = new Properties();
			try (FileInputStream in = new FileInputStream( serverProperties.toFile() ))
			{
				props.load( in );
				props.setProperty( "server-port", "" + newPort );
				FileOutputStream out = new FileOutputStream( serverProperties.toFile() );
				props.store( out, "server properties updated" );
				out.close();
			}
			catch( IOException writeFailure )
			{
				JOptionPane.showMessageDialog( null, "File not found or inaccessible (server.properties)", "Error",
						JOptionPane.ERROR_MESSAGE );
			}
		}
		setSavedServerPort( newPort );
	}

	public static void setServerPortChecked( Path serverDirectory, int newPort ) throws IOException
	{
		if( newPort < 1 || newPort > 65_535 )
			throw new IllegalArgumentException( "Port must be between 1 and 65535." );
		setServerProperty( serverDirectory, "server-port", Integer.toString( newPort ) );
		setSavedServerPort( newPort );
	}

	public static void setMaxPlayers( Path serverDirectory, int maxPlayers ) throws IOException
	{
		if( maxPlayers < 1 || maxPlayers > 1_000 )
			throw new IllegalArgumentException( "Max players must be between 1 and 1000." );
		setServerProperty( serverDirectory, "max-players", Integer.toString( maxPlayers ) );
	}

	private static void setServerProperty( Path serverDirectory, String key, String value ) throws IOException
	{
		Path serverProperties = serverDirectory.resolve( "server.properties" );
		if( !Files.isRegularFile( serverProperties ) )
			throw new IOException( "server.properties was not found in the selected server." );
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream( serverProperties ))
		{
			properties.load( input );
		}
		properties.setProperty( key, value );
		try (java.io.OutputStream output = Files.newOutputStream( serverProperties ))
		{
			properties.store( output, "server properties updated" );
		}
	}

	private static int getSavedServerPort()
	{
		if( checkIfExistsNetworkNameFileAndCreateIfNot() )
		{
			Properties props = new Properties();
			File file = app.AppPaths.dataFile( "networkName.properties" ).toFile();
			try (FileInputStream in = new FileInputStream( file ))
			{
				props.load( in );
				if( !props.containsKey( "server-port" ) )
					props.setProperty( "server-port", "25565" );
				return Integer.parseInt( props.getProperty( "server-port" ) );
			}
			catch( Exception unreadableConfiguration )
			{
				JOptionPane.showMessageDialog( null, "File not found or inaccessible (networkName.properties)", "Error",
						JOptionPane.ERROR_MESSAGE );
			}
		}
		return 25565;
	}

	private static void setSavedServerPort( int port )
	{
		if( checkIfExistsNetworkNameFileAndCreateIfNot() )
		{
			Properties props = new Properties();
			File file = app.AppPaths.dataFile( "networkName.properties" ).toFile();
			try (FileInputStream in = new FileInputStream( file ))
			{
				props.load( in );
				if( !props.containsKey( "server-port" ) )
					props.setProperty( "server-port", "25565" );
				// OJO: el valor solo se actualiza en memoria, aqui no hay store() a
				// disco. Comportamiento historico, se conserva tal cual
				props.setProperty( "server-port", "" + port );
			}
			catch( Exception unreadableConfiguration )
			{
				JOptionPane.showMessageDialog( null, "File not found or inaccessible (networkName.properties)", "Error",
						JOptionPane.ERROR_MESSAGE );
			}
		}
	}
}
