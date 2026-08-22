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

/**
 * Ventana principal y controlador de la aplicacion: es quien conoce a la vez el
 * servidor de Minecraft, el proveedor de nube y el dashboard, y quien traduce
 * entre ellos.
 *
 * Flujo: el dashboard nunca actua por su cuenta, emite intenciones por
 * {@link MinecraftDashboard.Actions} y esta clase las ejecuta; al reves,
 * {@link #refreshDashboardState()} recompone un State completo y lo empuja a la
 * vista. Todo el trabajo lento (red, git, arranque de Forge) va a hilos con
 * nombre propio, y solo se vuelve al EDT para tocar Swing.
 *
 * Decision heredada: el estado del servidor vive en campos estaticos publicos
 * porque GitUtils, ZipUtils y CustomCommands los leen directamente. Cambiar eso
 * es una refactorizacion aparte, no de estilo.
 */
public final class MainFrame
{

	// ---- FASE 1 — Estado global y componentes de menu ------------------------

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
	public static final Path CLOUD_PROVIDER_IN_USE_PATH = app.AppPaths.dataFile( "cloudProviderInUse.properties" );
	public static File serverOpenedDirectory = null;
	public static BufferedWriter serverWriter = null;
	public static Process serverProcess = null;
	public static boolean serverIsOn = false;
	public static CloudStorageProvider cloudProvider = null;
	public static String cloudProviderInUse = "noCloudProvider";
	public static String[] cloudInUseReminderText;
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
	private final AtomicBoolean privateBackupSetupInProgress = new AtomicBoolean( false );
	private final AtomicBoolean closeInProgress = new AtomicBoolean( false );
	private volatile Path lastAutomaticBackupAttempt;
	private volatile String activeHostLockRepo = null;
	private javax.swing.Timer playerRefreshTimer;

	// ---- FASE 2 — Arranque y cierre de la aplicacion -------------------------

	/** Punto de entrada: tema del sistema y construccion de la ventana en el EDT. */
	public static void main( String[] args )
	{

		ThemeManager.setupSystemTheme();

		EventQueue.invokeLater( new Runnable()
		{
			public void run()
			{
				try
				{
					window = new MainFrame();
					window.frame.setVisible( true );
					window.checkForUpdatesAsync();
				}
				catch( Exception startupFailure )
				{
					// Si la ventana no llega a construirse no hay UI donde avisar: la traza
					// en consola es la unica pista que le queda a quien reporte el fallo
					app.Log.event( "UI", "La ventana principal no pudo construirse", startupFailure );
				}
			}
		} );
	}

	public MainFrame()
	{
		initialize();
		configurePlayerPolling();
		installShutdownCleanup();
		startWorldStatusScanner();
	}

	// ---- Escaner de mundos suscritos ---------------------------------------

	private app.WorldStatusScanner worldStatusScanner;

	/**
	 * Arranca el escaner que vigila el candado de todos los mundos del tablero:
	 * los suscritos y los de los servers locales recientes. El listener guarda
	 * la foto; la interfaz la consume cuando repinta.
	 */
	private void startWorldStatusScanner()
	{
		worldStatusScanner = new app.WorldStatusScanner(
				this::watchedWorldRepos,
				jgit.HostLock::readStatus,
				// Cada foto nueva repinta las tarjetas de mundos; el skip-if-equal del
				// dashboard descarta los refrescos sin cambios reales
				status -> SwingUtilities.invokeLater( this::refreshDashboardState ) );
		worldStatusScanner.setTransitionListener( this::announceWorldTransition );
		// El progreso de clone/pull/push viaja del hilo de trabajo a la franja
		// de abajo a la derecha; Swing solo se toca desde el EDT
		app.TransferProgress.setListener( snapshot -> SwingUtilities.invokeLater( () ->
		{
			if( dashboard != null )
				dashboard.showTransferProgress( snapshot.title(), snapshot.detail(), snapshot.percent(), snapshot.active() );
		} ) );
		// El mapa avisa cuando pasa de dibujar a vigilar (y al reves): sin esto la
		// pantalla se quedaria en "construyendo" para siempre
		app.WorldMap.setStateListener( () -> SwingUtilities.invokeLater( this::refreshWorldMapState ) );
		worldStatusScanner.setEventsReader( jgit.WorldEvents::fetchNew );
		worldStatusScanner.setEventListener( this::onWorldEvent );
		worldStatusScanner.start();
	}

	/**
	 * Evento de otro peer llegado por el canal de GitHub. Los arranques/paradas
	 * de host ya se notifican via transiciones del candado: aqui solo van a la
	 * actividad; la notificacion de escritorio queda para el "quiero jugar".
	 */
	private void onWorldEvent( String repoFullName, jgit.WorldEvents.WorldEvent event )
	{
		String me = quietNickname();
		if( me != null && me.equalsIgnoreCase( event.nick() ) )
			return;
		if( System.currentTimeMillis() - event.atMillis() > 10 * 60 * 1000L )
			return;
		String world = repoFullName.contains( "/" ) ? repoFullName.substring( repoFullName.indexOf( '/' ) + 1 ) : repoFullName;
		switch( event.type() )
		{
			case "want_to_play" ->
			{
				String message = event.nick() + " wants to play " + world;
				app.Notifier.notifyWorldEvent( "Endershare", message );
				appendDashboardActivity( message );
			}
			case "host_started" -> appendDashboardActivity( event.nick() + " started hosting " + world );
			case "host_stopped" -> appendDashboardActivity( event.nick() + " stopped hosting " + world );
			default -> appendDashboardActivity( event.nick() + " · " + event.type() + " · " + world );
		}
	}

	/**
	 * Notificacion de escritorio cuando un mundo del tablero cambia de verdad:
	 * alguien empieza a hostear, lo deja libre o el host cambia de manos. Lo que
	 * hosteas tu no se anuncia: ya lo estas viendo.
	 */
	private void announceWorldTransition( app.WorldStatusScanner.Transition change )
	{
		app.WorldStatusScanner.WorldStatus was = change.previous();
		app.WorldStatusScanner.WorldStatus now = change.current();
		if( now.mine() || was.mine() )
			return;
		String world = now.repoFullName().contains( "/" )
				? now.repoFullName().substring( now.repoFullName().indexOf( '/' ) + 1 )
				: now.repoFullName();
		String message = null;
		if( !was.hosted() && now.hosted() )
		{
			String address = now.details() == null ? null : now.details().tunnelAddress();
			message = now.hostNickname() + " is hosting " + world
					+ (address != null && !address.isBlank() ? " — " + address : "");
		}
		else if( was.hosted() && !now.hosted() )
		{
			message = world + " is free to host";
		}
		else if( now.hosted() )
		{
			message = now.hostNickname() + " took over " + world;
		}
		if( message != null )
		{
			app.Notifier.notifyWorldEvent( "Endershare", message );
			appendDashboardActivity( message );
		}
	}

	/** Todos los repos que el tablero multi-server vigila: suscritos + servers locales recientes. */
	private java.util.List<String> watchedWorldRepos()
	{
		java.util.LinkedHashSet<String> repos = new java.util.LinkedHashSet<>( app.WorldSubscriptions.all( quietNickname() ) );
		for( MinecraftDashboard.ServerEntry entry : readRecentServers() )
		{
			String repo = repoFullNameForPath( entry.path() );
			if( repo != null )
				repos.add( repo );
		}
		// De paso se refresca cual es el mundo activo: corre en el hilo del
		// escaner en cada tick, asi el canal de eventos sigue al server abierto
		File opened = serverOpenedDirectory;
		worldStatusScanner.setActiveRepo( opened == null ? null : repoFullNameForPath( opened.getPath() ) );
		return new java.util.ArrayList<>( repos );
	}

	/** Nickname de la sesion abierta o null, sin dialogos: apto para hilos de fondo. */
	private static String quietNickname()
	{
		String result = null;
		try
		{
			result = TokenStore.getSavedUserData().get( "nickname" );
		}
		catch( Exception noSession )
		{
			// Sin sesion no hay mundos que vigilar: el escaner queda en vacio
		}
		return result;
	}

	/**
	 * Limpieza de ultimo recurso ante un kill, un cierre forzado o el apagado del
	 * sistema: sin esto el proceso hijo de Forge sigue vivo huerfano y el host lock
	 * de GitHub se queda cogido hasta que caduca el lease (incidente real).
	 *
	 * SIGKILL y un corte de corriente se saltan igualmente este gancho; para esos
	 * casos la unica red de seguridad es la caducidad del lease.
	 */
	private void installShutdownCleanup()
	{
		Runtime.getRuntime().addShutdownHook( new Thread( () ->
		{
			Process orphan = serverProcess;
			if( orphan != null && orphan.isAlive() )
			{
				// destroy() es TERM: el server de Minecraft guarda el mundo en su propio shutdown hook
				orphan.destroy();
				try
				{
					orphan.waitFor( 20, java.util.concurrent.TimeUnit.SECONDS );
				}
				catch( InterruptedException ignored )
				{
					Thread.currentThread().interrupt();
				}
			}
			String lockRepo = activeHostLockRepo;
			if( lockRepo != null )
			{
				try
				{
					HostLock.release( lockRepo );
				}
				catch( RuntimeException releaseFailure )
				{
					// Ya estamos apagando: si la red falla aqui no queda a quien avisar y el
					// lease caduca solo, asi que se deja constancia y se sigue cerrando
					app.Log.event( "SERVER_LIFECYCLE", "El host lock no pudo liberarse al cerrar; caducara solo", releaseFailure );
				}
			}
		}, "endershare-shutdown-cleanup" ) );
	}

	// ---- FASE 3 — Construccion de la ventana y los menus ---------------------

	/**
	 * Monta la ventana entera: menus, dashboard y el ultimo servidor abierto. La
	 * barra de menus se construye completa pero se deja oculta al final: el
	 * dashboard cubre ya todas sus acciones y se sigue disparando por doClick().
	 */
	private void initialize()
	{
		checkIfExistsDataFolder();
		networkName = ForgeUtils.getNetworkName();
		if( ZipUtils.existsDirectory( app.AppPaths.dataFile( "google_tokens/StoredCredential" ) ) )
		{
			cloudProvider = new GoogleDriveCloudProvider();
			cloudProvider.authenticate();
		}
		cloudProviderInUse = ZipUtils.getDataFromPropertiesFile( "cloudProviderInUse", CLOUD_PROVIDER_IN_USE_PATH );


		frame = new JFrame();
		int frameWidht = 1280;
		int frameHeight = 800;
		frame.setBounds( (Toolkit.getDefaultToolkit().getScreenSize().width / 2) - (frameWidht / 2),
				(Toolkit.getDefaultToolkit().getScreenSize().height / 2) - (frameHeight / 2), frameWidht, frameHeight );
		frame.setMinimumSize( new Dimension( 1100, 700 ) );
		frame.getContentPane().setBackground( view.dashboard.DashboardTheme.APP_BACKGROUND );
		frame.setDefaultCloseOperation( JFrame.DO_NOTHING_ON_CLOSE );


		List<Image> icons = List.of(
				new ImageIcon( MainFrame.class.getResource( "/icons/EndershareIcon-16.png" ) ).getImage(),
				new ImageIcon( MainFrame.class.getResource( "/icons/EndershareIcon-32.png" ) ).getImage(),
				new ImageIcon( MainFrame.class.getResource( "/icons/EndershareIcon-64.png" ) ).getImage() );

		frame.setIconImages( icons );

		frame.setTitle( "Endershare" );

		// DO_NOTHING_ON_CLOSE arriba + este listener: la X no puede cerrar la ventana
		// de golpe, tiene que pasar por el guardado y la liberacion del lock
		frame.addWindowListener( new WindowAdapter()
		{
			@Override
			public void windowClosing( WindowEvent closing )
			{
				saveAndClose();
			}
		} );

		JPanel panel = new JPanel();
		frame.getContentPane().add( panel, BorderLayout.NORTH );


		JMenuBar menuBar = new JMenuBar();
		menuBar.setBorder( null );
		menuBar.setBorderPainted( false );
		frame.setJMenuBar( menuBar );

		JMenu fileMenu = new JMenu( "File" );
		JMenu cloudMenu = new JMenu( "Cloud" );
		JMenu saveBackupsToCloudMenu = new JMenu( "Save backups to cloud..." );
		JMenu gitMenu = new JMenu( "GitHub" );
		gitMenu.setIcon( new ImageIcon( MainFrame.class.getResource( "/icons/github.png" ) ) );
		JMenu googleDriveMenu = new JMenu( "Google Drive" );
		googleDriveMenu.setIcon( new ImageIcon( MainFrame.class.getResource( "/icons/google-drive.png" ) ) );
		cloudInUseReminderText = new String[]{"<html><span style=' color: rgb(177, 177, 177);'>%s.</span></html>",
				"Currently saving backups in ", "No cloud provider configured yet",
				"%s choosen for saving backups, but you are not logged in"};
		String cloudStatus = cloudInUseReminderText[2];
		if( cloudProviderInUse != null )
		{
			if( isGitHubSelected() )
			{
				cloudStatus = TokenStore.sessionIsOpened()
						? cloudInUseReminderText[1] + cloudProviderInUse
						: cloudInUseReminderText[3].formatted( cloudProviderInUse );
			}
			else
			{
				cloudStatus = cloudProvider != null && cloudProvider.isSessionOpened()
						? cloudInUseReminderText[1] + cloudProviderInUse
						: cloudInUseReminderText[3].formatted( cloudProviderInUse );
			}
		}
		cloudInUseReminderMenuText = new JMenuItem( cloudInUseReminderText[0].formatted( cloudStatus ) );
		cloudInUseReminderMenuText.setEnabled( false );

		menuBar.add( fileMenu );
		menuBar.add( cloudMenu );
		cloudMenu.add( saveBackupsToCloudMenu );
		cloudMenu.add( gitMenu );
		cloudMenu.add( googleDriveMenu );
		cloudMenu.add( cloudInUseReminderMenuText );

		JRadioButtonMenuItem gitMenuItem = new JRadioButtonMenuItem( "GitHub" );
		gitMenuItem.setIcon( new ImageIcon( MainFrame.class.getResource( "/icons/github.png" ) ) );
		gitMenuItem.addActionListener( ghList ->
		{
			radioBtnListener( cloudMenu, saveBackupsToCloudMenu, gitMenuItem );
		} );

		JRadioButtonMenuItem googleDriveMenuItem = new JRadioButtonMenuItem( "Google Drive" );
		googleDriveMenuItem.setIcon( new ImageIcon( MainFrame.class.getResource( "/icons/google-drive.png" ) ) );
		googleDriveMenuItem.addActionListener( gglList ->
		{
			radioBtnListener( cloudMenu, saveBackupsToCloudMenu, googleDriveMenuItem );
		} );

		ButtonGroup group = new ButtonGroup();
		group.add( gitMenuItem );
		group.add( googleDriveMenuItem );

		Iterator<AbstractButton> it = group.getElements().asIterator();
		while( it.hasNext() )
		{
			JRadioButtonMenuItem radioBtn = (JRadioButtonMenuItem) it.next();
			if( radioBtn.getText().replaceAll( " ", "" ).equals( cloudProviderInUse ) )
				radioBtn.setSelected( true );
		}

		saveBackupsToCloudMenu.add( gitMenuItem );
		saveBackupsToCloudMenu.add( googleDriveMenuItem );

		JMenuItem installInvitedServerBtn = new JMenuItem( "Install invited server folder" );
		installInvitedServerBtn.addActionListener( insInviServBtn ->
		{
			GoogleWindows.cloneServerFolderWnd( frame );
		} );

		JMenuItem signOutDriveBtn = new JMenuItem( "Sign out" );
		JMenuItem loggedInGoogleDriveText = new JMenuItem(
				"<html><span style='color: rgb(177, 177, 177);'>Logged in Google Drive</span></html>" );
		loggedInGoogleDriveText.setEnabled( false );

		JMenuItem googleProfileBtn = new JMenuItem( "Profile" );
		googleProfileBtn.addActionListener( gglprf ->
		{
			GoogleWindows.googleProfileWnd();
		} );

		GoogleAddHostingUserBtn = new JMenuItem( "Add hosting user" );
		GoogleAddHostingUserBtn.addActionListener( gglhtusrBtn ->
		{
			GoogleWindows.addHostingUser();
		} );

		JMenuItem signIntoDriveBtn = new JMenuItem( "Sign into Google Drive" );
		signIntoDriveBtn.addActionListener( sgnggldr ->
		{
			cloudProvider = new GoogleDriveCloudProvider();
			new Thread( () ->
			{
				cloudProvider.authenticate();
			} ).start();
			SwingUtilities.invokeLater( () ->
			{
				if( cloudProvider != null || cloudProvider.isSessionOpened() )
				{
					signIntoDriveBtn.setVisible( false );
					signOutDriveBtn.setVisible( true );
					loggedInGoogleDriveText.setVisible( true );
					googleProfileBtn.setVisible( true );
					installInvitedServerBtn.setVisible( true );
					if( cloudProvider.getProviderName().equals( cloudProviderInUse ) )
					{
						cloudInUseReminderMenuText
								.setText( cloudInUseReminderText[0].formatted( cloudInUseReminderText[1] + cloudProviderInUse ) );
					}
				}
			} );
		} );

		signOutDriveBtn.addActionListener( sgntDrvBtn ->
		{
			String savedProviderName = cloudProvider.getProviderName();
			cloudProvider.closeSession();
			if( cloudProvider == null || !cloudProvider.isSessionOpened() )
			{
				signOutDriveBtn.setVisible( false );
				loggedInGoogleDriveText.setVisible( false );
				googleProfileBtn.setVisible( false );
				GoogleAddHostingUserBtn.setVisible( false );
				installInvitedServerBtn.setVisible( false );
				signIntoDriveBtn.setVisible( true );
				if( cloudProviderInUse.equals( savedProviderName ) )
				{
					cloudInUseReminderMenuText
							.setText( cloudInUseReminderText[0].formatted( cloudInUseReminderText[3].formatted( cloudProviderInUse ) ) );
				}
			}
		} );

		if( cloudProvider == null || !cloudProvider.isSessionOpened() && cloudProvider instanceof GoogleDriveCloudProvider )
		{
			loggedInGoogleDriveText.setVisible( false );
			signIntoDriveBtn.setVisible( true );
			signOutDriveBtn.setVisible( false );
			googleProfileBtn.setVisible( false );
			GoogleAddHostingUserBtn.setVisible( false );
			installInvitedServerBtn.setVisible( false );
		}
		else
		{
			loggedInGoogleDriveText.setVisible( true );
			signIntoDriveBtn.setVisible( false );
			googleProfileBtn.setVisible( true );
			installInvitedServerBtn.setVisible( true );
		}

		googleDriveMenu.add( signIntoDriveBtn );
		googleDriveMenu.add( loggedInGoogleDriveText );
		googleDriveMenu.add( googleProfileBtn );
		googleDriveMenu.add( GoogleAddHostingUserBtn );
		googleDriveMenu.add( installInvitedServerBtn );
		googleDriveMenu.add( signOutDriveBtn );

		addHostingUserBtn = new JMenuItem( "Add hosting user" );
		addHostingUserBtn.addActionListener( addhstngUsrBtn ->
		{
			GitWindows.addHostingUser();
		} );

		gitSignInBtn = new JMenuItem( "Sign into GitHub" );
		gitSignInBtn.addActionListener( gitLis ->
		{
			GitWindows.signIntoGitHubWnd( () ->
			{
				selectGitHubProvider();
				gitSignOutBtn.setVisible( true );
				repoInvitationsBtn.setVisible( true );
				gitHubProfileBtn.setVisible( true );
				cloneRepoBtn.setVisible( true );
				if( isGitHubSelected() )
				{
					cloudInUseReminderMenuText
							.setText( cloudInUseReminderText[0].formatted( cloudInUseReminderText[1] + cloudProviderInUse ) );
				}
				if( serverOpenedDirectory != null )
				{
					addHostingUserBtn.setVisible( GitUtils.repoExistInPath( serverOpenedDirectory.toPath() )
							&& GitUtils.hasRemoteOrigin( serverOpenedDirectory.toPath() ) );
					lastAutomaticBackupAttempt = null;
					configurePrivateBackupAsync( false );
				}
				refreshDashboardState();
			} );
		} );

		gitSignOutBtn = new JMenuItem( "Sign out" );
		gitSignOutBtn.addActionListener( gitOut ->
		{
			TokenStore.clear();
			if( isGitHubSelected() )
			{
				cloudInUseReminderMenuText
						.setText( cloudInUseReminderText[0].formatted( cloudInUseReminderText[3].formatted( cloudProviderInUse ) ) );
			}
			gitSignOutBtn.setVisible( false );
			repoInvitationsBtn.setVisible( false );
			gitHubProfileBtn.setVisible( false );
			addHostingUserBtn.setVisible( false );
			cloneRepoBtn.setVisible( false );
			addHostingUserBtn.setVisible( false );
			refreshDashboardState();
		} );

		repoInvitationsBtn = new JMenuItem( "Server Invitations" );
		repoInvitationsBtn.addActionListener( rpInvt ->
		{
			GitWindows.invitationslistWnd();
		} );

		gitHubProfileBtn = new JMenuItem( "Profile" );
		gitHubProfileBtn.addActionListener( prfBtn ->
		{
			GitWindows.gitHubProfileWnd();
		} );

		cloneRepoBtn = new JMenuItem( "clone a server repo" );
		cloneRepoBtn.addActionListener( clnRpBtn ->
		{
			GitWindows.cloneRepoWnd( frame );
		} );

		if( !TokenStore.sessionIsOpened() )
		{
			gitSignOutBtn.setVisible( false );
			repoInvitationsBtn.setVisible( false );
			gitHubProfileBtn.setVisible( false );
			addHostingUserBtn.setVisible( false );
			cloneRepoBtn.setVisible( false );
			addHostingUserBtn.setVisible( false );
		}

		gitMenu.add( gitSignInBtn );
		gitMenu.add( gitHubProfileBtn );
		gitMenu.add( cloneRepoBtn );
		gitMenu.add( addHostingUserBtn );
		gitMenu.add( repoInvitationsBtn );
		gitMenu.add( gitSignOutBtn );

		createServerBackupsFolderInCloud = new JButton( "Create backups folder in %s".formatted( cloudProviderInUse ) );

		contentPane = new JPanel( new BorderLayout() );
		contentPane.setBackground( view.dashboard.DashboardTheme.APP_BACKGROUND );
		dashboard = createDashboard();
		contentPane.add( dashboard, BorderLayout.CENTER );
		openServerOptions( contentPane );
		frame.getContentPane().add( contentPane );
		contentPane.setVisible( true );

		newServerMenuItem = new JMenuItem( "New Minecraft Server" );
		newServerMenuItem.setHorizontalAlignment( SwingConstants.LEFT );
		newServerMenuItem.addActionListener( mcSrv ->
		{
			JFileChooser fileChooser = new JFileChooser();
			fileChooser.setDialogTitle( "Choose an empty folder for the new server" );
			fileChooser.setFileSelectionMode( JFileChooser.DIRECTORIES_ONLY );
			int result = fileChooser.showOpenDialog( frame );
			if( result == JFileChooser.APPROVE_OPTION )
			{
				newMinecraftServerDirectory = fileChooser.getSelectedFile();
				String[] children = newMinecraftServerDirectory.list();
				if( !newMinecraftServerDirectory.isDirectory() || children == null || children.length != 0 )
				{
					showError( "Folder must be empty", "Choose an accessible empty directory for the new server." );
				}
				else
					showForgeVersionWizard( newMinecraftServerDirectory.toPath() );
			}

			if( result == JFileChooser.CANCEL_OPTION )
				newMinecraftServerDirectory = null;
		} );

		openServerMenuItem = new JMenuItem( "Open Server Folder" );
		openServerMenuItem.setHorizontalAlignment( SwingConstants.LEFT );
		openServerMenuItem.addActionListener( opSer ->
		{
			JFileChooser fileChooser = new JFileChooser();
			fileChooser.setFileSelectionMode( JFileChooser.DIRECTORIES_ONLY );
			int result = fileChooser.showOpenDialog( frame );
			if( result == JFileChooser.APPROVE_OPTION )
			{
				openKnownServer( fileChooser.getSelectedFile().getAbsolutePath() );
			}
		} );

		recentServersMenu = new JMenu( "Recent files..." );
		recentServerListGenerator();

		generalConfigurationsMenuItem = new JMenuItem( "General configurations" );
		generalConfigurationsMenuItem.addActionListener( gncnf ->
		{
			GeneralConfigurationsWindows.generalConfigurations();
		} );

		fileMenu.add( openServerMenuItem );
		fileMenu.add( newServerMenuItem );
		fileMenu.add( recentServersMenu );
		fileMenu.add( generalConfigurationsMenuItem );
		menuBar.setVisible( false );
	}

	// ---- FASE 4 — Aprovisionamiento: wizard de Forge/Fabric ------------------

	/**
	 * Abre el asistente de creacion. El array de una posicion es el rodeo para la
	 * dependencia circular: la accion de instalar necesita el wizard, que todavia
	 * no existe cuando se construye esa misma accion.
	 */
	private void showForgeVersionWizard( Path destination )
	{
		JDialog dialog = new JDialog( frame, "Create Minecraft Server", true );
		dialog.setDefaultCloseOperation( JDialog.DO_NOTHING_ON_CLOSE );
		dialog.setResizable( false );
		ForgeVersionWizard[] wizardReference = new ForgeVersionWizard[1];
		ForgeVersionWizard wizard = new ForgeVersionWizard( destination,
				selection -> installForgeFromWizard( dialog, wizardReference[0], destination, selection ),
				() ->
				{
					newMinecraftServerDirectory = null;
					dialog.dispose();
				} );
		wizardReference[0] = wizard;
		dialog.setContentPane( wizard );
		dialog.pack();
		dialog.setLocationRelativeTo( frame );
		wizard.loadCatalog( loader ->
		{
			if( "Fabric".equals( loader ) )
			{
				FabricInstaller.Catalog catalog = FabricInstaller.loadCatalogChecked();
				// El wizard invierte las listas para mostrar lo nuevo primero; la
				// meta API de Fabric ya viene nuevo-primero, asi que se compensa
				List<String> gameVersions = new java.util.ArrayList<>( catalog.gameVersions() );
				List<String> loaderVersions = new java.util.ArrayList<>( catalog.loaderVersions() );
				java.util.Collections.reverse( gameVersions );
				java.util.Collections.reverse( loaderVersions );
				return new ForgeVersionWizard.VersionCatalog( gameVersions, loaderVersions, true );
			}
			String metadata = ForgeUtils.downloadForgeMetadataChecked();
			List<String> forgeVersions = ForgeUtils.getForgeVersionsList( metadata );
			if( forgeVersions.isEmpty() )
				throw new IOException( "Forge returned an empty version catalogue." );
			return new ForgeVersionWizard.VersionCatalog(
					ForgeUtils.getMinecraftVersionsList( metadata ), forgeVersions );
		} );
		dialog.setVisible( true );
	}

	private void installForgeFromWizard( JDialog dialog, ForgeVersionWizard wizard, Path destination,
			ForgeVersionWizard.Selection selection )
	{
		wizard.setBusy( true, "Downloading " + selection.loader() + " " + selection.forgeVersion() + "…" );
		new SwingWorker<Void, String>()
		{
			@Override
			protected Void doInBackground() throws Exception
			{
				if( "Fabric".equals( selection.loader() ) )
				{
					publish( "Downloading the Fabric server launcher…" );
					FabricInstaller.installServerChecked( destination, selection.minecraftVersion(), selection.forgeVersion() );
				}
				else
				{
					Path installer = ForgeUtils.downloadForgeInstallerChecked( selection.forgeVersion() );
					publish( "Installing Forge server files…" );
					ForgeUtils.installForgeServerChecked( installer, destination );
				}
				if( !ForgeUtils.hasServerStartupCommand( destination ) )
				{
					throw new IOException( selection.loader() + " finished but no startup command is available." );
				}
				return null;
			}

			@Override
			protected void process( List<String> messages )
			{
				if( !messages.isEmpty() )
					wizard.setBusy( true, messages.get( messages.size() - 1 ) );
			}

			@Override
			protected void done()
			{
				try
				{
					get();
					wizard.showEulaStep(
							() -> ForgeUtils.openURL( "https://aka.ms/MinecraftEULA" ),
							() ->
							{
								if( !ForgeUtils.acceptEULA( destination ) )
								{
									wizard.showError( "eula.txt could not be written. Check folder permissions." );
									return;
								}
								newMinecraftServerDirectory = null;
								dialog.dispose();
								openKnownServer( destination.toAbsolutePath().toString() );
							} );
				}
				catch( Exception installFailure )
				{
					// SwingWorker envuelve la excepcion real en ExecutionException: al usuario
					// hay que enseñarle la causa de fondo, no el envoltorio
					Throwable root = installFailure;
					while( root.getCause() != null )
						root = root.getCause();
					wizard.showError( root.getMessage() );
				}
			}
		}.execute();
	}

	// ---- FASE 5 — Puente entre el dashboard y el controlador -----------------

	/** Cablea cada intencion del dashboard con la operacion real de esta clase. */
	private MinecraftDashboard createDashboard()
	{
		return new MinecraftDashboard( new MinecraftDashboard.Actions()
		{
			@Override
			public void createServer()
			{
				if( newServerMenuItem != null )
					newServerMenuItem.doClick();
			}
			@Override
			public void openServer()
			{
				if( openServerMenuItem != null )
					openServerMenuItem.doClick();
			}
			@Override
			public void selectServer( String path )
			{
				openKnownServer( path );
			}
			@Override
			public void playWorld( MinecraftDashboard.ServerEntry entry )
			{
				launchGameForWorld( entry );
			}
			@Override
			public void wantToPlay( MinecraftDashboard.ServerEntry entry )
			{
				new Thread( () ->
				{
					String repo = entry.remoteOnly() ? entry.path() : repoFullNameForPath( entry.path() );
					if( repo != null && jgit.WorldEvents.publish( repo, "want_to_play" ) )
						appendDashboardActivity( "Ping sent: you want to play " + entry.name() );
				}, "endershare-want-to-play" ).start();
			}
			@Override
			public void cloneInvitedServer()
			{
				if( !TokenStore.sessionIsOpened() )
				{
					showError( "GitHub account required", "Sign into GitHub before cloning an invited server." );
					showDashboardPage( MinecraftDashboard.Page.BACKUPS );
					return;
				}
				if( cloneRepoBtn != null )
					cloneRepoBtn.doClick();
			}
			@Override
			public void toggleServer()
			{
				toggleServerFromDashboard();
			}
			@Override
			public void refreshNetwork()
			{
				refreshNetworkAsync();
			}
			@Override
			public void syncNow()
			{
				synchronizeNow();
			}
			@Override
			public void importWorld()
			{
				importWorldFromDashboard();
			}
			@Override
			public void openModsFolder()
			{
				openModsFolderFromDashboard();
			}
			@Override
			public void openServerFolder()
			{
				openSelectedServerFolder();
			}
			@Override
			public void openServerSettings()
			{
				showDashboardPage( MinecraftDashboard.Page.SETTINGS );
			}
			@Override
			public void saveServerSettings( MinecraftDashboard.SettingsDraft settings )
			{
				saveServerSettingsFromDashboard( settings );
			}
			@Override
			public void openGeneralSettings()
			{
				if( generalConfigurationsMenuItem != null )
					generalConfigurationsMenuItem.doClick();
			}
			@Override
			public void createRepository()
			{
				createRepositoryFromDashboard();
			}
			@Override
			public void signIntoGitHub()
			{
				if( gitSignInBtn != null )
					gitSignInBtn.doClick();
			}
			@Override
			public void signOutOfGitHub()
			{
				if( gitSignOutBtn != null )
					gitSignOutBtn.doClick();
			}
			@Override
			public void showGitHubProfile()
			{
				if( gitHubProfileBtn != null )
					gitHubProfileBtn.doClick();
			}
			@Override
			public void showInvitations()
			{
				if( repoInvitationsBtn != null )
					repoInvitationsBtn.doClick();
			}
			@Override
			public void inviteHost()
			{
				if( addHostingUserBtn != null )
					addHostingUserBtn.doClick();
			}
			@Override
			public void sendCommand( String command )
			{
				if( serverIsOn && serverProcess != null && serverWriter != null )
				{
					String normalized = command == null ? "" : command.trim();
					if( "stop".equalsIgnoreCase( normalized ) || "/stop".equalsIgnoreCase( normalized ) )
						turnOffServer();
					else
						ForgeUtils.sendCommand( command, serverProcess, serverWriter );
				}
			}
			@Override
			public void openWorldMap()
			{
				openWorldMapInBrowser();
			}
			@Override
			public void buildWorldMap( boolean fullDetail )
			{
				startWorldMapBuild( fullDetail );
			}
			@Override
			public void stopWorldMap()
			{
				app.WorldMap.stopRendering();
				refreshWorldMapState();
			}
			@Override
			public void setWorldMapEnabled( boolean enabled )
			{
				changeWorldMapEnabled( enabled );
			}
		} );
	}

	// ---- FASE 6 — Descubrimiento de red y refresco del dashboard -------------

	/**
	 * Sondea la red P2P y traduce el resultado a fase del dashboard. Solo se
	 * adopta la lista de jugadores del peer remoto cuando el servidor local esta
	 * parado: con el nuestro encendido, la verdad la tiene la consola de Forge.
	 */
	public void checkServerStatus()
	{
		if( serverOpenedDirectory == null )
		{
			discoveredHost = "—";
			setDashboardPhase( Phase.NO_SERVER, "Open or create a Minecraft server" );
			return;
		}

		NetworkDiscoverClient.DiscoveryResult discovery = NetworkDiscoverClient.surroundDiscoverStatus( networkName, actualServerPort,
				3000 );
		boolean remoteHostFound = discovery.found();
		String remoteHostLabel = discovery.host();
		if( !serverIsOn )
		{
			if( remoteHostFound && discovery.rosterAvailable() )
			{
				playerPresence.replaceSnapshot( discovery.players(), discovery.onlinePlayers(), discovery.maxPlayers() );
			}
			else
			{
				playerPresence.reset( ForgeUtils.getMaxPlayers( serverOpenedDirectory.toPath() ) );
			}
		}
		// El discovery UDP solo ve la LAN: sin host ahi, el escaneo consulta ademas
		// el candado de GitHub, que es el mismo arbitro que usa START. Asi SCAN y
		// START cuentan la misma historia cuando el peer hostea por internet
		remoteHostTunnelAddress = null;
		if( !serverIsOn && !remoteHostFound )
		{
			HostLock.Status lock = cachedHostLockStatus();
			if( lock != null && lock.locked() && !lock.mine() && !lock.stale() )
			{
				remoteHostFound = true;
				remoteHostLabel = lock.hostNickname() + " (INTERNET)";
				// El host publica junto al lease su tunel y su aforo: los invitados
				// ven donde conectarse y cuanta gente hay sin mas canal que el candado
				jgit.HostLock.HostDetails details = lock.details();
				if( details != null )
				{
					remoteHostTunnelAddress = details.tunnelAddress();
					if( details.onlinePlayers() >= 0 && details.maxPlayers() > 0 )
						remoteHostLabel += " · " + details.onlinePlayers() + "/" + details.maxPlayers();
				}
			}
		}
		discoveredHost = remoteHostFound ? remoteHostLabel : (serverIsOn ? "LOCAL PROCESS" : "—");
		if( serverIsOn )
			setDashboardPhase( Phase.ONLINE, "Forge is accepting players" );
		else if( remoteHostFound )
			setDashboardPhase( Phase.REMOTE_HOST, "Another peer is hosting this world" );
		else
			setDashboardPhase( Phase.OFFLINE, "No active host discovered" );
	}

	private volatile HostLock.Status lastHostLockStatus;
	private volatile long lastHostLockCheckMillis;
	/** Direccion publica del host remoto, leida del candado; null sin host por internet. */
	private volatile String remoteHostTunnelAddress;

	/**
	 * Estado del candado de GitHub con cache de 60 segundos: el polling de fase
	 * remota repite el escaneo cada 10 y no debe convertirse en una rafaga de
	 * llamadas a la API. Devuelve null cuando no hay repo enlazado o sesion.
	 */
	private HostLock.Status cachedHostLockStatus()
	{
		HostLock.Status result = null;
		do
		{
			if( serverOpenedDirectory == null || !TokenStore.sessionIsOpened() )
				break;
			Path selectedServer = serverOpenedDirectory.toPath();
			if( !GitUtils.repoExistInPath( selectedServer ) || !GitUtils.hasRemoteOrigin( selectedServer ) )
				break;
			long now = System.currentTimeMillis();
			if( lastHostLockStatus != null && now - lastHostLockCheckMillis < 60_000 )
			{
				result = lastHostLockStatus;
				break;
			}
			String repoFullName = GitUtils.remoteRepoFullName( selectedServer );
			if( repoFullName == null || repoFullName.isBlank() )
				break;
			result = HostLock.readStatus( repoFullName );
			lastHostLockStatus = result;
			lastHostLockCheckMillis = now;
		} while( false );
		return result;
	}

	public void openServerOptions( JPanel fatherFrame )
	{
		if( serverOpenedDirectory == null )
			loadMostRecentServer();
		if( serverOpenedDirectory != null && TokenStore.sessionIsOpened() )
		{
			boolean linkedGitRepository = GitUtils.repoExistInPath( serverOpenedDirectory.toPath() )
					&& GitUtils.hasRemoteOrigin( serverOpenedDirectory.toPath() );
			boolean noProviderSelected = cloudProviderInUse == null || cloudProviderInUse.isBlank()
					|| "noCloudProvider".equals( cloudProviderInUse );
			if( linkedGitRepository || noProviderSelected )
				selectGitHubProvider();
		}

		turnOnOffBtn = dashboard == null ? null : dashboard.primaryActionButton();
		if( serverOpenedDirectory == null )
		{
			dashboardPhase = Phase.NO_SERVER;
			dashboardPhaseDetail = "Open or create a Minecraft server";
			discoveredHost = "—";
		}
		else
		{
			actualServerPort = ForgeUtils.getServerPort( serverOpenedDirectory.toPath() );
			if( dashboardPhase == Phase.NO_SERVER )
			{
				dashboardPhase = serverIsOn ? Phase.ONLINE : Phase.OFFLINE;
				dashboardPhaseDetail = serverIsOn ? "Forge is accepting players" : "Ready to check the network";
			}
		}

		refreshDashboardState();
		fatherFrame.revalidate();
		fatherFrame.repaint();
		if( recentServersMenu != null )
			recentServerListGenerator();
		boolean configuringBackup = configurePrivateBackupAsync( false );
		if( serverOpenedDirectory != null && !serverIsOn && !configuringBackup )
			refreshNetworkAsync();
	}

	private void showDashboardPage( MinecraftDashboard.Page page )
	{
		if( dashboard != null )
			dashboard.showPage( page );
	}

	private void setDashboardPhase( Phase phase, String detail )
	{
		dashboardPhase = phase;
		dashboardPhaseDetail = detail;
		if( phase != Phase.ERROR )
			dashboardError = "";
		refreshDashboardState();
	}

	private void refreshDashboardState()
	{
		if( dashboard == null )
			return;
		if( !SwingUtilities.isEventDispatchThread() )
		{
			SwingUtilities.invokeLater( this::refreshDashboardState );
			return;
		}

		boolean loaded = serverOpenedDirectory != null;
		boolean authenticated = TokenStore.sessionIsOpened();
		boolean linked = loaded && linkedRepoStatusCached( serverOpenedDirectory.toPath() );
		String account = "NOT CONNECTED";
		if( authenticated )
		{
			try
			{
				account = TokenStore.getSavedUserData().getOrDefault( "nickname", "CONNECTED" );
			}
			catch( Exception accountReadFailure )
			{
				// Token guardado pero ilegible o corrupto: se degrada a "no autenticado"
				// para que la UI ofrezca volver a entrar en vez de mentir con un nombre
				authenticated = false;
				app.Log.event( "UI", "La sesion de GitHub guardada no pudo leerse", accountReadFailure );
			}
		}

		String serverName = loaded ? getServerName() : null;
		String serverPath = loaded ? serverOpenedDirectory.getAbsolutePath() : null;
		String port = loaded ? Integer.toString( ForgeUtils.getServerPort( serverOpenedDirectory.toPath() ) ) : null;
		String ram = "—";
		if( loaded )
		{
			try
			{
				ram = ForgeUtils.getServerRAMAlloc( serverOpenedDirectory.toPath() ).replace( "-Xmx", "" );
			}
			catch( Exception ramReadFailure )
			{
				// Este refresco corre cada pocos segundos: si user_jvm_args.txt falta o no
				// se deja leer se muestra "—" y se sigue, sin llenar el log de ruido
				ram = "—";
			}
		}
		String repository = linked ? account + "/" + serverName : "NOT LINKED";
		String displayedSyncState;
		if( !isGitHubSelected() )
			displayedSyncState = authenticated ? "STANDBY" : "DISABLED";
		else if( !authenticated )
			displayedSyncState = "AUTH REQUIRED";
		else if( !linked )
			displayedSyncState = "NOT LINKED";
		else
			displayedSyncState = "NOT CONFIGURED".equals( syncState ) ? "READY" : syncState;
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
				(loaded ? LoaderKind.detect( serverOpenedDirectory.toPath() ).displayName().toUpperCase() : "FORGE") + " / JAVA 21",
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
				decoratedRecentServers() );
		dashboard.setState( dashboardState );
		PlayitAgentFile playitAgent = loaded ? PlayitAgentFile.load( serverOpenedDirectory.toPath() ) : null;
		boolean publicUrlEnabled = playitAgent != null && playitAgent.enabled;
		String publicUrlAddress = playitAgent == null ? null : playitAgent.tunnel_address;
		// Con un host remoto, la direccion buena es la que ese host publico en el
		// candado: es la que los invitados deben copiar para conectarse
		if( dashboardPhase == Phase.REMOTE_HOST && remoteHostTunnelAddress != null )
		{
			publicUrlEnabled = true;
			publicUrlAddress = remoteHostTunnelAddress;
		}
		dashboard.showPublicUrl( publicUrlEnabled, publicUrlAddress );
		refreshWorldMapState();
		turnOnOffBtn = dashboard.primaryActionButton();
		consoleArea = dashboard.consoleArea();
	}

	// ---- FASE 7 — Actualizador -----------------------------------------------

	private volatile String lastOfferedUpdateVersion = null;

	/**
	 * Comprobacion de version contra las releases publicas de GitHub: una al
	 * arrancar y despues cada minuto mientras la app siga abierta. Una sesion de
	 * hosting dura horas y con un unico chequeo al inicio no se enteraria nunca.
	 * Cada version se ofrece exactamente una vez; el resto del tiempo, silencio.
	 */
	private void checkForUpdatesAsync()
	{
		runUpdateCheck();
		javax.swing.Timer periodicUpdateCheck = new javax.swing.Timer( 60 * 1000, event -> runUpdateCheck() );
		periodicUpdateCheck.setRepeats( true );
		periodicUpdateCheck.start();
	}

	private void runUpdateCheck()
	{
		Thread checker = new Thread( () -> app.UpdateChecker.findNewerRelease().ifPresent( release -> SwingUtilities.invokeLater( () ->
		{
			if( release.version().equals( lastOfferedUpdateVersion ) )
				return;
			lastOfferedUpdateVersion = release.version();
			String[] options = {"UPDATE NOW", "LATER"};
			int choice = JOptionPane.showOptionDialog(
					frame,
					"Endershare " + release.version() + " is available (you are running " + app.UpdateChecker.currentVersion() + ").\n"
							+ "The app downloads the installer, closes itself and opens the installer for you.",
					"Update available",
					JOptionPane.YES_NO_OPTION,
					JOptionPane.INFORMATION_MESSAGE,
					null,
					options,
					options[0] );
			if( choice != JOptionPane.YES_OPTION )
				return;
			if( serverIsOn )
			{
				// Actualizar mientras se hostea: aviso explicito de que el mundo se
				// cierra con el ciclo completo (backup verificado + lock liberado)
				int confirmed = JOptionPane.showConfirmDialog( frame,
						"You are hosting right now. The update downloads in the background and\n"
								+ "then the world is stopped, fully backed up to GitHub and the host lock\n"
								+ "released — the app closes itself and the installer opens. Nothing is lost.",
						"Safe update", JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE );
				if( confirmed != JOptionPane.OK_OPTION )
				{
					// Que se lo vuelva a ofrecer en el siguiente chequeo
					lastOfferedUpdateVersion = null;
					app.Log.event( "UPDATER", "Actualizacion a " + release.version() + " aplazada por estar hosteando" );
					return;
				}
			}
			startSelfUpdate( release );
		} ) ), "endershare-update-check" );
		checker.setDaemon( true );
		checker.start();
	}

	/**
	 * Descarga el instalador en segundo plano y, cuando esta completo en disco,
	 * cierra la app por el ciclo seguro dejandolo lanzado. Si la descarga falla,
	 * se degrada al comportamiento antiguo: abrir la descarga en el navegador
	 * sin cerrar nada.
	 */
	private void startSelfUpdate( app.UpdateChecker.ReleaseInfo release )
	{
		appendDashboardActivity( "Downloading update " + release.version() + " in the background…" );
		Thread downloader = new Thread( () ->
		{
			java.nio.file.Path installer = null;
			try
			{
				installer = app.SelfUpdater.downloadInstaller( release.downloadUrl(),
						app.SelfUpdater.installerFileName( release.downloadUrl(), release.version() ) );
			}
			catch( Exception downloadFailure )
			{
				app.Log.event( "UPDATER", "La descarga del instalador fallo; se degrada al navegador", downloadFailure );
			}
			if( installer == null )
			{
				ForgeUtils.openURL( release.downloadUrl() != null ? release.downloadUrl() : release.pageUrl() );
				return;
			}
			pendingInstallerToLaunch = installer;
			SwingUtilities.invokeLater( () ->
			{
				appendDashboardActivity( "Update " + release.version() + " downloaded; closing to install" );
				saveAndClose();
			} );
		}, "endershare-update-download" );
		downloader.setDaemon( true );
		downloader.start();
	}

	// ---- FASE 8 — Caches y biblioteca de servidores recientes ----------------

	private volatile String linkedRepoCacheKey = null;
	private volatile boolean linkedRepoCacheValue = false;
	private volatile long linkedRepoCacheAtMillis = 0;

	/**
	 * El refresco del dashboard corre cada pocos segundos y cada apertura de JGit
	 * recorre el repo del mundo entero (miles de ficheros, y peor con el antivirus
	 * de Windows). Que el repo este enlazado no cambia casi nunca, asi que se
	 * cachea 30 segundos.
	 */
	private boolean linkedRepoStatusCached( Path serverDirectory )
	{
		String key = serverDirectory.toString();
		long now = System.currentTimeMillis();
		if( key.equals( linkedRepoCacheKey ) && now - linkedRepoCacheAtMillis < 30_000 )
			return linkedRepoCacheValue;
		boolean linked = GitUtils.repoExistInPath( serverDirectory ) && GitUtils.hasRemoteOrigin( serverDirectory );
		linkedRepoCacheKey = key;
		linkedRepoCacheValue = linked;
		linkedRepoCacheAtMillis = now;
		return linked;
	}

	/**
	 * Cierto cuando hay un jar de BlueMap en la carpeta mods del servidor. Salida
	 * unica con variable result en vez de do-while: el break pertenece al for.
	 */
	private void refreshWorldMapState()
	{
		File opened = serverOpenedDirectory;
		if( opened == null )
		{
			dashboard.showMapState( false, false, false, null );
			return;
		}
		Path repository = opened.toPath();
		dashboard.showMapState( app.WorldMap.isEnabledFor( repository ), app.WorldMap.hasBuiltMap( repository ),
				app.WorldMap.isRenderingFor( repository ), app.WorldMap.isWatchingFor( repository ),
				app.WorldMap.currentUrl().orElse( null ) );
	}

	/**
	 * Abre el mapa. Si el visor no se esta sirviendo hay que levantarlo primero:
	 * el proceso sirve la web nada mas arrancar, asi que se abre igual aunque
	 * todavia le queden regiones por dibujar.
	 */
	private void openWorldMapInBrowser()
	{
		File opened = serverOpenedDirectory;
		java.util.Optional<String> served = opened == null ? app.WorldMap.currentUrl()
				: app.WorldMap.viewerUrlFor( opened.toPath() );
		if( served.isPresent() )
			ForgeUtils.openURL( served.get() );
		else
			startWorldMapBuild( dashboard.wantsFullDetailMap() );
	}

	/**
	 * Construye el mapa 3D desde la copia local del mundo. Nunca toca el
	 * servidor de nadie ni escribe dentro del repositorio.
	 */
	private void startWorldMapBuild( boolean fullDetail )
	{
		File opened = serverOpenedDirectory;
		if( opened == null )
		{
			JOptionPane.showMessageDialog( frame, "Open a server first and then build its map.", "World map",
					JOptionPane.INFORMATION_MESSAGE );
			return;
		}
		Path repository = opened.toPath();
		java.util.Optional<Path> world = app.WorldMap.locateWorld( repository );
		if( world.isEmpty() )
		{
			JOptionPane.showMessageDialog( frame,
					"That server has no world files yet. Start it once so Minecraft creates the world, then build the map.",
					"World map", JOptionPane.INFORMATION_MESSAGE );
			return;
		}

		refreshWorldMapState();
		new SwingWorker<String, Void>()
		{
			@Override
			protected String doInBackground() throws Exception
			{
				// Rehacer sobre un mapa que ya existe significa que ha cambiado COMO
				// se dibuja, y eso el renderizador no lo detecta solo
				boolean redrawEverything = app.WorldMap.hasBuiltMap( repository );
				return app.WorldMap.startRendering( repository, world.get(), fullDetail, serverIsOn,
						redrawEverything );
			}

			@Override
			protected void done()
			{
				try
				{
					ForgeUtils.openURL( get() );
				}
				catch( InterruptedException interrupted )
				{
					Thread.currentThread().interrupt();
				}
				catch( java.util.concurrent.ExecutionException failure )
				{
					// SwingWorker envuelve la excepcion real: al usuario le sirve la de dentro
					Throwable cause = failure.getCause() == null ? failure : failure.getCause();
					JOptionPane.showMessageDialog( frame, "The map could not be built.\n\n" + cause.getMessage(),
							"World map", JOptionPane.ERROR_MESSAGE );
				}
				refreshWorldMapState();
			}
		}.execute();
	}

	private volatile List<MinecraftDashboard.ServerEntry> recentServersCache = null;
	private volatile String recentServersCacheKey = null;

	private List<MinecraftDashboard.ServerEntry> readRecentServers()
	{
		// El refresco del dashboard llama esto cada pocos segundos: relee el fichero y
		// re-detecta el loader de cada server solo cuando el fichero o la seleccion cambian
		Path recentServersPath = app.AppPaths.dataFile( "recentServers.properties" );
		long fileStamp = 0;
		try
		{
			if( Files.exists( recentServersPath ) )
				fileStamp = Files.getLastModifiedTime( recentServersPath ).toMillis();
		}
		catch( IOException stampFailure )
		{
			// Sin marca de tiempo el cacheKey queda en 0 y se recalcula la lista: mas
			// trabajo, pero nunca datos rancios
		}
		String selectedPath = serverOpenedDirectory == null ? "" : serverOpenedDirectory.getAbsolutePath();
		String cacheKey = fileStamp + "|" + selectedPath;
		List<MinecraftDashboard.ServerEntry> cached = recentServersCache;
		if( cached != null && cacheKey.equals( recentServersCacheKey ) )
			return cached;

		List<MinecraftDashboard.ServerEntry> entries = new ArrayList<>();
		List<String> paths = new ArrayList<>();
		Properties properties = new Properties();
		if( Files.exists( recentServersPath ) )
		{
			try (FileInputStream input = new FileInputStream( recentServersPath.toFile() ))
			{
				properties.load( input );
				String value = properties.getProperty( "recentServers", "" );
				for( String path : value.split( "\\|" ) )
				{
					if( !path.isBlank() && !paths.contains( path ) )
						paths.add( path );
				}
			}
			catch( IOException readFailure )
			{
				// Fichero ilegible: se degrada a lista vacia y el usuario siempre puede
				// volver a abrir la carpeta a mano
				app.Log.event( "UI", "La lista de servidores recientes no pudo leerse", readFailure );
			}
		}
		if( serverOpenedDirectory != null )
		{
			// El servidor abierto encabeza siempre la lista, este o no en el fichero
			String current = serverOpenedDirectory.getAbsolutePath().replace( '\\', '/' );
			paths.remove( current );
			paths.add( 0, current );
		}
		for( String path : paths )
		{
			File directory = new File( path );
			String name = directory.getName().isBlank() ? path : directory.getName();
			boolean selected = serverOpenedDirectory != null
					&& directory.getAbsolutePath().equals( serverOpenedDirectory.getAbsolutePath() );
			String detail = ForgeUtils.hasServerStartupCommand( directory.toPath() )
					? LoaderKind.detect( directory.toPath() ).displayName().toUpperCase() + " READY"
					: "MISSING STARTUP SCRIPT";
			entries.add( new MinecraftDashboard.ServerEntry( name, path, detail, selected ) );
		}
		recentServersCache = entries;
		recentServersCacheKey = cacheKey;
		return entries;
	}

	// Path del server -> repo de GitHub; leer el remote toca disco, asi que se
	// resuelve una sola vez por ruta ("" = sin repo enlazado)
	private final java.util.concurrent.ConcurrentHashMap<String, String> repoNameByPath = new java.util.concurrent.ConcurrentHashMap<>();

	private String repoFullNameForPath( String path )
	{
		String repo = repoNameByPath.computeIfAbsent( path, key ->
		{
			try
			{
				Path directory = Path.of( key );
				String resolved = GitUtils.repoExistInPath( directory ) && GitUtils.hasRemoteOrigin( directory )
						? GitUtils.remoteRepoFullName( directory )
						: null;
				return resolved == null ? "" : resolved;
			}
			catch( Exception unreadableRepo )
			{
				return "";
			}
		} );
		return repo.isEmpty() ? null : repo;
	}

	/**
	 * Filas de la pagina SERVERS: los servers locales recientes decorados con el
	 * estado en vivo del escaner, mas los mundos suscritos que no estan clonados
	 * en esta maquina. La base cacheada no incluye el estado: se decora en cada
	 * refresco (baratisimo, es un lookup en memoria) para que las tarjetas
	 * cambien en cuanto el escaner ve algo nuevo.
	 */
	private List<MinecraftDashboard.ServerEntry> decoratedRecentServers()
	{
		List<MinecraftDashboard.ServerEntry> base = readRecentServers();
		if( worldStatusScanner == null )
			return base;
		List<MinecraftDashboard.ServerEntry> decorated = new ArrayList<>();
		java.util.Set<String> coveredRepos = new java.util.HashSet<>();
		for( MinecraftDashboard.ServerEntry entry : base )
		{
			String repo = repoFullNameForPath( entry.path() );
			MinecraftDashboard.ServerEntry enriched = entry;
			if( repo != null )
			{
				coveredRepos.add( repo );
				var status = worldStatusScanner.statusOf( repo ).orElse( null );
				if( status != null )
				{
					enriched = new MinecraftDashboard.ServerEntry( entry.name(), entry.path(), entry.detail(),
							entry.selected(), worldStatusLine( status ), connectAddressOf( status ), false );
				}
			}
			decorated.add( enriched );
		}
		// Mundos suscritos sin copia local: se ven (y se puede copiar su IP si
		// estan vivos) aunque nunca se hayan clonado en esta maquina
		for( String repo : app.WorldSubscriptions.all( quietNickname() ) )
		{
			if( coveredRepos.contains( repo ) )
				continue;
			var status = worldStatusScanner.statusOf( repo ).orElse( null );
			String name = repo.contains( "/" ) ? repo.substring( repo.indexOf( '/' ) + 1 ) : repo;
			decorated.add( new MinecraftDashboard.ServerEntry( name, repo, "REMOTE WORLD", false,
					status == null ? "CHECKING…" : worldStatusLine( status ),
					status == null ? null : connectAddressOf( status ), true ) );
		}
		return decorated;
	}

	private static String worldStatusLine( app.WorldStatusScanner.WorldStatus status )
	{
		String result;
		if( !status.hosted() )
		{
			result = "FREE TO HOST";
		}
		else
		{
			result = status.mine() ? "LIVE · you are hosting" : "LIVE · " + status.hostNickname() + " hosting";
			jgit.HostLock.HostDetails details = status.details();
			if( details != null && details.onlinePlayers() >= 0 && details.maxPlayers() > 0 )
				result += " · " + details.onlinePlayers() + "/" + details.maxPlayers();
			if( details != null && details.minecraftVersion() != null )
				result += " · MC " + details.minecraftVersion();
		}
		return result;
	}

	private static String connectAddressOf( app.WorldStatusScanner.WorldStatus status )
	{
		jgit.HostLock.HostDetails details = status.details();
		return status.hosted() && details != null ? details.tunnelAddress() : null;
	}

	/**
	 * JOIN/PLAY de una tarjeta, flujo completo sin mods: el server queda en la
	 * lista Multiplayer (servers.dat), se elige version mirando lo que el
	 * jugador ya tiene instalado, y con Quick Play (1.20+) el juego arranca YA
	 * DENTRO del server. Si Minecraft esta abierto, se ofrece cerrarlo y entrar
	 * directo — un cliente en marcha no se puede teledirigir sin mod. De la
	 * cuenta se encarga el launcher: aqui no se toca ninguna credencial.
	 */
	private void launchGameForWorld( MinecraftDashboard.ServerEntry entry )
	{
		new Thread( () ->
		{
			String repo = entry.remoteOnly() ? entry.path() : repoFullNameForPath( entry.path() );
			var status = repo == null || worldStatusScanner == null
					? null
					: worldStatusScanner.statusOf( repo ).orElse( null );
			jgit.HostLock.HostDetails details = status == null ? null : status.details();
			String address = details == null ? null : details.tunnelAddress();
			String version = details == null ? null : details.minecraftVersion();
			// Con copia local, la carpeta del server es mejor fuente de version que
			// un lease que quiza la omitio
			if( version == null && !entry.remoteOnly() )
				version = ForgeUtils.getMinecraftVersion( Path.of( entry.path() ) );
			if( address != null )
				copyAddressToClipboard( address );

			Path minecraftDirectory = app.MinecraftLauncher.defaultMinecraftDirectory();

			// 1. Red de seguridad: pase lo que pase despues, el server queda en la
			// lista de Multiplayer del juego con la direccion fresca
			boolean listed = address != null && app.ServersDat.upsertServer(
					minecraftDirectory.resolve( "servers.dat" ), "Endershare · " + entry.name(), address );
			if( listed )
				appendDashboardActivity( entry.name() + " added to the in-game Multiplayer list" );

			// 2. Con el juego YA abierto no se puede entrar solo: se ofrece cerrar.
			// El proceso del server hosteado por esta app queda excluido SIEMPRE
			var runningClient = app.RunningMinecraftClient.find( hostedServerPids() );
			if( runningClient.isPresent() )
			{
				String runningVersion = runningClient.get().versionId();
				boolean sameVersion = version != null && runningVersion != null && runningVersion.contains( version );
				String message = sameVersion
						? "Minecraft is already open.\nThe server is now in your Multiplayer list.\n\nClose Minecraft and rejoin "
								+ entry.name() + " directly?"
						: "Minecraft is open with " + (runningVersion == null ? "another version" : runningVersion)
								+ ", but " + entry.name() + " runs " + version + ".\n\nClose Minecraft and join with the right version?";
				int choice = optionDialogOnEdt( "Minecraft is running", message,
						new Object[]{"CLOSE & JOIN", "KEEP PLAYING"} );
				if( choice != 0 )
				{
					appendDashboardActivity( "Join postponed: Minecraft stays open — the server is in your Multiplayer list" );
					return;
				}
				if( !app.RunningMinecraftClient.close( runningClient.get().pid() ) )
				{
					appendDashboardActivity( "Minecraft could not be closed — join it from the Multiplayer list" );
					return;
				}
				appendDashboardActivity( "Minecraft closed to rejoin with the right version" );
			}

			if( version == null )
			{
				// Sin version conocida no hay perfil fiable ni quick play: launcher
				// abierto y direccion copiada, como el flujo clasico
				boolean opened = app.MinecraftLauncher.openLauncher();
				appendDashboardActivity( opened
						? "Launcher opened — world version unknown, pick it manually (address copied)"
						: "Minecraft launcher not found on this machine" );
				return;
			}

			// 3. Version de juego: vanilla exacta por defecto; si el jugador tiene
			// instaladas variantes compatibles (su Fabric, OptiFine...) se le
			// ofrecen UNA vez y la eleccion se recuerda por mundo
			String baseVersion = version;
			java.util.List<String> candidates = app.MinecraftLauncher.installedVersionCandidates( minecraftDirectory, version );
			String remembered = repo == null ? null : app.MinecraftLauncher.rememberedJoinVersion( repo );
			if( remembered != null && candidates.contains( remembered ) )
			{
				baseVersion = remembered;
			}
			else if( candidates.size() > 1 )
			{
				String chosen = versionDialogOnEdt( entry.name(), version, candidates );
				if( chosen == null )
				{
					appendDashboardActivity( "Join cancelled" );
					return;
				}
				baseVersion = chosen;
				if( repo != null )
					app.MinecraftLauncher.rememberJoinVersion( repo, chosen );
			}

			// 4. Quick play: version heredada con --quickPlayMultiplayer para que
			// el juego arranque dentro del server; sin direccion (mundo libre) el
			// perfil apunta a la version elegida a secas
			boolean quickPlay = address != null
					&& app.MinecraftLauncher.writeQuickPlayVersion( minecraftDirectory, baseVersion, address );
			String profileVersion = quickPlay ? app.MinecraftLauncher.QUICK_PLAY_VERSION_ID : baseVersion;
			boolean profileReady = app.MinecraftLauncher.upsertProfile(
					app.MinecraftLauncher.defaultProfilesFile(), entry.name(), profileVersion );
			boolean launcherOpened = app.MinecraftLauncher.openLauncher();

			String summary;
			if( launcherOpened )
			{
				summary = quickPlay
						? "Launcher opened — press PLAY and you will spawn inside " + entry.name()
						: "Launcher opened" + (profileReady ? " with profile MC " + baseVersion : "")
								+ (listed ? " — the server is in your Multiplayer list" : "");
			}
			else
			{
				summary = address != null
						? "Minecraft launcher not found — server address copied to the clipboard"
						: "Minecraft launcher not found on this machine";
			}
			appendDashboardActivity( summary );
		}, "endershare-play-world" ).start();
	}

	/** PIDs que el detector de cliente debe ignorar: esta app y su server hosteado. */
	private static java.util.List<Long> hostedServerPids()
	{
		java.util.List<Long> excluded = new java.util.ArrayList<>();
		excluded.add( ProcessHandle.current().pid() );
		Process hosted = serverProcess;
		if( hosted != null && hosted.isAlive() )
		{
			excluded.add( hosted.pid() );
			hosted.toHandle().descendants().forEach( child -> excluded.add( child.pid() ) );
		}
		return excluded;
	}

	/** Corre fuera del EDT: opcion elegida (indice) o CLOSED_OPTION. */
	private int optionDialogOnEdt( String title, String message, Object[] options )
	{
		final int[] selection = {JOptionPane.CLOSED_OPTION};
		try
		{
			SwingUtilities.invokeAndWait( () -> selection[0] = JOptionPane.showOptionDialog( frame, message, title,
					JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0] ) );
		}
		catch( Exception dialogFailure )
		{
			// Sin dialogo (headless o EDT roto) se responde "no": el camino seguro
		}
		return selection[0];
	}

	/** Corre fuera del EDT: version elegida para entrar, o null si cancela. */
	private String versionDialogOnEdt( String worldName, String minecraftVersion, java.util.List<String> candidates )
	{
		final Object[] chosen = {null};
		try
		{
			SwingUtilities.invokeAndWait( () -> chosen[0] = JOptionPane.showInputDialog( frame,
					"Join " + worldName + " (Minecraft " + minecraftVersion + ") with:",
					"Choose game version", JOptionPane.QUESTION_MESSAGE, null,
					candidates.toArray(), candidates.get( 0 ) ) );
		}
		catch( Exception dialogFailure )
		{
			// Sin dialogo, la vanilla exacta es siempre una eleccion valida
			chosen[0] = minecraftVersion;
		}
		return (String) chosen[0];
	}

	private static void copyAddressToClipboard( String address )
	{
		try
		{
			java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
					.setContents( new java.awt.datatransfer.StringSelection( address ), null );
		}
		catch( Exception clipboardFailure )
		{
			// Sin portapapeles no se corta el flujo: la IP tambien esta en COPY IP
		}
	}

	private void loadMostRecentServer()
	{
		List<MinecraftDashboard.ServerEntry> entries = readRecentServers();
		if( entries.isEmpty() )
			return;
		File candidate = new File( entries.get( 0 ).path() );
		if( candidate.isDirectory() && ForgeUtils.hasServerStartupCommand( candidate.toPath() ) )
		{
			serverOpenedDirectory = candidate;
			actualServerPort = ForgeUtils.getServerPort( candidate.toPath() );
			playerPresence.reset( ForgeUtils.getMaxPlayers( candidate.toPath() ) );
			dashboardPhase = Phase.OFFLINE;
			dashboardPhaseDetail = "Ready to check the network";
		}
	}

	private void openKnownServer( String path )
	{
		File candidate = new File( path );
		if( !candidate.isDirectory() || !ForgeUtils.hasServerStartupCommand( candidate.toPath() ) )
		{
			showError( "Invalid server folder", "The selected folder does not contain a supported Forge or Fabric server." );
			return;
		}
		if( serverOpenedDirectory != null
				&& !candidate.getAbsoluteFile().equals( serverOpenedDirectory.getAbsoluteFile() )
				&& (serverIsOn || dashboardPhase.isBusy()) )
		{
			showError( "Server switch unavailable", "Wait for the current server operation to finish before opening another server." );
			return;
		}
		serverOpenedDirectory = candidate;
		lastAutomaticBackupAttempt = null;
		actualServerPort = ForgeUtils.getServerPort( candidate.toPath() );
		playerPresence.reset( ForgeUtils.getMaxPlayers( candidate.toPath() ) );
		dashboardPhase = Phase.OFFLINE;
		dashboardPhaseDetail = "Ready to check the network";
		discoveredHost = "—";
		syncState = "NOT CONFIGURED";
		lastSync = "—";
		dashboardError = "";
		rememberRecentServer( candidate );
		openServerOptions( contentPane );
		showDashboardPage( MinecraftDashboard.Page.OVERVIEW );
		appendDashboardActivity( LoaderKind.detect( candidate.toPath() ).displayName() + " server opened: " + candidate.getName() );
		stopBlockActivityWatcher();
		resumeWorldMapWatch();
		refreshBlockActivityWatcher();
	}

	/** Vigilante de bloques del mundo abierto; null si no hay ninguno en marcha. */
	private app.BlockActivityWatcher blockActivityWatcher = null;

	/**
	 * Arranca (o para) el seguimiento de bloques del mundo abierto.
	 *
	 * <p>Va atado al mapa: sin mapa encendido no hay donde pintar los
	 * marcadores, asi que no tiene sentido estar mirando la base del mod.</p>
	 */
	private void refreshBlockActivityWatcher()
	{
		File opened = serverOpenedDirectory;
		Path repository = opened == null ? null : opened.toPath();
		boolean wanted = repository != null && app.WorldMap.isEnabledFor( repository );

		if( !wanted )
		{
			stopBlockActivityWatcher();
			return;
		}
		if( blockActivityWatcher != null && blockActivityWatcher.isRunning() )
			return;
		java.util.Optional<Path> world = app.WorldMap.locateWorld( repository );
		if( world.isEmpty() )
			return;

		blockActivityWatcher = new app.BlockActivityWatcher( repository, world.get(), this::onBlockActivity );
		if( !blockActivityWatcher.detectorInstalled() )
		{
			// Sin el mod no hay nada que leer. No es un error: es que ese mundo aun
			// no lleva el detector de bloques
			blockActivityWatcher = null;
			return;
		}
		blockActivityWatcher.start();
		appendDashboardActivity( "Watching block activity on this world" );
	}

	private void stopBlockActivityWatcher()
	{
		if( blockActivityWatcher != null )
		{
			blockActivityWatcher.stop();
			blockActivityWatcher = null;
		}
	}

	/** Llega desde el hilo del vigilante: a la interfaz solo se entra por el EDT. */
	private void onBlockActivity( java.util.List<app.BlockActivity> activity )
	{
		if( activity.isEmpty() )
			return;
		// Con una racha de minado no se llena la actividad de mil lineas iguales:
		// se enseñan las ultimas y un resumen del resto
		int shown = Math.min( 3, activity.size() );
		for( int index = activity.size() - shown; index < activity.size(); index++ )
			appendDashboardActivity( activity.get( index ).describe() );
		if( activity.size() > shown )
			appendDashboardActivity( "…and " + (activity.size() - shown) + " more block changes" );
	}

	/**
	 * Enciende o apaga el mapa de este server. Al apagarlo se para el render en
	 * marcha: dejarlo trabajando para un mapa que ya nadie quiere ver seria
	 * gastar procesador por nada.
	 */
	private void changeWorldMapEnabled( boolean enabled )
	{
		File opened = serverOpenedDirectory;
		if( opened == null )
			return;
		Path repository = opened.toPath();
		try
		{
			app.WorldMap.setEnabledFor( repository, enabled );
		}
		catch( IOException notSaved )
		{
			app.Log.event( "WORLD_MAP", "No se pudo guardar si el mapa esta activado", notSaved );
		}
		if( !enabled && app.WorldMap.isRenderingFor( repository ) )
			app.WorldMap.stopRendering();
		refreshBlockActivityWatcher();
		refreshWorldMapState();
		appendDashboardActivity( enabled ? "3D map enabled for this server" : "3D map disabled for this server" );
	}

	/**
	 * Vuelve a poner en marcha el mapa de un mundo que ya lo tiene generado, en
	 * modo vigilancia: no rehace nada, se queda mirando los ficheros de region y
	 * redibuja solo lo que cambie. Asi el mapa esta al dia sin que nadie pulse
	 * nada, que es la gracia del tiempo real.
	 */
	private void resumeWorldMapWatch()
	{
		File opened = serverOpenedDirectory;
		if( opened == null )
			return;
		Path repository = opened.toPath();
		// Apagado por defecto: si nadie ha encendido el mapa de este server, no se
		// renderiza nada ni se gasta un byte
		if( !app.WorldMap.isEnabledFor( repository ) || !app.WorldMap.hasBuiltMap( repository )
				|| app.WorldMap.isRenderingFor( repository ) )
		{
			refreshWorldMapState();
			return;
		}
		java.util.Optional<Path> world = app.WorldMap.locateWorld( repository );
		if( world.isEmpty() )
			return;
		// La calidad la manda el mapa que ya hay, no la casilla de la pantalla:
		// mezclar calidades dejaria unas zonas con detalle y otras sin el
		boolean fullDetail = app.WorldMap.wasBuiltWithFullDetail( repository );
		new SwingWorker<Void, Void>()
		{
			@Override
			protected Void doInBackground() throws Exception
			{
				app.WorldMap.startRendering( repository, world.get(), fullDetail, serverIsOn );
				return null;
			}

			@Override
			protected void done()
			{
				// Un mapa que no arranca no puede interrumpir a nadie con un dialogo:
				// se queda apagado y el usuario lo enciende desde la pagina si quiere
				try
				{
					get();
				}
				catch( InterruptedException interrupted )
				{
					Thread.currentThread().interrupt();
				}
				catch( java.util.concurrent.ExecutionException failure )
				{
					app.Log.event( "WORLD_MAP", "No se pudo reanudar la vigilancia del mapa", failure );
				}
				refreshWorldMapState();
			}
		}.execute();
	}

	private void rememberRecentServer( File serverDirectory )
	{
		Path recentServersPath = app.AppPaths.dataFile( "recentServers.properties" );
		Properties properties = new Properties();
		try
		{
			Files.createDirectories( recentServersPath.getParent() );
			if( Files.exists( recentServersPath ) )
			{
				try (FileInputStream input = new FileInputStream( recentServersPath.toFile() ))
				{
					properties.load( input );
				}
			}
			String normalized = serverDirectory.getAbsolutePath().replace( '\\', '/' );
			List<String> paths = new ArrayList<>();
			paths.add( normalized );
			for( String existing : properties.getProperty( "recentServers", "" ).split( "\\|" ) )
			{
				if( !existing.isBlank() && !existing.equals( normalized ) && paths.size() < 12 )
					paths.add( existing );
			}
			properties.setProperty( "recentServers", String.join( "|", paths ) );
			try (FileOutputStream output = new FileOutputStream( recentServersPath.toFile() ))
			{
				properties.store( output, "Updated recent servers" );
			}
		}
		catch( IOException writeFailure )
		{
			app.Log.event( "UI", "La lista de servidores recientes no pudo guardarse", writeFailure );
			showError( "Recent servers", "The recent server list could not be updated." );
		}
	}

	// ---- FASE 9 — Ciclo de vida del servidor: arranque, sync e importacion ---

	private void toggleServerFromDashboard()
	{
		if( serverOpenedDirectory == null )
		{
			showError( "No server selected", "Open or create a Forge server before starting it." );
			showDashboardPage( MinecraftDashboard.Page.SERVERS );
			return;
		}
		if( serverIsOn )
			turnOffServer();
		else
			startServerFromDashboard();
	}

	private void startServerFromDashboard()
	{
		setDashboardPhase( Phase.DISCOVERING, "Checking whether another peer is already hosting" );
		appendDashboardActivity( "Checking the P2P network before start" );
		new Thread( () ->
		{
			String networkDiscoveryResult = NetworkDiscoverClient.surroundDiscoverIOException( networkName, actualServerPort, 3000 );
			if( !"NotFound".equals( networkDiscoveryResult ) )
			{
				discoveredHost = networkDiscoveryResult;
				setDashboardPhase( Phase.REMOTE_HOST, "Another peer is already hosting this world" );
				SwingUtilities.invokeLater( () -> JOptionPane.showMessageDialog( frame,
						"Another peer is already hosting this server at " + networkDiscoveryResult + ".",
						"Remote host active", JOptionPane.INFORMATION_MESSAGE ) );
				return;
			}

			try
			{
				activeHostLockRepo = null;
				if( isGitHubSelected() )
				{
					if( !TokenStore.sessionIsOpened() )
					{
						syncState = "AUTH REQUIRED";
						setDashboardFailure( "Sign into GitHub before starting this protected world." );
						return;
					}
					// El lock de GitHub arbitra el hosting por internet; el discovery UDP de
					// arriba solo ve peers dentro de la misma LAN virtual
					if( GitUtils.hasRemoteOrigin( serverOpenedDirectory.toPath() )
							&& !acquireHostLockForStart( GitUtils.remoteRepoFullName( serverOpenedDirectory.toPath() ) ) )
					{
						return;
					}
					setDashboardPhase( Phase.SYNCING, "Confirming the automatic private GitHub backup" );
					syncState = GitUtils.hasRemoteOrigin( serverOpenedDirectory.toPath() ) ? "PUSHING" : "INITIALIZING";
					GitUtils.PrivateBackupSetupResult setup = GitUtils.configurePrivateBackup( serverOpenedDirectory.toPath(),
							getServerName() );
					if( !setup.success() )
					{
						syncState = "FAILED";
						setDashboardFailure( "Private GitHub backup failed: " + setup.message() );
						return;
					}
					syncState = "UP TO DATE";
					lastSync = setup.alreadyLinked() ? "PUSH CONFIRMED" : "INITIAL PUSH";
					appendDashboardActivity( setup.message() );
					// Todo mundo que este usuario hostea queda suscrito: el escaner lo
					// vigilara aunque manana lo hostee otro peer
					app.WorldSubscriptions.subscribe( quietNickname(),
							GitUtils.remoteRepoFullName( serverOpenedDirectory.toPath() ) );
					// Server recién vinculado: el repo no existía al comprobar el lock arriba
					if( activeHostLockRepo == null
							&& !acquireHostLockForStart( GitUtils.remoteRepoFullName( serverOpenedDirectory.toPath() ) ) )
					{
						return;
					}
				}
				if( isGitHubSelected() && GitUtils.repoExistInPath( serverOpenedDirectory.toPath() ) )
				{
					setDashboardPhase( Phase.SYNCING, "Pulling the latest confirmed world from GitHub" );
					syncState = "PULLING";
					refreshDashboardState();
					// El backup de arriba acaba de dejar el arbol limpio: el pull se salta
					// su status (recorrido completo del mundo) para no pagar dos veces
					if( !TokenStore.sessionIsOpened() || !GitUtils.hasRemoteOrigin( serverOpenedDirectory.toPath() )
							|| !GitUtils.pull( serverOpenedDirectory.toPath(), true ) )
					{
						syncState = "FAILED";
						setDashboardFailure(
								"GitHub synchronization failed. The server was not started to avoid using an outdated world. Try PULL WORLD to repair synchronization." );
						return;
					}
					syncState = "UP TO DATE";
					lastSync = "PULL CONFIRMED";
					appendDashboardActivity( "Latest GitHub world pulled successfully" );
				}

				setDashboardPhase( Phase.STARTING, "Launching the Forge startup script" );
				playerPresence.reset( ForgeUtils.getMaxPlayers( serverOpenedDirectory.toPath() ) );
				serverProcess = ForgeUtils.executeMinecraftServer( serverOpenedDirectory.toPath() );
				if( serverProcess == null )
					throw new IOException( "Minecraft startup command failed" );
				worldSessionDirty = true;

				SwingUtilities.invokeLater( () ->
				{
					serverIsOn = true;
					discoveredHost = "LOCAL PROCESS";
					consoleArea = dashboard.consoleArea();
					if( !consoleArea.getText().isBlank() )
						consoleArea.append( "\n" );
					consoleArea.append( "[endershare] Starting " + getServerName() + "…\n" );
					serverWriter = ForgeUtils.configureServerWriter( serverProcess, serverWriter );
					consoleThread = ForgeUtils.getServerOutputs( serverProcess, consoleArea, this::handleServerOutputLine );
					dashboard.markServerStarted();
					appendDashboardActivity( "Forge process started; waiting for server readiness" );
					setDashboardPhase( Phase.STARTING, "Forge is loading the world; waiting for the Done signal" );
				} );
			}
			catch( Exception startFailure )
			{
				app.Log.event( "SERVER_LIFECYCLE", "El arranque del servidor de Minecraft fallo", startFailure );
				setDashboardFailure( "The Minecraft server could not be started. Check Java and the startup script." );
			}
		}, "endershare-dashboard-start" ).start();
	}

	private void refreshNetworkAsync()
	{
		if( serverOpenedDirectory == null )
		{
			showDashboardPage( MinecraftDashboard.Page.SERVERS );
			return;
		}
		if( !serverIsOn )
			setDashboardPhase( Phase.DISCOVERING, "Scanning the P2P network for an active host" );
		// SCAN manual = quiero datos frescos YA: se invalida tambien la foto del
		// escaner de mundos suscritos y la cache del candado del mundo actual
		lastHostLockCheckMillis = 0;
		if( worldStatusScanner != null )
			worldStatusScanner.refreshNow();
		new Thread( this::checkServerStatus, "endershare-network-scan" ).start();
	}

	/**
	 * Traida manual del mundo desde GitHub. Las condiciones van en cascada porque
	 * un pull sobre un mundo vivo lo pisaria: solo se descarga con el servidor
	 * local parado, sin ningun peer hosteando y con el repo privado ya enlazado.
	 */
	private void synchronizeNow()
	{
		do
		{
			if( serverOpenedDirectory == null )
			{
				showError( "No server selected", "Open a Forge server before pulling its world." );
				showDashboardPage( MinecraftDashboard.Page.SERVERS );
				break;
			}
			boolean worldInUse = serverIsOn || dashboardPhase == Phase.REMOTE_HOST || dashboardPhase.isBusy();
			if( worldInUse )
			{
				showError( "Pull unavailable", "The world can only be pulled while this server is offline and no peer is hosting it." );
				break;
			}
			if( !TokenStore.sessionIsOpened() )
			{
				showError( "GitHub account required", "Sign into GitHub before pulling this world." );
				break;
			}
			Path selectedServer = serverOpenedDirectory.toPath();
			boolean repositoryLinked = GitUtils.repoExistInPath( selectedServer ) && GitUtils.hasRemoteOrigin( selectedServer );
			if( !repositoryLinked )
			{
				showError( "Repository not linked", "Create or clone the private GitHub repository first." );
				break;
			}

			selectGitHubProvider();
			setDashboardPhase( Phase.SYNCING, "Pulling the latest confirmed world from GitHub" );
			syncState = "PULLING";
			appendDashboardActivity( "Manual world pull requested" );
			new Thread( () ->
			{
				if( GitUtils.pull( selectedServer ) )
				{
					syncState = "UP TO DATE";
					lastSync = "JUST NOW";
					appendDashboardActivity( "Latest GitHub world pulled successfully" );
					setDashboardPhase( Phase.OFFLINE, "World is current and safe to start" );
				}
				else if( GitUtils.hasLocalChanges( selectedServer ) )
				{
					// Cambios locales que nunca se respaldaron (tipico: la sesion anterior
					// se cerro a las bravas). El usuario decide: apartarlos a un snapshot
					// local y traer el mundo confirmado, o dejarlo todo como esta
					if( !confirmSnapshotAndTakeRemote() )
					{
						syncState = "LOCAL CHANGES";
						setDashboardPhase( Phase.OFFLINE, "Pull cancelled; local changes were preserved" );
						appendDashboardActivity( "Manual pull cancelled by the user; local changes kept" );
						return;
					}
					setDashboardPhase( Phase.SYNCING, "Saving a local snapshot and downloading the latest world" );
					String snapshotBranch = GitUtils.snapshotLocalChangesAndTakeRemote( selectedServer );
					if( snapshotBranch != null )
					{
						syncState = "UP TO DATE";
						lastSync = "JUST NOW";
						appendDashboardActivity( snapshotBranch.isEmpty()
								? "Latest GitHub world pulled successfully"
								: "Local changes kept in snapshot " + snapshotBranch + "; latest GitHub world pulled" );
						setDashboardPhase( Phase.OFFLINE, "World is current and safe to start" );
					}
					else
					{
						syncState = "FAILED";
						setDashboardFailure( "GitHub synchronization failed. Local changes were preserved." );
					}
				}
				else
				{
					syncState = "FAILED";
					setDashboardFailure( "GitHub synchronization failed. Check your connection and try again." );
				}
			}, "endershare-manual-sync" ).start();
		} while( false );
	}

	/** Corre fuera del EDT: el dialogo se pide con invokeAndWait y se espera la respuesta. */
	private boolean confirmSnapshotAndTakeRemote()
	{
		final int[] selection = {JOptionPane.NO_OPTION};
		try
		{
			SwingUtilities.invokeAndWait( () -> selection[0] = JOptionPane.showConfirmDialog( frame,
					"This world has local changes that were never backed up to GitHub\n"
							+ "(usually from a session that closed without SAVE & CLOSE).\n\n"
							+ "Keep those changes in a local snapshot and download the latest\n"
							+ "confirmed world from GitHub? Nothing is deleted.",
					"Local changes found", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE ) );
		}
		catch( Exception dialogFailure )
		{
			return false;
		}
		return selection[0] == JOptionPane.YES_OPTION;
	}

	/**
	 * Importa un mundo externo sobre el servidor abierto. Cascada de guardas: sin
	 * servidor, con el mundo en uso, con la carpeta mal configurada o sin
	 * confirmacion explicita del usuario no se toca nada del disco.
	 */
	private void importWorldFromDashboard()
	{
		do
		{
			if( serverOpenedDirectory == null )
			{
				showError( "No server selected", "Open or create the Forge server that will receive the imported world first." );
				showDashboardPage( MinecraftDashboard.Page.SERVERS );
				break;
			}
			boolean worldInUse = serverIsOn || dashboardPhase == Phase.REMOTE_HOST || dashboardPhase.isBusy();
			if( worldInUse )
			{
				showError( "Import unavailable", "Stop the local server and wait until no peer is hosting this world before importing." );
				break;
			}

			JFileChooser chooser = new JFileChooser();
			chooser.setDialogTitle( "Select a Minecraft world folder or ZIP" );
			chooser.setFileSelectionMode( JFileChooser.FILES_AND_DIRECTORIES );
			chooser.setAcceptAllFileFilterUsed( false );
			chooser.setFileFilter( new FileNameExtensionFilter( "Minecraft world folder or ZIP (*.zip)", "zip" ) );
			if( chooser.showOpenDialog( frame ) != JFileChooser.APPROVE_OPTION )
				break;

			File selectedServer = serverOpenedDirectory;
			Path target;
			try
			{
				target = WorldImportService.configuredWorldDirectory( selectedServer.toPath() );
			}
			catch( IOException invalidServer )
			{
				showError( "Invalid server configuration", invalidServer.getMessage() );
				break;
			}
			Path source = chooser.getSelectedFile().toPath();
			// El mundo anterior no se borra, se aparta: hay que decirlo antes de aceptar
			int confirmation = JOptionPane.showConfirmDialog( frame,
					"Import this world into:\n" + target + "\n\n"
							+ "If a world already exists, it will be moved intact to world-import-backups. Continue?",
					"Import Minecraft world", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE );
			if( confirmation != JOptionPane.YES_OPTION )
				break;

			importWorldInBackground( source, selectedServer );
		} while( false );
	}

	/** Importacion propiamente dicha, fuera del EDT: copia ficheros y puede tardar. */
	private void importWorldInBackground( Path source, File selectedServer )
	{
		setDashboardPhase( Phase.IMPORTING, "Validating and importing the selected world" );
		appendDashboardActivity( "Importing world from " + source.getFileName() );
		new Thread( () ->
		{
			WorldImportService.ImportResult result = WorldImportService.importWorld( source, selectedServer.toPath() );
			if( result.success() )
			{
				worldSessionDirty = true;
				if( GitUtils.repoExistInPath( selectedServer.toPath() ) )
					syncState = "LOCAL CHANGES";
				appendDashboardActivity( "World import completed; previous world preserved" );
				setDashboardPhase( Phase.OFFLINE, "Imported world is ready for a safe test start" );
				SwingUtilities.invokeLater( () -> JOptionPane.showMessageDialog( frame, result.message(),
						"World imported", JOptionPane.INFORMATION_MESSAGE ) );
			}
			else
			{
				setDashboardFailure( "World import failed: " + result.message() );
				SwingUtilities.invokeLater( () -> JOptionPane.showMessageDialog( frame, result.message(),
						"World import failed", JOptionPane.ERROR_MESSAGE ) );
			}
		}, "endershare-world-import" ).start();
	}

	private void openModsFolderFromDashboard()
	{
		if( serverOpenedDirectory == null )
			return;
		Path mods = serverOpenedDirectory.toPath().resolve( "mods" );
		if( !ZipUtils.existsDirectory( mods ) )
			ZipUtils.createDirectory( mods );
		ForgeUtils.openModsFolder( serverOpenedDirectory.toPath() );
	}

	private void openSelectedServerFolder()
	{
		if( serverOpenedDirectory == null )
			return;
		try
		{
			if( Desktop.isDesktopSupported() )
				Desktop.getDesktop().open( serverOpenedDirectory );
			else
				showError( "Open folder", "Opening folders is not supported on this desktop." );
		}
		catch( IOException openFailure )
		{
			app.Log.event( "UI", "La carpeta del servidor no pudo abrirse en el escritorio", openFailure );
			showError( "Open folder", "The selected server folder could not be opened." );
		}
	}

	/**
	 * Guarda los ajustes locales del servidor abierto. Son ficheros del mundo
	 * (server.properties, user_jvm_args.txt): escribirlos con el mundo vivo los
	 * dejaria descuadrados respecto al proceso en marcha, de ahi las guardas.
	 */
	private void saveServerSettingsFromDashboard( MinecraftDashboard.SettingsDraft settings )
	{
		do
		{
			if( serverOpenedDirectory == null )
			{
				dashboard.showSettingsResult( false, "Open a server before editing its settings." );
				break;
			}
			boolean worldInUse = serverIsOn || dashboardPhase == Phase.REMOTE_HOST || dashboardPhase.isBusy();
			if( worldInUse )
			{
				dashboard.showSettingsResult( false, "Stop every host before changing server settings." );
				break;
			}
			try
			{
				Path server = serverOpenedDirectory.toPath();
				ForgeUtils.setServerRAMAlloc( server, settings.ram() );
				ForgeUtils.setServerPortChecked( server, settings.port() );
				ForgeUtils.setMaxPlayers( server, settings.maxPlayers() );
				ForgeUtils.setNetworkNameChecked( settings.networkName() );
				networkName = settings.networkName();
				actualServerPort = settings.port();
				playerPresence.reset( settings.maxPlayers() );
				applyPublicUrlToggle( settings.publicUrl() );
				dashboard.showSettingsResult( true, "Saved locally · applies on the next start" );
				appendDashboardActivity( "Local server settings updated" );
				refreshDashboardState();
				refreshNetworkAsync();
			}
			catch( Exception saveFailure )
			{
				// "Ram exceeded" es el codigo que lanza ForgeUtils; se traduce a un mensaje
				// que explique al usuario que puede hacer al respecto
				boolean ramExceeded = "Ram exceeded".equalsIgnoreCase( saveFailure.getMessage() );
				String message = ramExceeded
						? "The requested RAM exceeds this machine's installed memory."
						: saveFailure.getMessage();
				dashboard.showSettingsResult( false, message == null ? "Settings could not be saved." : message );
			}
		} while( false );
	}

	private void createRepositoryFromDashboard()
	{
		lastAutomaticBackupAttempt = null;
		configurePrivateBackupAsync( true );
	}

	/**
	 * Lanza el alta unica del repositorio privado sin bloquear Swing. Devuelve si
	 * hay un alta en marcha, para que quien llame sepa que no debe encadenar otra
	 * operacion de red encima.
	 *
	 * Salida unica: la cascada de guardas descarta, por este orden, que no haya
	 * nada que respaldar, que ya este respaldado, que el mundo este en uso, que
	 * haya otra operacion ocupando el dashboard, que sea un reintento automatico
	 * ya fallado y, por ultimo, que otro hilo se haya adelantado.
	 */
	private boolean configurePrivateBackupAsync( boolean userRequested )
	{
		boolean result = false;
		do
		{
			if( serverOpenedDirectory == null || !TokenStore.sessionIsOpened() || !isGitHubSelected() )
				break;

			Path selectedServer = serverOpenedDirectory.toPath().toAbsolutePath().normalize();
			boolean alreadyLinked = GitUtils.repoExistInPath( selectedServer ) && GitUtils.hasRemoteOrigin( selectedServer );
			if( alreadyLinked )
				break;

			if( serverIsOn || dashboardPhase == Phase.REMOTE_HOST )
			{
				// Solo se avisa si lo pidio el usuario: el intento automatico es silencioso
				if( userRequested )
					showError( "Backup unavailable", "Stop every host before creating the initial private backup." );
				break;
			}
			if( dashboardPhase.isBusy() && !privateBackupSetupInProgress.get() )
				break;
			// El intento automatico no se repite sobre la misma carpeta: si fallo una vez,
			// insistir en cada refresco solo genera llamadas a GitHub en bucle
			if( !userRequested && selectedServer.equals( lastAutomaticBackupAttempt ) )
				break;
			if( !privateBackupSetupInProgress.compareAndSet( false, true ) )
			{
				// Otro hilo ya lo esta montando: hay alta en curso, aunque no la nuestra
				result = true;
				break;
			}

			lastAutomaticBackupAttempt = selectedServer;
			selectGitHubProvider();
			syncState = "INITIALIZING";
			setDashboardPhase( Phase.SYNCING, "Creating the automatic private GitHub backup" );
			appendDashboardActivity( "Preparing automatic private GitHub backup" );
			String serverName = selectedServer.getFileName().toString();
			startPrivateBackupThread( selectedServer, serverName );
			result = true;
		} while( false );
		return result;
	}

	/** Alta del repositorio privado en su propio hilo: crea el repo y hace el push inicial. */
	private void startPrivateBackupThread( Path selectedServer, String serverName )
	{
		new Thread( () ->
		{
			GitUtils.PrivateBackupSetupResult result = GitUtils.configurePrivateBackup( selectedServer, serverName );
			privateBackupSetupInProgress.set( false );
			if( result.success() )
			{
				syncState = "UP TO DATE";
				lastSync = result.alreadyLinked() ? "LINK VERIFIED" : "INITIAL PUSH";
				appendDashboardActivity( result.message() );
				setDashboardPhase( Phase.OFFLINE, "World is protected by a private GitHub repository" );
				SwingUtilities.invokeLater( () ->
				{
					if( addHostingUserBtn != null )
						addHostingUserBtn.setVisible( true );
				} );
				refreshNetworkAsync();
			}
			else
			{
				syncState = "FAILED";
				setDashboardFailure( "Automatic private backup failed: " + result.message() );
				SwingUtilities.invokeLater( () -> JOptionPane.showMessageDialog( frame,
						result.message() + "\n\nThe local world is safe. Use RETRY PRIVATE BACKUP after correcting the problem.",
						"Private GitHub backup needs attention", JOptionPane.ERROR_MESSAGE ) );
			}
		}, "endershare-private-backup-setup" ).start();
	}

	private void selectGitHubProvider()
	{
		if( "GitHub".equals( cloudProviderInUse ) )
			return;
		cloudProviderInUse = "GitHub";
		ZipUtils.createOrModiFyPropertiesFile( "cloudProviderInUse", cloudProviderInUse, CLOUD_PROVIDER_IN_USE_PATH );
	}

	private void setDashboardFailure( String message )
	{
		dashboardError = message;
		dashboardPhase = Phase.ERROR;
		dashboardPhaseDetail = message;
		refreshDashboardState();
		appendDashboardActivity( "ERROR · " + message );
	}

	private void showError( String title, String message )
	{
		SwingUtilities.invokeLater( () -> JOptionPane.showMessageDialog( frame, message, title, JOptionPane.ERROR_MESSAGE ) );
	}

	private String activityLine( String message )
	{
		return java.time.LocalTime.now().withNano( 0 ) + "  " + message;
	}

	/** Mantiene las escrituras del registro de actividad en el EDT: las llaman hilos de fondo. */
	public void appendDashboardActivity( String message )
	{
		if( dashboard == null )
			return;
		Runnable update = () -> dashboard.appendActivity( activityLine( message ) );
		if( SwingUtilities.isEventDispatchThread() )
			update.run();
		else
			SwingUtilities.invokeLater( update );
	}

	private final java.util.concurrent.ExecutorService remoteRosterRefreshExecutor = java.util.concurrent.Executors
			.newSingleThreadExecutor( runnable ->
			{
				Thread worker = new Thread( runnable, "endershare-remote-roster-refresh" );
				worker.setDaemon( true );
				return worker;
			} );
	private final java.util.concurrent.atomic.AtomicBoolean remoteRosterRefreshRunning = new java.util.concurrent.atomic.AtomicBoolean(
			false );

	private void configurePlayerPolling()
	{
		playerRefreshTimer = new javax.swing.Timer( 10_000, event ->
		{
			if( serverIsOn && dashboardPhase == Phase.ONLINE )
				requestPlayerList();
			else if( dashboardPhase == Phase.REMOTE_HOST )
			{
				// Un solo worker reutilizado y sin apilar ticks si la red va lenta
				if( remoteRosterRefreshRunning.compareAndSet( false, true ) )
				{
					remoteRosterRefreshExecutor.submit( () ->
					{
						try
						{
							checkServerStatus();
						}
						finally
						{
							remoteRosterRefreshRunning.set( false );
						}
					} );
				}
			}
		} );
		playerRefreshTimer.setInitialDelay( 2_000 );
		playerRefreshTimer.start();
	}

	private void handleServerOutputLine( String line )
	{
		if( playerPresence.acceptLine( line ) )
			refreshDashboardState();
		if( line != null && line.contains( "Done" ) )
			requestPlayerList();
	}

	private void requestPlayerList()
	{
		if( serverIsOn && serverProcess != null && serverWriter != null )
		{
			ForgeUtils.sendCommand( "list", serverProcess, serverWriter );
		}
	}

	/**
	 * Carga util compacta que se devuelve a los peers durante el descubrimiento. El
	 * formato es aditivo (;CLAVE=valor) para que una version vieja del cliente
	 * ignore lo que no conoce en vez de romperse, y la lista se capa a 80 nombres
	 * porque va en un unico datagrama UDP.
	 */
	public String playerDiscoveryPayload()
	{
		PlayerPresenceTracker.Snapshot presence = playerPresence.snapshot();
		String players = presence.players().stream().limit( 80 ).collect( java.util.stream.Collectors.joining( "," ) );
		return ";ONLINE=" + presence.onlineCount() + ";MAX=" + presence.maxPlayers() + ";PLAYERS=" + players;
	}

	private void saveAndClose()
	{
		if( privateBackupSetupInProgress.get() )
		{
			JOptionPane.showMessageDialog( frame,
					"The initial private backup is still running. Wait for GitHub confirmation before closing.",
					"Backup in progress", JOptionPane.INFORMATION_MESSAGE );
			return;
		}
		if( !closeInProgress.compareAndSet( false, true ) )
			return;
		frame.setCursor( Cursor.getPredefinedCursor( Cursor.WAIT_CURSOR ) );
		Process processToStop = serverIsOn ? serverProcess : null;
		if( processToStop != null )
		{
			setDashboardPhase( Phase.STOPPING, "Waiting for Forge to save before closing the application" );
			appendDashboardActivity( "Application close requested; saving the active world" );
			// Mismo desmontaje que el boton STOP: sin esto la X dejaba el heartbeat
			// y el tunel vivos y el lock colgado hasta caducar
			stopHostLockHeartbeat();
			stopPlayitTunnel();
			GitUtils.serverAutoSaveIsActive = false;
			ForgeUtils.sendCommand( "/stop", serverProcess, serverWriter );
		}

		new Thread( () ->
		{
			try
			{
				if( processToStop != null )
				{
					processToStop.waitFor();
					serverIsOn = false;
				}
			}
			catch( InterruptedException interrupted )
			{
				Thread.currentThread().interrupt();
				cancelCloseAfterBackupFailure( "The Forge stop operation was interrupted." );
				return;
			}

			// Sin server arrancado y con el repo limpio no hay nada que salvar:
			// cerrar la app debe ser instantaneo, no un ciclo de backup vacio.
			// El status de JGit recorre el mundo ENTERO (minutos en discos lentos):
			// solo se consulta si esta sesion llego a tocar el mundo
			boolean somethingToSave = processToStop != null
					|| (worldSessionDirty && serverOpenedDirectory != null && worldHasUnsavedChanges());
			GitUtils.PrivateBackupSetupResult backup = null;
			if( somethingToSave && serverOpenedDirectory != null && isGitHubSelected() )
			{
				setDashboardPhase( Phase.SAVING, "Creating verified GitHub backup batches before exit" );
				if( TokenStore.sessionIsOpened() )
				{
					backup = GitUtils.configurePrivateBackup( serverOpenedDirectory.toPath(), getServerName() );
				}
				else
				{
					backup = new GitUtils.PrivateBackupSetupResult( false, false, false,
							"The GitHub session is not available. Sign in again to protect this world." );
				}
			}

			if( backup != null && !backup.success() )
			{
				String failureMessage = backup.message();
				boolean exitAnyway = confirmExitWithoutBackup( failureMessage );
				if( !exitAnyway )
				{
					cancelCloseAfterBackupFailure( failureMessage );
					return;
				}
			}
			else if( backup != null )
			{
				syncState = "UP TO DATE";
				lastSync = "PUSH CONFIRMED";
				appendDashboardActivity( backup.message() );
			}

			String lockRepo = activeHostLockRepo;
			if( processToStop != null && lockRepo != null )
			{
				if( HostLock.release( lockRepo ) )
					appendDashboardActivity( "GitHub host lock released before exit" );
				jgit.WorldEvents.publish( lockRepo, "host_stopped" );
				activeHostLockRepo = null;
			}

			SwingUtilities.invokeLater( () ->
			{
				// Si el cierre viene de una actualizacion, el instalador se lanza como
				// proceso independiente justo antes de morir: sobrevive al exit y para
				// cuando copia ficheros la app ya no bloquea nada
				java.nio.file.Path installerToLaunch = pendingInstallerToLaunch;
				if( installerToLaunch != null )
					app.SelfUpdater.launchInstaller( installerToLaunch );
				frame.dispose();
				System.exit( 0 );
			} );
		}, "endershare-save-and-close" ).start();
	}

	private volatile java.nio.file.Path pendingInstallerToLaunch = null;

	/**
	 * true si ESTA sesion arranco el servidor o importo un mundo: solo entonces el
	 * cierre paga el status completo del repo. Restos de sesiones anteriores se
	 * resuelven por el flujo del mundo (START hace backup, PULL ofrece rescate).
	 */
	private volatile boolean worldSessionDirty = false;

	/** El cierre solo hace backup si hay algo que salvar; ante la duda, se salva. */
	private boolean worldHasUnsavedChanges()
	{
		boolean changed = true;
		try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open( serverOpenedDirectory ))
		{
			changed = !git.status().call().isClean();
		}
		catch( Exception statusFailure )
		{
			// Si no se puede saber, mejor un backup de mas que datos perdidos
			changed = true;
		}
		return changed;
	}

	private boolean confirmExitWithoutBackup( String failureMessage )
	{
		final int[] selection = {0};
		try
		{
			SwingUtilities.invokeAndWait( () -> selection[0] = JOptionPane.showOptionDialog( frame,
					"The local world was saved, but the private GitHub backup was not confirmed.\n\n"
							+ failureMessage + "\n\nKeep the app open to retry?",
					"Backup needs attention",
					JOptionPane.DEFAULT_OPTION,
					JOptionPane.ERROR_MESSAGE,
					null,
					new Object[]{"KEEP APP OPEN", "EXIT ANYWAY"},
					"KEEP APP OPEN" ) );
		}
		catch( Exception dialogFailure )
		{
			return false;
		}
		return selection[0] == 1;
	}

	private void cancelCloseAfterBackupFailure( String message )
	{
		closeInProgress.set( false );
		syncState = "FAILED";
		lastSync = "PUSH FAILED";
		serverProcess = null;
		serverWriter = null;
		serverIsOn = false;
		if( consoleThread != null )
			consoleThread.interrupt();
		if( responder != null )
			responder.closeListeningSocket();
		playerPresence.reset( serverOpenedDirectory == null ? 20 : ForgeUtils.getMaxPlayers( serverOpenedDirectory.toPath() ) );
		discoveredHost = "—";
		setDashboardFailure( "The world is safe locally, but GitHub needs attention: " + message );
		SwingUtilities.invokeLater( () ->
		{
			frame.setCursor( Cursor.getDefaultCursor() );
			if( dashboard != null )
				dashboard.markServerStopped();
		} );
	}

	private void serverConfigsFrame( JPanel fatherFrame )
	{
		JDialog configDialog = new JDialog( frame, "Server Configurations" );
		configDialog.getContentPane().setLayout( new BorderLayout() );
		configDialog.setResizable( false );
		int configDialogWidht = 340;
		int configDialogHeight = 420;
		configDialog.setSize( configDialogWidht, configDialogHeight );
		configDialog.setLocationRelativeTo( fatherFrame );
		configDialog.setDefaultCloseOperation( JFrame.DISPOSE_ON_CLOSE );

		// Los dos arrays van emparejados por indice: texto visible e intervalo en
		// segundos. El de enteros ademas esta ordenado, que es lo que permite el
		// binarySearch de abajo para recuperar la seleccion guardada
		String[] autoSaveIntervalsTexts = {"Off", "5 mins", "10 mins", "30 mins", "1 h", "2 h"};
		int[] autoSaveIntervalsInts = {0, 5 * 60, 10 * 60, 30 * 60, 1 * 60 * 60, 2 * 60 * 60};

		JPanel contentPane = new JPanel( new GridLayout( 10, 1 ) );
		JPanel buttonsPane = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );
		JScrollPane scroll = new JScrollPane( contentPane );
		scroll.setPreferredSize( new Dimension( 340, 340 ) );
		scroll.setVerticalScrollBarPolicy( JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED );
		scroll.setHorizontalScrollBarPolicy( JScrollPane.HORIZONTAL_SCROLLBAR_NEVER );
		JLabel networkIDLabel = new JLabel( "Nombre de la red" );
		JTextField networkIDInput = new JTextField();
		JLabel serverPortLabel = new JLabel( "Server port" );
		JTextField serverPortInput = new JTextField();
		JLabel serverRamAllocLabel = new JLabel( "RAM (GB or MB)" );
		JTextField serverRamAllocInput = new JTextField();

		JLabel autoSaveIntervalLabel = new JLabel( "Intervalo del autoguardado" );
		JComboBox<String> autoSaveIntervalSelect = new JComboBox<String>( autoSaveIntervalsTexts );

		int savedIntervalIndex = Arrays.binarySearch( autoSaveIntervalsInts, GitUtils.getSavedAutoSaveInteval() );
		autoSaveIntervalSelect.setSelectedIndex( savedIntervalIndex >= 0 ? savedIntervalIndex : 2 /* 10 mins */ );
		JButton saveBtn = new JButton( "Save" );

		contentPane.setBorder( BorderFactory.createEmptyBorder( 20, 20, 20, 20 ) );
		scroll.setBorder( null );
		networkIDInput.setText( ForgeUtils.getNetworkName() );
		serverPortInput.setText( ForgeUtils.getServerPort( serverOpenedDirectory.toPath() ) + "" );
		serverRamAllocInput.setText( ForgeUtils.getServerRAMAlloc( serverOpenedDirectory.toPath() ).replaceAll( "[-Xmx|G|M]", "" ) );

		contentPane.add( networkIDLabel );
		contentPane.add( networkIDInput );
		contentPane.add( serverPortLabel );
		contentPane.add( serverPortInput );
		contentPane.add( serverRamAllocLabel );
		contentPane.add( serverRamAllocInput );
		contentPane.add( autoSaveIntervalLabel );
		contentPane.add( autoSaveIntervalSelect );
		buttonsPane.add( saveBtn );
		configDialog.getContentPane().add( scroll, BorderLayout.NORTH );
		configDialog.getContentPane().add( buttonsPane, BorderLayout.SOUTH );

		configDialog.setVisible( true );

		saveBtn.addActionListener( save ->
		{
			if( !(ForgeUtils.getNetworkName().equals( networkIDInput.getText() )) )
			{
				ForgeUtils.setNetworkName( networkIDInput.getText() );
				networkName = networkIDInput.getText();
			}
			if( !((ForgeUtils.getServerPort( serverOpenedDirectory.toPath() ) + "").equals( serverPortInput.getText() )) )
			{
				ForgeUtils.setServerPort( serverOpenedDirectory.toPath(), Integer.parseInt( serverPortInput.getText() ) );
				actualServerPort = Integer.parseInt( serverPortInput.getText() );
			}
			if( !((ForgeUtils.getServerRAMAlloc( serverOpenedDirectory.toPath() ) + "").replaceAll( "[-Xmx|G|M]", "" )
					.equals( serverRamAllocInput.getText().replaceAll( "[-Xmx|G|M]", "" ) )) )
			{
				Pattern pattern = Pattern.compile( "^[0-9]*$" );
				Matcher matcher = pattern.matcher( serverRamAllocInput.getText().replaceAll( "[-Xmx|G|M]", "" ) );
				if( matcher.find() )
				{
					try
					{
						ForgeUtils.setServerRAMAlloc( serverOpenedDirectory.toPath(),
								Integer.parseInt( serverRamAllocInput.getText().replaceAll( "[-Xmx|G|M]", "" ) ) );
					}
					catch( Exception ramFailure )
					{
						// "Ram exceeded" es esperable y se avisa en la propia etiqueta; lo demas
						// es un fallo real de escritura y se registra con traza
						if( "Ram exceeded".equalsIgnoreCase( ramFailure.getMessage() ) )
							serverRamAllocLabel
									.setText( "<html>RAM (GB or MB) <span style='color:#fa4545'>Memoria libre insuficiente</span></html>" );
						else
							app.Log.event( "UI", "La RAM asignada no pudo guardarse", ramFailure );
						return;
					}
				}
			}
			int selectedAutosaveInteval = autoSaveIntervalsInts[autoSaveIntervalSelect.getSelectedIndex()];
			if( GitUtils.getSavedAutoSaveInteval() != selectedAutosaveInteval )
			{
				GitUtils.setAutoSaveInterval( selectedAutosaveInteval );
			}
			configDialog.dispose();
			actualServerPort = ForgeUtils.getServerPort( serverOpenedDirectory.toPath() );
			networkName = ForgeUtils.getNetworkName();
			refreshDashboardState();
			refreshNetworkAsync();
		} );
	}

	public static void checkIfExistsDataFolder()
	{
		try
		{
			Files.createDirectories( app.AppPaths.data() );
			// Probar escritura real por si el home estuviera restringido
			Path probe = app.AppPaths.dataFile( ".write-probe" );
			Files.writeString( probe, "ok" );
			Files.deleteIfExists( probe );
		}
		catch( IOException cannotWrite )
		{
			JOptionPane.showMessageDialog( null,
					"Endershare cannot write its data folder:\n"
							+ app.AppPaths.data() + "\n\n"
							+ "Check the permissions of your user home directory.",
					"Folder not writable", JOptionPane.ERROR_MESSAGE );
		}
	}

	private void recentServerListGenerator()
	{
		recentServersMenu.removeAll();
		File file = app.AppPaths.dataFile( "recentServers.properties" ).toFile();
		Properties props = new Properties();
		try (FileInputStream in = new FileInputStream( file ))
		{
			props.load( in );
			if( props.containsKey( "recentServers" ) )
			{
				for( String serverDirectory : props.getProperty( "recentServers" ).split( "\\|" ) )
				{
					JMenuItem item = new JMenuItem( serverDirectory );
					item.addActionListener( itm -> openKnownServer( serverDirectory ) );
					recentServersMenu.add( item );
				}
			}
			else
			{
				recentServersMenu.add( new JMenuItem( "No recent files opened..." ) );
				return;
			}
		}
		catch( IOException readFailure )
		{
			app.Log.event( "UI", "El fichero de servidores recientes no pudo leerse", readFailure );
			JOptionPane.showMessageDialog( null, "File not found or inaccessible", "Error", JOptionPane.ERROR_MESSAGE );
		}
		recentServersMenu.revalidate();
		recentServersMenu.repaint();
	}

	/**
	 * Ultimo tramo de la ruta del servidor abierto. Se separa a mano y no con
	 * File.getName() porque el separador depende de la maquina donde se guardo la
	 * ruta, no de la que la esta leyendo.
	 */
	public static String getServerName()
	{
		String result = "No server";
		do
		{
			if( serverOpenedDirectory == null )
				break;
			String directoryPath = serverOpenedDirectory.toString();
			String separator = directoryPath.contains( "\\" ) ? "\\" : "/";
			int lastSeparator = directoryPath.lastIndexOf( separator );
			result = directoryPath.substring( lastSeparator + 1, directoryPath.length() );
		} while( false );
		return result;
	}

	public static boolean isGitHubSelected()
	{
		return "GitHub".equals( cloudProviderInUse );
	}

	private void radioBtnListener( JMenu cloudMenu, JMenu saveBackupsToCloudMenu, JRadioButtonMenuItem radioButton )
	{
		SwingUtilities.invokeLater( () ->
		{
			cloudProviderInUse = radioButton.getText().replaceAll( " ", "" );
			app.Log.event( "UI", "Proveedor de nube seleccionado: " + cloudProviderInUse );
			if( !cloudProviderInUse.equals( "GitHub" ) )
			{
				if( cloudProvider == null || !cloudProvider.isSessionOpened() )
					cloudInUseReminderMenuText
							.setText( cloudInUseReminderText[0].formatted( cloudInUseReminderText[3].formatted( cloudProviderInUse ) ) );
				else
					cloudInUseReminderMenuText
							.setText( cloudInUseReminderText[0].formatted( cloudInUseReminderText[1] + cloudProviderInUse ) );
			}
			else
			{
				if( !TokenStore.sessionIsOpened() )
					cloudInUseReminderMenuText
							.setText( cloudInUseReminderText[0].formatted( cloudInUseReminderText[3].formatted( cloudProviderInUse ) ) );
				else
					cloudInUseReminderMenuText
							.setText( cloudInUseReminderText[0].formatted( cloudInUseReminderText[1] + cloudProviderInUse ) );
			}
			ZipUtils.createOrModiFyPropertiesFile( "cloudProviderInUse", cloudProviderInUse, CLOUD_PROVIDER_IN_USE_PATH );
			cloudMenu.doClick();
			saveBackupsToCloudMenu.doClick();
			openServerOptions( contentPane );
		} );
	}

	public void turnOffServer()
	{
		if( !serverIsOn || serverProcess == null )
			return;
		frame.setCursor( Cursor.getPredefinedCursor( Cursor.WAIT_CURSOR ) );
		setDashboardPhase( Phase.STOPPING, "Waiting for Forge to save and close the world" );
		appendDashboardActivity( "Stop requested; waiting for the Forge process" );
		stopHostLockHeartbeat();
		stopPlayitTunnel();
		GitUtils.stopAutoSaveAndWait();
		ForgeUtils.sendCommand( "/stop", serverProcess, serverWriter );
		new Thread( () ->
		{
			try
			{
				serverProcess.waitFor();
			}
			catch( InterruptedException interrupted )
			{
				// Se restaura la marca de interrupcion: este hilo no es el dueño de la
				// politica de cancelacion y quien lo lance debe poder verla
				Thread.currentThread().interrupt();
				app.Log.event( "SERVER_LIFECYCLE", "La espera al cierre de Forge fue interrumpida", interrupted );
				setDashboardFailure( "The server stop operation was interrupted." );
				return;
			}
			setDashboardPhase( Phase.SAVING, "Pushing the stopped world to the selected backup provider" );
			boolean gitBackupSucceeded = true;
			String gitBackupMessage = "World saved locally.";
			if( isGitHubSelected() )
			{
				GitUtils.PrivateBackupSetupResult backup = TokenStore.sessionIsOpened()
						? GitUtils.configurePrivateBackup( serverOpenedDirectory.toPath(), getServerName() )
						: new GitUtils.PrivateBackupSetupResult( false, false, false,
								"The GitHub session is invalid. Sign in again and retry the backup." );
				gitBackupSucceeded = backup.success();
				gitBackupMessage = backup.message();
				syncState = gitBackupSucceeded ? "UP TO DATE" : "FAILED";
				lastSync = gitBackupSucceeded ? "JUST NOW" : "PUSH FAILED";
			}
			if( !gitBackupSucceeded )
			{
				String failure = gitBackupMessage;
				SwingUtilities.invokeLater( () -> JOptionPane.showMessageDialog( null,
						"The server stopped safely, but GitHub did not confirm every backup batch.\n\n" + failure
								+ "\n\nThe local changes are preserved; use RETRY PRIVATE BACKUP.",
						"Git backup error", JOptionPane.ERROR_MESSAGE ) );
			}
			if( isGitHubSelected() && TokenStore.sessionIsOpened() && serverOpenedDirectory != null )
			{
				String lockRepo = GitUtils.remoteRepoFullName( serverOpenedDirectory.toPath() );
				if( lockRepo != null )
				{
					// El commit final de guardado invalida el healthcheck: se libera al instante,
					// sin esperar la caducidad del lease
					if( HostLock.release( lockRepo ) )
						appendDashboardActivity( "GitHub host lock released; the world is free to host" );
					else
						appendDashboardActivity( "The host lock could not be released; it will expire on its own within "
								+ (HostLock.DEFAULT_LEASE_SECONDS / 60) + " minutes" );
					jgit.WorldEvents.publish( lockRepo, "host_stopped" );
				}
			}
			activeHostLockRepo = null;
			if( cloudProvider != null && cloudProvider.getProviderName().equals( cloudProviderInUse ) && cloudProvider.isSessionOpened() )
			{
				if( cloudProvider.hasRemoteServerFolder() )
				{
					ZipUtils.createZip( serverOpenedDirectory.toPath(), ZipUtils.BACKUPS_ZIPS_FOLDER );
					cloudProvider.uploadServerBackup( ZipUtils.BACKUPS_ZIPS_FOLDER );
				}
			}

			if( consoleThread != null )
				consoleThread.interrupt();
			serverProcess = null;
			boolean backupSucceeded = gitBackupSucceeded;
			String backupMessage = gitBackupMessage;

			SwingUtilities.invokeLater( () ->
			{
				serverWriter = null;
				serverIsOn = false;
				playerPresence.reset( serverOpenedDirectory == null ? 20 : ForgeUtils.getMaxPlayers( serverOpenedDirectory.toPath() ) );
				if( responder != null )
					responder.closeListeningSocket();
				dashboard.markServerStopped();
				discoveredHost = "—";
				if( backupSucceeded )
				{
					appendDashboardActivity( backupMessage );
					setDashboardPhase( Phase.OFFLINE, "World saved; no active host discovered" );
				}
				else
				{
					setDashboardFailure( "The server stopped safely, but the GitHub backup needs attention." );
				}
				if( backupSucceeded )
					refreshNetworkAsync();
			} );
			SwingUtilities.invokeLater( () -> frame.setCursor( Cursor.getDefaultCursor() ) );

		}, "endershare-dashboard-stop" ).start();
	}

	/**
	 * Arbitra el lock de hosting en GitHub antes de arrancar. Un mundo sin repo
	 * remoto no tiene nada que arbitrar y pasa directo: el descubrimiento UDP ya
	 * cubre ese caso dentro de la LAN virtual.
	 */
	private boolean acquireHostLockForStart( String repoFullName )
	{
		boolean result = true;
		do
		{
			if( repoFullName == null )
				break;

			setDashboardPhase( Phase.DISCOVERING, "Arbitrating the GitHub host lock" );
			HostLock.AcquireResult lock = HostLock.acquire( repoFullName );
			if( lock.acquired() )
			{
				activeHostLockRepo = repoFullName;
				appendDashboardActivity( lock.message() );
				break;
			}

			result = false;
			// Peer legitimo hosteando no es un error: se informa sin teñir de rojo el
			// dashboard. Cualquier otro motivo si es un fallo que hay que resolver
			if( lock.blockedByPeer() )
			{
				setDashboardPhase( Phase.REMOTE_HOST, lock.message() );
				SwingUtilities.invokeLater( () -> JOptionPane.showMessageDialog( frame, lock.message(),
						"Another peer is hosting", JOptionPane.INFORMATION_MESSAGE ) );
			}
			else
			{
				setDashboardFailure( lock.message() );
			}
		} while( false );
		return result;
	}

	// ---- FASE 10 — Servicios de hosting: lock, autoguardado y tunel ----------

	/**
	 * Se dispara desde el gancho "Done" de la consola: heartbeat del host lock mas
	 * el autoguardado del mundo en vivo. Hasta ese momento Forge todavia esta
	 * cargando y no tiene sentido anunciar que este peer es el host.
	 */
	public void startHostServices()
	{
		String repoFullName = activeHostLockRepo;
		if( repoFullName != null )
		{
			stopHostLockHeartbeat();
			hostLockHeartbeatTimer = new java.util.Timer( "endershare-host-lock-heartbeat", true );
			hostLockHeartbeatTimer.scheduleAtFixedRate( new java.util.TimerTask()
			{
				private int consecutiveFailures = 0;
				@Override
				public void run()
				{
					if( !serverIsOn || serverProcess == null || !serverProcess.isAlive() )
					{
						// Forge murió solo: sin server no se sostiene el lease; caducará y otro podrá hostear
						stopHostLockHeartbeat();
						return;
					}
					// Cada latido lleva la foto fresca: tunel, aforo y version, para que
					// los invitados lo vean sin mas canal que el propio candado
					publishHostDetails();
					if( HostLock.heartbeat( repoFullName ) )
					{
						consecutiveFailures = 0;
						appendDashboardActivity( "Host lock heartbeat confirmed on GitHub" );
					}
					else
					{
						consecutiveFailures++;
						appendDashboardActivity( "Host lock heartbeat failed; the lease may expire in "
								+ (HostLock.DEFAULT_LEASE_SECONDS / 60) + " minutes" );
						// 3 fallos seguidos = 15 min sin renovar: el lease ya caducó y otro peer
						// podría arrancar el mismo mundo — esto tiene que verse, no solo loguearse
						if( consecutiveFailures >= 3 )
						{
							setDashboardFailure( "GitHub unreachable: the host lock lease expired. "
									+ "Check your connection — another peer could start this world in parallel." );
						}
					}
				}
			}, HostLock.HEARTBEAT_SECONDS * 1000L, HostLock.HEARTBEAT_SECONDS * 1000L );
		}
		if( GitUtils.autoSaveSecondsInterval > 0 && isGitHubSelected() && TokenStore.sessionIsOpened() )
		{
			GitUtils.activeAutoSave();
		}
		startWorldMapLiveUpdates();
		startPlayitTunnelIfConfigured();
		if( repoFullName != null )
		{
			// Latido inmediato fuera de ciclo: el primero programado tarda 5 minutos
			// y la direccion de conexion debe estar visible para los invitados YA
			new Thread( () ->
			{
				publishHostDetails();
				HostLock.heartbeat( repoFullName );
				jgit.WorldEvents.publish( repoFullName, "host_started" );
			}, "endershare-host-details-publish" ).start();
		}
	}

	/** Cada cuanto se le pide al mundo que baje a disco lo que lleva en memoria. */
	static final int MAP_LIVE_SAVE_SECONDS = 60;
	private java.util.Timer worldMapLiveTimer = null;

	/**
	 * Hace que el mapa se vea vivo mientras se juega.
	 *
	 * <p>El renderizador vigila los ficheros de region, pero Minecraft no los
	 * escribe al momento: se los guarda en memoria y los baja a disco cada varios
	 * minutos. Sin esto, lo que construyes tarda un buen rato en aparecer en el
	 * mapa aunque todo lo demas funcione. Pidiendo un guardado cada minuto, el
	 * mapa va como mucho un minuto por detras de la realidad.</p>
	 *
	 * <p>Solo se pide mientras haya un mapa vigilando: a quien no usa el mapa no
	 * se le cobra ni un guardado de mas.</p>
	 */
	private void startWorldMapLiveUpdates()
	{
		stopWorldMapLiveUpdates();
		worldMapLiveTimer = new java.util.Timer( "endershare-world-map-live", true );
		worldMapLiveTimer.scheduleAtFixedRate( new java.util.TimerTask()
		{
			@Override
			public void run()
			{
				File opened = serverOpenedDirectory;
				if( !serverIsOn || serverProcess == null || !serverProcess.isAlive() || opened == null )
				{
					stopWorldMapLiveUpdates();
					return;
				}
				if( !app.WorldMap.isRenderingFor( opened.toPath() ) )
					return;
				ForgeUtils.sendCommand( "save-all", serverProcess, serverWriter );
			}
		}, MAP_LIVE_SAVE_SECONDS * 1000L, MAP_LIVE_SAVE_SECONDS * 1000L );
	}

	private void stopWorldMapLiveUpdates()
	{
		if( worldMapLiveTimer != null )
		{
			worldMapLiveTimer.cancel();
			worldMapLiveTimer = null;
		}
	}

	/** Construye y publica la foto del host que viaja adjunta al lease del candado. */
	private void publishHostDetails()
	{
		try
		{
			PlayitAgentFile agent = serverOpenedDirectory == null
					? null
					: PlayitAgentFile.load( serverOpenedDirectory.toPath() );
			PlayerPresenceTracker.Snapshot presence = playerPresence.snapshot();
			// El tunel de playit manda; sin tunel se publica la IP publica del host
			// con el puerto del server, que es lo que un peer pega en Minecraft.
			// Quien elige el camino de la IP abre el puerto en su router el mismo
			String tunnel = agent != null && agent.enabled ? agent.tunnel_address : null;
			String address = app.PublicAddress.chooseAddress( tunnel,
					tunnel == null || tunnel.isBlank() ? app.PublicAddress.resolvePublicIp() : null,
					actualServerPort );
			HostLock.publishDetails( new HostLock.HostDetails(
					address,
					presence.onlineCount(),
					presence.maxPlayers(),
					serverOpenedDirectory == null
							? null
							: ForgeUtils.getMinecraftVersion( serverOpenedDirectory.toPath() ) ) );
		}
		catch( Exception detailFailure )
		{
			// La publicacion es cosmetica: un fallo aqui jamas debe frenar el latido
			app.Log.event( "HOST_LOCK", "No se pudo componer la foto del host", detailFailure );
		}
	}

	private static void stopHostLockHeartbeat()
	{
		if( hostLockHeartbeatTimer != null )
		{
			hostLockHeartbeatTimer.cancel();
			hostLockHeartbeatTimer = null;
		}
		// Sin hosting activo no hay foto que publicar en el siguiente lease
		HostLock.clearPublishedDetails();
	}

	/** Levanta el tunel publico opcional de playit.gg si este mundo lo tiene activado. */
	private void startPlayitTunnelIfConfigured()
	{
		if( serverOpenedDirectory == null )
			return;
		PlayitAgentFile agent = PlayitAgentFile.load( serverOpenedDirectory.toPath() );
		if( agent == null || !agent.readyToStart() )
			return;
		stopPlayitTunnel();
		activePlayitTunnel = new PlayitTunnel( agent.secret_key, actualServerPort, this::appendDashboardActivity );
		activePlayitTunnel.start();
		appendDashboardActivity( "Starting the public playit.gg tunnel…" );
	}

	private static void stopPlayitTunnel()
	{
		if( activePlayitTunnel != null )
		{
			activePlayitTunnel.stop();
			activePlayitTunnel = null;
		}
	}

	/** Aplica el interruptor de la pagina de Settings sobre el estado de playit guardado por mundo. */
	private void applyPublicUrlToggle( boolean wanted )
	{
		PlayitAgentFile storedAgent = PlayitAgentFile.load( serverOpenedDirectory.toPath() );
		boolean enabledNow = storedAgent != null && storedAgent.enabled;
		if( wanted )
		{
			// Re-guardar con el toggle ya activo tambien cura un setup a medias
			// (sin secret valido o sin direccion todavia)
			if( !enabledNow || storedAgent.secret_key == null || storedAgent.tunnel_address == null )
			{
				enablePublicUrl( storedAgent );
			}
			return;
		}
		if( !enabledNow )
			return;
		storedAgent.enabled = false;
		try
		{
			storedAgent.save( serverOpenedDirectory.toPath() );
			appendDashboardActivity( "Public URL disabled for this world" );
		}
		catch( IOException failure )
		{
			appendDashboardActivity( "Public URL could not be disabled: " + failure.getMessage() );
		}
		stopPlayitTunnel();
	}

	/**
	 * Activa la URL publica de este mundo en un hilo de fondo: verifica el secreto
	 * guardado, reclama un agente nuevo en el navegador cuando hace falta, resuelve
	 * la direccion fija y lo persiste todo en el fichero compartido del repo.
	 *
	 * Va en segundo plano porque reclamar el agente espera a que una persona
	 * autorice en el navegador, con un margen de hasta diez minutos.
	 */
	private void enablePublicUrl( PlayitAgentFile existingAgent )
	{
		Path serverDirectory = serverOpenedDirectory.toPath();
		new Thread( () ->
		{
			PlayitAgentFile agent = existingAgent != null ? existingAgent : new PlayitAgentFile();
			agent.enabled = true;

			if( agent.secret_key != null && !PlayitTunnel.secretWorks( agent.secret_key ) )
			{
				appendDashboardActivity( "The stored playit key was rejected; requesting a new authorization" );
				agent.secret_key = null;
				agent.tunnel_address = null;
			}

			if( agent.secret_key == null )
			{
				String claimCode = PlayitTunnel.newClaimCode();
				String claimUrl = PlayitTunnel.claimUrl( claimCode );
				SwingUtilities.invokeLater( () -> ForgeUtils.openURL( claimUrl ) );
				appendDashboardActivity( "Authorize the playit.gg tunnel in the opened browser tab (guest works): " + claimUrl );
				PlayitTunnel.ClaimOutcome outcome = PlayitTunnel.claimAgent( claimCode, 10 * 60 );
				if( !outcome.ok() )
				{
					appendDashboardActivity( "playit.gg authorization failed: " + outcome.error() );
					SwingUtilities.invokeLater( MainFrame.this::refreshDashboardState );
					return;
				}
				agent.secret_key = outcome.secretKey();
			}

			try
			{
				agent.tunnel_address = PlayitTunnel.ensureTunnel( agent.secret_key );
			}
			catch( IOException tunnelFailure )
			{
				appendDashboardActivity( "playit authorized, but the tunnel is not ready yet: " + tunnelFailure.getMessage() );
			}
			try
			{
				agent.save( serverDirectory );
			}
			catch( IOException failure )
			{
				appendDashboardActivity( "Public URL could not be saved: " + failure.getMessage() );
				return;
			}
			appendDashboardActivity( agent.tunnel_address != null
					? "Public URL ready for every host of this world: " + agent.tunnel_address
					: "playit authorized; the address will appear when the tunnel starts" );
			if( serverIsOn )
				startPlayitTunnelIfConfigured();
			SwingUtilities.invokeLater( MainFrame.this::refreshDashboardState );
		}, "endershare-playit-claim" ).start();
	}

}
