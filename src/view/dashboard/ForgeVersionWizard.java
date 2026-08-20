package view.dashboard;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Asistente compacto de dos pasos para aprovisionar un servidor: primero
 * loader + version, despues la aceptacion explicita de la EULA de Minecraft.
 *
 * El panel no descarga nada por su cuenta: la carga del catalogo y la del
 * instalador las delega el controlador en workers de fondo, para que Swing
 * nunca se congele mientras la red responde. Aqui solo vive el estado visual.
 */
public final class ForgeVersionWizard extends JPanel
{

	// ---- FASE 1 — Contratos de datos y estado -------------------------------

	/** Builds indexados por version de Minecraft, o comunes a todas (loaders de Fabric). */
	public record VersionCatalog( List<String> minecraftVersions, List<String> forgeVersions, boolean sharedBuilds )
	{
		public VersionCatalog {
			minecraftVersions = minecraftVersions == null ? List.of() : List.copyOf( minecraftVersions );
			forgeVersions = forgeVersions == null ? List.of() : List.copyOf( forgeVersions );
		}

		public VersionCatalog( List<String> minecraftVersions, List<String> forgeVersions )
		{
			this( minecraftVersions, forgeVersions, false );
		}
	}

	public record Selection( String loader, String minecraftVersion, String forgeVersion )
	{
	}

	@FunctionalInterface
	public interface CatalogLoader
	{
		VersionCatalog load( String loader ) throws Exception;
	}

	private static final String MINECRAFT_PLACEHOLDER = "Select Minecraft version";
	private static final String FORGE_PLACEHOLDER = "Select build";

	private final JComboBox<String> loaderSelect = new JComboBox<>( new String[]{"Forge", "Fabric"} );
	private final JComboBox<String> minecraftSelect = new JComboBox<>();
	private final JComboBox<String> forgeSelect = new JComboBox<>();
	private final JLabel statusLabel = DashboardTheme.label( "Loading Forge catalogue…", DashboardTheme.TEXT_MUTED, 11, Font.PLAIN );
	private final JLabel stepLabel = DashboardTheme.eyebrow( "STEP 1 OF 2 · VERSION" );
	private final JProgressBar progress = new JProgressBar();
	private final JButton secondaryButton = new JButton( "VIEW EULA" );
	private final JButton cancelButton = new JButton( "CANCEL" );
	private final JButton primaryButton = new JButton( "INSTALL SERVER" );
	private final Map<String, List<String>> forgeByMinecraft = new LinkedHashMap<>();
	private final Consumer<Selection> installAction;
	private Runnable primaryAction;
	private CatalogLoader catalogLoader;

	// ---- FASE 2 — Construccion de la UI -------------------------------------

	public ForgeVersionWizard( Path destination, Consumer<Selection> installAction, Runnable cancelAction )
	{
		this.installAction = Objects.requireNonNull( installAction, "installAction" );
		Objects.requireNonNull( cancelAction, "cancelAction" );

		setLayout( new BorderLayout() );
		setBackground( DashboardTheme.APP_BACKGROUND );
		setBorder( BorderFactory.createEmptyBorder( 24, 28, 20, 28 ) );
		setPreferredSize( new Dimension( 720, 410 ) );

		add( buildBody( destination ), BorderLayout.CENTER );
		add( buildFooter( cancelAction ), BorderLayout.SOUTH );
		configureSelectors();
		setBusy( true, "Loading available Minecraft and Forge versions…" );
	}

	private JPanel buildBody( Path destination )
	{
		JPanel body = new JPanel();
		body.setOpaque( false );
		body.setLayout( new BoxLayout( body, BoxLayout.Y_AXIS ) );

		stepLabel.setAlignmentX( Component.LEFT_ALIGNMENT );
		JLabel title = DashboardTheme.label( "Create Minecraft server", DashboardTheme.TEXT, 26, Font.PLAIN );
		title.setAlignmentX( Component.LEFT_ALIGNMENT );
		JLabel subtitle = DashboardTheme.label( "Choose a loader and a compatible pair. Installation runs in the background.",
				DashboardTheme.TEXT_MUTED, 11, Font.PLAIN );
		subtitle.setAlignmentX( Component.LEFT_ALIGNMENT );
		body.add( stepLabel );
		body.add( Box.createVerticalStrut( 9 ) );
		body.add( title );
		body.add( Box.createVerticalStrut( 6 ) );
		body.add( subtitle );
		body.add( Box.createVerticalStrut( 20 ) );

		JPanel destinationPanel = new JPanel( new BorderLayout( 14, 0 ) );
		destinationPanel.setBackground( DashboardTheme.PANEL_BACKGROUND );
		destinationPanel.setBorder( DashboardTheme.paddedSectionBorder( 12, 14, 12, 14 ) );
		destinationPanel.add( DashboardTheme.eyebrow( "DESTINATION" ), BorderLayout.WEST );
		// Absoluta y normalizada: el usuario tiene que poder verificar donde va a
		// escribir el instalador antes de aceptar, sin ".." ni rutas relativas
		String destinationPath = destination.toAbsolutePath().normalize().toString();
		JLabel path = DashboardTheme.label( destinationPath, DashboardTheme.TEXT, 11, Font.PLAIN );
		destinationPanel.add( path, BorderLayout.CENTER );
		destinationPanel.setAlignmentX( Component.LEFT_ALIGNMENT );
		destinationPanel.setMaximumSize( new Dimension( Integer.MAX_VALUE, 52 ) );
		body.add( destinationPanel );
		body.add( Box.createVerticalStrut( 14 ) );

		JPanel selectors = new JPanel( new GridBagLayout() );
		selectors.setOpaque( false );
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridy = 0;
		constraints.weightx = 0.34;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.insets = new Insets( 0, 0, 0, 7 );
		selectors.add( field( "SERVER TYPE", loaderSelect, "Forge for mods, Fabric is lighter" ), constraints );
		constraints.gridx = 1;
		constraints.weightx = 0.33;
		constraints.insets = new Insets( 0, 7, 0, 7 );
		selectors.add( field( "MINECRAFT VERSION", minecraftSelect, "Filters compatible builds" ), constraints );
		constraints.gridx = 2;
		constraints.insets = new Insets( 0, 7, 0, 0 );
		selectors.add( field( "LOADER BUILD", forgeSelect, "Forge installer or Fabric loader" ), constraints );
		selectors.setAlignmentX( Component.LEFT_ALIGNMENT );
		selectors.setMaximumSize( new Dimension( Integer.MAX_VALUE, 105 ) );
		body.add( selectors );
		body.add( Box.createVerticalStrut( 16 ) );

		statusLabel.setAlignmentX( Component.LEFT_ALIGNMENT );
		progress.setIndeterminate( true );
		progress.setBorder( BorderFactory.createEmptyBorder() );
		progress.setBackground( DashboardTheme.HAIRLINE );
		progress.setForeground( DashboardTheme.GREEN );
		progress.setMaximumSize( new Dimension( Integer.MAX_VALUE, 2 ) );
		progress.setAlignmentX( Component.LEFT_ALIGNMENT );
		body.add( statusLabel );
		body.add( Box.createVerticalStrut( 9 ) );
		body.add( progress );
		body.add( Box.createVerticalGlue() );
		return body;
	}

	private JPanel field( String label, JComboBox<String> input, String helper )
	{
		JPanel panel = new JPanel();
		panel.setOpaque( false );
		panel.setLayout( new BoxLayout( panel, BoxLayout.Y_AXIS ) );
		JLabel heading = DashboardTheme.eyebrow( label );
		JLabel description = DashboardTheme.label( helper, DashboardTheme.TEXT_DIM, 10, Font.PLAIN );
		heading.setAlignmentX( Component.LEFT_ALIGNMENT );
		input.setAlignmentX( Component.LEFT_ALIGNMENT );
		description.setAlignmentX( Component.LEFT_ALIGNMENT );
		input.setMaximumSize( new Dimension( Integer.MAX_VALUE, 36 ) );
		input.setPrototypeDisplayValue( "1.20.1-47.3.0              " );
		DashboardTheme.styleInput( input );
		panel.add( heading );
		panel.add( Box.createVerticalStrut( 7 ) );
		panel.add( input );
		panel.add( Box.createVerticalStrut( 5 ) );
		panel.add( description );
		return panel;
	}

	private JPanel buildFooter( Runnable cancelAction )
	{
		JPanel footer = new JPanel( new FlowLayout( FlowLayout.RIGHT, 8, 0 ) );
		footer.setOpaque( false );
		footer.setBorder( BorderFactory.createMatteBorder( 1, 0, 0, 0, DashboardTheme.HAIRLINE ) );
		secondaryButton.setVisible( false );
		DashboardTheme.styleButton( secondaryButton, DashboardTheme.ButtonKind.QUIET );
		DashboardTheme.styleButton( cancelButton, DashboardTheme.ButtonKind.SECONDARY );
		DashboardTheme.styleButton( primaryButton, DashboardTheme.ButtonKind.PRIMARY );
		cancelButton.addActionListener( event -> cancelAction.run() );
		primaryButton.addActionListener( event ->
		{
			if( primaryAction != null )
				primaryAction.run();
		} );
		footer.add( secondaryButton );
		footer.add( cancelButton );
		footer.add( primaryButton );
		return footer;
	}

	// ---- FASE 3 — Carga del catalogo ----------------------------------------

	private void configureSelectors()
	{
		loaderSelect.addActionListener( event -> reloadCatalogForSelectedLoader() );
		minecraftSelect.addActionListener( event -> populateForgeVersions() );
		forgeSelect.addActionListener( event -> updateInstallAvailability() );
		primaryAction = () ->
		{
			Selection selection = selection();
			if( selection != null )
				installAction.accept( selection );
		};
	}

	public void loadCatalog( CatalogLoader loader )
	{
		catalogLoader = loader;
		reloadCatalogForSelectedLoader();
	}

	private void reloadCatalogForSelectedLoader()
	{
		if( catalogLoader == null )
			return;
		String loader = selectedLoader();
		setBusy( true, "Loading available Minecraft and " + loader + " versions…" );
		new SwingWorker<VersionCatalog, Void>()
		{
			@Override
			protected VersionCatalog doInBackground() throws Exception
			{
				return catalogLoader.load( loader );
			}

			@Override
			protected void done()
			{
				try
				{
					// Un cambio de loader durante la carga invalida este resultado
					if( loader.equals( selectedLoader() ) )
						applyCatalog( get() );
				}
				catch( Exception failure )
				{
					showError( rootMessage( failure ) );
				}
			}
		}.execute();
	}

	void applyCatalog( VersionCatalog catalog )
	{
		forgeByMinecraft.clear();
		for( String minecraft : catalog.minecraftVersions() )
		{
			if( minecraft != null && !minecraft.startsWith( "Select " ) )
				forgeByMinecraft.putIfAbsent( minecraft, new ArrayList<>() );
		}
		for( String forge : catalog.forgeVersions() )
		{
			if( forge == null )
				continue;
			// Fabric publica un unico juego de loaders valido para cualquier version
			// del juego, asi que se replica entero en vez de intentar emparejarlo
			if( catalog.sharedBuilds() )
			{
				for( List<String> builds : forgeByMinecraft.values() )
					builds.add( forge );
				continue;
			}
			// Forge nombra sus builds "1.20.1-47.3.0": el prefijo antes del guion es la
			// version de Minecraft con la que casa. Sin guion no se puede emparejar
			if( !forge.contains( "-" ) )
				continue;
			String minecraft = forge.substring( 0, forge.indexOf( '-' ) );
			forgeByMinecraft.computeIfAbsent( minecraft, ignored -> new ArrayList<>() ).add( forge );
		}
		// Invertido para que lo mas nuevo quede arriba del desplegable
		List<String> minecraftVersions = new ArrayList<>( new LinkedHashSet<>( forgeByMinecraft.keySet() ) );
		Collections.reverse( minecraftVersions );
		minecraftSelect.removeAllItems();
		minecraftSelect.addItem( MINECRAFT_PLACEHOLDER );
		minecraftVersions.forEach( minecraftSelect::addItem );
		forgeSelect.removeAllItems();
		forgeSelect.addItem( FORGE_PLACEHOLDER );
		setBusy( false, minecraftVersions.size() + " Minecraft releases available" );
		updateInstallAvailability();
	}

	// ---- FASE 4 — Seleccion y disponibilidad del boton ----------------------

	private void populateForgeVersions()
	{
		forgeSelect.removeAllItems();
		forgeSelect.addItem( FORGE_PLACEHOLDER );
		Object selected = minecraftSelect.getSelectedItem();
		boolean knownMinecraftVersion = selected != null && forgeByMinecraft.containsKey( selected.toString() );
		if( knownMinecraftVersion )
		{
			List<String> versions = new ArrayList<>( forgeByMinecraft.get( selected.toString() ) );
			Collections.reverse( versions );
			versions.forEach( forgeSelect::addItem );
			forgeSelect.setEnabled( true );
			statusLabel.setText( versions.size() + " compatible " + selectedLoader() + " builds" );
		}
		else
		{
			// Sin version de Minecraft elegida no hay lista de builds que ofrecer
			forgeSelect.setEnabled( false );
		}
		updateInstallAvailability();
	}

	private void updateInstallAvailability()
	{
		// La barra indeterminada marca que hay una descarga viva: instalar encima
		// dejaria la carpeta a medias
		boolean catalogReady = !progress.isIndeterminate();
		primaryButton.setEnabled( catalogReady && selection() != null );
	}

	/** Par loader/version elegido, o null mientras siga habiendo un placeholder seleccionado. */
	public Selection selection()
	{
		Selection result = null;
		Object minecraft = minecraftSelect.getSelectedItem();
		Object forge = forgeSelect.getSelectedItem();
		boolean placeholderPending = MINECRAFT_PLACEHOLDER.equals( minecraft ) || FORGE_PLACEHOLDER.equals( forge );
		if( minecraft != null && forge != null && !placeholderPending )
			result = new Selection( selectedLoader(), minecraft.toString(), forge.toString() );
		return result;
	}

	public String selectedLoader()
	{
		Object loader = loaderSelect.getSelectedItem();
		return loader == null ? "Forge" : loader.toString();
	}

	// ---- FASE 5 — Estados visibles: ocupado, error y paso de EULA -----------

	public void setBusy( boolean busy, String message )
	{
		loaderSelect.setEnabled( !busy );
		minecraftSelect.setEnabled( !busy && minecraftSelect.getItemCount() > 1 );
		forgeSelect.setEnabled( !busy && minecraftSelect.getSelectedIndex() > 0 );
		cancelButton.setEnabled( !busy );
		progress.setVisible( busy );
		progress.setIndeterminate( busy );
		statusLabel.setForeground( busy ? DashboardTheme.AMBER : DashboardTheme.TEXT_MUTED );
		statusLabel.setText( message );
		primaryButton.setEnabled( !busy && selection() != null );
	}

	public void showError( String message )
	{
		setBusy( false, message == null || message.isBlank() ? "Forge operation failed." : message );
		statusLabel.setForeground( DashboardTheme.RED );
	}

	/**
	 * Segundo paso: los selectores quedan bloqueados porque el servidor ya esta
	 * instalado en disco, y el boton principal pasa a ser la aceptacion explicita
	 * de la EULA. Se reemplazan los listeners del boton secundario en vez de
	 * añadirlos para que no se acumulen si se vuelve a entrar en este paso.
	 */
	public void showEulaStep( Runnable viewEula, Runnable acceptEula )
	{
		stepLabel.setText( "STEP 2 OF 2 · MINECRAFT EULA" );
		loaderSelect.setEnabled( false );
		minecraftSelect.setEnabled( false );
		forgeSelect.setEnabled( false );
		progress.setVisible( false );
		progress.setIndeterminate( false );
		statusLabel.setForeground( DashboardTheme.GREEN );
		statusLabel.setText( selectedLoader() + " is installed. Review and explicitly accept the Minecraft EULA to open it." );
		secondaryButton.setVisible( true );
		for( var listener : secondaryButton.getActionListeners() )
			secondaryButton.removeActionListener( listener );
		secondaryButton.addActionListener( event -> viewEula.run() );
		cancelButton.setEnabled( true );
		primaryButton.setText( "ACCEPT EULA & OPEN" );
		primaryButton.setEnabled( true );
		primaryAction = acceptEula;
		revalidate();
		repaint();
	}

	// ---- FASE 6 — Accesos para los tests y utilidades -----------------------

	JComboBox<String> loaderSelect()
	{
		return loaderSelect;
	}
	JComboBox<String> minecraftSelect()
	{
		return minecraftSelect;
	}
	JComboBox<String> forgeSelect()
	{
		return forgeSelect;
	}
	JButton primaryButton()
	{
		return primaryButton;
	}
	JLabel statusLabel()
	{
		return statusLabel;
	}

	/**
	 * Mensaje de la causa mas profunda: SwingWorker envuelve todo en
	 * ExecutionException, y su getMessage() es el nombre de la clase interna, que
	 * al usuario no le dice nada.
	 */
	private static String rootMessage( Throwable failure )
	{
		Throwable root = failure;
		while( root.getCause() != null )
			root = root.getCause();
		String rootMessage = root.getMessage();
		return rootMessage == null ? "Forge operation failed." : rootMessage;
	}
}
