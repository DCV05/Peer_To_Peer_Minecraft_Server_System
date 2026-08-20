package view;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JTextPane;

import cloud.google.GoogleDriveCloudProvider;
import view.dashboard.DashboardDialogSupport;

/**
 * Dialogos de la integracion con Google Drive: invitar a otro host, ver el
 * perfil conectado y clonar una carpeta de servidor compartida. Todo lo que
 * toca la red (invitar, descargar) se lanza fuera del hilo de Swing o con el
 * cursor de espera puesto, porque son operaciones de varios segundos.
 */
public final class GoogleWindows
{

	private static boolean hasErrors = false;

	// ---- FASE 1 — Invitacion de hosts ---------------------------------------

	public static void addHostingUser()
	{
		JDialog addHostingUserDialog = new JDialog();
		addHostingUserDialog.setTitle( "Add hosting user to this server" );
		addHostingUserDialog.getContentPane().setLayout( new BorderLayout() );
		addHostingUserDialog.setResizable( false );
		int widthSignInDialog = 310;
		int heightSignInDialog = 150;
		addHostingUserDialog.setSize( widthSignInDialog, heightSignInDialog );
		addHostingUserDialog.setLocationRelativeTo( null );
		addHostingUserDialog.setDefaultCloseOperation( JFrame.DISPOSE_ON_CLOSE );

		JPanel contentPane = new JPanel( new GridLayout( 2, 1 ) );
		JPanel buttonsPane = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );

		String googleDriveEmailLabelText = "Google account email";
		JLabel googleDriveEmailLabel = new JLabel( googleDriveEmailLabelText );
		JTextField googleDriveEmailInput = new JTextField();

		JButton cancelBtn = new JButton( "Cancel" );
		JButton addUserBtn = new JButton( "Add user" );

		contentPane.setBorder( BorderFactory.createEmptyBorder( 20, 20, 20, 20 ) );

		contentPane.add( googleDriveEmailLabel );
		contentPane.add( googleDriveEmailInput );

		buttonsPane.add( cancelBtn );
		buttonsPane.add( addUserBtn );

		addHostingUserDialog.add( contentPane, BorderLayout.NORTH );
		addHostingUserDialog.add( buttonsPane, BorderLayout.SOUTH );

		addUserBtn.addActionListener( addUserEvent ->
		{
			hasErrors = false;
			String errorMessageTemplate = "<html>%s - <span style='color:#fa4545'>%s</span></html>";

			boolean emailIsEmpty = fieldIsEmpty( googleDriveEmailLabel, googleDriveEmailInput );
			Pattern emailPattern = Pattern.compile( "[a-zA-Z0-9._]+@[a-zA-Z]+(([.][a-z]+)*)[.][a-z]{2,}" );
			Matcher emailMatcher = emailPattern.matcher( googleDriveEmailInput.getText() );
			if( !emailMatcher.find() && !emailIsEmpty )
			{
				googleDriveEmailLabel
						.setText( String.format( errorMessageTemplate, googleDriveEmailLabelText, "Use a valid email format." ) );
				hasErrors = true;
			}

			if( hasErrors )
				return;

			// La invitacion manda un correo real al invitado: se confirma antes
			Object[] confirmButtons = {"Cancel", "Accept"};
			int chosenOption = JOptionPane.showOptionDialog(
					null,
					"Are you sure do you want to add the user '" + googleDriveEmailInput.getText() + "' to the hosting list?",
					"Invitation confirmation",
					JOptionPane.INFORMATION_MESSAGE,
					JOptionPane.DEFAULT_OPTION,
					null,
					confirmButtons,
					confirmButtons[0] );

			boolean userAccepted = chosenOption == 1;
			if( userAccepted )
			{
				boolean invitedSuccessfully = MainFrame.cloudProvider.inviteUser( googleDriveEmailInput.getText() );
				if( invitedSuccessfully )
				{
					JOptionPane.showMessageDialog(
							null,
							"User invited to hosting successfully!",
							"Google Drive",
							JOptionPane.INFORMATION_MESSAGE );
				}
				else
				{
					JOptionPane.showMessageDialog( null, "Unable to invite user to hosting, try again.", "Google Drive error",
							JOptionPane.ERROR_MESSAGE );
				}
			}


			addHostingUserDialog.dispose();
		} );

		cancelBtn.addActionListener( cancelEvent ->
		{
			addHostingUserDialog.dispose();
		} );
		DashboardDialogSupport.show( addHostingUserDialog );
	}

	// ---- FASE 2 — Perfil conectado ------------------------------------------

	public static void googleProfileWnd()
	{
		JDialog googleDriveProfileDialog = new JDialog();
		googleDriveProfileDialog.setTitle( "Google profile" );
		googleDriveProfileDialog.getContentPane().setLayout( new BorderLayout() );
		googleDriveProfileDialog.setResizable( false );
		int widthGoogleDriveProfileDialog = 360;
		int heightGoogleDriveProfileDialog = 150;
		googleDriveProfileDialog.setSize( widthGoogleDriveProfileDialog, heightGoogleDriveProfileDialog );
		googleDriveProfileDialog.setLocationRelativeTo( null );
		googleDriveProfileDialog.setDefaultCloseOperation( JFrame.DISPOSE_ON_CLOSE );

		JPanel contentPane = new JPanel( new GridLayout( 2, 1 ) );
		JPanel buttonsPane = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );

		JLabel googleEmailLabel = new JLabel( "Logged as:" );
		JTextPane googleEmailInput = new JTextPane();

		Map<String, Object> userData = ((GoogleDriveCloudProvider) MainFrame.cloudProvider).getUserInfo();

		ImageIcon profileIcon;
		JLabel imageLabel;
		try
		{
			profileIcon = new ImageIcon( URL.of( URI.create( (String) userData.get( "profilePhoto" ) ), null ) );
			Image scaledPhoto = profileIcon.getImage().getScaledInstance( 64, 64, Image.SCALE_SMOOTH );
			imageLabel = new JLabel( new ImageIcon( scaledPhoto ) );
		}
		catch( MalformedURLException | NullPointerException photoUnavailable )
		{
			// Sin foto (cuenta sin avatar o URL rara) el dialogo se muestra igual:
			// el email es lo unico que el usuario necesita ver aqui
			imageLabel = null;
		}

		googleEmailInput.setText( (String) userData.get( "email" ) );


		JButton closeBtn = new JButton( "Close" );

		contentPane.setBorder( BorderFactory.createEmptyBorder( 20, 20, 20, 20 ) );
		googleEmailInput.setEditable( false );

		contentPane.add( googleEmailLabel );
		if( imageLabel != null )
			contentPane.add( imageLabel );
		contentPane.add( googleEmailInput );

		buttonsPane.add( closeBtn );

		googleDriveProfileDialog.add( contentPane, BorderLayout.NORTH );
		googleDriveProfileDialog.add( buttonsPane, BorderLayout.SOUTH );

		closeBtn.addActionListener( closeEvent ->
		{
			googleDriveProfileDialog.dispose();
		} );
		DashboardDialogSupport.show( googleDriveProfileDialog );
	}

	// ---- FASE 3 — Clonado de carpetas compartidas ---------------------------

	public static void cloneServerFolderWnd( JFrame frame )
	{
		JDialog googleDriveServerFoldersCloneListDialog = new JDialog();
		googleDriveServerFoldersCloneListDialog.setTitle( "Server invited folders" );
		googleDriveServerFoldersCloneListDialog.getContentPane().setLayout( new BorderLayout() );
		googleDriveServerFoldersCloneListDialog.setResizable( false );
		int widthGoogleDriveServerFoldersCloneListDialog = 560;
		int heightGoogleDriveServerFoldersCloneListDialog = 230;
		googleDriveServerFoldersCloneListDialog.setSize( widthGoogleDriveServerFoldersCloneListDialog,
				heightGoogleDriveServerFoldersCloneListDialog );
		googleDriveServerFoldersCloneListDialog.setLocationRelativeTo( null );
		googleDriveServerFoldersCloneListDialog.setDefaultCloseOperation( JFrame.DISPOSE_ON_CLOSE );

		List<String> serverFolderlist = MainFrame.cloudProvider.getInvitedFolderList();

		JPanel contentPane;
		boolean hasFolders = serverFolderlist != null && serverFolderlist.size() > 0;
		if( hasFolders )
		{
			// Una fila por carpeta: el grid se dimensiona con el tamano de la lista
			contentPane = new JPanel( new GridLayout( serverFolderlist.size(), 1 ) );
			contentPane.setBorder( BorderFactory.createEmptyBorder( 5, 5, 0, 5 ) );
		}
		else
		{
			// Sin carpetas el borde grande centra el mensaje de lista vacia
			contentPane = new JPanel( new BorderLayout() );
			contentPane.setBorder( BorderFactory.createEmptyBorder( 70, 200, 70, 200 ) );
		}

		JPanel buttonsPane = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );

		createClonelistComponents( contentPane, frame, googleDriveServerFoldersCloneListDialog, serverFolderlist );
		JButton closeBtn = new JButton( "Close" );

		buttonsPane.add( closeBtn );

		googleDriveServerFoldersCloneListDialog.add( contentPane, BorderLayout.NORTH );
		googleDriveServerFoldersCloneListDialog.add( buttonsPane, BorderLayout.SOUTH );

		closeBtn.addActionListener( closeEvent ->
		{
			googleDriveServerFoldersCloneListDialog.dispose();
		} );
		DashboardDialogSupport.show( googleDriveServerFoldersCloneListDialog );
	}

	private static void createClonelistComponents( JPanel contentPane, JFrame frame, JDialog googleDriveServerFoldersCloneListDialog,
			List<String> serverFolderlist )
	{
		contentPane.removeAll();
		String labelTextTemplate = "<html><b>Creator: </b>%s - <b>Server Folder: </b>%s</html>";
		if( serverFolderlist == null || serverFolderlist.size() < 1 )
		{
			contentPane
					.add( new JLabel( "<html><span style='color: gray; text-align: center;'>No server folders to install</span></html>" ) );
			return;
		}

		for( String serverFolderId : serverFolderlist )
		{
			List<String> names = ((GoogleDriveCloudProvider) MainFrame.cloudProvider).getRelevantFolderInfo( serverFolderId );

			JPanel cloneContainer = new JPanel( new FlowLayout() );
			cloneContainer.setBorder( BorderFactory.createEmptyBorder( 5, 0, 5, 0 ) );
			JLabel textLabel = new JLabel( String.format( labelTextTemplate, names.get( 0 ), names.get( 1 ) ) );
			JButton cloneBtn = new JButton( "clone" );

			cloneContainer.add( textLabel );
			cloneContainer.add( cloneBtn );
			contentPane.add( cloneContainer );

			cloneBtn.addActionListener( cloneEvent ->
			{
				JFileChooser chooser = new JFileChooser();
				chooser.setFileSelectionMode( JFileChooser.DIRECTORIES_ONLY );
				int chooserResult = chooser.showOpenDialog( frame );
				if( chooserResult == JFileChooser.APPROVE_OPTION )
				{
					File cloneDirectory = chooser.getSelectedFile();
					// El clonado borra y reescribe el destino: si no esta vacio se
					// perderian los ficheros que ya hubiera dentro
					boolean directoryIsNotEmpty = cloneDirectory.isDirectory() && cloneDirectory.list().length != 0;
					if( directoryIsNotEmpty )
					{
						JOptionPane.showMessageDialog(
								cloneContainer,
								"Debe seleccionar un directorio vacío.",
								"Error",
								JOptionPane.ERROR_MESSAGE );
					}
					else
					{
						frame.setCursor( Cursor.getPredefinedCursor( Cursor.WAIT_CURSOR ) );
						// La descarga va en su propio hilo para no congelar la ventana
						new Thread( () ->
						{
							File cloneDirectoryServer = Path.of( cloneDirectory.toString(), names.get( 1 ) ).toFile();
							MainFrame.serverOpenedDirectory = cloneDirectoryServer;
							// Marca al proveedor de que buscamos la copia de otro dueno:
							// sin ella descartaria los backups por fecha y no clonaria nada
							GoogleDriveCloudProvider.isSearchingBackUpForClonning = true;
							boolean clonedSuccessfully = MainFrame.cloudProvider.downloadServerBackup( cloneDirectoryServer.toPath() );
							GoogleDriveCloudProvider.isSearchingBackUpForClonning = false;
							frame.setCursor( Cursor.getDefaultCursor() );
							if( clonedSuccessfully )
							{
								JOptionPane.showMessageDialog(
										null,
										"Server cloned successfully!",
										"Google Drive",
										JOptionPane.INFORMATION_MESSAGE );
								googleDriveServerFoldersCloneListDialog.dispose();
								MainFrame.window.openServerOptions( MainFrame.contentPane );
							}
							else
							{
								JOptionPane.showMessageDialog(
										null,
										"Server not installed. This server does not have any backups saved. Please ask the owner to create at least one backup.",
										"Google Drive",
										JOptionPane.INFORMATION_MESSAGE );
							}
						} ).start();
					}
				}
			} );
		}
	}

	private static boolean fieldIsEmpty( JLabel errorLabel, JTextField input )
	{
		boolean result = input.getText().trim().isEmpty();
		if( result )
		{
			errorLabel.setText( String.format( "<html>%s - <span style='color:#fa4545'>%s</span></html>", errorLabel.getText(),
					"Field can not be empty." ) );
			hasErrors = true;
		}
		return result;
	}
}
