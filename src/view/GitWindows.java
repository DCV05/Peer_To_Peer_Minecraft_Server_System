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

public class GitWindows
{

	private static boolean hasErrors;
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds( 20 );
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout( REQUEST_TIMEOUT ).build();
	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	public static void signIntoGitHubWnd( Runnable doAfterSignIn )
	{
		//Dialog creation and configurations.
		JDialog githubSignInDialog = new JDialog();
		githubSignInDialog.setTitle( "Sign into GitHub" );
		githubSignInDialog.getContentPane().setLayout( new BorderLayout() );
		githubSignInDialog.setResizable( false );
		int widthSignInDialog = 500;
		int heightSignInDialog = 300;
		githubSignInDialog.setSize( widthSignInDialog, heightSignInDialog );
		githubSignInDialog.setLocationRelativeTo( null );
		githubSignInDialog.setDefaultCloseOperation( JFrame.DISPOSE_ON_CLOSE );

		//General layout for the components.
		JPanel contentPane = new JPanel( new GridLayout( 6, 1 ) );
		JPanel buttonsPane = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );

		//Labels and inputs.
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

		//Components configurations.
		contentPane.setBorder( BorderFactory.createEmptyBorder( 20, 20, 20, 20 ) );
		emailLabel.setBorder( BorderFactory.createEmptyBorder( 10, 0, 0, 0 ) );
		tokenLabel.setBorder( BorderFactory.createEmptyBorder( 10, 0, 0, 0 ) );

		//Push the general containers and all its children.
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

		//Event listeners.
		signInBtn.addActionListener( sgbtn ->
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
			Pattern pattern = Pattern.compile( "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$" );
			Matcher matcher = pattern.matcher( email );
			if( !matcher.matches() && !emailIsEmpty )
			{
				emailLabel.setText( String.format( errorMessageTemplate, emailLabelText, "Use a valid email format." ) );
				hasErrors = true;
			}

			fieldIsEmpty( tokenLabel, tokenInput );
			if( hasErrors )
				return;

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

		cancelBtn.addActionListener( cnlBtn ->
		{
			githubSignInDialog.dispose();
		} );
		DashboardDialogSupport.show( githubSignInDialog );
	}

	public static void addHostingUser()
	{
		//Dialog creation and configurations.
		JDialog addHostingUserDialog = new JDialog();
		addHostingUserDialog.setTitle( "Add hosting user to this server" );
		addHostingUserDialog.getContentPane().setLayout( new BorderLayout() );
		addHostingUserDialog.setResizable( false );
		int widthSignInDialog = 310;
		int heightSignInDialog = 150;
		addHostingUserDialog.setSize( widthSignInDialog, heightSignInDialog );
		addHostingUserDialog.setLocationRelativeTo( null );
		addHostingUserDialog.setDefaultCloseOperation( JFrame.DISPOSE_ON_CLOSE );

		//General layout for the components.
		JPanel contentPane = new JPanel( new GridLayout( 2, 1 ) );
		JPanel buttonsPane = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );

		//Labels and Inputs
		String gitHubNicknameLabelText = "GitHub nickname";
		JLabel gitHubNicknameLabel = new JLabel( gitHubNicknameLabelText );
		JTextField gitHubNicknameInput = new JTextField();

		//Buttons
		JButton cancelBtn = new JButton( "Cancel" );
		JButton addUserBtn = new JButton( "Add user" );

		//Components configurations.
		contentPane.setBorder( BorderFactory.createEmptyBorder( 20, 20, 20, 20 ) );

		//Push the general containers and all its children.
		contentPane.add( gitHubNicknameLabel );
		contentPane.add( gitHubNicknameInput );

		buttonsPane.add( cancelBtn );
		buttonsPane.add( addUserBtn );

		addHostingUserDialog.add( contentPane, BorderLayout.NORTH );
		addHostingUserDialog.add( buttonsPane, BorderLayout.SOUTH );

		//Event listeners
		addUserBtn.addActionListener( addUsrBtn ->
		{
			hasErrors = false;
			String errorMessageTemplate = "<html>%s - <span style='color:#fa4545'>%s</span></html>";
			Map<String, String> userData;
			try
			{
				userData = TokenStore.getSavedUserData();
			}
			catch( Exception e )
			{
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

			Object[] confirmButtons = {"Cancel", "Accept"};
			int opt = JOptionPane.showOptionDialog(
					null,
					"Are you sure do you want to add the user '" + gitHubNicknameInput.getText() + "' to the hosting list?",
					"Invitation confirmation",
					JOptionPane.INFORMATION_MESSAGE,
					JOptionPane.DEFAULT_OPTION,
					null,
					confirmButtons,
					confirmButtons[0] );

			if( opt == 1 )
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

		cancelBtn.addActionListener( cnlbtn ->
		{
			addHostingUserDialog.dispose();
		} );
		DashboardDialogSupport.show( addHostingUserDialog );
	}

	public static void invitationslistWnd()
	{
		//Dialog creation and configurations.
		JDialog invitaitionslistDialog = new JDialog();
		invitaitionslistDialog.setTitle( "Pending invitations list" );
		invitaitionslistDialog.getContentPane().setLayout( new BorderLayout() );
		invitaitionslistDialog.setResizable( false );
		int widthInvitationsListDialog = 360;
		int heightInvitationsListDialog = 170;
		invitaitionslistDialog.setSize( widthInvitationsListDialog, heightInvitationsListDialog );
		invitaitionslistDialog.setLocationRelativeTo( null );
		invitaitionslistDialog.setDefaultCloseOperation( JFrame.DISPOSE_ON_CLOSE );

		//We get the invitations list.
		List<Map<String, Object>> invitationsList = GitUtils.getAllPendingInvitations();

		//General layout for the components.
		JPanel contentPane;
		JScrollPane scrollPane;

		if( invitationsList != null && invitationsList.size() > 0 )
		{
			contentPane = new JPanel( new GridLayout( invitationsList.size(), 1 ) );
			scrollPane = new JScrollPane( contentPane );
			contentPane.setBorder( BorderFactory.createEmptyBorder( 5, 5, 0, 5 ) );
			scrollPane.setPreferredSize( new Dimension( 355, 95 ) );
			scrollPane.setVerticalScrollBarPolicy( JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED );
			scrollPane.setHorizontalScrollBarPolicy( JScrollPane.HORIZONTAL_SCROLLBAR_NEVER );
		}
		else
		{
			contentPane = new JPanel( new BorderLayout() );
			contentPane.setBorder( BorderFactory.createEmptyBorder( 45, 110, 45, 110 ) );
			scrollPane = new JScrollPane( contentPane );
		}

		JPanel buttonsPane = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );

		JButton closeBtn = new JButton( "Close" );

		//Configurations
		scrollPane.setBorder( null );

		createInvitationListComponents( contentPane, invitationsList );
		buttonsPane.add( closeBtn );

		invitaitionslistDialog.add( scrollPane, BorderLayout.NORTH );
		invitaitionslistDialog.add( buttonsPane, BorderLayout.SOUTH );

		//EventListeners
		closeBtn.addActionListener( clsBtn ->
		{
			invitaitionslistDialog.dispose();
		} );
		DashboardDialogSupport.show( invitaitionslistDialog );
	}

	public static void gitHubProfileWnd()
	{
		//Dialog creation and configurations.
		JDialog gitHubProfileDialog = new JDialog();
		gitHubProfileDialog.setTitle( "GitHub profile" );
		gitHubProfileDialog.getContentPane().setLayout( new BorderLayout() );
		gitHubProfileDialog.setResizable( false );
		int widthGitHubProfileDialog = 360;
		int heightGitHubProfileDialog = 230;
		gitHubProfileDialog.setSize( widthGitHubProfileDialog, heightGitHubProfileDialog );
		gitHubProfileDialog.setLocationRelativeTo( null );
		gitHubProfileDialog.setDefaultCloseOperation( JFrame.DISPOSE_ON_CLOSE );

		//General layout for the components.
		JPanel contentPane = new JPanel( new GridLayout( 6, 1 ) );
		JPanel buttonsPane = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );

		//Labels and Inputs
		JLabel gitHubNicknameLabel = new JLabel( "GitHub nickname" );
		JTextPane gitHubNicknameInput = new JTextPane();
		JLabel gitHubEmailLabel = new JLabel( "GitHub email" );
		JTextPane gitHubEmailInput = new JTextPane();
		JLabel gitHubTokenLabel = new JLabel( "<html>GitHub token - <span style='color: gray;'>not displayed</span></html>" );
		JTextPane gitHubTokenInput = new JTextPane();

		//Default data
		Map<String, String> userData;
		try
		{
			userData = TokenStore.getSavedUserData();
		}
		catch( Exception e )
		{
			JOptionPane.showMessageDialog( null, "Session invalid, consider sign in again.", "Error", JOptionPane.ERROR_MESSAGE );
			gitHubProfileDialog.dispose();
			return;
		}

		gitHubNicknameInput.setText( userData.get( "nickname" ) );
		gitHubEmailInput.setText( userData.get( "email" ) );
		gitHubTokenInput.setText( "Stored for this local session" );


		//Buttons
		JButton closeBtn = new JButton( "Close" );

		//Components configurations.
		contentPane.setBorder( BorderFactory.createEmptyBorder( 20, 20, 20, 20 ) );
		gitHubNicknameInput.setEditable( false );
		gitHubEmailInput.setEditable( false );
		gitHubTokenInput.setEditable( false );

		//Push the general containers and all its children.
		contentPane.add( gitHubNicknameLabel );
		contentPane.add( gitHubNicknameInput );
		contentPane.add( gitHubEmailLabel );
		contentPane.add( gitHubEmailInput );
		contentPane.add( gitHubTokenLabel );
		contentPane.add( gitHubTokenInput );

		buttonsPane.add( closeBtn );

		gitHubProfileDialog.add( contentPane, BorderLayout.NORTH );
		gitHubProfileDialog.add( buttonsPane, BorderLayout.SOUTH );

		//Event Listeners
		closeBtn.addActionListener( clsBtn ->
		{
			gitHubProfileDialog.dispose();
		} );
		DashboardDialogSupport.show( gitHubProfileDialog );
	}

	public static void cloneRepoWnd( JFrame frame )
	{
		//First the user selects one of the repositories that he has joined.
		//Dialog creation and configurations.
		JDialog gitHubRepositoriesCloneListDialog = new JDialog();
		gitHubRepositoriesCloneListDialog.setTitle( "Server repositories" );
		gitHubRepositoriesCloneListDialog.getContentPane().setLayout( new BorderLayout() );
		gitHubRepositoriesCloneListDialog.setResizable( false );
		int widthGitHubRepositoriesCloneListDialog = 360;
		int heightGitHubRepositoriesCloneListDialog = 230;
		gitHubRepositoriesCloneListDialog.setSize( widthGitHubRepositoriesCloneListDialog, heightGitHubRepositoriesCloneListDialog );
		gitHubRepositoriesCloneListDialog.setLocationRelativeTo( null );
		gitHubRepositoriesCloneListDialog.setDefaultCloseOperation( JFrame.DISPOSE_ON_CLOSE );

		//We get the repositories list
		List<String> repos = GitUtils.getRepoJoined();

		//General layout for the components.
		JPanel contentPane;
		if( repos != null && repos.size() > 0 )
		{
			contentPane = new JPanel( new GridLayout( repos.size(), 1 ) ); //We use the 'repos' size so we always get a grid that has the same number of rows than the length.
			contentPane.setBorder( BorderFactory.createEmptyBorder( 5, 5, 0, 5 ) );
		}
		else
		{
			contentPane = new JPanel( new BorderLayout() );
			contentPane.setBorder( BorderFactory.createEmptyBorder( 70, 105, 70, 105 ) );
		}

		JPanel buttonsPane = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );

		//Buttons
		createClonelistComponents( contentPane, frame, gitHubRepositoriesCloneListDialog, repos );
		JButton closeBtn = new JButton( "Close" );

		//Push the general containers and all its children.

		buttonsPane.add( closeBtn );

		gitHubRepositoriesCloneListDialog.add( contentPane, BorderLayout.NORTH );
		gitHubRepositoriesCloneListDialog.add( buttonsPane, BorderLayout.SOUTH );

		//Event Listeners
		closeBtn.addActionListener( clsBtn ->
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

			//Push all the children.
			cloneContainer.add( textLabel );
			cloneContainer.add( cloneBtn );
			contentPane.add( cloneContainer );

			cloneBtn.addActionListener( clnBtn ->
			{
				JFileChooser chooser = new JFileChooser();
				chooser.setFileSelectionMode( JFileChooser.DIRECTORIES_ONLY );
				int result = chooser.showOpenDialog( frame );
				if( result == JFileChooser.APPROVE_OPTION )
				{
					File cloneDirectory = chooser.getSelectedFile();
					if( cloneDirectory.isDirectory() && cloneDirectory.list().length != 0 )
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
		for( Map<String, Object> object : invitationsList )
		{
			Map<?, ?> repoInfo = object.get( "repository" ) instanceof Map<?, ?> repo ? repo : null;
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

			//Push all the children.
			invitationContainer.add( textLabel );
			invitationContainer.add( acceptBtn );
			contentPane.add( invitationContainer );

			acceptBtn.addActionListener( accptBtn ->
			{
				Object invitationId = object.get( "id" );
				boolean invitationAcceptedSuccessfully = invitationId instanceof Number number
						&& GitUtils.acceptInvitationById( number.intValue() );
				invitationContainer.remove( acceptBtn );
				if( invitationAcceptedSuccessfully )
				{
					GitUtils.saveRepoJoined( fullNameRepo );
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

	private static boolean fieldIsEmpty( JLabel errorLabel, JTextField input )
	{
		if( input.getText().trim().isEmpty() )
		{
			errorLabel.setText( String.format( "<html>%s - <span style='color:#fa4545'>%s</span></html>", errorLabel.getText(),
					"Field can not be empty." ) );
			hasErrors = true;
			return true;
		}
		return false;
	}

	private static boolean checkNickname( String nickname )
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
		catch( Exception e )
		{
			return false;
		}

		return response.statusCode() == 200;
	}

	static String getAuthenticatedLogin( String token )
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
		catch( Exception e )
		{
			return null;
		}

		if( response.statusCode() != 200 )
			return null;
		try
		{
			JsonNode body = JSON_MAPPER.readTree( response.body() );
			String login = body.path( "login" ).asText( null );
			return login == null || login.isBlank() ? null : login;
		}
		catch( Exception e )
		{
			return null;
		}
	}

	private static String githubApiBase()
	{
		String base = System.getProperty( "p2pmss.githubApiBase", "https://api.github.com" );
		return base.endsWith( "/" ) ? base.substring( 0, base.length() - 1 ) : base;
	}
}
