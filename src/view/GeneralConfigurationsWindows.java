package view;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

import cloud.ZipUtils;
import minecraftServerManagement.ForgeUtils;
import view.dashboard.DashboardDialogSupport;

/**
 * Dialogo de configuraciones generales del servidor. Guarda la lista de
 * operadores en un properties propio (no en el ops.json de Minecraft) porque
 * tiene que sobrevivir a los borrados y clonados de la carpeta del servidor, y
 * la sincroniza con el servidor vivo mandandole /op y /deop de lo que cambio.
 */
public final class GeneralConfigurationsWindows
{

	public static final Path USER_OPS_PATH = app.AppPaths.dataFile( "userOps.properties" );
	private static boolean hasErrors = false;

	// ---- FASE 1 — Construccion del dialogo ----------------------------------

	public static void generalConfigurations()
	{
		JDialog generalConfigurationsDialog = new JDialog();
		generalConfigurationsDialog.setTitle( "General configurations" );
		generalConfigurationsDialog.getContentPane().setLayout( new BorderLayout() );
		generalConfigurationsDialog.setResizable( false );
		int widthGeneralConfigurationsDialog = 560;
		int heightGeneralConfigurationsDialog = 200;
		generalConfigurationsDialog.setSize( widthGeneralConfigurationsDialog, heightGeneralConfigurationsDialog );
		generalConfigurationsDialog.setLocationRelativeTo( null );
		generalConfigurationsDialog.setDefaultCloseOperation( JFrame.DISPOSE_ON_CLOSE );


		JPanel contentPane;
		JScrollPane scrollPane;

		contentPane = new JPanel( new GridLayout( 4, 1 ) );
		scrollPane = new JScrollPane( contentPane );
		contentPane.setBorder( BorderFactory.createEmptyBorder( 10, 20, 0, 20 ) );
		scrollPane.setPreferredSize( new Dimension( 355, 100 ) );
		scrollPane.setVerticalScrollBarPolicy( JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED );
		scrollPane.setHorizontalScrollBarPolicy( JScrollPane.HORIZONTAL_SCROLLBAR_NEVER );

		String usersOpsLabelText = "Users operators (format: name, name, name...)";
		JLabel usersOpsLabel = new JLabel( usersOpsLabelText );
		JTextField userOpsInput = new JTextField();

		String usersOpsForCustomCommandsLabelText = "Custom commands operators (format: name, name, name...)";
		JLabel usersOpsForCustomCommandsLabel = new JLabel( usersOpsForCustomCommandsLabelText );
		JTextField usersOpsForCustomCommandsInput = new JTextField();

		JPanel buttonsPane = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );

		JButton saveBtn = new JButton( "Save" );
		JButton closeBtn = new JButton( "Close" );

		if( ZipUtils.existsDirectory( USER_OPS_PATH ) )
		{
			userOpsInput.setText( ZipUtils.getDataFromPropertiesFile( "userOps", USER_OPS_PATH ) );
			usersOpsForCustomCommandsInput.setText( ZipUtils.getDataFromPropertiesFile( "usersOpsForCustomCommands", USER_OPS_PATH ) );
		}

		scrollPane.setBorder( null );

		contentPane.add( usersOpsLabel );
		contentPane.add( userOpsInput );
		contentPane.add( usersOpsForCustomCommandsLabel );
		contentPane.add( usersOpsForCustomCommandsInput );

		buttonsPane.add( closeBtn );
		buttonsPane.add( saveBtn );

		generalConfigurationsDialog.add( scrollPane, BorderLayout.NORTH );
		generalConfigurationsDialog.add( buttonsPane, BorderLayout.SOUTH );

		// ---- FASE 2 — Guardado y sincronizacion con el servidor vivo --------

		saveBtn.addActionListener( saveEvent ->
		{
			String errorTemplate = "<html>%s <span style='color: #fa4545;'>%s</span></html>";
			String errorMessage = "Invalid format";

			usersOpsLabel.setText( usersOpsLabelText );
			usersOpsForCustomCommandsLabel.setText( usersOpsForCustomCommandsLabelText );

			// Sin cambios no se reescribe nada: evita mandar /op y /deop de lo mismo
			boolean savedFileExists = ZipUtils.existsDirectory( USER_OPS_PATH );
			boolean nothingChanged = savedFileExists
					&& userOpsInput.getText().equals( ZipUtils.getDataFromPropertiesFile( "userOps", USER_OPS_PATH ) )
					&& usersOpsForCustomCommandsInput.getText()
							.equals( ZipUtils.getDataFromPropertiesFile( "usersOpsForCustomCommands", USER_OPS_PATH ) );
			if( nothingChanged )
			{
				generalConfigurationsDialog.dispose();
				return;
			}

			if( !checkFormatValidity( userOpsInput.getText() ) )
				usersOpsLabel.setText( errorTemplate.formatted( usersOpsLabelText, errorMessage ) );

			if( !checkFormatValidity( usersOpsForCustomCommandsInput.getText() ) )
				usersOpsForCustomCommandsLabel.setText( errorTemplate.formatted( usersOpsForCustomCommandsLabelText, errorMessage ) );

			if( !hasErrors )
			{
				String previousUserOps = ZipUtils.getDataFromPropertiesFile( "userOps", USER_OPS_PATH );


				ZipUtils.createOrModiFyPropertiesFile( "userOps", userOpsInput.getText(), USER_OPS_PATH );
				ZipUtils.createOrModiFyPropertiesFile( "usersOpsForCustomCommands", usersOpsForCustomCommandsInput.getText(),
						USER_OPS_PATH );

				if( MainFrame.serverIsOn )
				{
					// Con el servidor arrancado el ops.json ya esta cargado en memoria:
					// el cambio solo cuaja mandando los comandos por la consola
					if( ZipUtils.getDataFromPropertiesFile( "userOps", USER_OPS_PATH ) instanceof String ops && !ops.isBlank() )
					{
						for( String removedNickname : previousUserOps.split( ", " ) )
						{
							if( !userOpsInput.getText().contains( removedNickname ) )
								ForgeUtils.sendCommand( "/deop " + removedNickname, MainFrame.serverProcess, MainFrame.serverWriter );
						}
						for( String addedNickname : userOpsInput.getText().split( ", " ) )
						{
							if( !previousUserOps.contains( addedNickname ) )
								ForgeUtils.sendCommand( "/op " + addedNickname, MainFrame.serverProcess, MainFrame.serverWriter );
						}
					}
				}
				else
					// Servidor parado: se borra el ops.json para que lo regenere con la
					// lista nueva en el proximo arranque
					ZipUtils.deleteDirectory( Path.of( MainFrame.serverOpenedDirectory.toString() + "/ops.json" ) );

				generalConfigurationsDialog.dispose();

			}

		} );

		closeBtn.addActionListener( closeEvent ->
		{
			generalConfigurationsDialog.dispose();
		} );
		DashboardDialogSupport.show( generalConfigurationsDialog );
	}

	/** Una lista vacia es valida; lo que no vale es dejarla acabada en coma o espacio. */
	private static boolean checkFormatValidity( String text )
	{
		boolean result = false;
		do
		{
			// El blanco no toca hasErrors: no hay nada que validar
			if( text.isBlank() )
			{
				result = true;
				break;
			}

			Pattern noTrailingSeparatorPattern = Pattern.compile( "^.*[^, ]$" );
			Matcher matcher = noTrailingSeparatorPattern.matcher( text );
			if( matcher.matches() )
			{
				hasErrors = false;
				result = true;
				break;
			}

			hasErrors = true;
		} while( false );
		return result;
	}
}
