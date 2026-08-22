package view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jgit.GitUtils;
import jgit.TokenStore;
import view.dashboard.DashboardDialogSupport;

/**
 * Dialogos de la integracion con GitHub: alta de sesion, invitaciones y clonado
 * de los repositorios de servidor. La sesion se valida SIEMPRE contra la API
 * antes de guardarla (el token manda sobre lo que el usuario teclee en el
 * nickname) y el token nunca se vuelve a mostrar una vez guardado.
 */
public final class GitWindows
{

	private static boolean hasErrors;
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds( 20 );
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout( REQUEST_TIMEOUT ).build();
	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	// ---- FASE 1 — Alta de sesion --------------------------------------------

	public static void signIntoGitHubWnd( Runnable doAfterSignIn )
	{
		JDialog githubSignInDialog = new JDialog();
		githubSignInDialog.setTitle( "Sign into GitHub" );
		githubSignInDialog.getContentPane().setLayout( new BorderLayout() );
		githubSignInDialog.setResizable( false );
		int widthSignInDialog = 500;
		int heightSignInDialog = 300;
		githubSignInDialog.setSize( widthSignInDialog, heightSignInDialog );
		githubSignInDialog.setLocationRelativeTo( null );
		githubSignInDialog.setDefaultCloseOperation( JFrame.DISPOSE_ON_CLOSE );

		JPanel contentPane = new JPanel( new GridLayout( 6, 1 ) );
		JPanel buttonsPane = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );

		String nicknameLabelText = "GitHub nickname";
		JLabel nicknameLabel = new JLabel( nicknameLabelText );
		JTextField nicknameInput = new JTextField();

		String emailLabelText = "GitHub email";
		JLabel emailLabel = new JLabel( emailLabelText );
		JTextField emailInput = new JTextField();

		String tokenLabelText = "GitHub token";
		JLabel tokenLabel = new JLabel( tokenLabelText );
		JPasswordField tokenInput = new JPasswordField();

		JButton signInBtn = new JButton( "Sign in" );
		JButton cancelBtn = new JButton( "Cancel" );

		contentPane.setBorder( BorderFactory.createEmptyBorder( 20, 20, 20, 20 ) );
		emailLabel.setBorder( BorderFactory.createEmptyBorder( 10, 0, 0, 0 ) );
		tokenLabel.setBorder( BorderFactory.createEmptyBorder( 10, 0, 0, 0 ) );

		contentPane.add( nicknameLabel );
		contentPane.add( nicknameInput );
		contentPane.add( emailLabel );
		contentPane.add( emailInput );
		contentPane.add( tokenLabel );
		contentPane.add( tokenInput );

		buttonsPane.add( cancelBtn );
		buttonsPane.add( signInBtn );

		githubSignInDialog.add( contentPane, BorderLayout.NORTH );
		githubSignInDialog.add( buttonsPane, BorderLayout.SOUTH );

		signInBtn.addActionListener( signInEvent ->
		{
			hasErrors = false;
			String errorMessageTemplate = "<html>%s - <span style='color:#fa4545'>%s</span></html>";
			String nickname = nicknameInput.getText().trim();
			String email = emailInput.getText().trim();
			String token = new String( tokenInput.getPassword() ).trim();

			nicknameLabel.setText( nicknameLabelText );
			emailLabel.setText( emailLabelText );
			tokenLabel.setText( tokenLabelText );

			fieldIsEmpty( nicknameLabel, nicknameInput );
			boolean emailIsEmpty = fieldIsEmpty( emailLabel, emailInput );
			Pattern emailPattern = Pattern.compile( "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$" );
			Matcher emailMatcher = emailPattern.matcher( email );
			if( !emailMatcher.matches() && !emailIsEmpty )
			{
				emailLabel.setText( String.format( errorMessageTemplate, emailLabelText, "Use a valid email format." ) );
				hasErrors = true;
			}

			fieldIsEmpty( tokenLabel, tokenInput );
			if( hasErrors )
				return;

			// El token manda sobre el nickname tecleado: si no coinciden, el push
			// posterior fallaria con un error de git indescifrable para el usuario
			String authenticatedLogin = getAuthenticatedLogin( token );
			if( authenticatedLogin == null )
			{
				tokenLabel.setText(
						String.format( errorMessageTemplate, tokenLabelText, "Token is invalid, expired or can not reach GitHub." ) );
				return;
			}
			if( !authenticatedLogin.equalsIgnoreCase( nickname ) )
			{
				nicknameLabel.setText(
						String.format( errorMessageTemplate, nicknameLabelText, "This token belongs to '" + authenticatedLogin + "'." ) );
				return;
			}

			if( !TokenStore.saveUserData( authenticatedLogin, email, token ) )
			{
				tokenLabel.setText( String.format( errorMessageTemplate, tokenLabelText,
						"Could not write the session to " + app.AppPaths.data() + ". Check the folder permissions." ) );
				return;
			}

			doAfterSignIn.run();
			githubSignInDialog.dispose();
		} );

		cancelBtn.addActionListener( cancelEvent ->
		{
			githubSignInDialog.dispose();
		} );
		DashboardDialogSupport.show( githubSignInDialog );
	}

	// ---- FASE 2 — Invitaciones ----------------------------------------------

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

		String gitHubNicknameLabelText = "GitHub nickname";
		JLabel gitHubNicknameLabel = new JLabel( gitHubNicknameLabelText );
		JTextField gitHubNicknameInput = new JTextField();

		JButton cancelBtn = new JButton( "Cancel" );
		JButton addUserBtn = new JButton( "Add user" );

		contentPane.setBorder( BorderFactory.createEmptyBorder( 20, 20, 20, 20 ) );

		contentPane.add( gitHubNicknameLabel );
		contentPane.add( gitHubNicknameInput );

		buttonsPane.add( cancelBtn );
		buttonsPane.add( addUserBtn );

		addHostingUserDialog.add( contentPane, BorderLayout.NORTH );
		addHostingUserDialog.add( buttonsPane, BorderLayout.SOUTH );

		addUserBtn.addActionListener( addUserEvent ->
		{
			hasErrors = false;
			String errorMessageTemplate = "<html>%s - <span style='color:#fa4545'>%s</span></html>";
			Map<String, String> userData;
			try
			{
				userData = TokenStore.getSavedUserData();
			}
			catch( Exception invalidSession )
			{
				// Sesion ilegible o corrupta: no se puede saber a quien se invita
				app.Log.event( "GIT_AUTH_UI", "No se pudo leer la sesion guardada al invitar a un host", invalidSession );
				JOptionPane.showMessageDialog( null, "Session invalid, sign in again.", "Error", JOptionPane.ERROR_MESSAGE );
				addHostingUserDialog.dispose();
				return;
			}

			gitHubNicknameLabel.setText( gitHubNicknameLabelText );

			if( userData.get( "nickname" ).equals( gitHubNicknameInput.getText() ) )
			{
				gitHubNicknameLabel
						.setText( String.format( errorMessageTemplate, gitHubNicknameLabelText, "You can not invite yourself." ) );
				hasErrors = true;
			}

			boolean nicknameIsEmpty = fieldIsEmpty( gitHubNicknameLabel, gitHubNicknameInput );
			if( !nicknameIsEmpty && !checkNickname( gitHubNicknameInput.getText() ) )
			{
				gitHubNicknameLabel.setText( String.format( errorMessageTemplate, gitHubNicknameLabelText, "Nickname doesn't exists." ) );
				hasErrors = true;
			}


			if( hasErrors )
				return;

			// La invitacion manda un correo real al invitado: se confirma antes
			Object[] confirmButtons = {"Cancel", "Accept"};
			int chosenOption = JOptionPane.showOptionDialog(
					null,
					"Are you sure do you want to add the user '" + gitHubNicknameInput.getText() + "' to the hosting list?",
					"Invitation confirmation",
					JOptionPane.INFORMATION_MESSAGE,
					JOptionPane.DEFAULT_OPTION,
					null,
					confirmButtons,
					confirmButtons[0] );

			boolean userAccepted = chosenOption == 1;
			if( userAccepted )
			{
				boolean invitedSuccessfully = GitUtils.inviteHostingUser( gitHubNicknameInput.getText() );
				if( invitedSuccessfully )
				{
					JOptionPane.showMessageDialog(
							null,
							"User invited to hosting successfully!",
							"Git",
							JOptionPane.INFORMATION_MESSAGE );
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

	public static void invitationslistWnd()
	{
		JDialog invitaitionslistDialog = new JDialog();
		invitaitionslistDialog.setTitle( "Pending invitations list" );
		invitaitionslistDialog.getContentPane().setLayout( new BorderLayout() );
		invitaitionslistDialog.setResizable( false );
		int widthInvitationsListDialog = 360;
		int heightInvitationsListDialog = 170;
		invitaitionslistDialog.setSize( widthInvitationsListDialog, heightInvitationsListDialog );
		invitaitionslistDialog.setLocationRelativeTo( null );
		invitaitionslistDialog.setDefaultCloseOperation( JFrame.DISPOSE_ON_CLOSE );

		List<Map<String, Object>> invitationsList = GitUtils.getAllPendingInvitations();

		JPanel contentPane;
		JScrollPane scrollPane;

		boolean hasInvitations = invitationsList != null && invitationsList.size() > 0;
		if( hasInvitations )
		{
			// Una fila por invitacion: el grid se dimensiona con el tamano de la lista
			contentPane = new JPanel( new GridLayout( invitationsList.size(), 1 ) );
			scrollPane = new JScrollPane( contentPane );
			contentPane.setBorder( BorderFactory.createEmptyBorder( 5, 5, 0, 5 ) );
			scrollPane.setPreferredSize( new Dimension( 355, 95 ) );
			scrollPane.setVerticalScrollBarPolicy( JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED );
			scrollPane.setHorizontalScrollBarPolicy( JScrollPane.HORIZONTAL_SCROLLBAR_NEVER );
		}
		else
		{
			// Sin invitaciones el borde grande centra el mensaje de lista vacia
			contentPane = new JPanel( new BorderLayout() );
			contentPane.setBorder( BorderFactory.createEmptyBorder( 45, 110, 45, 110 ) );
			scrollPane = new JScrollPane( contentPane );
		}

		JPanel buttonsPane = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );

		JButton closeBtn = new JButton( "Close" );

		scrollPane.setBorder( null );

		createInvitationListComponents( contentPane, invitationsList );
		buttonsPane.add( closeBtn );

		invitaitionslistDialog.add( scrollPane, BorderLayout.NORTH );
		invitaitionslistDialog.add( buttonsPane, BorderLayout.SOUTH );

		closeBtn.addActionListener( closeEvent ->
		{
			invitaitionslistDialog.dispose();
		} );
		DashboardDialogSupport.show( invitaitionslistDialog );
	}

	// ---- FASE 3 — Perfil conectado ------------------------------------------

	public static void gitHubProfileWnd()
	{
		JDialog gitHubProfileDialog = new JDialog();
		gitHubProfileDialog.setTitle( "GitHub profile" );
		gitHubProfileDialog.getContentPane().setLayout( new BorderLayout() );
		gitHubProfileDialog.setResizable( false );
		int widthGitHubProfileDialog = 360;
		int heightGitHubProfileDialog = 230;
		gitHubProfileDialog.setSize( widthGitHubProfileDialog, heightGitHubProfileDialog );
		gitHubProfileDialog.setLocationRelativeTo( null );
		gitHubProfileDialog.setDefaultCloseOperation( JFrame.DISPOSE_ON_CLOSE );

		JPanel contentPane = new JPanel( new GridLayout( 6, 1 ) );
		JPanel buttonsPane = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );

		JLabel gitHubNicknameLabel = new JLabel( "GitHub nickname" );
		JTextPane gitHubNicknameInput = new JTextPane();
		JLabel gitHubEmailLabel = new JLabel( "GitHub email" );
		JTextPane gitHubEmailInput = new JTextPane();
		JLabel gitHubTokenLabel = new JLabel( "<html>GitHub token - <span style='color: gray;'>not displayed</span></html>" );
		JTextPane gitHubTokenInput = new JTextPane();

		Map<String, String> userData;
		try
		{
			userData = TokenStore.getSavedUserData();
		}
		catch( Exception invalidSession )
		{
			app.Log.event( "GIT_AUTH_UI", "No se pudo leer la sesion guardada al abrir el perfil", invalidSession );
			JOptionPane.showMessageDialog( null, "Session invalid, consider sign in again.", "Error", JOptionPane.ERROR_MESSAGE );
			gitHubProfileDialog.dispose();
			return;
		}

		gitHubNicknameInput.setText( userData.get( "nickname" ) );
		gitHubEmailInput.setText( userData.get( "email" ) );
		// El token no se reimprime nunca una vez guardado
		gitHubTokenInput.setText( "Stored for this local session" );


		JButton closeBtn = new JButton( "Close" );

		contentPane.setBorder( BorderFactory.createEmptyBorder( 20, 20, 20, 20 ) );
		gitHubNicknameInput.setEditable( false );
		gitHubEmailInput.setEditable( false );
		gitHubTokenInput.setEditable( false );

		contentPane.add( gitHubNicknameLabel );
		contentPane.add( gitHubNicknameInput );
		contentPane.add( gitHubEmailLabel );
		contentPane.add( gitHubEmailInput );
		contentPane.add( gitHubTokenLabel );
		contentPane.add( gitHubTokenInput );

		buttonsPane.add( closeBtn );

		gitHubProfileDialog.add( contentPane, BorderLayout.NORTH );
		gitHubProfileDialog.add( buttonsPane, BorderLayout.SOUTH );

		closeBtn.addActionListener( closeEvent ->
		{
			gitHubProfileDialog.dispose();
		} );
		DashboardDialogSupport.show( gitHubProfileDialog );
	}

	// ---- FASE 4 — Clonado de repositorios -----------------------------------

	/** Solo se ofrecen los repositorios a los que el usuario ya se ha unido. */
	public static void cloneRepoWnd( JFrame frame )
	{
		JDialog gitHubRepositoriesCloneListDialog = new JDialog();
		gitHubRepositoriesCloneListDialog.setTitle( "Server repositories" );
		gitHubRepositoriesCloneListDialog.getContentPane().setLayout( new BorderLayout() );
		gitHubRepositoriesCloneListDialog.setResizable( false );
		int widthGitHubRepositoriesCloneListDialog = 360;
		int heightGitHubRepositoriesCloneListDialog = 230;
		gitHubRepositoriesCloneListDialog.setSize( widthGitHubRepositoriesCloneListDialog, heightGitHubRepositoriesCloneListDialog );
		gitHubRepositoriesCloneListDialog.setLocationRelativeTo( null );
		gitHubRepositoriesCloneListDialog.setDefaultCloseOperation( JFrame.DISPOSE_ON_CLOSE );

		List<String> repos = GitUtils.getRepoJoined();

		JPanel contentPane;
		boolean hasRepos = repos != null && repos.size() > 0;
		if( hasRepos )
		{
			// Una fila por repositorio: el grid se dimensiona con el tamano de la lista
			contentPane = new JPanel( new GridLayout( repos.size(), 1 ) );
			contentPane.setBorder( BorderFactory.createEmptyBorder( 5, 5, 0, 5 ) );
		}
		else
		{
			// Sin repositorios el borde grande centra el mensaje de lista vacia
			contentPane = new JPanel( new BorderLayout() );
			contentPane.setBorder( BorderFactory.createEmptyBorder( 70, 105, 70, 105 ) );
		}

		JPanel buttonsPane = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );

		createClonelistComponents( contentPane, frame, gitHubRepositoriesCloneListDialog, repos );
		JButton closeBtn = new JButton( "Close" );

		buttonsPane.add( closeBtn );

		gitHubRepositoriesCloneListDialog.add( contentPane, BorderLayout.NORTH );
		gitHubRepositoriesCloneListDialog.add( buttonsPane, BorderLayout.SOUTH );

		closeBtn.addActionListener( closeEvent ->
		{
			gitHubRepositoriesCloneListDialog.dispose();
		} );
		DashboardDialogSupport.show( gitHubRepositoriesCloneListDialog );
	}

	private static void createClonelistComponents( JPanel contentPane, JFrame frame, JDialog dialog, List<String> repos )
	{
		contentPane.removeAll();
		String labelTextTemplate = "<html><b>Creator: </b>%s - <b>Repository: </b>%s</html>";
		if( repos == null || repos.size() < 1 )
		{
			contentPane.add( new JLabel( "<html><span style='color: gray; text-align: center;'>No repositories to clone</span></html>" ) );
			return;
		}

		for( String repoFullName : repos )
		{
			String[] names = repoFullName.split( "/" );
			JPanel cloneContainer = new JPanel( new FlowLayout() );
			cloneContainer.setBorder( BorderFactory.createEmptyBorder( 5, 0, 5, 0 ) );
			JLabel textLabel = new JLabel( String.format( labelTextTemplate, names[0], names[1] ) );
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
					// git clone exige destino vacio: se avisa antes de intentarlo
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
						// El clonado va en su propio hilo para no congelar la ventana
						new Thread( () ->
						{
							boolean clonedSuccessfully = GitUtils.cloneRepoInPath( cloneDirectory.toPath(), repoFullName );
							frame.setCursor( Cursor.getDefaultCursor() );
							if( clonedSuccessfully )
							{
								JOptionPane.showMessageDialog(
										null,
										"Repository cloned successfully!",
										"Git",
										JOptionPane.INFORMATION_MESSAGE );
								dialog.dispose();
							}
						} ).start();
					}
				}
			} );
		}
	}

	private static void createInvitationListComponents( JPanel contentPane, List<Map<String, Object>> invitationsList )
	{
		contentPane.removeAll();
		String labelTextTemplate = "<html><b>%s</b> invited you to <i>%s</i></html>";
		if( invitationsList == null )
		{
			contentPane.add(
					new JLabel( "<html><span style='color: #fa4545; text-align: center;'>Could not load invitations</span></html>" ),
					BorderLayout.CENTER );
			return;
		}
		if( invitationsList.size() < 1 )
		{
			contentPane.add( new JLabel( "<html><span style='color: gray; text-align: center;'>No invitations pending</span></html>" ),
					BorderLayout.CENTER );
			return;
		}
		for( Map<String, Object> invitation : invitationsList )
		{
			// La respuesta de GitHub puede venir sin repositorio o con un full_name
			// que no sea "dueno/repo": esa invitacion se salta en vez de romper la lista
			Map<?, ?> repoInfo = invitation.get( "repository" ) instanceof Map<?, ?> repo ? repo : null;
			if( repoInfo == null )
				continue;

			String fullNameRepo = String.valueOf( repoInfo.get( "full_name" ) );
			String[] fullNameParts = fullNameRepo.split( "/", 2 );
			if( fullNameParts.length != 2 )
				continue;
			String userSenderNickname = fullNameParts[0];
			String repoName = fullNameParts[1];

			JPanel invitationContainer = new JPanel( new FlowLayout() );
			invitationContainer.setBorder( BorderFactory.createEmptyBorder( 5, 0, 5, 0 ) );
			JLabel textLabel = new JLabel( String.format( labelTextTemplate, userSenderNickname, repoName ) );
			JButton acceptBtn = new JButton( "Accept" );

			invitationContainer.add( textLabel );
			invitationContainer.add( acceptBtn );
			contentPane.add( invitationContainer );

			acceptBtn.addActionListener( acceptEvent ->
			{
				Object invitationId = invitation.get( "id" );
				boolean invitationAcceptedSuccessfully = invitationId instanceof Number number
						&& GitUtils.acceptInvitationById( number.intValue() );
				// El boton desaparece haya ido bien o mal: la invitacion ya se ha
				// consumido en GitHub y reintentarla devolveria 404
				invitationContainer.remove( acceptBtn );
				if( invitationAcceptedSuccessfully )
				{
					GitUtils.saveRepoJoined( fullNameRepo );
					// La suscripcion absorbe los repos unidos, pero registrarla aqui
					// evita esperar a la proxima migracion implicita
					subscribeQuietly( fullNameRepo );
					invitationContainer.add( new JLabel( "<html><span style='color: green;'>Accepted ✓</span></html>" ) );
				}
				else
				{
					invitationContainer.add( new JLabel( "<html><span style='color: #fa4545;'>Error</span></html>" ) );
				}
				invitationContainer.revalidate();
				invitationContainer.repaint();
			} );
		}
	}

	// ---- FASE 5 — Validaciones contra la API de GitHub ----------------------

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

	/** Un nickname existe si GitHub devuelve 200 en su perfil publico. */
	private static boolean checkNickname( String nickname )
	{
		boolean result = false;
		do
		{
			String encodedNickname = URLEncoder.encode( nickname.trim(), StandardCharsets.UTF_8 );
			HttpRequest request = HttpRequest.newBuilder()
					.uri( URI.create( githubApiBase() + "/users/" + encodedNickname ) )
					.timeout( REQUEST_TIMEOUT )
					.header( "User-Agent", "Peer_To_Peer_Minecraft_Server_System/1.0" )
					.header( "Accept", "application/vnd.github+json" )
					.header( "X-GitHub-Api-Version", "2022-11-28" )
					.build();

			HttpResponse<Void> response;
			try
			{
				response = HTTP_CLIENT.send( request, HttpResponse.BodyHandlers.discarding() );
			}
			catch( Exception requestFailure )
			{
				// Sin red no se puede confirmar el nickname: se trata como inexistente
				// para no invitar a ciegas a alguien que quiza no existe
				app.Log.event( "GIT_AUTH_UI", "No se pudo comprobar el nickname en GitHub", requestFailure );
				break;
			}

			result = response.statusCode() == 200;
		} while( false );
		return result;
	}

	/**
	 * Login canonico del dueno del token, o null si el token no vale o GitHub no
	 * responde. Es la unica fuente fiable del nickname: lo que teclee el usuario
	 * solo sirve para avisarle de que se ha equivocado de cuenta.
	 */
	static String getAuthenticatedLogin( String token )
	{
		String result = null;
		do
		{
			HttpRequest request = HttpRequest.newBuilder()
					.uri( URI.create( githubApiBase() + "/user" ) )
					.GET()
					.timeout( REQUEST_TIMEOUT )
					.header( "Authorization", "Bearer " + token )
					.header( "User-Agent", "Peer_To_Peer_Minecraft_Server_System/1.0" )
					.header( "Accept", "application/vnd.github+json" )
					.header( "X-GitHub-Api-Version", "2022-11-28" )
					.build();

			HttpResponse<String> response;
			try
			{
				response = HTTP_CLIENT.send( request, HttpResponse.BodyHandlers.ofString() );
			}
			catch( Exception requestFailure )
			{
				app.Log.event( "GIT_AUTH_UI", "No se pudo validar el token contra GitHub", requestFailure );
				break;
			}

			// 401/403: token invalido, caducado o sin el scope necesario
			if( response.statusCode() != 200 )
				break;

			try
			{
				JsonNode body = JSON_MAPPER.readTree( response.body() );
				String login = body.path( "login" ).asText( null );
				result = login == null || login.isBlank() ? null : login;
			}
			catch( Exception malformedBody )
			{
				// Respuesta que no es el JSON esperado (proxy, portal cautivo...)
				app.Log.event( "GIT_AUTH_UI", "Respuesta de /user ilegible", malformedBody );
			}
		} while( false );
		return result;
	}

	/** La base se puede sobreescribir por system property para poder testear contra un servidor local. */
	private static String githubApiBase()
	{
		String base = System.getProperty( "endershare.githubApiBase", "https://api.github.com" );
		boolean endsWithSlash = base.endsWith( "/" );
		return endsWithSlash ? base.substring( 0, base.length() - 1 ) : base;
	}

	/** Suscribe el mundo sin dialogos: un fallo aqui no debe romper la aceptacion. */
	private static void subscribeQuietly( String repoFullName )
	{
		try
		{
			app.WorldSubscriptions.subscribe( TokenStore.getSavedUserData().get( "nickname" ), repoFullName );
		}
		catch( Exception noSession )
		{
			// Sin sesion legible no hay a quien apuntar la suscripcion: la migracion
			// implicita desde joined_repos la recuperara en el proximo arranque
		}
	}
}
