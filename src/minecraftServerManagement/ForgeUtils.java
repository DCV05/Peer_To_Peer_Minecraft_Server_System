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


public class ForgeUtils {
	
	public static final Path DIR_INSTALLERS = app.AppPaths.dataFile("forge_installers");
	private static final String FORGE_METADATA_URL = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
	private static final int DOWNLOAD_TIMEOUT_MILLIS = 30_000;
		
	public static Path downloadForgeInstaller(String version){
		try {
			return downloadForgeInstallerChecked(version);
		} catch(IOException failure) {
			JOptionPane.showMessageDialog(null, failure.getMessage(), "Forge download failed", JOptionPane.ERROR_MESSAGE);
			return null;
		}
	}

	/** Downloads a Forge installer with finite network timeouts and an atomic final path. */
	public static Path downloadForgeInstallerChecked(String version) throws IOException {
		if(version == null || !version.matches("[0-9A-Za-z._+-]+")) {
			throw new IOException("Select a valid Forge version.");
		}
		Files.createDirectories(DIR_INSTALLERS);
		String url = String.format("https://maven.minecraftforge.net/net/minecraftforge/forge/%s/forge-%s-installer.jar", version, version);
		Path destination = DIR_INSTALLERS.resolve(String.format("forge-%s-installer.jar", version));
		Path partial = DIR_INSTALLERS.resolve(destination.getFileName() + ".part");
		URLConnection connection = openConnection(url);
		try(InputStream in = connection.getInputStream()) {
			Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
			Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
		} catch(IOException failure) {
			Files.deleteIfExists(partial);
			throw new IOException("Forge installer could not be downloaded. Check the connection and retry.", failure);
		}
		return destination;
	}
	
	public static void installForgeServer(Path forgeInstallerFile, Path forgeServerInstalationDirectory){
		try {
			installForgeServerChecked(forgeInstallerFile, forgeServerInstalationDirectory);
		} catch(InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			JOptionPane.showMessageDialog(null, "Forge installation was interrupted.", "Error", JOptionPane.ERROR_MESSAGE);
		} catch(IOException failure) {
			JOptionPane.showMessageDialog(null, failure.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	/** Runs the Forge installer and only succeeds when its process exits cleanly. */
	public static void installForgeServerChecked(Path forgeInstallerFile, Path forgeServerInstalationDirectory)
			throws IOException, InterruptedException {
		if(forgeInstallerFile == null || !Files.isRegularFile(forgeInstallerFile)) {
			throw new IOException("The downloaded Forge installer is missing.");
		}
		if(forgeServerInstalationDirectory == null || !Files.isDirectory(forgeServerInstalationDirectory)) {
			throw new IOException("The selected server folder is not accessible.");
		}
		ProcessBuilder pb = new ProcessBuilder(
				"java",
				"-jar",
				forgeInstallerFile.toAbsolutePath().toString(),
				"--installServer"
		);
		
		pb.directory(forgeServerInstalationDirectory.toFile());
		pb.inheritIO();
		
		try {
			Process process = pb.start();
			int exitCode = process.waitFor();
			if(exitCode != 0) throw new IOException("Forge installer exited with code " + exitCode + ".");
		} finally {
			Files.deleteIfExists(forgeInstallerFile);
		}
	}
	
	public static boolean acceptEULA(Path forgeServerInstalationDirectory){
		Path eula = forgeServerInstalationDirectory.resolve("eula.txt");
		if(!(Files.exists(eula))) 
			try {
				Files.createFile(eula);
			} catch (IOException e) {
				JOptionPane.showMessageDialog(null, "File not found or inaccessible (eula.txt)", "Error", JOptionPane.ERROR_MESSAGE);
			}

		try {
			Files.writeString(eula, 
					  "#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\r\n"
					+ "eula=true");
		} catch (IOException e) {
			return false;
		}
		return true;
	}
	
	public static String downloadForgeMetadata() {
		try {
			return downloadForgeMetadataChecked();
		} catch(IOException failure) {
			JOptionPane.showMessageDialog(null, failure.getMessage(), "Forge catalogue failed", JOptionPane.ERROR_MESSAGE);
			return null;
		}
	}

	public static String downloadForgeMetadataChecked() throws IOException {
		URLConnection connection = openConnection(FORGE_METADATA_URL);
		try(InputStream in = connection.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch(IOException failure) {
			throw new IOException("Forge versions could not be loaded. Check the connection and retry.", failure);
		}
	}

	private static URLConnection openConnection(String url) throws IOException {
		URLConnection connection = URI.create(url).toURL().openConnection();
		connection.setConnectTimeout(DOWNLOAD_TIMEOUT_MILLIS);
		connection.setReadTimeout(DOWNLOAD_TIMEOUT_MILLIS);
		connection.setRequestProperty("User-Agent", "Peer_To_Peer_Minecraft_Server_System/1.6");
		return connection;
	}
	
	public static List<String> getForgeVersionsList(String forgeMetadata){
		List<String> forgeVersions = new ArrayList<>();
		if(forgeMetadata == null || forgeMetadata.isBlank()) return forgeVersions;
		
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder = null;
		Document document = null;
		
		try {
			builder = factory.newDocumentBuilder();
			document = builder.parse(new ByteArrayInputStream(forgeMetadata.getBytes()));
		}catch(ParserConfigurationException p) {
			JOptionPane.showMessageDialog(null, "XML metadata parser error", "Error", JOptionPane.ERROR_MESSAGE);
		} catch (SAXException e) {
			JOptionPane.showMessageDialog(null, "XML metadata parser error", "Error", JOptionPane.ERROR_MESSAGE);
		} catch (IOException e) {
			JOptionPane.showMessageDialog(null, "File not found or inaccessible (forgeMetadata)", "Error", JOptionPane.ERROR_MESSAGE);
		}
		
		if(document != null) {
			NodeList versionNodes = document.getElementsByTagName("version");
			
			for(int i = 0; i < versionNodes.getLength(); i++) {
				forgeVersions.add(versionNodes.item(i).getTextContent());
			}
		}
		
		return forgeVersions;
	}
	
	public static List<String> getMinecraftVersionsList(String forgeMetadata){
		List<String> forgeVersions = getForgeVersionsList(forgeMetadata);
		List<String> minecraftVersions = new ArrayList<>();
		
		for(String forgeVersion : forgeVersions) {
			minecraftVersions.add(forgeVersion.split("-")[0]);
		}
		minecraftVersions = new ArrayList<>(minecraftVersions.stream().distinct().toList());
		minecraftVersions.add(0, "Select Minecraft version");
		
		return minecraftVersions;
	}
	
	public static List<String> getForgeVersionsForMinecraftVersion(String minecraftVersion, List<String> forgeVersions){
		List<String> forgeVersionsFilteredList = new ArrayList<>();
		Stream<String> forgeVersionsFilteredStream = forgeVersions.stream().filter(fgVer -> fgVer.split("-")[0].equals(minecraftVersion));
		forgeVersionsFilteredList.addAll(forgeVersionsFilteredStream.toList());
		forgeVersionsFilteredList.add(0, "Select a Forge version");
		
		return forgeVersionsFilteredList;
	}
	
	public static void openURL(String url) {
	    try {
	        Desktop desktop = Desktop.getDesktop();
	        if (desktop.isSupported(Desktop.Action.BROWSE)) {
	            desktop.browse(new URI(url));
	        }
	    } catch (Exception e) {
	        e.printStackTrace();
	    }
	}
	
	public static void openModsFolder(Path serverDirectory) {
		File modsDirectory = serverDirectory.resolve("mods").toFile();
		if(!(Files.exists(modsDirectory.toPath()))) {
			try {
				Files.createDirectories(modsDirectory.toPath());
			} catch (IOException e) {
				JOptionPane.showMessageDialog(null, "Directory not found or inaccessible (mods folder)", "Error", JOptionPane.ERROR_MESSAGE);
			}
		}
		else {
			try {
				Desktop.getDesktop().open(modsDirectory);
			} catch (IOException e) {
				JOptionPane.showMessageDialog(null, "Directory not found or inaccessible (mods folder)", "Error", JOptionPane.ERROR_MESSAGE);
			}
		}
	}

	public static boolean hasServerStartupCommand(Path serverDirectory) {
		return buildStartupCommand(serverDirectory, isWindows()) != null;
	}
	
	public static Process executeMinecraftServer(Path serverDirectory) {
		try {
			List<String> command = buildStartupCommand(serverDirectory, isWindows());
			if(command == null) return null;
			ProcessBuilder pb = new ProcessBuilder(command);

			pb.directory(serverDirectory.toFile());
			pb.redirectErrorStream(true);
			return pb.start();
		} catch (IOException e) {
			return null;
		}
	}

	static List<String> buildStartupCommand(Path serverDirectory, boolean windows) {
		Path startupScript = findStartupScript(serverDirectory, windows);
		if(startupScript != null) {
			return windows
					? List.of("cmd.exe", "/c", startupScript.getFileName().toString(), "nogui")
					: List.of("/bin/sh", startupScript.getFileName().toString(), "nogui");
		}

		String serverJarName = getServerJarName(serverDirectory);
		return serverJarName == null
				? null
				: List.of("java", getServerRAMAlloc(serverDirectory), "-jar", serverJarName, "nogui");
	}

	private static Path findStartupScript(Path serverDirectory, boolean windows) {
		String[] candidates = windows
				? new String[] {"run.bat", "start.bat"}
				: new String[] {"run.sh", "start.sh"};
		for(String candidate : candidates) {
			Path script = serverDirectory.resolve(candidate);
			if(Files.isRegularFile(script)) return script;
		}
		return null;
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}
	
	public static Thread getServerOutputs(Process serverProcess, JTextArea consoleArea) {
		return getServerOutputs(serverProcess, consoleArea, line -> {});
	}

	/** True only for the real server-ready line, e.g. Done (1.614s)! — chat containing "Done" must not match. */
	static boolean isServerReadyLine(String line) {
		return line != null && line.matches(".*\\bDone \\(.*\\)!.*");
	}

	/**
	 * Streams Forge output to both the console and an optional state observer.
	 * The whole loop body is exception-proof: if this thread dies, nobody reads
	 * the child's pipe, the buffer fills up and the server freezes for players.
	 */
	public static Thread getServerOutputs(Process serverProcess, JTextArea consoleArea, Consumer<String> outputObserver) {
		 Thread consoleThread = new Thread(() -> {
		     try (BufferedReader reader = new BufferedReader(new InputStreamReader(serverProcess.getInputStream()))) {

		         String line;
		         while ((line = reader.readLine()) != null) {
		             String finalLine = line;
		             try {
						if(outputObserver != null) outputObserver.accept(finalLine);
		             } catch(RuntimeException observerFailure) {
						observerFailure.printStackTrace();
		             }
		             noteConsoleLine(finalLine);
		             try {
		                 if(finalLine.contains("> \\")) CustomCommands.processCustomCommand(finalLine);
		             } catch(RuntimeException commandFailure) {
						commandFailure.printStackTrace();
		             }
		             if(isServerReadyLine(finalLine)) {
						// El arranque de los servicios de host puede tardar segundos: fuera
						// del hilo lector, que su unico trabajo es vaciar el pipe del server
						new Thread(() -> {
							try {
								MainFrame.responder = new DiscoveryResponder(MainFrame.networkName,
										() -> MainFrame.window == null ? "" : MainFrame.window.playerDiscoveryPayload())
										.listenAsync(MainFrame.actualServerPort);
								MainFrame.window.checkServerStatus();
								MainFrame.window.startHostServices();

								if(ZipUtils.existsDirectory(GeneralConfigurationsWindows.USER_OPS_PATH)) {
									for(String nickname : ZipUtils.getDataFromPropertiesFile("userOps", GeneralConfigurationsWindows.USER_OPS_PATH).split(", ")) {
										ForgeUtils.sendCommand("/op " + nickname, MainFrame.serverProcess, MainFrame.serverWriter);
									}
								}
							} catch(RuntimeException hostServicesFailure) {
								hostServicesFailure.printStackTrace();
							}
						}, "p2pmss-host-services").start();
		             }
		             // La respuesta al sondeo de jugadores (un "list" cada pocos segundos)
		             // alimenta al tracker pero no se pinta: solo ensucia la consola
		             if(!isPresencePollNoise(finalLine)) {
		                 SwingUtilities.invokeLater(() -> {
		                     consoleArea.append(finalLine + "\n");
		                     trimConsole(consoleArea);
		                     consoleArea.setCaretPosition(consoleArea.getDocument().getLength());
		                 });
		             }
		         }
		
		     } catch (IOException e) {}
		 }, "ServerOutputReader");
		 consoleThread.start();
		return consoleThread;
	}
	
	private static final java.util.regex.Pattern PRESENCE_POLL_NOISE = java.util.regex.Pattern.compile(
			"There are\\s+\\d+\\s+of a max of\\s+\\d+\\s+players online", java.util.regex.Pattern.CASE_INSENSITIVE);
	private static final int CONSOLE_MAX_LINES = 1200;
	private static final int CONSOLE_KEEP_LINES = 800;

	/** Roster-poll responses feed the presence tracker but should not reach the visible console. */
	static boolean isPresencePollNoise(String line) {
		return line != null && PRESENCE_POLL_NOISE.matcher(line).find();
	}

	/** Caps the console document so hours of hosting cannot degrade the whole UI. */
	private static void trimConsole(JTextArea consoleArea) {
		int lines = consoleArea.getLineCount();
		if(lines <= CONSOLE_MAX_LINES) return;
		try {
			consoleArea.getDocument().remove(0, consoleArea.getLineStartOffset(lines - CONSOLE_KEEP_LINES));
		} catch(javax.swing.text.BadLocationException unreachable) {}
	}

	private static volatile java.util.concurrent.CountDownLatch savedTheGameLatch = null;

	/** Feeds console lines to whoever is waiting for a save confirmation. */
	public static void noteConsoleLine(String line) {
		java.util.concurrent.CountDownLatch latch = savedTheGameLatch;
		if(latch != null && line != null && line.contains("Saved the game")) latch.countDown();
	}

	/**
	 * Sends "/save-all flush" and blocks until the server prints "Saved the game"
	 * (or the timeout expires). Committing before that confirmation copies region
	 * files mid-write and produces a corrupt snapshot — the reason naive live
	 * backups appear to "not work".
	 */
	public static boolean flushWorldToDisk(Process serverProcess, BufferedWriter serverWriter, long timeoutSeconds) {
		java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
		savedTheGameLatch = latch;
		try {
			sendCommand("/save-all flush", serverProcess, serverWriter);
			return latch.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} finally {
			savedTheGameLatch = null;
		}
	}

	public static BufferedWriter configureServerWriter(Process serverProcess, BufferedWriter serverWriter) {
		serverWriter = new BufferedWriter(new OutputStreamWriter(serverProcess.getOutputStream()));
		return serverWriter;
	}
	
    public static synchronized void sendCommand(String command, Process serverProcess, BufferedWriter serverWriter) {
        if (serverProcess != null && serverProcess.isAlive() && serverWriter != null) {
            try {
                serverWriter.write(command);
                serverWriter.newLine();
                serverWriter.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
	
	public static String getServerRAMAlloc(Path serverDirectory) {
		Pattern memoryPattern = Pattern.compile("-Xmx[0-9]+[GM]", Pattern.CASE_INSENSITIVE);
		Path jvmArgs = serverDirectory.resolve("user_jvm_args.txt");
		if(Files.isRegularFile(jvmArgs)) {
			try {
				for(String line : Files.readAllLines(jvmArgs)) {
					if(isCommentedLine(line)) continue;
					Matcher matcher = memoryPattern.matcher(line);
					if(matcher.find()) return matcher.group();
				}
			} catch (IOException ignored) {}
		}

		for(String scriptName : new String[] {"run.bat", "start.bat", "run.sh", "start.sh"}) {
			Path script = serverDirectory.resolve(scriptName);
			if(!Files.isRegularFile(script)) continue;
			try {
				for(String line : Files.readAllLines(script)) {
					if(isCommentedLine(line)) continue;
					Matcher matcher = memoryPattern.matcher(line);
					if(matcher.find()) return matcher.group();
				}
			} catch (IOException ignored) {}
		}
		// Default when no explicit -Xmx exists yet (fresh Forge installs ship it commented out).
		// 1G starves a real modded world; 4G is a safer launch default for hosts.
		return "-Xmx4G";
	}
	
	public static void setServerRAMAlloc(Path serverDirectory, int gb) throws Exception {
		String memoryUnit = "G";
		// Values up to the installed GB are interpreted as GB; larger values as MB.
		// Available/free memory is not a reliable launch limit on macOS and Linux.
		OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
		long totalRamGb = (long) (os.getTotalMemorySize() / (Math.pow(1024L, 3)));
		
		if(gb <= 0) throw new Exception("Ram exceeded");
		if(gb > totalRamGb) memoryUnit = "M";
		if(memoryUnit.equals("M") && gb > totalRamGb * 1024L) throw new Exception("Ram exceeded");
		
		setServerRAMAlloc(serverDirectory, gb + memoryUnit);
	}

	/** Sets an exact JVM maximum such as {@code 4G} or {@code 2048M}. */
	public static void setServerRAMAlloc(Path serverDirectory, String allocation) throws Exception {
		String normalized = allocation == null ? "" : allocation.trim().toUpperCase();
		Matcher allocationMatcher = Pattern.compile("^([1-9][0-9]*)([GM])$").matcher(normalized);
		if(!allocationMatcher.matches()) throw new IllegalArgumentException("RAM must use a value such as 4G or 2048M.");
		long amount = Long.parseLong(allocationMatcher.group(1));
		long requestedBytes = "G".equals(allocationMatcher.group(2))
				? amount * 1024L * 1024L * 1024L
				: amount * 1024L * 1024L;
		OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
		if(requestedBytes <= 0 || requestedBytes > os.getTotalMemorySize()) throw new Exception("Ram exceeded");

		Path jvmArgs = serverDirectory.resolve("user_jvm_args.txt");
		Path path = Files.isRegularFile(jvmArgs) ? jvmArgs : findMemoryConfigurationScript(serverDirectory);
		if(path == null) throw new IOException("No JVM arguments or startup script found");
		List<String> lines = Files.readAllLines(path);
		String replacement = "-Xmx" + normalized;
		boolean replaced = false;
		for(int i = 0; i < lines.size(); i++) {
			if(!isCommentedLine(lines.get(i)) && Pattern.compile("-Xmx[0-9]+[GM]", Pattern.CASE_INSENSITIVE).matcher(lines.get(i)).find()) {
				lines.set(i, lines.get(i).replaceAll("(?i)-Xmx[0-9]+[GM]", replacement));
				replaced = true;
			}
		}
		if(!replaced && path.equals(jvmArgs)) lines.add(replacement);
		if(!replaced && !path.equals(jvmArgs)) throw new IOException("No -Xmx option found in startup script");
		Files.write(path, lines);
	}

	private static boolean isCommentedLine(String line) {
		return line.trim().startsWith("#");
	}

	private static Path findMemoryConfigurationScript(Path serverDirectory) {
		for(String scriptName : new String[] {"run.bat", "start.bat", "run.sh", "start.sh"}) {
			Path script = serverDirectory.resolve(scriptName);
			if(Files.isRegularFile(script)) return script;
		}
		return null;
	}
	
	
	public static String getServerJarName(Path serverDirectory) {
		for(String scriptName : new String[] {"start.bat", "start.sh", "run.bat", "run.sh"}) {
			Path script = serverDirectory.resolve(scriptName);
			if(!Files.isRegularFile(script)) continue;
			try {
				for(String line : Files.readAllLines(script)) {
					if(!line.contains("java")) continue;
					Matcher matcher = Pattern.compile("(?:^|\\s)([^\\s\"']+\\.jar)(?:\\s|$)").matcher(line);
					if(matcher.find()) return matcher.group(1);
				}
			} catch (IOException ignored) {}
		}

		try(Stream<Path> files = Files.list(serverDirectory)) {
			return files.filter(Files::isRegularFile)
					.map(path -> path.getFileName().toString())
					.filter(name -> name.endsWith(".jar") && !name.contains("installer"))
					.sorted((left, right) -> Boolean.compare(right.startsWith("forge-"), left.startsWith("forge-")))
					.findFirst()
					.orElse(null);
		} catch (IOException ignored) {}
		return null;
	}
	
	public static boolean checkIfExistsNetworkNameFileAndCreateIfNot() {
		if(!(Files.exists(app.AppPaths.dataFile("networkName.properties")))) {
			try {
				Files.createFile(app.AppPaths.dataFile("networkName.properties"));
				return false;
			} catch (IOException e) {
				JOptionPane.showMessageDialog(null, "File not found or inaccessible (networkName.properties)", "Error", JOptionPane.ERROR_MESSAGE);
			}
		}
		return true;
	}
	
	public static String getNetworkName() {
		if(checkIfExistsNetworkNameFileAndCreateIfNot()) {
			Properties props = new Properties();
			File file = app.AppPaths.dataFile("networkName.properties").toFile();
			try(FileInputStream in = new FileInputStream(file)){
				props.load(in);
				if(!(props.containsKey("networkName"))) {
					props.setProperty("networkName", "DefaultNetworkName");
				    FileOutputStream out = new FileOutputStream(file);
			        props.store(out, "Network name updated");
			        out.close();
				}
				return props.getProperty("networkName");
			}
			catch (IOException e) {
				JOptionPane.showMessageDialog(null, "File not found or inaccessible (networkName.properties)", "Error", JOptionPane.ERROR_MESSAGE);
			}
		}
		return "DefaultNetworkName";
	}
	
	public static void setNetworkName(String newNetworkName) {
		if(checkIfExistsNetworkNameFileAndCreateIfNot()) {
			Properties props = new Properties();
			File file = app.AppPaths.dataFile("networkName.properties").toFile();
			try(FileInputStream in = new FileInputStream(file)){
				props.load(in);
				props.setProperty("networkName", newNetworkName);
			    FileOutputStream out = new FileOutputStream(file);
		        props.store(out, "Network name updated");
		        out.close();
			}
			catch (IOException e) {
				JOptionPane.showMessageDialog(null, "File not found or inaccessible (networkName.properties)", "Error", JOptionPane.ERROR_MESSAGE);
			}
		}
	}

	/** Checked variant used by the inline dashboard editor. */
	public static void setNetworkNameChecked(String newNetworkName) throws IOException {
		String normalized = newNetworkName == null ? "" : newNetworkName.trim();
		if(normalized.isBlank()) throw new IllegalArgumentException("Network name cannot be empty.");
		Path dataDirectory = app.AppPaths.data();
		Files.createDirectories(dataDirectory);
		Path file = dataDirectory.resolve("networkName.properties");
		Properties properties = new Properties();
		if(Files.isRegularFile(file)) {
			try(InputStream input = Files.newInputStream(file)) { properties.load(input); }
		}
		properties.setProperty("networkName", normalized);
		try(java.io.OutputStream output = Files.newOutputStream(file)) {
			properties.store(output, "Network name updated");
		}
	}
	
	public static int getServerPort(Path serverDirectory) {
		Path serverProperties = serverDirectory.resolve("server.properties");
		if(Files.exists(serverProperties)) {
			Properties props = new Properties();
			try(FileInputStream in = new FileInputStream(serverProperties.toFile())){
				props.load(in);
				return Integer.parseInt(props.getProperty("server-port"));
			} 
			catch (IOException e) {
				JOptionPane.showMessageDialog(null, "File not found or inaccessible (server.properties)", "Error", JOptionPane.ERROR_MESSAGE);
			}
		}
		return getSavedServerPort();
	}

	public static int getMaxPlayers(Path serverDirectory) {
		Path serverProperties = serverDirectory.resolve("server.properties");
		if(Files.isRegularFile(serverProperties)) {
			Properties properties = new Properties();
			try(FileInputStream input = new FileInputStream(serverProperties.toFile())) {
				properties.load(input);
				return Math.max(1, Integer.parseInt(properties.getProperty("max-players", "20")));
			} catch(IOException | NumberFormatException ignored) {}
		}
		return 20;
	}
	
	public static void setServerPort(Path serverDirectory, int newPort) {
		Path serverProperties = serverDirectory.resolve("server.properties");
		if(Files.exists(serverProperties)) {
			Properties props = new Properties();
			try(FileInputStream in = new FileInputStream(serverProperties.toFile())){
				props.load(in);
				props.setProperty("server-port", "" + newPort);
			    FileOutputStream out = new FileOutputStream(serverProperties.toFile());
		        props.store(out, "server properties updated");
		        out.close();
			} 
			catch (IOException e) {
				JOptionPane.showMessageDialog(null, "File not found or inaccessible (server.properties)", "Error", JOptionPane.ERROR_MESSAGE);
			}
		}
		setSavedServerPort(newPort);
	}

	public static void setServerPortChecked(Path serverDirectory, int newPort) throws IOException {
		if(newPort < 1 || newPort > 65_535) throw new IllegalArgumentException("Port must be between 1 and 65535.");
		setServerProperty(serverDirectory, "server-port", Integer.toString(newPort));
		setSavedServerPort(newPort);
	}

	public static void setMaxPlayers(Path serverDirectory, int maxPlayers) throws IOException {
		if(maxPlayers < 1 || maxPlayers > 1_000) throw new IllegalArgumentException("Max players must be between 1 and 1000.");
		setServerProperty(serverDirectory, "max-players", Integer.toString(maxPlayers));
	}

	private static void setServerProperty(Path serverDirectory, String key, String value) throws IOException {
		Path serverProperties = serverDirectory.resolve("server.properties");
		if(!Files.isRegularFile(serverProperties)) throw new IOException("server.properties was not found in the selected server.");
		Properties properties = new Properties();
		try(InputStream input = Files.newInputStream(serverProperties)) { properties.load(input); }
		properties.setProperty(key, value);
		try(java.io.OutputStream output = Files.newOutputStream(serverProperties)) {
			properties.store(output, "server properties updated");
		}
	}
	
	private static int getSavedServerPort() {
		if(checkIfExistsNetworkNameFileAndCreateIfNot()) {
			Properties props = new Properties();
			File file = app.AppPaths.dataFile("networkName.properties").toFile();
			try(FileInputStream in = new FileInputStream(file)){
				props.load(in);
				if(!(props.containsKey("server-port"))) props.setProperty("server-port", "25565");
				return Integer.parseInt(props.getProperty("server-port"));
			} 
			catch(Exception e) {
				JOptionPane.showMessageDialog(null, "File not found or inaccessible (networkName.properties)", "Error", JOptionPane.ERROR_MESSAGE);
			}
		}
		return 25565;
	}
	
	private static void setSavedServerPort(int port) {
		if(checkIfExistsNetworkNameFileAndCreateIfNot()) {
			Properties props = new Properties();
			File file = app.AppPaths.dataFile("networkName.properties").toFile();
			try(FileInputStream in = new FileInputStream(file)){
				props.load(in);
				if(!(props.containsKey("server-port"))) props.setProperty("server-port", "25565");
				props.setProperty("server-port", ""+port);
			}
			catch(Exception e) {
				JOptionPane.showMessageDialog(null, "File not found or inaccessible (networkName.properties)", "Error", JOptionPane.ERROR_MESSAGE);
			}
		}
	}
}
