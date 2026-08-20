package cloud.google;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JOptionPane;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.UrlEncodedContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.About;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.Permission;
import com.google.api.services.drive.model.PermissionList;
import com.google.api.services.oauth2.Oauth2;
import com.google.api.services.oauth2.model.Userinfo;

import cloud.CloudStorageProvider;
import cloud.ZipUtils;
import minecraftServerManagement.ForgeUtils;
import view.MainFrame;

/**
 * Copias de seguridad del servidor sobre Google Drive. La jerarquia es siempre
 * P2PMSS-Backups/{nombre del servidor}/{zips}, y las carpetas se marcan con
 * appProperties (app + type) para poder encontrarlas por consulta aunque el
 * usuario las mueva o las renombre en su Drive. Un host invitado no es dueno de
 * la carpeta raiz, de ahi que casi toda busqueda se repita con y sin el filtro
 * 'me' in owners. Cualquier fallo de red avisa por dialogo y deja la traza en el
 * log: la app tiene que seguir funcionando en local aunque la nube no responda.
 */
public final class GoogleDriveCloudProvider implements CloudStorageProvider
{

	private static final String APPLICATION_NAME = "PeerToPeerMinecraftServerSystem";
	private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
	private static final List<String> SCOPES = List.of( DriveScopes.DRIVE );
	private static final String CREDENTIALS_FOLDER = app.AppPaths.dataFile( "google_tokens" ).toString();
	private static final Map<String, String> metadata = new HashMap<>();
	private static final Map<String, String> metadataChildren = new HashMap<>();

	public static boolean isSearchingBackUpForClonning = false;

	static
	{
		metadata.put( "app", APPLICATION_NAME );
		metadata.put( "type", "root" );

		metadataChildren.putAll( metadata );
		metadataChildren.put( "type", "children" );
	}

	private Drive drive = null;
	private Credential credential = null;

	// ---- FASE 1 — Sesion ----------------------------------------------------

	@Override
	public void authenticate()
	{

		try (InputStream credentialsInput = GoogleDriveCloudProvider.class
				.getResourceAsStream( "/credentials/GoolgeDriveCredentials.json" ))
		{

			GoogleClientSecrets clientSecrets = GoogleClientSecrets.load( JSON_FACTORY, new InputStreamReader( credentialsInput ) );

			GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
					GoogleNetHttpTransport.newTrustedTransport(),
					JSON_FACTORY,
					clientSecrets,
					SCOPES ).setDataStoreFactory( new FileDataStoreFactory( new File( CREDENTIALS_FOLDER ) ) )
					.setAccessType( "offline" )
					.setApprovalPrompt( "force" )
					.build();

			// El navegador vuelve a una pagina propia en vez de al "recibido" por
			// defecto de la libreria: el usuario tiene que ver que ya puede cerrar
			LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort( 8888 )
					.setLandingPages( "https://p2pmss.vercel.app/OAuth/success", "https://p2pmss.vercel.app/OAuth/failed" ).build();

			Credential authorizedCredential = new AuthorizationCodeInstalledApp( flow, receiver ).authorize( "user" );
			this.credential = authorizedCredential;

			this.drive = new Drive.Builder(
					GoogleNetHttpTransport.newTrustedTransport(),
					JSON_FACTORY,
					authorizedCredential ).setApplicationName( APPLICATION_NAME )
					.build();

		}
		catch( IOException credentialsFailure )
		{
			app.Log.event( "CLOUD_BACKUP", "No se pudo leer el fichero de credenciales de Google Drive", credentialsFailure );
			JOptionPane.showMessageDialog( null, "Credentials file not found or inaccessible or another thing went wrong, try again.",
					"Error", JOptionPane.ERROR_MESSAGE );
		}
		catch( GeneralSecurityException transportFailure )
		{
			app.Log.event( "CLOUD_BACKUP", "No se pudo crear el transporte seguro contra Google Drive", transportFailure );
			JOptionPane.showMessageDialog( null, "Something went wrong, try again.", "Google Drive error", JOptionPane.ERROR_MESSAGE );
		}
	}

	// ---- FASE 2 — Subida y descarga de copias -------------------------------

	@Override
	public void uploadServerBackup( Path serverZip )
	{
		if( drive == null )
		{
			JOptionPane.showMessageDialog( null, "Sign into Google Drive to be able to upload server data.", "Error",
					JOptionPane.ERROR_MESSAGE );
			return;
		}

		try
		{
			String rootFolderId = getServerFolderIdWithMetadata( "P2PMSS-Backups", metadata, iAmOwner() );
			String serverFolderId = getOrCreateServerFolderId( MainFrame.getServerName(), rootFolderId, metadataChildren, iAmOwner(),
					false );
			if( serverFolderId == null )
				getOrCreateServerFolderId( MainFrame.getServerName(), null, metadataChildren, iAmOwner(), false );

			LocalDateTime todayDate = LocalDateTime.now();

			com.google.api.services.drive.model.File fileMetadata = new com.google.api.services.drive.model.File();
			fileMetadata.setName( String.format( "%s_%s", MainFrame.getServerName(), todayDate ) );
			fileMetadata.setParents( List.of( serverFolderId ) );

			FileContent mediaContent = new FileContent( "application/zip", serverZip.toFile() );

			drive.files().create( fileMetadata, mediaContent )
					.setFields( "id, name" )
					.execute();
			// La marca local de fecha se escribe solo si la subida fue bien: si no,
			// la proxima comparacion creeria que la copia remota ya esta al dia
			ZipUtils.createOrModiFyPropertiesFile( MainFrame.getServerName() + "-" + getProviderName(), todayDate.toString(),
					lastServerBackUpDate );
		}
		catch( GoogleJsonResponseException apiFailure )
		{
			app.Log.event( "CLOUD_BACKUP", "Google Drive rechazo la subida de " + serverZip, apiFailure );
			JOptionPane.showMessageDialog( null, "Unable to upload file: " + apiFailure.getDetails(), "Error", JOptionPane.ERROR_MESSAGE );
		}
		catch( IOException uploadFailure )
		{
			app.Log.event( "CLOUD_BACKUP", "No se pudo subir " + serverZip, uploadFailure );
			JOptionPane.showMessageDialog( null, "File not found or inaccessible or another thing went wrong, try again.", "Error",
					JOptionPane.ERROR_MESSAGE );
		}

	}

	/** Solo el dueno tiene la carpeta del servidor colgando de SU raiz P2PMSS-Backups. */
	public boolean iAmOwner()
	{
		boolean result = false;
		String rootFolderId = getServerFolderIdWithMetadata( "P2PMSS-Backups", metadata, true );
		if( rootFolderId != null )
			result = getServerFolderId( MainFrame.getServerName(), rootFolderId ) != null;
		return result;
	}

	@Override
	public boolean downloadServerBackup( Path serverDestinataryFolder )
	{
		boolean result = false;
		do
		{
			if( !hasBackUp() )
				break;

			if( !ZipUtils.existsDirectory( serverDestinataryFolder ) )
				ZipUtils.createDirectory( serverDestinataryFolder );

			String parentFolderId = getServerFolderIdWithMetadata( "P2PMSS-Backups", metadata, iAmOwner() );
			String serverFolderId = getOrCreateServerFolderId( MainFrame.getServerName(), parentFolderId, metadataChildren, iAmOwner(),
					false );
			// Si no cuelga de nuestra raiz, la carpeta es de otro dueno y llega
			// compartida: se busca otra vez sin filtrar por padre
			if( serverFolderId == null )
				serverFolderId = getOrCreateServerFolderId( MainFrame.getServerName(), null, metadataChildren, iAmOwner(), false );
			if( !Files.exists( ZipUtils.DOWNLOADS_BACKUPS_ZIPS_FOLDER ) )
				ZipUtils.createDirectory( ZipUtils.DOWNLOADS_BACKUPS_ZIPS_FOLDER );

			try
			{

				FileList latestBackupList = drive.files().list()
						.setQ( "'" + serverFolderId + "' in parents and trashed = false" )
						.setOrderBy( "createdTime desc" )
						.setPageSize( 1 )
						.setFields( "files(id, name, createdTime)" )
						.execute();

				// Salvaguarda: sin copia remota no se toca la carpeta local
				if( latestBackupList.getFiles().isEmpty() )
					break;


				com.google.api.services.drive.model.File serverRemoteZip;

				try (OutputStream downloadOutput = Files
						.newOutputStream(
								ZipUtils.DOWNLOADS_BACKUPS_ZIPS_FOLDER.resolve( latestBackupList.getFiles().get( 0 ).getName() + ".zip" ) ))
				{
					serverRemoteZip = latestBackupList.getFiles().get( 0 );
					drive.files().get( serverRemoteZip.getId() ).executeMediaAndDownloadTo( downloadOutput );
				}

				// La RAM asignada es configuracion de ESTA maquina, no del backup: se
				// guarda antes de borrar la carpeta para reponerla despues
				String ramAlloc = ForgeUtils.getServerRAMAlloc( serverDestinataryFolder );

				ZipUtils.deleteDirectory( serverDestinataryFolder );
				ZipUtils.createDirectory( serverDestinataryFolder );

				ZipUtils.unzip( Path.of( ZipUtils.DOWNLOADS_BACKUPS_ZIPS_FOLDER.toString() + "/" + serverRemoteZip.getName() + ".zip" ),
						serverDestinataryFolder );

				try
				{
					ForgeUtils.setServerRAMAlloc( serverDestinataryFolder, Integer.parseInt( ramAlloc.replaceAll( "[-Xmx|G]", "" ) ) );
				}
				catch( Exception ramRestoreFailure )
				{
					// Sin RAM previa (servidor recien clonado) se queda la del backup:
					// no es motivo para dar la descarga por fallida
					app.Log.event( "CLOUD_BACKUP", "No se pudo reponer la RAM asignada del servidor", ramRestoreFailure );
				}

				ZipUtils.createOrModiFyPropertiesFile( MainFrame.getServerName() + "-" + getProviderName(),
						serverRemoteZip.getCreatedTime().toString().replace( "Z", "" ), lastServerBackUpDate );

				result = true;

			}
			catch( IOException downloadFailure )
			{
				JOptionPane.showMessageDialog( null, "Something went wrong, try again.", "Error", JOptionPane.ERROR_MESSAGE );
				app.Log.event( "CLOUD_BACKUP", "No se pudo descargar la copia en " + serverDestinataryFolder, downloadFailure );
			}
		} while( false );
		return result;
	}

	/** Hay copia utilizable si existe alguna mas nueva que la ultima que ya tenemos en local. */
	@Override
	public boolean hasBackUp()
	{
		boolean result = false;
		do
		{
			if( drive == null )
				break;

			String appFolderId = getServerFolderIdWithMetadata( "P2PMSS-Backups", metadata );

			String serverFolderId = getOrCreateServerFolderId( MainFrame.getServerName(), appFolderId, metadataChildren, iAmOwner(),
					false );
			if( serverFolderId == null )
			{
				// Segunda pasada sin padre: la carpeta puede llegar compartida por su dueno
				serverFolderId = getOrCreateServerFolderId( MainFrame.getServerName(), null, metadataChildren, iAmOwner(), false );
				if( serverFolderId == null )
					break;
			}

			try
			{
				FileList backupsFileList = drive.files().list()
						.setQ( "'" + serverFolderId + "' in parents and trashed = false" )
						.setSupportsAllDrives( true )
						.setIncludeItemsFromAllDrives( true )
						.setFields( "files(id, name, createdTime)" )
						.execute();

				List<com.google.api.services.drive.model.File> backups = backupsFileList.getFiles();
				if( backups.isEmpty() )
					break;
				// Clonando no hay copia local con la que comparar: vale cualquiera
				else if( isSearchingBackUpForClonning )
				{
					result = true;
					break;
				}

				String savedLocalDateString = ZipUtils.getDataFromPropertiesFile( MainFrame.getServerName() + "-" + getProviderName(),
						lastServerBackUpDate );
				// Sin marca local, una fecha antigua cualquiera hace que toda copia
				// remota cuente como mas nueva
				if( savedLocalDateString == null )
					savedLocalDateString = LocalDateTime.of( LocalDate.of( 2005, 3, 8 ), LocalTime.of( 12, 35, 32, 456 ) ).toString();

				LocalDateTime savedLocalDate = LocalDateTime.parse( savedLocalDateString );
				List<com.google.api.services.drive.model.File> newerBackups = backups.stream()
						.filter( file -> LocalDateTime.parse( file.getCreatedTime().toString().replace( "Z", "" ) )
								.isAfter( savedLocalDate ) )
						.toList();
				if( newerBackups.size() < 1 )
					break;

				result = true;
			}
			catch( IOException listFailure )
			{
				app.Log.event( "CLOUD_BACKUP", "No se pudo listar las copias de " + MainFrame.getServerName(), listFailure );
			}
		} while( false );
		return result;
	}

	@Override
	public String getProviderName()
	{
		return "GoogleDrive";
	}

	// ---- FASE 3 — Resolucion de carpetas ------------------------------------

	private String getServerFolderId( String folderName, String parentFolder )
	{
		return getOrCreateServerFolderId( folderName, parentFolder, null, true, false );
	}

	private String getServerFolderIdWithMetadata( String folderName, Map<String, String> metadata )
	{
		return getOrCreateServerFolderId( folderName, null, metadata, true, false );
	}

	private String getServerFolderIdWithMetadata( String folderName, Map<String, String> metadata, boolean owneds )
	{
		return getOrCreateServerFolderId( folderName, null, metadata, owneds, false );
	}

	private String getOrCreateServerFolderId( String folderName )
	{
		return getOrCreateServerFolderId( folderName, null, null, true, true );
	}

	private String getOrCreateServerFolderIdWithMetadata( String folderName, Map<String, String> metadata )
	{
		return getOrCreateServerFolderId( folderName, null, metadata, true, true );
	}


	/**
	 * Punto unico de busqueda (y creacion opcional) de carpetas. El apostrofe del
	 * nombre se escapa porque la query de Drive va entre comillas simples.
	 */
	private String getOrCreateServerFolderId( String folderName, String parentFolder, Map<String, String> metadata, boolean owneds,
			boolean createIfNotExists )
	{
		String result = null;
		do
		{
			String queryString = "name='%s' and mimeType='application/vnd.google-apps.folder' and trashed=false";

			if( parentFolder != null )
				queryString = "name='%s' and mimeType='application/vnd.google-apps.folder' and '%s' in parents and trashed=false";

			String query = String.format(
					queryString,
					folderName.replace( "'", "\\'" ),
					"%s" );

			if( owneds )
				query += " and 'me' in owners";

			if( parentFolder != null )
				query = String.format(
						query,
						parentFolder );

			// Las appProperties son la marca de la app: distinguen NUESTRA carpeta de
			// otra que el usuario tenga con el mismo nombre
			if( metadata != null )
				query += " and appProperties has { key='app' and value='" + APPLICATION_NAME
						+ "' } and appProperties has { key='type' and value='" + metadata.get( "type" ) + "' }";

			try
			{
				FileList foundFolders = drive.files().list()
						.setQ( query )
						.setSpaces( "drive" )
						.setFields( "files(id, name)" )
						.execute();

				if( !foundFolders.getFiles().isEmpty() )
					result = foundFolders.getFiles().get( 0 ).getId();
			}
			catch( IOException lookupFailure )
			{
				// El refresh token caducado solo se ve en el cuerpo del error: se
				// limpia la sesion local y se vuelve a pedir consentimiento
				if( lookupFailure instanceof TokenResponseException tokenFailure )
				{
					String errorMessage = tokenFailure.getContent();
					boolean tokenExpired = errorMessage.contains( "Token" ) && errorMessage.contains( "expired" );
					if( tokenExpired )
					{
						closeLocalSession();
						authenticate();
					}
				}
			}

			if( result != null )
				break;

			if( !createIfNotExists )
				break;

			try
			{

				com.google.api.services.drive.model.File folderMetadata = new com.google.api.services.drive.model.File();

				folderMetadata.setName( folderName );
				folderMetadata.setMimeType( "application/vnd.google-apps.folder" );
				if( parentFolder != null )
					folderMetadata.setParents( List.of( parentFolder ) );
				if( metadata != null )
					folderMetadata.setAppProperties( metadata );

				com.google.api.services.drive.model.File createdFolder = drive.files()
						.create( folderMetadata )
						.setFields( "id" )
						.execute();

				result = createdFolder.getId();
			}
			catch( IOException creationFailure )
			{
				JOptionPane.showMessageDialog( null,
						"File " + folderName + " not found or inaccessible or another thing went wrong, try again.", "Error",
						JOptionPane.ERROR_MESSAGE );
				app.Log.event( "CLOUD_BACKUP", "No se pudo crear la carpeta " + folderName + " en Google Drive", creationFailure );
			}
		} while( false );
		return result;
	}

	// ---- FASE 4 — Cierre de sesion y permisos -------------------------------

	@Override
	public void closeSession()
	{
		try
		{
			// Revocar en el servidor ademas de borrar el token local: si no, el
			// consentimiento sigue vivo en la cuenta de Google del usuario
			String revokeUrl = "https://oauth2.googleapis.com/revoke?token="
					+ this.credential.getAccessToken();

			Map<String, String> revokeParameters = new HashMap<>();
			revokeParameters.put( "token", credential.getRefreshToken() );

			UrlEncodedContent content = new UrlEncodedContent( revokeParameters );

			HttpRequestFactory requestFactory = GoogleNetHttpTransport.newTrustedTransport()
					.createRequestFactory();

			HttpRequest request = requestFactory.buildPostRequest(
					new GenericUrl( revokeUrl ),
					content );

			request.execute();

			ZipUtils.deleteDirectory( Path.of( CREDENTIALS_FOLDER ) );

			this.credential = null;
			this.drive = null;

		}
		catch( Exception revokeFailure )
		{
			app.Log.event( "CLOUD_BACKUP", "No se pudo revocar la sesion de Google Drive", revokeFailure );
		}
	}

	/** Olvida la sesion en esta maquina sin revocarla en Google: se usa al refrescar un token caducado. */
	public void closeLocalSession()
	{
		ZipUtils.deleteDirectory( Path.of( CREDENTIALS_FOLDER ) );

		this.credential = null;
		this.drive = null;
	}

	@Override
	public boolean isSessionOpened()
	{
		return credential != null & drive != null;
	}

	@Override
	public boolean inviteUser( String email )
	{
		boolean result = false;
		do
		{
			if( drive == null )
				break;

			String parentFolderId = getServerFolderIdWithMetadata( "P2PMSS-Backups", metadata );
			String folderId = getServerFolderId( MainFrame.getServerName(), parentFolderId );

			// Sin carpeta propia no somos el dueno: un invitado no puede reinvitar
			if( folderId == null )
			{
				JOptionPane.showMessageDialog( null, "Only the owner of this server can invite new hosts.", "Error",
						JOptionPane.ERROR_MESSAGE );
				break;
			}

			// writer, no owner: el invitado sube copias pero no puede borrar la carpeta
			Permission permission = new Permission()
					.setType( "user" )
					.setRole( "writer" )
					.setEmailAddress( email );

			try
			{
				drive.permissions().create( folderId, permission )
						.setSendNotificationEmail( true )
						.setFields( "id" )
						.execute();

				result = true;
			}
			catch( IOException invitationFailure )
			{
				JOptionPane.showMessageDialog( null, "File not found or inaccessible or another thing went wrong, try again.", "Error",
						JOptionPane.ERROR_MESSAGE );
				app.Log.event( "CLOUD_BACKUP", "No se pudo invitar a " + email + " a la carpeta del servidor", invitationFailure );
			}
		} while( false );
		return result;
	}

	@Override
	public boolean hasRemoteServerFolder()
	{
		boolean result = false;
		if( drive != null )
		{
			String parentFolderId = getServerFolderIdWithMetadata( "P2PMSS-Backups", metadata );
			String serverFolder = getOrCreateServerFolderId( MainFrame.getServerName(), parentFolderId, metadataChildren, iAmOwner(),
					false );
			if( serverFolder == null )
				getOrCreateServerFolderId( MainFrame.getServerName(), null, metadataChildren, iAmOwner(), false );
			result = serverFolder != null;
		}
		return result;
	}

	// ---- FASE 5 — Alta de carpeta y datos del usuario -----------------------

	@Override
	public void createSavingFolder()
	{
		if( drive == null )
			return;

		String parentId = getOrCreateServerFolderIdWithMetadata( "P2PMSS-Backups", metadata );
		String backupFolderId = getOrCreateServerFolderId( MainFrame.getServerName(), parentId, metadataChildren, true, true );

		// La carpeta se estrena con una copia: una carpeta vacia haria creer al
		// resto de hosts que este servidor no tiene nada que descargar
		ZipUtils.createZip( MainFrame.serverOpenedDirectory.toPath(), ZipUtils.BACKUPS_ZIPS_FOLDER );
		uploadServerBackup( ZipUtils.BACKUPS_ZIPS_FOLDER );

		if( backupFolderId != null )
		{
			JOptionPane.showMessageDialog(
					null,
					"Backups folder created successfully!",
					"Google Drive",
					JOptionPane.INFORMATION_MESSAGE );
			MainFrame.createServerBackupsFolderInCloud.setVisible( false );
		}
	}

	@Override
	public Map<String, Object> getUserInfo()
	{
		Map<String, Object> result = null;
		do
		{
			if( drive == null )
				break;

			try
			{
				About about = drive.about()
						.get()
						.setFields( "user(displayName,emailAddress,permissionId)" )
						.execute();

				Map<String, Object> user = new HashMap<>();
				user.put( "profilePhoto", about.getUser().getPhotoLink() );
				user.put( "displayName", about.getUser().getDisplayName() );
				user.put( "email", about.getUser().getEmailAddress() );
				user.put( "permissionId", about.getUser().getPermissionId() );

				result = user;

			}
			catch( IOException userInfoFailure )
			{
				app.Log.event( "CLOUD_BACKUP", "No se pudo leer el perfil de Google Drive", userInfoFailure );
			}
		} while( false );
		return result;
	}

	/** Carpetas de servidor que otros hosts han compartido con esta cuenta. */
	@Override
	public List<String> getInvitedFolderList()
	{
		if( drive == null )
			return null;
		String query = "mimeType='application/vnd.google-apps.folder' " +
				"and sharedWithMe " +
				"and appProperties has { key='app' and value='" + APPLICATION_NAME + "' }  and trashed=false";

		List<String> result = new ArrayList<>();
		String pageToken = null;

		// Este do/while es paginacion real de la API, no el patron de salida unica:
		// Drive devuelve las carpetas por paginas y hay que pedirlas todas
		do
		{
			FileList files;
			try
			{
				files = drive.files().list()
						.setQ( query )
						.setFields(
								"nextPageToken, files(id, name, owners, parents, appProperties)" )
						.setPageToken( pageToken )
						.execute();

				files.getFiles().forEach( file -> result.add( file.getId() ) );
				pageToken = files.getNextPageToken();
			}
			catch( IOException pageFailure )
			{
				app.Log.event( "CLOUD_BACKUP", "No se pudo listar las carpetas compartidas", pageFailure );
			}

		} while( pageToken != null );
		app.Log.event( "CLOUD_BACKUP", "Carpetas compartidas encontradas: " + result );
		return result;
	}

	/** Dueno y nombre de una carpeta compartida, para poder pintarla en la lista de clonado. */
	public List<String> getRelevantFolderInfo( String folderId )
	{
		List<String> result = null;
		try
		{
			PermissionList permissionList = drive.permissions()
					.list( folderId )
					.setFields(
							"permissions(id, emailAddress, displayName, role, type)" )
					.execute();

			com.google.api.services.drive.model.File folder = drive.files()
					.get( folderId )
					.setFields( "name" )
					.execute();

			// Vale el primer permiso de persona con correo: es el dueno que nos invito
			for( Permission permission : permissionList.getPermissions() )
			{
				boolean isRealUser = "user".equals( permission.getType() )
						&& permission.getEmailAddress() != null
						&& !permission.getEmailAddress().equals( "me" );
				if( isRealUser )
				{
					result = List.of( permission.getEmailAddress(), folder.getName() );
					break;
				}
			}

		}
		catch( IOException permissionsFailure )
		{
			app.Log.event( "CLOUD_BACKUP", "No se pudo leer los permisos de la carpeta " + folderId, permissionsFailure );
		}

		return result;
	}
}
