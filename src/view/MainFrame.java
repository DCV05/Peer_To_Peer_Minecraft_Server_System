package view;

import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Toolkit;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import cloud.CloudStorageProvider;
import cloud.ZipUtils;
import cloud.google.GoogleDriveCloudProvider;
import jgit.GitUtils;
import jgit.HostLock;
import jgit.TokenStore;
import minecraftServerManagement.FabricInstaller;
import minecraftServerManagement.ForgeUtils;
import minecraftServerManagement.LoaderKind;
import playit.PlayitAgentFile;
import playit.PlayitTunnel;
import minecraftServerManagement.PlayerPresenceTracker;
import minecraftServerManagement.WorldImportService;
import vpn.DiscoveryResponder;
import vpn.NetworkDiscoverClient;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Desktop;

import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JMenu;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.SwingConstants;

import view.dashboard.MinecraftDashboard;
import view.dashboard.ForgeVersionWizard;
import view.dashboard.MinecraftDashboard.Phase;

public class MainFrame {
	
	private static File newMinecraftServerDirectory = null;
	private static JTextArea consoleArea = null;
	private static Thread consoleThread = null;
	private static JMenu recentServersMenu = null;
	private static JMenuItem addHostingUserBtn = null;
	private static JMenuItem repoInvitationsBtn = null;
	private static JMenuItem gitSignOutBtn = null;
	private static JMenuItem gitSignInBtn = null;
	private static JMenuItem gitHubProfileBtn = null;
	private static JMenuItem cloneRepoBtn = null;
	private static JMenuItem newServerMenuItem = null;
	private static JMenuItem openServerMenuItem = null;
	private static JMenuItem generalConfigurationsMenuItem = null;
	private static JMenuItem GoogleAddHostingUserBtn = null;

	public static JButton turnOnOffBtn = null;
	public static String networkName = null;
	public static int actualServerPort = 0;
	public static DiscoveryResponder responder = null;
	public static JPanel contentPane = null;
	public static JButton createServerBackupsFolderInCloud = null;
	public static final Path CLOUD_PROVIDER_IN_USE_PATH = Path.of("data/cloudProviderInUse.properties");
	public static File serverOpenedDirectory = null;
	public static BufferedWriter serverWriter = null;
	public static Process serverProcess = null;
	public static boolean serverIsOn = false;
	public static CloudStorageProvider cloudProvider = null;
	public static String cloudProviderInUse = "noCloudProvider";
	public static String cloudInUseReminderText[];
	public static JMenuItem cloudInUseReminderMenuText;
	public static MainFrame window = null;
	private static java.util.Timer hostLockHeartbeatTimer = null;
	private static PlayitTunnel activePlayitTunnel = null;

	private JFrame frame;
	private MinecraftDashboard dashboard;
	private volatile Phase dashboardPhase = Phase.NO_SERVER;
	private volatile String dashboardPhaseDetail = "Open or create a Minecraft server";
	private volatile String discoveredHost = "—";
	private volatile String syncState = "NOT CONFIGURED";
	private volatile String lastSync = "—";
	private volatile String dashboardError = "";
	private final PlayerPresenceTracker playerPresence = new PlayerPresenceTracker();
	private final AtomicBoolean privateBackupSetupInProgress = new AtomicBoolean(false);
	private final AtomicBoolean closeInProgress = new AtomicBoolean(false);
	private volatile Path lastAutomaticBackupAttempt;
	private volatile String activeHostLockRepo = null;
	private javax.swing.Timer playerRefreshTimer;

	/**
	 * Launch the application.
	 */
	public static void main(String[] args) {

		ThemeManager.setupSystemTheme();
		
		EventQueue.invokeLater(new Runnable() {
			public void run() {
				try {
					window = new MainFrame();
					window.frame.setVisible(true);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}

	/**
	 * Create the application.
	 */
	public MainFrame() {
		initialize();
		configurePlayerPolling();
	}

	/**
	 * Initialize the contents of the frame.
	 */
	private void initialize() {
		checkIfExistsDataFolder();
		//Initialize networkName
		networkName = ForgeUtils.getNetworkName();
		if(ZipUtils.existsDirectory(Path.of("data/google_tokens/StoredCredential"))) {
			cloudProvider = new GoogleDriveCloudProvider();
			cloudProvider.authenticate();
		}
		cloudProviderInUse = ZipUtils.getDataFromPropertiesFile("cloudProviderInUse", CLOUD_PROVIDER_IN_USE_PATH);
			
		
		frame = new JFrame();
		int frameWidht = 1280;
		int frameHeight = 800;
		frame.setBounds((Toolkit.getDefaultToolkit().getScreenSize().width / 2) - (frameWidht / 2), (Toolkit.getDefaultToolkit().getScreenSize().height / 2) - (frameHeight / 2), frameWidht, frameHeight);
		frame.setMinimumSize(new Dimension(1100, 700));
		frame.getContentPane().setBackground(view.dashboard.DashboardTheme.APP_BACKGROUND);
		frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		

        List<Image> icons = List.of(
            new ImageIcon(MainFrame.class.getResource("/icons/P2PMSSIcon-16.png")).getImage(),
            new ImageIcon(MainFrame.class.getResource("/icons/P2PMSSIcon-32.png")).getImage(),
            new ImageIcon(MainFrame.class.getResource("/icons/P2PMSSIcon-64.png")).getImage()
        );

        frame.setIconImages(icons);
		
		frame.setTitle("Peer To Peer Minecraft Server System");

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
            	saveAndClose();
            }
        });
		
		JPanel panel = new JPanel();
		frame.getContentPane().add(panel, BorderLayout.NORTH);

		
		JMenuBar menuBar = new JMenuBar();
		menuBar.setBorder(null);
		menuBar.setBorderPainted(false);
		frame.setJMenuBar(menuBar);
		
		JMenu fileMenu = new JMenu("File");
		JMenu cloudMenu = new JMenu("Cloud");
		JMenu saveBackupsToCloudMenu = new JMenu("Save backups to cloud...");
		JMenu gitMenu = new JMenu("GitHub");
		gitMenu.setIcon(new ImageIcon(MainFrame.class.getResource("/icons/github.png")));
		JMenu googleDriveMenu = new JMenu("Google Drive");
		googleDriveMenu.setIcon(new ImageIcon(MainFrame.class.getResource("/icons/google-drive.png")));
		cloudInUseReminderText = new String[]{"<html><span style=' color: rgb(177, 177, 177);'>%s.</span></html>", "Currently saving backups in ", "No cloud provider configured yet", "%s choosen for saving backups, but you are not logged in"};
		String cloudStatus = cloudInUseReminderText[2];
		if(cloudProviderInUse != null) {
			if(isGitHubSelected()) {
				cloudStatus = TokenStore.sessionIsOpened() ? cloudInUseReminderText[1] + cloudProviderInUse : cloudInUseReminderText[3].formatted(cloudProviderInUse);
			}
			else {
				cloudStatus = cloudProvider != null && cloudProvider.isSessionOpened() ? cloudInUseReminderText[1] + cloudProviderInUse : cloudInUseReminderText[3].formatted(cloudProviderInUse);
			}
		}
		cloudInUseReminderMenuText = new JMenuItem(cloudInUseReminderText[0].formatted(cloudStatus));
		cloudInUseReminderMenuText.setEnabled(false);
		
		menuBar.add(fileMenu);
		menuBar.add(cloudMenu);
		cloudMenu.add(saveBackupsToCloudMenu);
		cloudMenu.add(gitMenu);
		cloudMenu.add(googleDriveMenu);
		cloudMenu.add(cloudInUseReminderMenuText);
		
		JRadioButtonMenuItem gitMenuItem = new JRadioButtonMenuItem("GitHub");
		gitMenuItem.setIcon(new ImageIcon(MainFrame.class.getResource("/icons/github.png")));
		gitMenuItem.addActionListener(ghList -> {
			radioBtnListener(cloudMenu, saveBackupsToCloudMenu, gitMenuItem);
		});
		
		JRadioButtonMenuItem googleDriveMenuItem = new JRadioButtonMenuItem("Google Drive");
		googleDriveMenuItem.setIcon(new ImageIcon(MainFrame.class.getResource("/icons/google-drive.png")));
		googleDriveMenuItem.addActionListener(gglList -> {
			radioBtnListener(cloudMenu, saveBackupsToCloudMenu, googleDriveMenuItem);
		});
		
		ButtonGroup group = new ButtonGroup();
		group.add(gitMenuItem);
		group.add(googleDriveMenuItem);
		
		Iterator<AbstractButton> it = group.getElements().asIterator();
		while(it.hasNext()) {
			JRadioButtonMenuItem radioBtn = (JRadioButtonMenuItem) it.next();
			if(radioBtn.getText().replaceAll(" ", "").equals(cloudProviderInUse)) radioBtn.setSelected(true);
		}
		
		saveBackupsToCloudMenu.add(gitMenuItem);
		saveBackupsToCloudMenu.add(googleDriveMenuItem);
		
		JMenuItem installInvitedServerBtn = new JMenuItem("Install invited server folder");
		installInvitedServerBtn.addActionListener(insInviServBtn -> {
			GoogleWindows.cloneServerFolderWnd(frame);
		});
		
		JMenuItem signOutDriveBtn = new JMenuItem("Sign out");
		JMenuItem loggedInGoogleDriveText = new JMenuItem("<html><span style='color: rgb(177, 177, 177);'>Logged in Google Drive</span></html>");
		loggedInGoogleDriveText.setEnabled(false);
		
		JMenuItem googleProfileBtn = new JMenuItem("Profile");
		googleProfileBtn.addActionListener(gglprf -> {
			GoogleWindows.googleProfileWnd();
		});
		
		GoogleAddHostingUserBtn = new JMenuItem("Add hosting user");
		GoogleAddHostingUserBtn.addActionListener(gglhtusrBtn -> {
			GoogleWindows.addHostingUser();
		});
	
		JMenuItem signIntoDriveBtn = new JMenuItem("Sign into Google Drive");
		signIntoDriveBtn.addActionListener(sgnggldr -> {
			cloudProvider = new GoogleDriveCloudProvider();
			new Thread(() -> {
				cloudProvider.authenticate();
			}).start();
			SwingUtilities.invokeLater(() -> {
				if(cloudProvider != null || cloudProvider.isSessionOpened()) {
					signIntoDriveBtn.setVisible(false);
					signOutDriveBtn.setVisible(true);
					loggedInGoogleDriveText.setVisible(true);
					googleProfileBtn.setVisible(true);
					installInvitedServerBtn.setVisible(true);
					if(cloudProvider.getProviderName().equals(cloudProviderInUse)) {
						cloudInUseReminderMenuText.setText(cloudInUseReminderText[0].formatted(cloudInUseReminderText[1] + cloudProviderInUse));
					}
				}
			});
		});
		
		signOutDriveBtn.addActionListener(sgntDrvBtn -> {
			String savedProviderName = cloudProvider.getProviderName();
			cloudProvider.closeSession();
			if(cloudProvider == null || !cloudProvider.isSessionOpened()) {
				signOutDriveBtn.setVisible(false);
				loggedInGoogleDriveText.setVisible(false);
				googleProfileBtn.setVisible(false);
				GoogleAddHostingUserBtn.setVisible(false);
				installInvitedServerBtn.setVisible(false);
				signIntoDriveBtn.setVisible(true);
				if(cloudProviderInUse.equals(savedProviderName)) {
					cloudInUseReminderMenuText.setText(cloudInUseReminderText[0].formatted(cloudInUseReminderText[3].formatted(cloudProviderInUse)));
				}
			}
		});
		
		if(cloudProvider == null || !cloudProvider.isSessionOpened() && cloudProvider instanceof GoogleDriveCloudProvider) {
			loggedInGoogleDriveText.setVisible(false);
			signIntoDriveBtn.setVisible(true);
			signOutDriveBtn.setVisible(false);
			googleProfileBtn.setVisible(false);
			GoogleAddHostingUserBtn.setVisible(false);
			installInvitedServerBtn.setVisible(false);
		}
		else {
			loggedInGoogleDriveText.setVisible(true);
			signIntoDriveBtn.setVisible(false);
			googleProfileBtn.setVisible(true);
			installInvitedServerBtn.setVisible(true);
		}
		
		googleDriveMenu.add(signIntoDriveBtn);
		googleDriveMenu.add(loggedInGoogleDriveText);
		googleDriveMenu.add(googleProfileBtn);
		googleDriveMenu.add(GoogleAddHostingUserBtn);
		googleDriveMenu.add(installInvitedServerBtn);
		googleDriveMenu.add(signOutDriveBtn);
		
		addHostingUserBtn = new JMenuItem("Add hosting user");
		addHostingUserBtn.addActionListener(addhstngUsrBtn -> {
			GitWindows.addHostingUser();
		});
		
		gitSignInBtn = new JMenuItem("Sign into GitHub");
		gitSignInBtn.addActionListener(gitLis ->{
			GitWindows.signIntoGitHubWnd(() -> {
					selectGitHubProvider();
					gitSignOutBtn.setVisible(true);
					repoInvitationsBtn.setVisible(true);
					gitHubProfileBtn.setVisible(true);
					cloneRepoBtn.setVisible(true);
					if(isGitHubSelected()) {
						cloudInUseReminderMenuText.setText(cloudInUseReminderText[0].formatted(cloudInUseReminderText[1] + cloudProviderInUse));
					}
						if(serverOpenedDirectory != null) {
							addHostingUserBtn.setVisible(GitUtils.repoExistInPath(serverOpenedDirectory.toPath()) && GitUtils.hasRemoteOrigin(serverOpenedDirectory.toPath()));
							lastAutomaticBackupAttempt = null;
							configurePrivateBackupAsync(false);
						}
					refreshDashboardState();
			});
		});
		
		gitSignOutBtn = new JMenuItem("Sign out");
		gitSignOutBtn.addActionListener(gitOut -> {
			TokenStore.clear();
			if(isGitHubSelected()) {
				cloudInUseReminderMenuText.setText(cloudInUseReminderText[0].formatted(cloudInUseReminderText[3].formatted(cloudProviderInUse)));
			}
			gitSignOutBtn.setVisible(false);
			repoInvitationsBtn.setVisible(false);
			gitHubProfileBtn.setVisible(false);
			addHostingUserBtn.setVisible(false);
			cloneRepoBtn.setVisible(false);
			addHostingUserBtn.setVisible(false);
			refreshDashboardState();
		});
		
		repoInvitationsBtn = new JMenuItem("Server Invitations");
		repoInvitationsBtn.addActionListener(rpInvt -> {
			GitWindows.invitationslistWnd();
		});
		
		gitHubProfileBtn = new JMenuItem("Profile");
		gitHubProfileBtn.addActionListener(prfBtn -> {
			GitWindows.gitHubProfileWnd();
		});
		
		cloneRepoBtn = new JMenuItem("clone a server repo");
		cloneRepoBtn.addActionListener(clnRpBtn -> {
			GitWindows.cloneRepoWnd(frame);
		});
		
		if(!TokenStore.sessionIsOpened()) {
			gitSignOutBtn.setVisible(false);
			repoInvitationsBtn.setVisible(false);
			gitHubProfileBtn.setVisible(false);
			addHostingUserBtn.setVisible(false);
			cloneRepoBtn.setVisible(false);
			addHostingUserBtn.setVisible(false);
		}
		
		gitMenu.add(gitSignInBtn);
		gitMenu.add(gitHubProfileBtn);
		gitMenu.add(cloneRepoBtn);
		gitMenu.add(addHostingUserBtn);
		gitMenu.add(repoInvitationsBtn);
		gitMenu.add(gitSignOutBtn);
		
		createServerBackupsFolderInCloud = new JButton("Create backups folder in %s".formatted(cloudProviderInUse));
		
		contentPane = new JPanel(new BorderLayout());
		contentPane.setBackground(view.dashboard.DashboardTheme.APP_BACKGROUND);
		dashboard = createDashboard();
		contentPane.add(dashboard, BorderLayout.CENTER);
		openServerOptions(contentPane);
		frame.getContentPane().add(contentPane);
		contentPane.setVisible(true);
		
		newServerMenuItem = new JMenuItem("New Minecraft Server");
		newServerMenuItem.setHorizontalAlignment(SwingConstants.LEFT);
		newServerMenuItem.addActionListener(mcSrv -> {
			JFileChooser fileChooser = new JFileChooser();
			fileChooser.setDialogTitle("Choose an empty folder for the new server");
			fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			int result = fileChooser.showOpenDialog(frame);
			if(result == JFileChooser.APPROVE_OPTION) {
				newMinecraftServerDirectory = fileChooser.getSelectedFile();
				String[] children = newMinecraftServerDirectory.list();
				if(!newMinecraftServerDirectory.isDirectory() || children == null || children.length != 0) {
					showError("Folder must be empty", "Choose an accessible empty directory for the new server.");
				} else showForgeVersionWizard(newMinecraftServerDirectory.toPath());
			}
			
			if(result == JFileChooser.CANCEL_OPTION) newMinecraftServerDirectory = null;
		});
		
		openServerMenuItem = new JMenuItem("Open Server Folder");
		openServerMenuItem.setHorizontalAlignment(SwingConstants.LEFT);
		openServerMenuItem.addActionListener(opSer -> {
			JFileChooser fileChooser = new JFileChooser();
			fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
				int result = fileChooser.showOpenDialog(frame);
				if(result == JFileChooser.APPROVE_OPTION) {
					openKnownServer(fileChooser.getSelectedFile().getAbsolutePath());
				}
		});
		
		recentServersMenu = new JMenu("Recent files...");
		recentServerListGenerator();
		
		generalConfigurationsMenuItem = new JMenuItem("General configurations");
		generalConfigurationsMenuItem.addActionListener(gncnf -> {
			GeneralConfigurationsWindows.generalConfigurations();
		});
		
		fileMenu.add(openServerMenuItem);
		fileMenu.add(newServerMenuItem);
		fileMenu.add(recentServersMenu);
		fileMenu.add(generalConfigurationsMenuItem);
		menuBar.setVisible(false);
	}

	private void showForgeVersionWizard(Path destination) {
		JDialog dialog = new JDialog(frame, "Create Minecraft Server", true);
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		dialog.setResizable(false);
		ForgeVersionWizard[] wizardReference = new ForgeVersionWizard[1];
		ForgeVersionWizard wizard = new ForgeVersionWizard(destination,
				selection -> installForgeFromWizard(dialog, wizardReference[0], destination, selection),
				() -> {
					newMinecraftServerDirectory = null;
					dialog.dispose();
				});
		wizardReference[0] = wizard;
		dialog.setContentPane(wizard);
		dialog.pack();
		dialog.setLocationRelativeTo(frame);
		wizard.loadCatalog(loader -> {
			if("Fabric".equals(loader)) {
				FabricInstaller.Catalog catalog = FabricInstaller.loadCatalogChecked();
				// El wizard invierte las listas para mostrar lo nuevo primero; la
				// meta API de Fabric ya viene nuevo-primero, asi que se compensa
				List<String> gameVersions = new java.util.ArrayList<>(catalog.gameVersions());
				List<String> loaderVersions = new java.util.ArrayList<>(catalog.loaderVersions());
				java.util.Collections.reverse(gameVersions);
				java.util.Collections.reverse(loaderVersions);
				return new ForgeVersionWizard.VersionCatalog(gameVersions, loaderVersions, true);
			}
			String metadata = ForgeUtils.downloadForgeMetadataChecked();
			List<String> forgeVersions = ForgeUtils.getForgeVersionsList(metadata);
			if(forgeVersions.isEmpty()) throw new IOException("Forge returned an empty version catalogue.");
			return new ForgeVersionWizard.VersionCatalog(
					ForgeUtils.getMinecraftVersionsList(metadata), forgeVersions);
		});
		dialog.setVisible(true);
	}

	private void installForgeFromWizard(JDialog dialog, ForgeVersionWizard wizard, Path destination,
			ForgeVersionWizard.Selection selection) {
		wizard.setBusy(true, "Downloading " + selection.loader() + " " + selection.forgeVersion() + "…");
		new SwingWorker<Void, String>() {
			@Override protected Void doInBackground() throws Exception {
				if("Fabric".equals(selection.loader())) {
					publish("Downloading the Fabric server launcher…");
					FabricInstaller.installServerChecked(destination, selection.minecraftVersion(), selection.forgeVersion());
				} else {
					Path installer = ForgeUtils.downloadForgeInstallerChecked(selection.forgeVersion());
					publish("Installing Forge server files…");
					ForgeUtils.installForgeServerChecked(installer, destination);
				}
				if(!ForgeUtils.hasServerStartupCommand(destination)) {
					throw new IOException(selection.loader() + " finished but no startup command is available.");
				}
				return null;
			}

			@Override protected void process(List<String> messages) {
				if(!messages.isEmpty()) wizard.setBusy(true, messages.get(messages.size() - 1));
			}

			@Override protected void done() {
				try {
					get();
					wizard.showEulaStep(
							() -> ForgeUtils.openURL("https://aka.ms/MinecraftEULA"),
							() -> {
								if(!ForgeUtils.acceptEULA(destination)) {
									wizard.showError("eula.txt could not be written. Check folder permissions.");
									return;
								}
								newMinecraftServerDirectory = null;
								dialog.dispose();
								openKnownServer(destination.toAbsolutePath().toString());
							});
				} catch(Exception failure) {
					Throwable root = failure;
					while(root.getCause() != null) root = root.getCause();
					wizard.showError(root.getMessage());
				}
			}
		}.execute();
	}

	private MinecraftDashboard createDashboard() {
		return new MinecraftDashboard(new MinecraftDashboard.Actions() {
			@Override public void createServer() {
				if(newServerMenuItem != null) newServerMenuItem.doClick();
			}
			@Override public void openServer() {
				if(openServerMenuItem != null) openServerMenuItem.doClick();
			}
			@Override public void selectServer(String path) {
				openKnownServer(path);
			}
			@Override public void cloneInvitedServer() {
				if(!TokenStore.sessionIsOpened()) {
					showError("GitHub account required", "Sign into GitHub before cloning an invited server.");
					showDashboardPage(MinecraftDashboard.Page.BACKUPS);
					return;
				}
				if(cloneRepoBtn != null) cloneRepoBtn.doClick();
			}
			@Override public void toggleServer() {
				toggleServerFromDashboard();
			}
			@Override public void refreshNetwork() {
				refreshNetworkAsync();
			}
			@Override public void syncNow() {
				synchronizeNow();
			}
			@Override public void importWorld() {
				importWorldFromDashboard();
			}
			@Override public void openModsFolder() {
				openModsFolderFromDashboard();
			}
			@Override public void openServerFolder() {
				openSelectedServerFolder();
			}
			@Override public void openServerSettings() {
				showDashboardPage(MinecraftDashboard.Page.SETTINGS);
			}
			@Override public void saveServerSettings(MinecraftDashboard.SettingsDraft settings) {
				saveServerSettingsFromDashboard(settings);
			}
			@Override public void openGeneralSettings() {
				if(generalConfigurationsMenuItem != null) generalConfigurationsMenuItem.doClick();
			}
			@Override public void createRepository() {
				createRepositoryFromDashboard();
			}
			@Override public void signIntoGitHub() {
				if(gitSignInBtn != null) gitSignInBtn.doClick();
			}
			@Override public void signOutOfGitHub() {
				if(gitSignOutBtn != null) gitSignOutBtn.doClick();
			}
			@Override public void showGitHubProfile() {
				if(gitHubProfileBtn != null) gitHubProfileBtn.doClick();
			}
			@Override public void showInvitations() {
				if(repoInvitationsBtn != null) repoInvitationsBtn.doClick();
			}
			@Override public void inviteHost() {
				if(addHostingUserBtn != null) addHostingUserBtn.doClick();
			}
			@Override public void sendCommand(String command) {
				if(serverIsOn && serverProcess != null && serverWriter != null) {
					String normalized = command == null ? "" : command.trim();
					if("stop".equalsIgnoreCase(normalized) || "/stop".equalsIgnoreCase(normalized)) turnOffServer();
					else ForgeUtils.sendCommand(command, serverProcess, serverWriter);
				}
			}
		});
	}

	public void checkServerStatus() {
		if(serverOpenedDirectory == null) {
			discoveredHost = "—";
			setDashboardPhase(Phase.NO_SERVER, "Open or create a Minecraft server");
			return;
		}

		NetworkDiscoverClient.DiscoveryResult discovery = NetworkDiscoverClient.surroundDiscoverStatus(networkName, actualServerPort, 3000);
		boolean remoteHostFound = discovery.found();
		if(!serverIsOn) {
			if(remoteHostFound && discovery.rosterAvailable()) {
				playerPresence.replaceSnapshot(discovery.players(), discovery.onlinePlayers(), discovery.maxPlayers());
			} else {
				playerPresence.reset(ForgeUtils.getMaxPlayers(serverOpenedDirectory.toPath()));
			}
		}
		discoveredHost = remoteHostFound ? discovery.host() : (serverIsOn ? "LOCAL PROCESS" : "—");
		if(serverIsOn) setDashboardPhase(Phase.ONLINE, "Forge is accepting players");
		else if(remoteHostFound) setDashboardPhase(Phase.REMOTE_HOST, "Another peer is hosting this world");
		else setDashboardPhase(Phase.OFFLINE, "No active host discovered");
	}

	public void openServerOptions(JPanel fatherFrame) {
		if(serverOpenedDirectory == null) loadMostRecentServer();
		if(serverOpenedDirectory != null && TokenStore.sessionIsOpened()) {
			boolean linkedGitRepository = GitUtils.repoExistInPath(serverOpenedDirectory.toPath())
					&& GitUtils.hasRemoteOrigin(serverOpenedDirectory.toPath());
			boolean noProviderSelected = cloudProviderInUse == null || cloudProviderInUse.isBlank()
					|| "noCloudProvider".equals(cloudProviderInUse);
			if(linkedGitRepository || noProviderSelected) selectGitHubProvider();
		}

		turnOnOffBtn = dashboard == null ? null : dashboard.primaryActionButton();
		if(serverOpenedDirectory == null) {
			dashboardPhase = Phase.NO_SERVER;
			dashboardPhaseDetail = "Open or create a Minecraft server";
			discoveredHost = "—";
		} else {
			actualServerPort = ForgeUtils.getServerPort(serverOpenedDirectory.toPath());
			if(dashboardPhase == Phase.NO_SERVER) {
				dashboardPhase = serverIsOn ? Phase.ONLINE : Phase.OFFLINE;
				dashboardPhaseDetail = serverIsOn ? "Forge is accepting players" : "Ready to check the network";
			}
		}

		refreshDashboardState();
		fatherFrame.revalidate();
		fatherFrame.repaint();
		if(recentServersMenu != null) recentServerListGenerator();
		boolean configuringBackup = configurePrivateBackupAsync(false);
		if(serverOpenedDirectory != null && !serverIsOn && !configuringBackup) refreshNetworkAsync();
	}

	private void showDashboardPage(MinecraftDashboard.Page page) {
		if(dashboard != null) dashboard.showPage(page);
	}

	private void setDashboardPhase(Phase phase, String detail) {
		dashboardPhase = phase;
		dashboardPhaseDetail = detail;
		if(phase != Phase.ERROR) dashboardError = "";
		refreshDashboardState();
	}

	private void refreshDashboardState() {
		if(dashboard == null) return;
		if(!SwingUtilities.isEventDispatchThread()) {
			SwingUtilities.invokeLater(this::refreshDashboardState);
			return;
		}

		boolean loaded = serverOpenedDirectory != null;
		boolean authenticated = TokenStore.sessionIsOpened();
		boolean linked = loaded && GitUtils.repoExistInPath(serverOpenedDirectory.toPath()) && GitUtils.hasRemoteOrigin(serverOpenedDirectory.toPath());
		String account = "NOT CONNECTED";
		if(authenticated) {
			try {
				account = TokenStore.getSavedUserData().getOrDefault("nickname", "CONNECTED");
			} catch(Exception ignored) {
				authenticated = false;
			}
		}

		String serverName = loaded ? getServerName() : null;
		String serverPath = loaded ? serverOpenedDirectory.getAbsolutePath() : null;
		String port = loaded ? Integer.toString(ForgeUtils.getServerPort(serverOpenedDirectory.toPath())) : null;
		String ram = "—";
		if(loaded) {
			try {
				ram = ForgeUtils.getServerRAMAlloc(serverOpenedDirectory.toPath()).replace("-Xmx", "");
			} catch(Exception ignored) {}
		}
		String repository = linked ? account + "/" + serverName : "NOT LINKED";
		String displayedSyncState;
		if(!isGitHubSelected()) displayedSyncState = authenticated ? "STANDBY" : "DISABLED";
		else if(!authenticated) displayedSyncState = "AUTH REQUIRED";
		else if(!linked) displayedSyncState = "NOT LINKED";
		else displayedSyncState = "NOT CONFIGURED".equals(syncState) ? "READY" : syncState;
		PlayerPresenceTracker.Snapshot presence = playerPresence.snapshot();

		MinecraftDashboard.State dashboardState = new MinecraftDashboard.State(
				loaded,
				loaded ? dashboardPhase : Phase.NO_SERVER,
				loaded ? dashboardPhaseDetail : "Open or create a Minecraft server",
				serverName,
				serverPath,
				discoveredHost,
				port,
				ram,
				networkName,
				(loaded ? LoaderKind.detect(serverOpenedDirectory.toPath()).displayName().toUpperCase() : "FORGE") + " / JAVA 21",
				presence.players(),
				presence.onlineCount(),
				presence.maxPlayers(),
				isGitHubSelected(),
				authenticated,
				linked,
				account,
				repository,
				displayedSyncState,
				lastSync,
				dashboardError,
				readRecentServers()
		);
		dashboard.setState(dashboardState);
		PlayitAgentFile playitAgent = loaded ? PlayitAgentFile.load(serverOpenedDirectory.toPath()) : null;
		dashboard.showPublicUrl(playitAgent != null && playitAgent.enabled,
				playitAgent == null ? null : playitAgent.tunnel_address);
		turnOnOffBtn = dashboard.primaryActionButton();
		consoleArea = dashboard.consoleArea();
	}

	private List<MinecraftDashboard.ServerEntry> readRecentServers() {
		List<MinecraftDashboard.ServerEntry> entries = new ArrayList<>();
		List<String> paths = new ArrayList<>();
		Path recentServersPath = Path.of("data/recentServers.properties");
		Properties properties = new Properties();
		if(Files.exists(recentServersPath)) {
			try(FileInputStream input = new FileInputStream(recentServersPath.toFile())) {
				properties.load(input);
				String value = properties.getProperty("recentServers", "");
				for(String path : value.split("\\|")) {
					if(!path.isBlank() && !paths.contains(path)) paths.add(path);
				}
			} catch(IOException ignored) {}
		}
		if(serverOpenedDirectory != null) {
			String current = serverOpenedDirectory.getAbsolutePath().replace('\\', '/');
			paths.remove(current);
			paths.add(0, current);
		}
		for(String path : paths) {
			File directory = new File(path);
			String name = directory.getName().isBlank() ? path : directory.getName();
			boolean selected = serverOpenedDirectory != null && directory.getAbsolutePath().equals(serverOpenedDirectory.getAbsolutePath());
			String detail = ForgeUtils.hasServerStartupCommand(directory.toPath())
					? LoaderKind.detect(directory.toPath()).displayName().toUpperCase() + " READY"
					: "MISSING STARTUP SCRIPT";
			entries.add(new MinecraftDashboard.ServerEntry(name, path, detail, selected));
		}
		return entries;
	}

	private void loadMostRecentServer() {
		List<MinecraftDashboard.ServerEntry> entries = readRecentServers();
		if(entries.isEmpty()) return;
		File candidate = new File(entries.get(0).path());
		if(candidate.isDirectory() && ForgeUtils.hasServerStartupCommand(candidate.toPath())) {
			serverOpenedDirectory = candidate;
			actualServerPort = ForgeUtils.getServerPort(candidate.toPath());
			playerPresence.reset(ForgeUtils.getMaxPlayers(candidate.toPath()));
			dashboardPhase = Phase.OFFLINE;
			dashboardPhaseDetail = "Ready to check the network";
		}
	}

	private void openKnownServer(String path) {
		File candidate = new File(path);
		if(!candidate.isDirectory() || !ForgeUtils.hasServerStartupCommand(candidate.toPath())) {
			showError("Invalid server folder", "The selected folder does not contain a supported Forge or Fabric server.");
			return;
		}
		if(serverOpenedDirectory != null
				&& !candidate.getAbsoluteFile().equals(serverOpenedDirectory.getAbsoluteFile())
				&& (serverIsOn || dashboardPhase.isBusy())) {
			showError("Server switch unavailable", "Wait for the current server operation to finish before opening another server.");
			return;
		}
		serverOpenedDirectory = candidate;
		lastAutomaticBackupAttempt = null;
		actualServerPort = ForgeUtils.getServerPort(candidate.toPath());
		playerPresence.reset(ForgeUtils.getMaxPlayers(candidate.toPath()));
		dashboardPhase = Phase.OFFLINE;
		dashboardPhaseDetail = "Ready to check the network";
		discoveredHost = "—";
		syncState = "NOT CONFIGURED";
		lastSync = "—";
		dashboardError = "";
		rememberRecentServer(candidate);
		openServerOptions(contentPane);
		showDashboardPage(MinecraftDashboard.Page.OVERVIEW);
		appendDashboardActivity(LoaderKind.detect(candidate.toPath()).displayName() + " server opened: " + candidate.getName());
	}

	private void rememberRecentServer(File serverDirectory) {
		Path recentServersPath = Path.of("data/recentServers.properties");
		Properties properties = new Properties();
		try {
			Files.createDirectories(recentServersPath.getParent());
			if(Files.exists(recentServersPath)) {
				try(FileInputStream input = new FileInputStream(recentServersPath.toFile())) {
					properties.load(input);
				}
			}
			String normalized = serverDirectory.getAbsolutePath().replace('\\', '/');
			List<String> paths = new ArrayList<>();
			paths.add(normalized);
			for(String existing : properties.getProperty("recentServers", "").split("\\|")) {
				if(!existing.isBlank() && !existing.equals(normalized) && paths.size() < 12) paths.add(existing);
			}
			properties.setProperty("recentServers", String.join("|", paths));
			try(FileOutputStream output = new FileOutputStream(recentServersPath.toFile())) {
				properties.store(output, "Updated recent servers");
			}
		} catch(IOException e) {
			showError("Recent servers", "The recent server list could not be updated.");
		}
	}

	private void toggleServerFromDashboard() {
		if(serverOpenedDirectory == null) {
			showError("No server selected", "Open or create a Forge server before starting it.");
			showDashboardPage(MinecraftDashboard.Page.SERVERS);
			return;
		}
		if(serverIsOn) turnOffServer();
		else startServerFromDashboard();
	}

	private void startServerFromDashboard() {
		setDashboardPhase(Phase.DISCOVERING, "Checking whether another peer is already hosting");
		appendDashboardActivity("Checking the P2P network before start");
		new Thread(() -> {
			String networkDiscoveryResult = NetworkDiscoverClient.surroundDiscoverIOException(networkName, actualServerPort, 3000);
			if(!"NotFound".equals(networkDiscoveryResult)) {
				discoveredHost = networkDiscoveryResult;
				setDashboardPhase(Phase.REMOTE_HOST, "Another peer is already hosting this world");
				SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame,
						"Another peer is already hosting this server at " + networkDiscoveryResult + ".",
						"Remote host active", JOptionPane.INFORMATION_MESSAGE));
				return;
			}

				try {
					activeHostLockRepo = null;
					if(isGitHubSelected()) {
						if(!TokenStore.sessionIsOpened()) {
							syncState = "AUTH REQUIRED";
							setDashboardFailure("Sign into GitHub before starting this protected world.");
							return;
						}
						// El lock de GitHub arbitra el hosting por internet; el discovery UDP de
						// arriba solo ve peers dentro de la misma LAN virtual
						if(GitUtils.hasRemoteOrigin(serverOpenedDirectory.toPath())
								&& !acquireHostLockForStart(GitUtils.remoteRepoFullName(serverOpenedDirectory.toPath()))) {
							return;
						}
						setDashboardPhase(Phase.SYNCING, "Confirming the automatic private GitHub backup");
						syncState = GitUtils.hasRemoteOrigin(serverOpenedDirectory.toPath()) ? "PUSHING" : "INITIALIZING";
						GitUtils.PrivateBackupSetupResult setup = GitUtils.configurePrivateBackup(serverOpenedDirectory.toPath(), getServerName());
						if(!setup.success()) {
							syncState = "FAILED";
							setDashboardFailure("Private GitHub backup failed: " + setup.message());
							return;
						}
						syncState = "UP TO DATE";
						lastSync = setup.alreadyLinked() ? "PUSH CONFIRMED" : "INITIAL PUSH";
						appendDashboardActivity(setup.message());
						// Server recién vinculado: el repo no existía al comprobar el lock arriba
						if(activeHostLockRepo == null
								&& !acquireHostLockForStart(GitUtils.remoteRepoFullName(serverOpenedDirectory.toPath()))) {
							return;
						}
					}
					if(isGitHubSelected() && GitUtils.repoExistInPath(serverOpenedDirectory.toPath())) {
					setDashboardPhase(Phase.SYNCING, "Pulling the latest confirmed world from GitHub");
					syncState = "PULLING";
					refreshDashboardState();
					if(!TokenStore.sessionIsOpened() || !GitUtils.hasRemoteOrigin(serverOpenedDirectory.toPath()) || !GitUtils.pull(serverOpenedDirectory.toPath())) {
						syncState = "FAILED";
						setDashboardFailure("GitHub synchronization failed. The server was not started to avoid using an outdated world.");
						return;
					}
					syncState = "UP TO DATE";
					lastSync = "PULL CONFIRMED";
					appendDashboardActivity("Latest GitHub world pulled successfully");
				}

				setDashboardPhase(Phase.STARTING, "Launching the Forge startup script");
				playerPresence.reset(ForgeUtils.getMaxPlayers(serverOpenedDirectory.toPath()));
				serverProcess = ForgeUtils.executeMinecraftServer(serverOpenedDirectory.toPath());
				if(serverProcess == null) throw new IOException("Minecraft startup command failed");

				SwingUtilities.invokeLater(() -> {
					serverIsOn = true;
					discoveredHost = "LOCAL PROCESS";
					consoleArea = dashboard.consoleArea();
					if(!consoleArea.getText().isBlank()) consoleArea.append("\n");
					consoleArea.append("[p2pmss] Starting " + getServerName() + "…\n");
					serverWriter = ForgeUtils.configureServerWriter(serverProcess, serverWriter);
					consoleThread = ForgeUtils.getServerOutputs(serverProcess, consoleArea, this::handleServerOutputLine);
					dashboard.markServerStarted();
					appendDashboardActivity("Forge process started; waiting for server readiness");
					setDashboardPhase(Phase.STARTING, "Forge is loading the world; waiting for the Done signal");
				});
			} catch(Exception e) {
				e.printStackTrace();
				setDashboardFailure("The Minecraft server could not be started. Check Java and the startup script.");
			}
		}, "p2pmss-dashboard-start").start();
	}

	private void refreshNetworkAsync() {
		if(serverOpenedDirectory == null) {
			showDashboardPage(MinecraftDashboard.Page.SERVERS);
			return;
		}
		if(!serverIsOn) setDashboardPhase(Phase.DISCOVERING, "Scanning the P2P network for an active host");
		new Thread(this::checkServerStatus, "p2pmss-network-scan").start();
	}

	private void synchronizeNow() {
		if(serverOpenedDirectory == null) {
			showError("No server selected", "Open a Forge server before pulling its world.");
			showDashboardPage(MinecraftDashboard.Page.SERVERS);
			return;
		}
		if(serverIsOn || dashboardPhase == Phase.REMOTE_HOST || dashboardPhase.isBusy()) {
			showError("Pull unavailable", "The world can only be pulled while this server is offline and no peer is hosting it.");
			return;
		}
		if(!TokenStore.sessionIsOpened()) {
			showError("GitHub account required", "Sign into GitHub before pulling this world.");
			return;
		}
		if(!GitUtils.repoExistInPath(serverOpenedDirectory.toPath()) || !GitUtils.hasRemoteOrigin(serverOpenedDirectory.toPath())) {
			showError("Repository not linked", "Create or clone the private GitHub repository first.");
			return;
		}
		Path selectedServer = serverOpenedDirectory.toPath();
		selectGitHubProvider();
		setDashboardPhase(Phase.SYNCING, "Pulling the latest confirmed world from GitHub");
		syncState = "PULLING";
		appendDashboardActivity("Manual world pull requested");
		new Thread(() -> {
			boolean success = GitUtils.pull(selectedServer);
			if(success) {
				syncState = "UP TO DATE";
				lastSync = "JUST NOW";
				appendDashboardActivity("Latest GitHub world pulled successfully");
				setDashboardPhase(Phase.OFFLINE, "World is current and safe to start");
			} else {
				syncState = "FAILED";
				setDashboardFailure("GitHub synchronization failed. Local changes were preserved.");
			}
		}, "p2pmss-manual-sync").start();
	}

	private void importWorldFromDashboard() {
		if(serverOpenedDirectory == null) {
			showError("No server selected", "Open or create the Forge server that will receive the imported world first.");
			showDashboardPage(MinecraftDashboard.Page.SERVERS);
			return;
		}
		if(serverIsOn || dashboardPhase == Phase.REMOTE_HOST || dashboardPhase.isBusy()) {
			showError("Import unavailable", "Stop the local server and wait until no peer is hosting this world before importing.");
			return;
		}

		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Select a Minecraft world folder or ZIP");
		chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
		chooser.setAcceptAllFileFilterUsed(false);
		chooser.setFileFilter(new FileNameExtensionFilter("Minecraft world folder or ZIP (*.zip)", "zip"));
		if(chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;

		File selectedServer = serverOpenedDirectory;
		Path target;
		try {
			target = WorldImportService.configuredWorldDirectory(selectedServer.toPath());
		} catch(IOException invalidServer) {
			showError("Invalid server configuration", invalidServer.getMessage());
			return;
		}
		Path source = chooser.getSelectedFile().toPath();
		int confirmation = JOptionPane.showConfirmDialog(frame,
				"Import this world into:\n" + target + "\n\n"
				+ "If a world already exists, it will be moved intact to world-import-backups. Continue?",
				"Import Minecraft world", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if(confirmation != JOptionPane.YES_OPTION) return;

		setDashboardPhase(Phase.IMPORTING, "Validating and importing the selected world");
		appendDashboardActivity("Importing world from " + source.getFileName());
		new Thread(() -> {
			WorldImportService.ImportResult result = WorldImportService.importWorld(source, selectedServer.toPath());
			if(result.success()) {
				if(GitUtils.repoExistInPath(selectedServer.toPath())) syncState = "LOCAL CHANGES";
				appendDashboardActivity("World import completed; previous world preserved");
				setDashboardPhase(Phase.OFFLINE, "Imported world is ready for a safe test start");
				SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, result.message(),
						"World imported", JOptionPane.INFORMATION_MESSAGE));
			} else {
				setDashboardFailure("World import failed: " + result.message());
				SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, result.message(),
						"World import failed", JOptionPane.ERROR_MESSAGE));
			}
		}, "p2pmss-world-import").start();
	}

	private void openModsFolderFromDashboard() {
		if(serverOpenedDirectory == null) return;
		Path mods = serverOpenedDirectory.toPath().resolve("mods");
		if(!ZipUtils.existsDirectory(mods)) ZipUtils.createDirectory(mods);
		ForgeUtils.openModsFolder(serverOpenedDirectory.toPath());
	}

	private void openSelectedServerFolder() {
		if(serverOpenedDirectory == null) return;
		try {
			if(Desktop.isDesktopSupported()) Desktop.getDesktop().open(serverOpenedDirectory);
			else showError("Open folder", "Opening folders is not supported on this desktop.");
		} catch(IOException e) {
			showError("Open folder", "The selected server folder could not be opened.");
		}
	}

	private void saveServerSettingsFromDashboard(MinecraftDashboard.SettingsDraft settings) {
		if(serverOpenedDirectory == null) {
			dashboard.showSettingsResult(false, "Open a server before editing its settings.");
			return;
		}
		if(serverIsOn || dashboardPhase == Phase.REMOTE_HOST || dashboardPhase.isBusy()) {
			dashboard.showSettingsResult(false, "Stop every host before changing server settings.");
			return;
		}
		try {
			Path server = serverOpenedDirectory.toPath();
			ForgeUtils.setServerRAMAlloc(server, settings.ram());
			ForgeUtils.setServerPortChecked(server, settings.port());
			ForgeUtils.setMaxPlayers(server, settings.maxPlayers());
			ForgeUtils.setNetworkNameChecked(settings.networkName());
			networkName = settings.networkName();
			actualServerPort = settings.port();
			playerPresence.reset(settings.maxPlayers());
			applyPublicUrlToggle(settings.publicUrl());
			dashboard.showSettingsResult(true, "Saved locally · applies on the next start");
			appendDashboardActivity("Local server settings updated");
			refreshDashboardState();
			refreshNetworkAsync();
		} catch(Exception failure) {
			String message = "Ram exceeded".equalsIgnoreCase(failure.getMessage())
					? "The requested RAM exceeds this machine's installed memory."
					: failure.getMessage();
			dashboard.showSettingsResult(false, message == null ? "Settings could not be saved." : message);
		}
	}

	private void createRepositoryFromDashboard() {
		lastAutomaticBackupAttempt = null;
		configurePrivateBackupAsync(true);
	}

	/** Starts the one-time private repository setup without blocking Swing. */
	private boolean configurePrivateBackupAsync(boolean userRequested) {
		if(serverOpenedDirectory == null || !TokenStore.sessionIsOpened() || !isGitHubSelected()) return false;
		Path selectedServer = serverOpenedDirectory.toPath().toAbsolutePath().normalize();
		if(GitUtils.repoExistInPath(selectedServer) && GitUtils.hasRemoteOrigin(selectedServer)) return false;
		if(serverIsOn || dashboardPhase == Phase.REMOTE_HOST) {
			if(userRequested) showError("Backup unavailable", "Stop every host before creating the initial private backup.");
			return false;
		}
		if(dashboardPhase.isBusy() && !privateBackupSetupInProgress.get()) return false;
		if(!userRequested && selectedServer.equals(lastAutomaticBackupAttempt)) return false;
		if(!privateBackupSetupInProgress.compareAndSet(false, true)) return true;

		lastAutomaticBackupAttempt = selectedServer;
		selectGitHubProvider();
		syncState = "INITIALIZING";
		setDashboardPhase(Phase.SYNCING, "Creating the automatic private GitHub backup");
		appendDashboardActivity("Preparing automatic private GitHub backup");
		String serverName = selectedServer.getFileName().toString();
		new Thread(() -> {
			GitUtils.PrivateBackupSetupResult result = GitUtils.configurePrivateBackup(selectedServer, serverName);
			privateBackupSetupInProgress.set(false);
			if(result.success()) {
				syncState = "UP TO DATE";
				lastSync = result.alreadyLinked() ? "LINK VERIFIED" : "INITIAL PUSH";
				appendDashboardActivity(result.message());
				setDashboardPhase(Phase.OFFLINE, "World is protected by a private GitHub repository");
				SwingUtilities.invokeLater(() -> {
					if(addHostingUserBtn != null) addHostingUserBtn.setVisible(true);
				});
				refreshNetworkAsync();
			} else {
				syncState = "FAILED";
				setDashboardFailure("Automatic private backup failed: " + result.message());
				SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame,
						result.message() + "\n\nThe local world is safe. Use RETRY PRIVATE BACKUP after correcting the problem.",
						"Private GitHub backup needs attention", JOptionPane.ERROR_MESSAGE));
			}
		}, "p2pmss-private-backup-setup").start();
		return true;
	}

	private void selectGitHubProvider() {
		if("GitHub".equals(cloudProviderInUse)) return;
		cloudProviderInUse = "GitHub";
		ZipUtils.createOrModiFyPropertiesFile("cloudProviderInUse", cloudProviderInUse, CLOUD_PROVIDER_IN_USE_PATH);
	}

	private void setDashboardFailure(String message) {
		dashboardError = message;
		dashboardPhase = Phase.ERROR;
		dashboardPhaseDetail = message;
		refreshDashboardState();
		appendDashboardActivity("ERROR · " + message);
	}

	private void showError(String title, String message) {
		SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, message, title, JOptionPane.ERROR_MESSAGE));
	}

	private String activityLine(String message) {
		return java.time.LocalTime.now().withNano(0) + "  " + message;
	}

	/** Keeps activity-log mutations on Swing's event-dispatch thread. */
	public void appendDashboardActivity(String message) {
		if(dashboard == null) return;
		Runnable update = () -> dashboard.appendActivity(activityLine(message));
		if(SwingUtilities.isEventDispatchThread()) update.run();
		else SwingUtilities.invokeLater(update);
	}

	private void configurePlayerPolling() {
		playerRefreshTimer = new javax.swing.Timer(10_000, event -> {
			if(serverIsOn && dashboardPhase == Phase.ONLINE) requestPlayerList();
			else if(dashboardPhase == Phase.REMOTE_HOST) {
				new Thread(this::checkServerStatus, "p2pmss-remote-roster-refresh").start();
			}
		});
		playerRefreshTimer.setInitialDelay(2_000);
		playerRefreshTimer.start();
	}

	private void handleServerOutputLine(String line) {
		if(playerPresence.acceptLine(line)) refreshDashboardState();
		if(line != null && line.contains("Done")) requestPlayerList();
	}

	private void requestPlayerList() {
		if(serverIsOn && serverProcess != null && serverWriter != null) {
			ForgeUtils.sendCommand("list", serverProcess, serverWriter);
		}
	}

	/** Compact, backwards-compatible payload returned to peers during discovery. */
	public String playerDiscoveryPayload() {
		PlayerPresenceTracker.Snapshot presence = playerPresence.snapshot();
		String players = presence.players().stream().limit(80).collect(java.util.stream.Collectors.joining(","));
		return ";ONLINE=" + presence.onlineCount() + ";MAX=" + presence.maxPlayers() + ";PLAYERS=" + players;
	}

	private void saveAndClose() {
		if(privateBackupSetupInProgress.get()) {
			JOptionPane.showMessageDialog(frame,
					"The initial private backup is still running. Wait for GitHub confirmation before closing.",
					"Backup in progress", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		if(!closeInProgress.compareAndSet(false, true)) return;
		frame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		Process processToStop = serverIsOn ? serverProcess : null;
		if(processToStop != null) {
			setDashboardPhase(Phase.STOPPING, "Waiting for Forge to save before closing the application");
			appendDashboardActivity("Application close requested; saving the active world");
			GitUtils.serverAutoSaveIsActive = false;
			ForgeUtils.sendCommand("/stop", serverProcess, serverWriter);
		}

		new Thread(() -> {
			try {
				if(processToStop != null) {
					processToStop.waitFor();
					serverIsOn = false;
				}
			} catch(InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				cancelCloseAfterBackupFailure("The Forge stop operation was interrupted.");
				return;
			}

			GitUtils.PrivateBackupSetupResult backup = null;
			if(serverOpenedDirectory != null && isGitHubSelected()) {
				setDashboardPhase(Phase.SAVING, "Creating verified GitHub backup batches before exit");
				if(TokenStore.sessionIsOpened()) {
					backup = GitUtils.configurePrivateBackup(serverOpenedDirectory.toPath(), getServerName());
				} else {
					backup = new GitUtils.PrivateBackupSetupResult(false, false, false,
							"The GitHub session is not available. Sign in again to protect this world.");
				}
			}

			if(backup != null && !backup.success()) {
				String failureMessage = backup.message();
				boolean exitAnyway = confirmExitWithoutBackup(failureMessage);
				if(!exitAnyway) {
					cancelCloseAfterBackupFailure(failureMessage);
					return;
				}
			} else if(backup != null) {
				syncState = "UP TO DATE";
				lastSync = "PUSH CONFIRMED";
				appendDashboardActivity(backup.message());
			}

			SwingUtilities.invokeLater(() -> {
				frame.dispose();
				System.exit(0);
			});
		}, "p2pmss-save-and-close").start();
	}

	private boolean confirmExitWithoutBackup(String failureMessage) {
		final int[] selection = {0};
		try {
			SwingUtilities.invokeAndWait(() -> selection[0] = JOptionPane.showOptionDialog(frame,
					"The local world was saved, but the private GitHub backup was not confirmed.\n\n"
							+ failureMessage + "\n\nKeep the app open to retry?",
					"Backup needs attention",
					JOptionPane.DEFAULT_OPTION,
					JOptionPane.ERROR_MESSAGE,
					null,
					new Object[]{"KEEP APP OPEN", "EXIT ANYWAY"},
					"KEEP APP OPEN"));
		} catch(Exception dialogFailure) {
			return false;
		}
		return selection[0] == 1;
	}

	private void cancelCloseAfterBackupFailure(String message) {
		closeInProgress.set(false);
		syncState = "FAILED";
		lastSync = "PUSH FAILED";
		serverProcess = null;
		serverWriter = null;
		serverIsOn = false;
		if(consoleThread != null) consoleThread.interrupt();
		if(responder != null) responder.closeListeningSocket();
		playerPresence.reset(serverOpenedDirectory == null ? 20 : ForgeUtils.getMaxPlayers(serverOpenedDirectory.toPath()));
		discoveredHost = "—";
		setDashboardFailure("The world is safe locally, but GitHub needs attention: " + message);
		SwingUtilities.invokeLater(() -> {
			frame.setCursor(Cursor.getDefaultCursor());
			if(dashboard != null) dashboard.markServerStopped();
		});
	}
	
	private void serverConfigsFrame(JPanel fatherFrame) {
		JDialog configDialog = new JDialog(frame, "Server Configurations");
		configDialog.getContentPane().setLayout(new BorderLayout());
		configDialog.setResizable(false);
		int configDialogWidht = 340;
		int configDialogHeight = 420;
		configDialog.setSize(configDialogWidht, configDialogHeight);
		configDialog.setLocationRelativeTo(fatherFrame);
		configDialog.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

		//Array for the autoSaveSelect
		String[] autoSaveIntervalsTexts = { "Off", "5 mins", "10 mins", "30 mins", "1 h", "2 h" };
		int[] autoSaveIntervalsInts = { 0, 5 * 60, 10 * 60, 30 * 60, 1 * 60 * 60, 2 * 60 * 60 };

		JPanel contentPane = new JPanel(new GridLayout(10, 1));
		JPanel buttonsPane = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JScrollPane scroll = new JScrollPane(contentPane);
		scroll.setPreferredSize(new Dimension(340, 340));
		scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		JLabel networkIDLabel = new JLabel("Nombre de la red");
		JTextField networkIDInput = new JTextField();
		JLabel serverPortLabel = new JLabel("Server port");
		JTextField serverPortInput = new JTextField();
		JLabel serverRamAllocLabel = new JLabel("RAM (GB or MB)");
		JTextField serverRamAllocInput = new JTextField();
		
		JLabel autoSaveIntervalLabel = new JLabel("Intervalo del autoguardado");
		JComboBox<String> autoSaveIntervalSelect = new JComboBox<String>(autoSaveIntervalsTexts);

		int savedIntervalIndex = Arrays.binarySearch(autoSaveIntervalsInts, GitUtils.getSavedAutoSaveInteval());
		autoSaveIntervalSelect.setSelectedIndex(savedIntervalIndex >= 0 ? savedIntervalIndex : 2 /* 10 mins */);
		JButton saveBtn = new JButton("Save");
		
		contentPane.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
		scroll.setBorder(null);
		networkIDInput.setText(ForgeUtils.getNetworkName());
		serverPortInput.setText(ForgeUtils.getServerPort(serverOpenedDirectory.toPath())+"");
		serverRamAllocInput.setText(ForgeUtils.getServerRAMAlloc(serverOpenedDirectory.toPath()).replaceAll("[-Xmx|G|M]",""));
		
		contentPane.add(networkIDLabel);
		contentPane.add(networkIDInput);
		contentPane.add(serverPortLabel);
		contentPane.add(serverPortInput);
		contentPane.add(serverRamAllocLabel);
		contentPane.add(serverRamAllocInput);
		contentPane.add(autoSaveIntervalLabel);
		contentPane.add(autoSaveIntervalSelect);
		buttonsPane.add(saveBtn);
		configDialog.getContentPane().add(scroll, BorderLayout.NORTH);
		configDialog.getContentPane().add(buttonsPane, BorderLayout.SOUTH);
		
		configDialog.setVisible(true);
		
		saveBtn.addActionListener(save -> {
			if(!(ForgeUtils.getNetworkName().equals(networkIDInput.getText()))){
				ForgeUtils.setNetworkName(networkIDInput.getText());
				networkName = networkIDInput.getText();
			}
			if(!((ForgeUtils.getServerPort(serverOpenedDirectory.toPath())+"").equals(serverPortInput.getText()))){
				ForgeUtils.setServerPort(serverOpenedDirectory.toPath(), Integer.parseInt(serverPortInput.getText()));
				actualServerPort = Integer.parseInt(serverPortInput.getText());
			}
			if(!((ForgeUtils.getServerRAMAlloc(serverOpenedDirectory.toPath())+"").replaceAll("[-Xmx|G|M]", "").equals(serverRamAllocInput.getText().replaceAll("[-Xmx|G|M]", "")))) {
				Pattern pattern = Pattern.compile("^[0-9]*$");
				Matcher matcher = pattern.matcher(serverRamAllocInput.getText().replaceAll("[-Xmx|G|M]", ""));
				if(matcher.find()) {
					try {
						ForgeUtils.setServerRAMAlloc(serverOpenedDirectory.toPath(), Integer.parseInt(serverRamAllocInput.getText().replaceAll("[-Xmx|G|M]", "")));
					}
					catch(Exception ramExpection) {
						if(ramExpection.getMessage().equalsIgnoreCase("Ram exceeded"))
							serverRamAllocLabel.setText("<html>RAM (GB or MB) <span style='color:#fa4545'>Memoria libre insuficiente</span></html>");
						else ramExpection.printStackTrace();
						return;
					}
				}
			}
			int selectedAutosaveInteval = autoSaveIntervalsInts[autoSaveIntervalSelect.getSelectedIndex()];
			if(GitUtils.getSavedAutoSaveInteval() != selectedAutosaveInteval) {
				GitUtils.setAutoSaveInterval(selectedAutosaveInteval);
			}
			configDialog.dispose();
			actualServerPort = ForgeUtils.getServerPort(serverOpenedDirectory.toPath());
			networkName = ForgeUtils.getNetworkName();
			refreshDashboardState();
			refreshNetworkAsync();
		});
	}
	
	public static void checkIfExistsDataFolder() {
		try {
			Files.createDirectories(Paths.get("data"));
			// Probar escritura real: existir no garantiza poder escribir (zip sin
			// extraer, Program Files, consola en carpeta protegida...)
			Path probe = Paths.get("data", ".write-probe");
			Files.writeString(probe, "ok");
			Files.deleteIfExists(probe);
		}
		catch(IOException cannotWrite) {
			JOptionPane.showMessageDialog(null,
					"P2PMSS cannot write next to where it is running:\n"
					+ Paths.get("").toAbsolutePath() + "\n\n"
					+ "Move the app to a normal folder you own (for example Desktop or\n"
					+ "C:\\Users\\you\\p2pmss) — extract it fully if it came in a ZIP —\n"
					+ "and launch it from there. The app stores its session and settings\n"
					+ "in a 'data' folder created beside itself.",
					"Folder not writable", JOptionPane.ERROR_MESSAGE);
		}
	}
	
	private void recentServerListGenerator() {
		recentServersMenu.removeAll();
		File file = new File("data/recentServers.properties");
		Properties props = new Properties();
		try(FileInputStream in = new FileInputStream(file)) {
			props.load(in);
			if(props.containsKey("recentServers")) {
				for(String serverDirectory : props.getProperty("recentServers").split("\\|")) {
					JMenuItem item = new JMenuItem(serverDirectory);
						item.addActionListener(itm -> openKnownServer(serverDirectory));
					recentServersMenu.add(item);
				}
			}
			else { 
				recentServersMenu.add(new JMenuItem("No recent files opened..."));
				return;
			}
		} catch (IOException e) {
			e.printStackTrace();
			JOptionPane.showMessageDialog(null, "File not found or inaccessible", "Error", JOptionPane.ERROR_MESSAGE);
		}
		recentServersMenu.revalidate();
		recentServersMenu.repaint();
	}
	
	public static String getServerName() {
		if(serverOpenedDirectory == null) return "No server";
		if(!(serverOpenedDirectory.toString().contains("\\")))
			return serverOpenedDirectory.toString().substring(serverOpenedDirectory.toString().lastIndexOf("/") + 1, serverOpenedDirectory.toString().length());
	   return serverOpenedDirectory.toString().substring(serverOpenedDirectory.toString().lastIndexOf("\\") + 1, serverOpenedDirectory.toString().length());
	}

	public static boolean isGitHubSelected() {
		return "GitHub".equals(cloudProviderInUse);
	}
	
	private void radioBtnListener(JMenu cloudMenu, JMenu saveBackupsToCloudMenu, JRadioButtonMenuItem radioButton) {
	    SwingUtilities.invokeLater(() -> {
	    	cloudProviderInUse = radioButton.getText().replaceAll(" ", "");
	    	System.out.println(cloudProviderInUse);
	    	if(!cloudProviderInUse.equals("GitHub")) {
	    		if(cloudProvider == null || !cloudProvider.isSessionOpened()) 
	    			cloudInUseReminderMenuText.setText(cloudInUseReminderText[0].formatted(cloudInUseReminderText[3].formatted(cloudProviderInUse)));
	    		else
	    			cloudInUseReminderMenuText.setText(cloudInUseReminderText[0].formatted(cloudInUseReminderText[1] + cloudProviderInUse));
	    	}
	    	else {
	    		if(!TokenStore.sessionIsOpened())
	    			cloudInUseReminderMenuText.setText(cloudInUseReminderText[0].formatted(cloudInUseReminderText[3].formatted(cloudProviderInUse)));
	    		else
	    			cloudInUseReminderMenuText.setText(cloudInUseReminderText[0].formatted(cloudInUseReminderText[1] + cloudProviderInUse));
	    	}
			ZipUtils.createOrModiFyPropertiesFile("cloudProviderInUse", cloudProviderInUse, CLOUD_PROVIDER_IN_USE_PATH);
			cloudMenu.doClick();
			saveBackupsToCloudMenu.doClick();
			openServerOptions(contentPane);
	    });
	}
	
	public void turnOffServer() {
		if(!serverIsOn || serverProcess == null) return;
		frame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		setDashboardPhase(Phase.STOPPING, "Waiting for Forge to save and close the world");
		appendDashboardActivity("Stop requested; waiting for the Forge process");
		stopHostLockHeartbeat();
		stopPlayitTunnel();
		GitUtils.stopAutoSaveAndWait();
		ForgeUtils.sendCommand("/stop", serverProcess, serverWriter);
		new Thread(() ->{
			try {
				serverProcess.waitFor();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				setDashboardFailure("The server stop operation was interrupted.");
				return;
			}
			setDashboardPhase(Phase.SAVING, "Pushing the stopped world to the selected backup provider");
			boolean gitBackupSucceeded = true;
			String gitBackupMessage = "World saved locally.";
			if(isGitHubSelected()) {
				GitUtils.PrivateBackupSetupResult backup = TokenStore.sessionIsOpened()
						? GitUtils.configurePrivateBackup(serverOpenedDirectory.toPath(), getServerName())
						: new GitUtils.PrivateBackupSetupResult(false, false, false,
								"The GitHub session is invalid. Sign in again and retry the backup.");
				gitBackupSucceeded = backup.success();
				gitBackupMessage = backup.message();
				syncState = gitBackupSucceeded ? "UP TO DATE" : "FAILED";
				lastSync = gitBackupSucceeded ? "JUST NOW" : "PUSH FAILED";
			}
			if(!gitBackupSucceeded) {
				String failure = gitBackupMessage;
				SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
						"The server stopped safely, but GitHub did not confirm every backup batch.\n\n" + failure
								+ "\n\nThe local changes are preserved; use RETRY PRIVATE BACKUP.",
						"Git backup error", JOptionPane.ERROR_MESSAGE));
			}
			if(isGitHubSelected() && TokenStore.sessionIsOpened() && serverOpenedDirectory != null) {
				String lockRepo = GitUtils.remoteRepoFullName(serverOpenedDirectory.toPath());
				if(lockRepo != null) {
					// El commit final de guardado invalida el healthcheck: se libera al instante,
					// sin esperar la caducidad del lease
					if(HostLock.release(lockRepo)) appendDashboardActivity("GitHub host lock released; the world is free to host");
					else appendDashboardActivity("The host lock could not be released; it will expire on its own within "
							+ (HostLock.DEFAULT_LEASE_SECONDS / 60) + " minutes");
				}
			}
			activeHostLockRepo = null;
			if(cloudProvider != null && cloudProvider.getProviderName().equals(cloudProviderInUse) && cloudProvider.isSessionOpened()) {
				if(cloudProvider.hasRemoteServerFolder()) {
					ZipUtils.createZip(serverOpenedDirectory.toPath(), ZipUtils.BACKUPS_ZIPS_FOLDER);
					cloudProvider.uploadServerBackup(ZipUtils.BACKUPS_ZIPS_FOLDER);
				}
			}
			
			if(consoleThread != null) consoleThread.interrupt();
			serverProcess = null;
			boolean backupSucceeded = gitBackupSucceeded;
			String backupMessage = gitBackupMessage;
			
			SwingUtilities.invokeLater(() -> {
				serverWriter = null;
				serverIsOn = false;
				playerPresence.reset(serverOpenedDirectory == null ? 20 : ForgeUtils.getMaxPlayers(serverOpenedDirectory.toPath()));
				if(responder != null) responder.closeListeningSocket();
				dashboard.markServerStopped();
				discoveredHost = "—";
				if(backupSucceeded) {
					appendDashboardActivity(backupMessage);
					setDashboardPhase(Phase.OFFLINE, "World saved; no active host discovered");
				} else {
					setDashboardFailure("The server stopped safely, but the GitHub backup needs attention.");
				}
				if(backupSucceeded) refreshNetworkAsync();
			});
			SwingUtilities.invokeLater(() -> frame.setCursor(Cursor.getDefaultCursor()));

		}, "p2pmss-dashboard-stop").start();
	}

	private boolean acquireHostLockForStart(String repoFullName) {
		if(repoFullName == null) return true;
		setDashboardPhase(Phase.DISCOVERING, "Arbitrating the GitHub host lock");
		HostLock.AcquireResult lock = HostLock.acquire(repoFullName);
		if(lock.acquired()) {
			activeHostLockRepo = repoFullName;
			appendDashboardActivity(lock.message());
			return true;
		}
		if(lock.blockedByPeer()) {
			setDashboardPhase(Phase.REMOTE_HOST, lock.message());
			SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, lock.message(),
					"Another peer is hosting", JOptionPane.INFORMATION_MESSAGE));
		} else {
			setDashboardFailure(lock.message());
		}
		return false;
	}

	/** Started from the console "Done" hook: host lock heartbeat plus the live world autosave. */
	public void startHostServices() {
		String repoFullName = activeHostLockRepo;
		if(repoFullName != null) {
			stopHostLockHeartbeat();
			hostLockHeartbeatTimer = new java.util.Timer("p2pmss-host-lock-heartbeat", true);
			hostLockHeartbeatTimer.scheduleAtFixedRate(new java.util.TimerTask() {
				@Override public void run() {
					if(!serverIsOn || serverProcess == null || !serverProcess.isAlive()) {
						// Forge murió solo: sin server no se sostiene el lease; caducará y otro podrá hostear
						stopHostLockHeartbeat();
						return;
					}
					if(HostLock.heartbeat(repoFullName)) appendDashboardActivity("Host lock heartbeat confirmed on GitHub");
					else appendDashboardActivity("Host lock heartbeat failed; the lease may expire in "
							+ (HostLock.DEFAULT_LEASE_SECONDS / 60) + " minutes");
				}
			}, HostLock.HEARTBEAT_SECONDS * 1000L, HostLock.HEARTBEAT_SECONDS * 1000L);
		}
		if(GitUtils.autoSaveSecondsInterval > 0 && isGitHubSelected() && TokenStore.sessionIsOpened()) {
			GitUtils.activeAutoSave();
		}
		startPlayitTunnelIfConfigured();
	}

	private static void stopHostLockHeartbeat() {
		if(hostLockHeartbeatTimer != null) {
			hostLockHeartbeatTimer.cancel();
			hostLockHeartbeatTimer = null;
		}
	}

	/** Brings the optional public playit.gg tunnel up when this world has it enabled. */
	private void startPlayitTunnelIfConfigured() {
		if(serverOpenedDirectory == null) return;
		PlayitAgentFile agent = PlayitAgentFile.load(serverOpenedDirectory.toPath());
		if(agent == null || !agent.readyToStart()) return;
		stopPlayitTunnel();
		activePlayitTunnel = new PlayitTunnel(agent.secret_key, actualServerPort, this::appendDashboardActivity);
		activePlayitTunnel.start();
		appendDashboardActivity("Starting the public playit.gg tunnel…");
	}

	private static void stopPlayitTunnel() {
		if(activePlayitTunnel != null) {
			activePlayitTunnel.stop();
			activePlayitTunnel = null;
		}
	}

	/** Applies the Settings-page toggle against the stored per-world playit state. */
	private void applyPublicUrlToggle(boolean wanted) {
		PlayitAgentFile storedAgent = PlayitAgentFile.load(serverOpenedDirectory.toPath());
		boolean enabledNow = storedAgent != null && storedAgent.enabled;
		if(wanted) {
			// Re-guardar con el toggle ya activo tambien cura un setup a medias
			// (sin secret valido o sin direccion todavia)
			if(!enabledNow || storedAgent.secret_key == null || storedAgent.tunnel_address == null) {
				enablePublicUrl(storedAgent);
			}
			return;
		}
		if(!enabledNow) return;
		storedAgent.enabled = false;
		try {
			storedAgent.save(serverOpenedDirectory.toPath());
			appendDashboardActivity("Public URL disabled for this world");
		} catch(IOException failure) {
			appendDashboardActivity("Public URL could not be disabled: " + failure.getMessage());
		}
		stopPlayitTunnel();
	}

	/**
	 * Enables the public URL for this world on a background thread: verifies any
	 * stored secret, claims a new agent in the browser when needed, resolves the
	 * fixed address and persists everything in the shared repo file.
	 */
	private void enablePublicUrl(PlayitAgentFile existingAgent) {
		Path serverDirectory = serverOpenedDirectory.toPath();
		new Thread(() -> {
			PlayitAgentFile agent = existingAgent != null ? existingAgent : new PlayitAgentFile();
			agent.enabled = true;

			if(agent.secret_key != null && !PlayitTunnel.secretWorks(agent.secret_key)) {
				appendDashboardActivity("The stored playit key was rejected; requesting a new authorization");
				agent.secret_key = null;
				agent.tunnel_address = null;
			}

			if(agent.secret_key == null) {
				String claimCode = PlayitTunnel.newClaimCode();
				String claimUrl = PlayitTunnel.claimUrl(claimCode);
				SwingUtilities.invokeLater(() -> ForgeUtils.openURL(claimUrl));
				appendDashboardActivity("Authorize the playit.gg tunnel in the opened browser tab (guest works): " + claimUrl);
				PlayitTunnel.ClaimOutcome outcome = PlayitTunnel.claimAgent(claimCode, 10 * 60);
				if(!outcome.ok()) {
					appendDashboardActivity("playit.gg authorization failed: " + outcome.error());
					SwingUtilities.invokeLater(MainFrame.this::refreshDashboardState);
					return;
				}
				agent.secret_key = outcome.secretKey();
			}

			try {
				agent.tunnel_address = PlayitTunnel.ensureTunnel(agent.secret_key);
			} catch(IOException tunnelFailure) {
				appendDashboardActivity("playit authorized, but the tunnel is not ready yet: " + tunnelFailure.getMessage());
			}
			try {
				agent.save(serverDirectory);
			} catch(IOException failure) {
				appendDashboardActivity("Public URL could not be saved: " + failure.getMessage());
				return;
			}
			appendDashboardActivity(agent.tunnel_address != null
					? "Public URL ready for every host of this world: " + agent.tunnel_address
					: "playit authorized; the address will appear when the tunnel starts");
			if(serverIsOn) startPlayitTunnelIfConfigured();
			SwingUtilities.invokeLater(MainFrame.this::refreshDashboardState);
		}, "p2pmss-playit-claim").start();
	}

}
