package jgit;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JOptionPane;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import minecraftServerManagement.ForgeUtils;
import view.MainFrame;

/**
 * Todo el trato con Git y con la API de GitHub: alta del repositorio privado del
 * mundo, backups por lotes verificados, invitaciones a otros peers y autosave en
 * caliente mientras el servidor corre.
 *
 * <p>Dos decisiones gobiernan este fichero. La primera: un backup solo se da por
 * bueno cuando GitHub confirma el push, nunca cuando el commit local existe; por
 * eso cada lote se comprueba antes de crear el siguiente. La segunda: todas las
 * operaciones remotas llevan timeout, porque una red colgada a medias dejaba
 * push y pull bloqueados para siempre y con ellos el botón STOP.</p>
 *
 * <p>Los métodos son síncronos por diseño: quien los llame debe hacerlo fuera
 * del hilo de eventos de Swing.</p>
 */
public final class GitUtils
{

	// ---- FASE 1 — Estado compartido y contratos de resultado ---------------

	public static volatile boolean serverAutoSaveIsActive = false;
	public static int autoSaveSecondsInterval = 10/*minutes*/ * 60; // Default 10 min: pierde poco y no infla el repo.
	public static Thread autoSaveProcess = null; //By default.

	public static final Path JOINED_REPOS = app.AppPaths.dataFile( "joined_repos.properties" );
	private static final String GITHUB_API_PROPERTY = "p2pmss.githubApiBase";
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds( 30 );
	// Sin timeout, una red colgada a medias deja push/pull bloqueados para siempre
	// (y con ellos el boton STOP). Valores generosos para mundos grandes.
	static final int REMOTE_GIT_TIMEOUT_SECONDS = 600;
	static final int CLONE_TIMEOUT_SECONDS = 1800;
	static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout( REQUEST_TIMEOUT ).build();
	static final ObjectMapper JSON_MAPPER = new ObjectMapper();
	private static final String BACKUP_IGNORE_START = "# BEGIN P2PMSS MANAGED BACKUP EXCLUDES";
	private static final String BACKUP_IGNORE_END = "# END P2PMSS MANAGED BACKUP EXCLUDES";
	private static final List<String> BACKUP_IGNORE_LINES = List.of(
			BACKUP_IGNORE_START,
			"# Runtime output and local rollback data are not part of a playable server backup.",
			"/logs/",
			"/crash-reports/",
			"/world-import-backups/",
			"/.p2pmss-import-*/",
			"/bluemap/",
			"/.fabric/",
			"**/session.lock",
			"**/*.tmp",
			"**/.DS_Store",
			BACKUP_IGNORE_END );

	public record GitHubRepositoryResult( boolean success, String cloneUrl, int statusCode, String message, boolean existing )
	{
		/** Repositorio recien creado en la cuenta del usuario. */
		static GitHubRepositoryResult created( String cloneUrl, int statusCode )
		{
			return new GitHubRepositoryResult( true, cloneUrl, statusCode, "Repository created.", false );
		}

		/** El repositorio ya estaba en GitHub y se reutiliza tal cual. */
		static GitHubRepositoryResult existingLinked( String cloneUrl, int statusCode )
		{
			return new GitHubRepositoryResult( true, cloneUrl, statusCode, "The existing repository will be linked.", true );
		}

		/** Fallo antes de hablar con GitHub: no hay codigo de estado que dar. */
		static GitHubRepositoryResult failure( String message )
		{
			return new GitHubRepositoryResult( false, null, 0, message, false );
		}

		static GitHubRepositoryResult failure( int statusCode, String message )
		{
			return new GitHubRepositoryResult( false, null, statusCode, message, false );
		}

		/** Fallo mientras se recuperaba un repositorio que YA existia. */
		static GitHubRepositoryResult existingFailure( String message )
		{
			return new GitHubRepositoryResult( false, null, 0, message, true );
		}

		static GitHubRepositoryResult existingFailure( int statusCode, String message )
		{
			return new GitHubRepositoryResult( false, null, statusCode, message, true );
		}
	}

	public record PrivateBackupSetupResult( boolean success, boolean alreadyLinked, boolean existingRemote, String message )
	{
		/** El servidor no llego siquiera a intentar enlazarse con GitHub. */
		static PrivateBackupSetupResult failure( String message )
		{
			return new PrivateBackupSetupResult( false, false, false, message );
		}

		/** Fallo durante el alta; existingRemote dice si el repositorio ya estaba alli. */
		static PrivateBackupSetupResult setupFailure( boolean existingRemote, String message )
		{
			return new PrivateBackupSetupResult( false, false, existingRemote, message );
		}

		/** El servidor ya estaba enlazado y el backup posterior fallo. */
		static PrivateBackupSetupResult alreadyLinkedFailure( String message )
		{
			return new PrivateBackupSetupResult( false, true, true, message );
		}

		/** El servidor ya estaba enlazado y su estado quedo confirmado en GitHub. */
		static PrivateBackupSetupResult alreadyLinked( String message )
		{
			return new PrivateBackupSetupResult( true, true, true, message );
		}

		/** Alta completada; existingRemote dice si se reutilizo un repositorio previo. */
		static PrivateBackupSetupResult created( boolean existingRemote, String message )
		{
			return new PrivateBackupSetupResult( true, false, existingRemote, message );
		}
	}

	public record BackupPushResult( boolean success, int committedBatches, String message )
	{
	}

	// ---- FASE 2 — Alta del backup privado ----------------------------------

	/**
	 * Crea (o recupera) el repositorio privado de un servidor, hace el primer push
	 * verificado y marca los ficheros que son de esta máquina. Es síncrono por
	 * diseño: no debe llamarse desde el hilo de eventos de Swing.
	 */
	public static PrivateBackupSetupResult configurePrivateBackup( Path repoDirectory, String repoName )
	{
		PrivateBackupSetupResult result;
		do
		{
			if( repoDirectory == null || !Files.isDirectory( repoDirectory ) )
			{
				result = PrivateBackupSetupResult.failure( "The selected server folder is not accessible." );
				break;
			}
			if( repoName == null || repoName.isBlank() )
			{
				result = PrivateBackupSetupResult.failure( "The server needs a valid repository name." );
				break;
			}
			if( !ensureBackupIgnoreFile( repoDirectory ) )
			{
				result = PrivateBackupSetupResult.failure( "The backup exclusion file could not be prepared." );
				break;
			}

			Map<String, String> userData;
			try
			{
				userData = TokenStore.getSavedUserData();
			}
			catch( Exception invalidSession )
			{
				result = PrivateBackupSetupResult.failure( "Sign into GitHub again before protecting this world." );
				break;
			}

			if( repoExistInPath( repoDirectory ) && hasRemoteOrigin( repoDirectory ) )
			{
				if( !setLocalIdentity( repoDirectory ) )
				{
					result = PrivateBackupSetupResult.alreadyLinkedFailure( "The repository identity could not be configured." );
					break;
				}
				BackupPushResult backup = commitAndPush( repoDirectory, false );
				if( !backup.success() )
				{
					result = PrivateBackupSetupResult.alreadyLinkedFailure( backup.message() );
					break;
				}
				if( !protectMachineLocalFiles( repoDirectory ) )
				{
					result = PrivateBackupSetupResult.alreadyLinkedFailure(
							"The world was pushed, but local-only files could not be protected." );
					break;
				}
				// Cero lotes no es un fallo: significa que GitHub ya tenia exactamente
				// lo que hay en disco
				String confirmation = backup.committedBatches() == 0
						? "Private GitHub backup is linked and remote state was confirmed."
						: backup.message();
				result = PrivateBackupSetupResult.alreadyLinked( confirmation );
				break;
			}

			// El preflight va ANTES de crear nada remoto: un mundo con un fichero de
			// mas de 100 MiB no debe dejar un repositorio vacio tirado en la cuenta.
			// Solo se recorre el disco en el alta: en un repo ya enlazado el preflight
			// lo hace commitAndPush y unicamente cuando hay cambios que subir
			GitBackupPreflight.Result preflight = GitBackupPreflight.inspect( repoDirectory );
			if( !preflight.safe() )
			{
				result = PrivateBackupSetupResult.failure( preflight.message() );
				break;
			}

			GitHubRepositoryResult remote = createRepoInGitHub( userData.get( "token" ), repoName );
			if( !remote.success() )
			{
				result = PrivateBackupSetupResult.setupFailure( remote.existing(), remote.message() );
				break;
			}
			if( !createRepoIfNotExistsInPath( repoDirectory ) )
			{
				result = PrivateBackupSetupResult.setupFailure( remote.existing(),
						"The local Git repository could not be initialized." );
				break;
			}

			BackupPushResult initialBackup = linkLocalRepoToExternalResult( remote.cloneUrl(), repoDirectory );
			if( !initialBackup.success() )
			{
				String reason = remote.existing()
						? "The existing repository could not accept the next backup batch. " + initialBackup.message()
						: "GitHub did not accept the next initial backup batch. " + initialBackup.message();
				result = PrivateBackupSetupResult.setupFailure( remote.existing(), reason );
				break;
			}
			if( !protectMachineLocalFiles( repoDirectory ) )
			{
				result = PrivateBackupSetupResult.setupFailure( remote.existing(),
						"The world was pushed, but local-only files could not be protected." );
				break;
			}

			String linkNote = remote.existing() ? "Existing private repository linked. " : "Private repository created. ";
			result = PrivateBackupSetupResult.created( remote.existing(), linkNote + initialBackup.message() );
		} while( false );
		return result;
	}

	/**
	 * Añade un bloque gestionado sin pisar las reglas de ignore que haya escrito el
	 * dueño del servidor. Dejar los logs fuera de Git reduce el tamaño de la subida
	 * inicial y evita que una consola en marcha ensucie el árbol tras cada pull.
	 */
	static boolean ensureBackupIgnoreFile( Path repoDirectory )
	{
		boolean result;
		Path ignoreFile = repoDirectory.resolve( ".gitignore" );
		try
		{
			List<String> original = Files.exists( ignoreFile )
					? new ArrayList<>( Files.readAllLines( ignoreFile ) )
					: new ArrayList<>();
			List<String> updated = new ArrayList<>();
			boolean insideManagedBlock = false;
			int insertionPoint = -1;

			// Se reescribe el bloque entero en su sitio original: asi las reglas del
			// usuario conservan su orden aunque el bloque gestionado cambie
			for( String line : original )
			{
				if( BACKUP_IGNORE_START.equals( line ) )
				{
					insideManagedBlock = true;
					if( insertionPoint < 0 )
						insertionPoint = updated.size();
					continue;
				}
				if( insideManagedBlock && BACKUP_IGNORE_END.equals( line ) )
				{
					insideManagedBlock = false;
					continue;
				}
				if( !insideManagedBlock )
					updated.add( line );
			}
			if( insertionPoint < 0 )
			{
				if( !updated.isEmpty() && !updated.getLast().isBlank() )
					updated.add( "" );
				insertionPoint = updated.size();
			}
			updated.addAll( insertionPoint, BACKUP_IGNORE_LINES );
			Files.write( ignoreFile, updated, StandardCharsets.UTF_8 );
			result = true;
		}
		catch( IOException ignoreFileFailure )
		{
			app.Log.event( "GIT_BACKUP", "No se pudo escribir el .gitignore gestionado en " + repoDirectory, ignoreFileFailure );
			result = false;
		}
		return result;
	}

	static boolean protectMachineLocalFiles( Path repoDirectory )
	{
		return protectMachineLocalFile( repoDirectory, Path.of( "server.properties" ) )
				&& protectMachineLocalFile( repoDirectory, Path.of( "user_jvm_args.txt" ) );
	}

	private static boolean protectMachineLocalFile( Path repoDirectory, Path relativePath )
	{
		boolean result;
		do
		{
			// Decidir por pertenencia al indice, no por existencia en disco: Forge crea
			// server.properties en el primer arranque, asi que el fichero puede existir
			// sin haberse indexado nunca (lo cubre .git/info/exclude). Pedir
			// setSkipWorktree sobre un fichero no indexado hacia que el backup de
			// parada entero reportase PUSH FAILED aun con todos los lotes confirmados.
			if( isTrackedInIndex( repoDirectory, relativePath ) )
			{
				result = setSkipWorktree( repoDirectory, relativePath, true );
				break;
			}

			Path exclude = repoDirectory.resolve( ".git/info/exclude" );
			try
			{
				Files.createDirectories( exclude.getParent() );
				List<String> lines = Files.exists( exclude ) ? Files.readAllLines( exclude ) : new ArrayList<>();
				String pattern = relativePath.toString().replace( '\\', '/' );
				if( !lines.contains( pattern ) )
				{
					lines.add( pattern );
					Files.write( exclude, lines );
				}
				result = true;
			}
			catch( IOException excludeFailure )
			{
				app.Log.event( "GIT_BACKUP", "No se pudo excluir " + relativePath + " en " + repoDirectory, excludeFailure );
				result = false;
			}
		} while( false );
		return result;
	}

	private static boolean isTrackedInIndex( Path repoDirectory, Path relativePath )
	{
		boolean result;
		try (Git git = Git.open( repoDirectory.toFile() ))
		{
			String indexPath = relativePath.toString().replace( '\\', '/' );
			result = git.getRepository().readDirCache().getEntry( indexPath ) != null;
		}
		catch( Exception indexFailure )
		{
			// Sin indice legible se asume no rastreado: la ruta alternativa
			// (.git/info/exclude) es inofensiva aunque sobre
			result = false;
		}
		return result;
	}

	// ---- FASE 3 — Repositorio local: init, identidad y flags ---------------

	public static boolean createRepoInPath( Path repoDirectory )
	{
		boolean result;
		do
		{
			Map<String, String> userdata;
			try
			{
				userdata = TokenStore.getSavedUserData();
			}
			catch( Exception invalidSession )
			{
				result = false;
				break;
			}

			try (Git git = Git.init().setDirectory( repoDirectory.toFile() ).call())
			{
				result = setLocalIdentity( git, userdata );
			}
			catch( Exception initFailure )
			{
				app.Log.event( "GIT_BACKUP", "No se pudo inicializar el repositorio en " + repoDirectory, initFailure );
				result = false;
			}
		} while( false );
		return result;
	}

	public static boolean createRepoIfNotExistsInPath( Path repoDirectory )
	{
		return repoExistInPath( repoDirectory ) || createRepoInPath( repoDirectory );
	}

	public static boolean repoExistInPath( Path repoDirectory )
	{
		boolean result;
		try (Git ignored = Git.open( repoDirectory.toFile() ))
		{
			result = true;
		}
		catch( IOException notARepository )
		{
			result = false;
		}
		return result;
	}

	public static boolean hasRemoteOrigin( Path repoDirectory )
	{
		boolean result;
		try (Git git = Git.open( repoDirectory.toFile() ))
		{
			String originUrl = git.getRepository().getConfig().getString( "remote", "origin", "url" );
			result = originUrl != null && !originUrl.isBlank();
		}
		catch( IOException notARepository )
		{
			result = false;
		}
		return result;
	}

	/** Resuelve "owner/repo" desde la URL de origin, o null si el servidor no está enlazado. */
	public static String remoteRepoFullName( Path repoDirectory )
	{
		String result;
		try (Git git = Git.open( repoDirectory.toFile() ))
		{
			String originUrl = git.getRepository().getConfig().getString( "remote", "origin", "url" );
			result = parseRepoFullName( originUrl );
		}
		catch( IOException notARepository )
		{
			result = null;
		}
		return result;
	}

	/** Acepta tanto la forma https como la ssh, con o sin sufijo .git. */
	static String parseRepoFullName( String originUrl )
	{
		String result;
		do
		{
			if( originUrl == null || originUrl.isBlank() )
			{
				result = null;
				break;
			}
			Matcher matcher = Pattern.compile( "github\\.com[:/]([^/:]+)/([^/:]+?)(?:\\.git)?/?$" ).matcher( originUrl.trim() );
			if( !matcher.find() )
			{
				result = null;
				break;
			}
			result = matcher.group( 1 ) + "/" + matcher.group( 2 );
		} while( false );
		return result;
	}

	public static void removeRemoteOrigin( Path repoDirectory )
	{
		try (Git git = Git.open( repoDirectory.toFile() ))
		{
			StoredConfig config = git.getRepository().getConfig();
			config.unsetSection( "remote", "origin" );
			config.save();
		}
		catch( Exception unlinkFailure )
		{
			app.Log.event( "GIT_BACKUP", "No se pudo quitar el remoto origin de " + repoDirectory, unlinkFailure );
		}
	}

	public static boolean linkLocalRepoToExternal( String cloneUrl, String token, Path repoDirectory )
	{
		return linkLocalRepoToExternalResult( cloneUrl, repoDirectory ).success();
	}

	private static BackupPushResult linkLocalRepoToExternalResult( String cloneUrl, Path repoDirectory )
	{
		BackupPushResult result;
		do
		{
			Map<String, String> userdata;
			try
			{
				userdata = TokenStore.getSavedUserData();
			}
			catch( Exception invalidSession )
			{
				result = new BackupPushResult( false, 0, "Sign into GitHub again before retrying." );
				break;
			}

			try (Git git = Git.open( repoDirectory.toFile() ))
			{
				if( cloneUrl == null || cloneUrl.isBlank() )
				{
					result = new BackupPushResult( false, 0, "GitHub did not provide a repository URL." );
					break;
				}
				if( !setLocalIdentity( git, userdata ) )
				{
					result = new BackupPushResult( false, 0, "The local Git identity could not be configured." );
					break;
				}

				StoredConfig config = git.getRepository().getConfig();
				config.setString( "remote", "origin", "url", cloneUrl );
				config.setString( "remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*" );
				config.save();
			}
			catch( Exception linkFailure )
			{
				app.Log.event( "GIT_BACKUP", "No se pudo enlazar " + repoDirectory + " con GitHub", linkFailure );
				result = new BackupPushResult( false, 0, "The local repository could not be linked to GitHub." );
				break;
			}

			// El primer backup va fuera del try: el repositorio debe estar cerrado
			// antes de que commitAndPush lo vuelva a abrir
			result = commitAndPush( repoDirectory, false );
		} while( false );
		return result;
	}

	/*
	 * Writes the identity of the account signed into the application into the LOCAL configuration of the repository (.git/config).
	 * The local configuration takes precedence over the user's global ~/.gitconfig, so the commits made by this application are
	 * always signed with the application account, and the git identity of the machine is neither inherited nor modified.
	 */
	public static boolean setLocalIdentity( Git git, Map<String, String> userdata )
	{
		boolean result;
		do
		{
			try
			{
				if( userdata == null || isBlank( userdata.get( "nickname" ) ) || isBlank( userdata.get( "email" ) ) )
				{
					result = false;
					break;
				}
				StoredConfig config = git.getRepository().getConfig();
				config.setString( "user", null, "name", userdata.get( "nickname" ) );
				config.setString( "user", null, "email", userdata.get( "email" ) );
				config.save();
				result = true;
			}
			catch( Exception identityFailure )
			{
				app.Log.event( "GIT_AUTH", "No se pudo escribir la identidad local de Git", identityFailure );
				result = false;
			}
		} while( false );
		return result;
	}

	public static boolean setLocalIdentity( Path repoDirectory )
	{
		boolean result;
		do
		{
			Map<String, String> userdata;
			try
			{
				userdata = TokenStore.getSavedUserData();
			}
			catch( Exception invalidSession )
			{
				result = false;
				break;
			}

			try (Git git = Git.open( repoDirectory.toFile() ))
			{
				result = setLocalIdentity( git, userdata );
			}
			catch( Exception openFailure )
			{
				app.Log.event( "GIT_BACKUP", "No se pudo abrir el repositorio " + repoDirectory, openFailure );
				result = false;
			}
		} while( false );
		return result;
	}

	public static boolean setSkipWorktree( Path repoDirectory, Path filePath, boolean shouldSkip )
	{
		boolean result;
		try (Git git = Git.open( repoDirectory.toFile() ))
		{
			result = setSkipWorktree( git, repoDirectory, filePath, shouldSkip );
		}
		catch( Exception openFailure )
		{
			app.Log.event( "GIT_BACKUP", "No se pudo abrir el repositorio " + repoDirectory, openFailure );
			result = false;
		}
		return result;
	}

	private static boolean setSkipWorktree( Git git, Path repoDirectory, Path filePath, boolean shouldSkip )
	{
		boolean result;
		do
		{
			Path absoluteRepo = repoDirectory.toAbsolutePath().normalize();
			Path absoluteFile = filePath.isAbsolute()
					? filePath.toAbsolutePath().normalize()
					: absoluteRepo.resolve( filePath ).normalize();
			// Un fichero fuera del repositorio jamas debe tocar su indice
			if( !absoluteFile.startsWith( absoluteRepo ) )
			{
				result = false;
				break;
			}
			// Nada que marcar todavia: no es un error, el fichero puede aparecer luego
			if( !Files.exists( absoluteFile ) )
			{
				result = true;
				break;
			}

			String relativePath = absoluteRepo.relativize( absoluteFile ).toString().replace( '\\', '/' );
			Repository repo = git.getRepository();

			DirCache cache = null;
			try
			{
				if( repo.readDirCache().getEntry( relativePath ) == null )
				{
					result = false;
					break;
				}
				cache = repo.lockDirCache();
				DirCacheEditor editor = cache.editor();
				editor.add( new DirCacheEditor.PathEdit( relativePath )
				{
					@Override
					public void apply( DirCacheEntry entry )
					{
						// JGit expone el bit skip-worktree como solo lectura. Assume-valid es
						// su flag local equivalente y mantiene estos ficheros rastreados pero
						// especificos de la maquina fuera de status/add/commit, sin necesitar
						// el Git nativo instalado.
						entry.setAssumeValid( shouldSkip );
					}
				} );
				editor.commit();
				result = true;
			}
			catch( Exception indexFailure )
			{
				app.Log.event( "GIT_BACKUP", "No se pudo marcar " + relativePath + " en el indice", indexFailure );
				result = false;
			}
			finally
			{
				// El lock del indice se suelta pase lo que pase: dejarlo tomado deja el
				// repositorio inutilizable para cualquier operacion posterior
				if( cache != null )
					cache.unlock();
			}
		} while( false );
		return result;
	}

	// ---- FASE 4 — API de GitHub: repos, invitaciones y colaboradores -------

	public static GitHubRepositoryResult createRepoInGitHub( String token, String repoName )
	{
		GitHubRepositoryResult result;
		do
		{
			String json;
			try
			{
				json = JSON_MAPPER.createObjectNode().put( "name", repoName ).put( "private", true ).toString();
			}
			catch( Exception serializationFailure )
			{
				result = GitHubRepositoryResult.failure( "The repository request could not be created." );
				break;
			}

			HttpRequest request = HttpRequest.newBuilder()
					.uri( URI.create( githubApiBase() + "/user/repos" ) )
					.POST( HttpRequest.BodyPublishers.ofString( json ) )
					.timeout( REQUEST_TIMEOUT )
					.header( "Authorization", "Bearer " + token )
					.header( "User-Agent", "Peer_To_Peer_Minecraft_Server_System/1.0" )
					.header( "Accept", "application/vnd.github+json" )
					.header( "Content-Type", "application/json" )
					.header( "X-GitHub-Api-Version", "2022-11-28" )
					.build();

			HttpResponse<String> response;
			try
			{
				response = HTTP_CLIENT.send( request, HttpResponse.BodyHandlers.ofString() );
			}
			catch( Exception requestFailure )
			{
				result = GitHubRepositoryResult.failure( "GitHub could not be reached. Check the connection and try again." );
				break;
			}

			// Un 401 significa token muerto: se cierra la sesion aqui para que la UI
			// pida credenciales nuevas en vez de reintentar en bucle
			if( response.statusCode() == 401 )
				TokenStore.invalidateSession();

			if( response.statusCode() == 201 )
			{
				result = repositoryResultFromBody( response.body(), response.statusCode(), false );
				break;
			}

			if( response.statusCode() == 422 )
			{
				// 422 = el nombre ya existe en la cuenta. Se intenta reutilizar ese
				// repositorio; si no se puede, cae al mensaje de error de GitHub
				GitHubRepositoryResult existing = null;
				try
				{
					Map<String, String> userData = TokenStore.getSavedUserData();
					existing = getExistingRepository( token, userData.get( "nickname" ), repoName );
				}
				catch( Exception invalidSession )
				{
					// Sin sesion no hay a quien preguntarle por el repositorio existente
				}
				if( existing != null && existing.success() )
				{
					result = existing;
					break;
				}
			}

			result = GitHubRepositoryResult.failure( response.statusCode(), githubErrorMessage( response ) );
		} while( false );
		return result;
	}

	private static GitHubRepositoryResult getExistingRepository( String token, String owner, String repoName )
	{
		GitHubRepositoryResult result;
		do
		{
			String url = githubApiBase() + "/repos/" + encodePathSegment( owner ) + "/" + encodePathSegment( repoName );
			HttpRequest request = authenticatedRequest( url, token ).GET().build();
			try
			{
				HttpResponse<String> response = HTTP_CLIENT.send( request, HttpResponse.BodyHandlers.ofString() );
				if( response.statusCode() == 401 )
					TokenStore.invalidateSession();
				if( response.statusCode() == 200 )
				{
					result = repositoryResultFromBody( response.body(), response.statusCode(), true );
					break;
				}
				result = GitHubRepositoryResult.existingFailure( response.statusCode(), githubErrorMessage( response ) );
			}
			catch( Exception requestFailure )
			{
				result = GitHubRepositoryResult.existingFailure( "The existing repository could not be checked." );
			}
		} while( false );
		return result;
	}

	private static GitHubRepositoryResult repositoryResultFromBody( String body, int statusCode, boolean existing )
	{
		GitHubRepositoryResult result;
		try
		{
			JsonNode json = JSON_MAPPER.readTree( body );
			String cloneUrl = json.path( "clone_url" ).asText( null );
			// Sin clone_url el repositorio es inservible aunque GitHub diga 201
			if( cloneUrl == null || cloneUrl.isBlank() )
				throw new IOException( "clone_url missing" );
			result = existing
					? GitHubRepositoryResult.existingLinked( cloneUrl, statusCode )
					: GitHubRepositoryResult.created( cloneUrl, statusCode );
		}
		catch( Exception invalidBody )
		{
			String message = "GitHub returned an invalid repository response.";
			result = existing
					? GitHubRepositoryResult.existingFailure( statusCode, message )
					: GitHubRepositoryResult.failure( statusCode, message );
		}
		return result;
	}

	/** Prefiere el motivo que da GitHub; si el cuerpo no es su JSON de error, queda el código. */
	private static String githubErrorMessage( HttpResponse<String> response )
	{
		String result = "GitHub error " + response.statusCode() + ".";
		try
		{
			String message = JSON_MAPPER.readTree( response.body() ).path( "message" ).asText();
			if( !message.isBlank() )
				result = "GitHub error " + response.statusCode() + ": " + message;
		}
		catch( Exception unreadableBody )
		{
			// Cuerpo no JSON (una pagina de error de proxy, por ejemplo): vale el generico
		}
		return result;
	}

	public static Map<String, String> convertJsonStringToMap( String json )
	{
		Map<String, String> responseMap = new HashMap<>();
		String withoutBraces = json.replaceAll( "[{|}]", "" );
		for( String keyValueLine : withoutBraces.split( ",\"" ) )
		{
			String[] cells = keyValueLine.split( "\":" );
			responseMap.put( cells[0].replace( '"', ' ' ).trim(), cells[1].replace( '"', ' ' ).trim() );
		}
		return responseMap;
	}

	public static List<Map<String, Object>> convertJsonStringToMapJson( String json )
	{
		List<Map<String, Object>> result;
		ObjectMapper mapper = new ObjectMapper();
		try
		{
			result = mapper.readValue( json, new TypeReference<>()
			{
			} );
		}
		catch( Exception invalidJson )
		{
			result = null;
		}
		return result;
	}

	/** Invita al usuario indicado al repositorio del servidor abierto ahora mismo. */
	public static boolean inviteHostingUser( String username )
	{
		boolean result;
		try
		{
			Map<String, String> userData = TokenStore.getSavedUserData();
			String repo = MainFrame.getServerName();
			result = inviteHostingUser( userData.get( "token" ), userData.get( "nickname" ), repo, username );
		}
		catch( Exception invalidSession )
		{
			JOptionPane.showMessageDialog( null, "Something went wrong, try again.", "Error", JOptionPane.ERROR_MESSAGE );
			result = false;
		}
		return result;
	}

	public static boolean inviteHostingUser( String token, String owner, String repo, String username )
	{
		boolean result;
		do
		{
			// Permiso push, no admin: un invitado juega y hostea, pero no puede
			// borrar el repositorio del mundo
			String json = """
					{
						"permission": "push"
					}
					""";

			String collaboratorUrl = githubApiBase() + "/repos/" + encodePathSegment( owner ) + "/" + encodePathSegment( repo )
					+ "/collaborators/" + encodePathSegment( username );
			HttpRequest request = authenticatedRequest( collaboratorUrl, token )
					.PUT( HttpRequest.BodyPublishers.ofString( json ) )
					.header( "Content-Type", "application/json" )
					.build();

			HttpResponse<String> response;
			try
			{
				response = HTTP_CLIENT.send( request, HttpResponse.BodyHandlers.ofString() );
			}
			catch( Exception requestFailure )
			{
				JOptionPane.showMessageDialog( null, "Something went wrong, try again.", "Error", JOptionPane.ERROR_MESSAGE );
				result = false;
				break;
			}

			if( response.statusCode() == 401 )
				TokenStore.invalidateSession();
			// 201 = invitacion creada, 204 = ya era colaborador
			result = response.statusCode() == 201 || response.statusCode() == 204;
		} while( false );
		return result;
	}

	public static List<Map<String, Object>> getAllPendingInvitations()
	{
		List<Map<String, Object>> result;
		do
		{
			String token;
			try
			{
				token = TokenStore.getSavedUserData().get( "token" );
			}
			catch( Exception invalidSession )
			{
				result = null;
				break;
			}

			HttpRequest request = authenticatedRequest( githubApiBase() + "/user/repository_invitations", token )
					.GET()
					.build();
			HttpResponse<String> response;
			try
			{
				response = HTTP_CLIENT.send( request, HttpResponse.BodyHandlers.ofString() );
			}
			catch( Exception requestFailure )
			{
				JOptionPane.showMessageDialog( null, "Something went wrong, try again.", "Error", JOptionPane.ERROR_MESSAGE );
				result = null;
				break;
			}

			if( response.statusCode() == 401 )
				TokenStore.invalidateSession();
			if( response.statusCode() != 200 )
			{
				result = null;
				break;
			}

			try
			{
				result = JSON_MAPPER.readValue( response.body(), new TypeReference<>()
				{
				} );
			}
			catch( Exception invalidBody )
			{
				result = null;
			}
		} while( false );
		return result;
	}

	public static boolean acceptInvitationById( int id )
	{
		boolean result;
		do
		{
			String token;
			try
			{
				token = TokenStore.loadToken();
			}
			catch( Exception invalidSession )
			{
				result = false;
				break;
			}

			HttpRequest request = authenticatedRequest( githubApiBase() + "/user/repository_invitations/" + id, token )
					.method( "PATCH", HttpRequest.BodyPublishers.noBody() )
					.build();

			HttpResponse<String> response;
			try
			{
				response = HTTP_CLIENT.send( request, HttpResponse.BodyHandlers.ofString() );
			}
			catch( Exception requestFailure )
			{
				result = false;
				break;
			}

			if( response.statusCode() == 401 )
				TokenStore.invalidateSession();
			// Cualquier 2xx cuenta como aceptada
			boolean accepted = Integer.toString( response.statusCode() ).startsWith( "2" );
			result = accepted;
		} while( false );
		return result;
	}

	public static void saveRepoJoined( String repo )
	{
		do
		{
			Map<String, String> userData;
			try
			{
				userData = TokenStore.getSavedUserData();
			}
			catch( Exception invalidSession )
			{
				JOptionPane.showMessageDialog( null, "Session invalid, consider sign in again.", "Error", JOptionPane.ERROR_MESSAGE );
				break;
			}

			String nickname = userData.get( "nickname" );
			Path joinedRepos = joinedReposPath();
			Properties props = new Properties();
			// La lista se guarda por usuario: en una maquina compartida cada cuenta ve
			// solo los mundos a los que ella se unio
			String propertyName = "joined_repos_by_" + nickname;
			try
			{
				Files.createDirectories( joinedRepos.getParent() );
				if( Files.exists( joinedRepos ) )
				{
					try (FileInputStream in = new FileInputStream( joinedRepos.toFile() ))
					{
						props.load( in );
					}
				}

				// LinkedHashSet: sin duplicados y conservando el orden de union
				Set<String> repos = new LinkedHashSet<>();
				String previousValue = props.getProperty( propertyName, "" );
				if( !previousValue.isBlank() )
					repos.addAll( Arrays.asList( previousValue.split( "," ) ) );
				repos.add( repo );
				props.setProperty( propertyName, String.join( ",", repos ) );

				try (FileOutputStream out = new FileOutputStream( joinedRepos.toFile() ))
				{
					props.store( out, "Updated joined repos by users." );
				}
			}
			catch( IOException writeFailure )
			{
				app.Log.event( "GIT_BACKUP", "No se pudo guardar la lista de repos unidos en " + joinedRepos, writeFailure );
				JOptionPane.showMessageDialog( null, "File not found or inaccessible " + joinedRepos, "Error", JOptionPane.ERROR_MESSAGE );
			}
		} while( false );
	}

	public static List<String> getRepoJoined()
	{
		List<String> result = null;
		do
		{
			Path joinedRepos = joinedReposPath();
			if( !(Files.exists( joinedRepos )) )
				break;

			Properties props = new Properties();
			try (FileInputStream in = new FileInputStream( joinedRepos.toFile() ))
			{
				Map<String, String> userData = TokenStore.getSavedUserData();

				props.load( in );
				String propertyName = "joined_repos_by_" + userData.get( "nickname" );
				if( props.containsKey( propertyName ) )
				{
					String[] reposArray = props.getProperty( propertyName ).split( "," );
					result = Arrays.asList( reposArray );
				}
			}
			catch( IOException readFailure )
			{
				JOptionPane.showMessageDialog( null, "File not found or inaccessible " + joinedRepos, "Error", JOptionPane.ERROR_MESSAGE );
			}
			catch( Exception invalidSession )
			{
				JOptionPane.showMessageDialog( null, "Session invalid, consider sign in again.", "Error", JOptionPane.ERROR_MESSAGE );
			}
		} while( false );
		return result;
	}

	// ---- FASE 5 — Clonado y backup por lotes -------------------------------

	public static boolean cloneRepoInPath( Path clonePath, String repoFullName )
	{
		return cloneRepoFromUrl( clonePath, "https://github.com/%s.git".formatted( repoFullName ) );
	}

	static boolean cloneRepoFromUrl( Path clonePath, String cloneUrl )
	{
		boolean result;
		do
		{
			Map<String, String> userdata;
			try
			{
				userdata = TokenStore.getSavedUserData();
			}
			catch( Exception invalidSession )
			{
				JOptionPane.showMessageDialog( null, "Session invalid, consider sign in again.", "Error", JOptionPane.ERROR_MESSAGE );
				result = false;
				break;
			}

			UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider( userdata.get( "nickname" ),
					userdata.get( "token" ) );

			try (Git git = Git.cloneRepository()
					.setURI( cloneUrl )
					.setDirectory( clonePath.toFile() )
					.setCredentialsProvider( credentials )
					.setTimeout( CLONE_TIMEOUT_SECONDS )
					.call())
			{
				if( !setLocalIdentity( git, userdata ) )
				{
					result = false;
					break;
				}
				// Los ficheros de esta maquina se marcan ya en el clonado: si no, el
				// primer backup del invitado subiria el server.properties del host
				if( !setSkipWorktree( git, clonePath, Path.of( "server.properties" ), true ) )
				{
					result = false;
					break;
				}
				if( !setSkipWorktree( git, clonePath, Path.of( "user_jvm_args.txt" ), true ) )
				{
					result = false;
					break;
				}
				result = true;
			}
			catch( Exception cloneFailure )
			{
				app.Log.event( "GIT_BACKUP", "No se pudo clonar " + cloneUrl + " en " + clonePath, cloneFailure );
				JOptionPane.showMessageDialog( null, "File not found or inaccessible " + clonePath + " or git error occurred.", "Error",
						JOptionPane.ERROR_MESSAGE );
				result = false;
			}
		} while( false );
		return result;
	}

	public static boolean autoCommitAndPush()
	{
		return autoCommitAndPush( false );
	}

	public static boolean autoCommitAndPush( boolean isServerStopping )
	{
		return MainFrame.serverOpenedDirectory != null
				&& commitAndPush( MainFrame.serverOpenedDirectory.toPath(), isServerStopping ).success();
	}

	/**
	 * Commitea y sube el servidor seleccionado en lotes acotados y verificados.
	 *
	 * <p>Primero se empuja un HEAD pendiente con el árbol limpio, que es como se
	 * reanuda un lote commiteado durante un fallo de red anterior. Cada commit
	 * siguiente se queda por debajo de 256 MiB de entrada sin comprimir y se
	 * confirma contra GitHub antes de crear el siguiente.</p>
	 *
	 * <p>Salidas múltiples a propósito: el bucle de lotes debe poder abandonar en
	 * cuanto GitHub rechaza uno, informando de cuántos quedaron confirmados.</p>
	 */
	public static synchronized BackupPushResult commitAndPush( Path repoDirectory, boolean isServerStopping )
	{
		if( repoDirectory == null || !Files.isDirectory( repoDirectory ) )
		{
			return new BackupPushResult( false, 0, "The selected server folder is not accessible." );
		}
		if( !ensureBackupIgnoreFile( repoDirectory ) )
		{
			return new BackupPushResult( false, 0, "The backup exclusion file could not be updated." );
		}
		Map<String, String> userdata;
		try
		{
			userdata = TokenStore.getSavedUserData();
		}
		catch( Exception invalidSession )
		{
			return new BackupPushResult( false, 0, "The GitHub session is invalid. Sign in again and retry." );
		}

		UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider( userdata.get( "nickname" ),
				userdata.get( "token" ) );

		try (Git git = Git.open( repoDirectory.toFile() ))
		{
			if( !setLocalIdentity( git, userdata ) )
			{
				return new BackupPushResult( false, 0, "The local Git identity could not be configured." );
			}

			Status status = git.status().call();
			// Un push fallido deja el arbol limpio con uno o mas commits locales
			// pendientes. Hay que confirmarlos antes de reportar como salvado un mundo
			// "sin cambios". Con ficheros sucios se commitea primero, para que un push
			// rechazado deje igualmente un arbol limpio que se pueda pull/merge y
			// reintentar sin perder datos.
			if( status.isClean() && git.getRepository().resolve( "HEAD" ) != null )
			{
				PushCheckResult pending = pushAndCheckDetailed( git, credentials );
				if( !pending.success() )
					return new BackupPushResult( false, 0, pending.message() );
			}

			Set<String> changedPaths = backupPaths( status );
			if( changedPaths.isEmpty() )
			{
				return new BackupPushResult( true, 0, "GitHub already has the latest confirmed world state." );
			}

			// El preflight recorre el disco entero (stat de cada fichero del mundo):
			// solo se paga cuando de verdad hay algo que subir. Un arranque con el
			// arbol limpio se ahorra el recorrido completo
			GitBackupPreflight.Result preflight = GitBackupPreflight.inspect( repoDirectory );
			if( !preflight.safe() )
				return new BackupPushResult( false, 0, preflight.message() );

			Map<String, GitBackupPreflight.FileEntry> existingFiles = new HashMap<>();
			for( GitBackupPreflight.FileEntry file : preflight.files() )
			{
				existingFiles.put( portablePath( file.relativePath() ), file );
			}

			// Un fichero cambiado que el preflight no peso (borrado, por ejemplo) entra
			// con tamano 0: sigue necesitando su sitio en un lote
			List<GitBackupPreflight.FileEntry> changedFiles = new ArrayList<>();
			for( String changedPath : changedPaths )
			{
				GitBackupPreflight.FileEntry file = existingFiles.get( changedPath );
				if( file != null )
					changedFiles.add( file );
				else
					changedFiles.add( new GitBackupPreflight.FileEntry( Path.of( changedPath ), 0 ) );
			}
			changedFiles.sort( Comparator.comparing( file -> portablePath( file.relativePath() ) ) );

			List<List<GitBackupPreflight.FileEntry>> batches = GitBackupPreflight.batches( changedFiles );
			int committedBatches = 0;
			for( int index = 0; index < batches.size(); index++ )
			{
				List<GitBackupPreflight.FileEntry> batch = batches.get( index );
				var add = git.add();
				boolean hasExistingFiles = false;
				for( GitBackupPreflight.FileEntry file : batch )
				{
					String relative = portablePath( file.relativePath() );
					if( Files.exists( repoDirectory.resolve( file.relativePath() ) ) )
					{
						add.addFilepattern( relative );
						hasExistingFiles = true;
					}
					else if( status.getMissing().contains( relative ) )
					{
						git.rm().addFilepattern( relative ).call();
					}
				}
				if( hasExistingFiles )
					add.call();

				Status staged = git.status().call();
				if( !hasStagedChanges( staged ) )
					continue;

				String reason = isServerStopping ? " after clean server stop" : "";
				git.commit()
						.setAuthor( userdata.get( "nickname" ), userdata.get( "email" ) )
						.setCommitter( userdata.get( "nickname" ), userdata.get( "email" ) )
						.setMessage( "World backup %d/%d by %s on %s%s".formatted(
								index + 1, batches.size(), userdata.get( "email" ), LocalDate.now(), reason ) )
						.call();
				committedBatches++;

				// Verificar lote a lote: sin esto, un fallo de red al final dejaria
				// commits locales que el usuario cree subidos
				PushCheckResult pushed = pushAndCheckDetailed( git, credentials );
				if( !pushed.success() )
				{
					return new BackupPushResult( false, committedBatches,
							"Backup batch " + (index + 1) + "/" + batches.size() + " remains local. " + pushed.message() );
				}
			}

			String largeFileNote = preflight.largeFiles().isEmpty()
					? ""
					: " GitHub accepted " + preflight.largeFiles().size() + " file(s) above its 50 MiB warning threshold.";
			return new BackupPushResult( true, committedBatches,
					"GitHub confirmed " + committedBatches + " backup batch(es); the protected server tree now totals "
							+ GitBackupPreflight.humanSize( preflight.totalBytes() ) + "." + largeFileNote );
		}
		catch( Exception backupFailure )
		{
			app.Log.event( "GIT_BACKUP", "El backup de " + repoDirectory + " no pudo completarse", backupFailure );
			String detail = backupFailure.getMessage() == null
					? backupFailure.getClass().getSimpleName()
					: backupFailure.getMessage();
			return new BackupPushResult( false, 0, "Git could not finish the backup: " + detail );
		}
	}

	private static boolean pushAndCheck( Git git, UsernamePasswordCredentialsProvider credentials ) throws GitAPIException
	{
		return pushAndCheckDetailed( git, credentials ).success();
	}

	private record PushCheckResult( boolean success, String message, boolean nonFastForward )
	{
	}

	/** Ciclos de fetch+rebase+push ante un remoto que avanza mientras subimos. */
	private static final int PUSH_RECOVERY_ATTEMPTS = 3;

	/**
	 * Empuja y, cuando el remoto ha avanzado mientras tanto (otro peer o un backup
	 * fuera de la aplicación), hace fetch, rebasa el backup local sobre el estado
	 * remoto y reintenta hasta {@link #PUSH_RECOVERY_ATTEMPTS} veces: con un solo
	 * reintento, dos pushes ajenos seguidos bastaban para tumbar el backup.
	 * Durante el rebase ganan los ficheros del host activo: la máquina que corre
	 * el mundo manda sobre su estado actual.
	 */
	private static PushCheckResult pushAndCheckDetailed( Git git, UsernamePasswordCredentialsProvider credentials ) throws GitAPIException
	{
		PushCheckResult result;
		do
		{
			result = pushOnce( git, credentials );

			for( int attempt = 1; attempt <= PUSH_RECOVERY_ATTEMPTS; attempt++ )
			{
				// Solo el rechazo por non-fast-forward es recuperable; cualquier otro
				// fallo se devuelve tal cual para no enmascarar el motivo real
				if( result.success() || !result.nonFastForward() )
					break;

				try
				{
					git.fetch().setCredentialsProvider( credentials ).setTimeout( REMOTE_GIT_TIMEOUT_SECONDS ).call();
					String branch = git.getRepository().getBranch();
					// Rebase normal; solo los ficheros en CONFLICTO se resuelven a favor del
					// backup local (THEIRS durante un rebase = los commits que se reaplican):
					// la maquina que corre el mundo es la autoridad sobre su estado actual
					RebaseResult rebase = git.rebase()
							.setUpstream( "refs/remotes/origin/" + branch )
							.setContentMergeStrategy( org.eclipse.jgit.merge.ContentMergeStrategy.THEIRS )
							.call();
					if( !rebase.getStatus().isSuccessful() )
					{
						// Abortar deja el repositorio como estaba: es preferible un backup
						// fallido a un rebase a medias que bloquee los siguientes
						try
						{
							git.rebase().setOperation( RebaseCommand.Operation.ABORT ).call();
						}
						catch( GitAPIException unwindFailure )
						{
							app.Log.event( "GIT_BACKUP", "No se pudo abortar el rebase de recuperacion", unwindFailure );
						}
						result = new PushCheckResult( false,
								result.message() + " Automatic rebase onto the new remote state failed (" + rebase.getStatus() + ").",
								true );
						break;
					}
				}
				catch( Exception recoveryFailure )
				{
					result = new PushCheckResult( false,
							result.message() + " Automatic recovery failed: " + recoveryFailure.getMessage(), true );
					break;
				}

				app.Log.event( "GIT_BACKUP", "Push rechazado por non-fast-forward; reintento " + attempt + "/" + PUSH_RECOVERY_ATTEMPTS );
				result = pushOnce( git, credentials );
			}
		} while( false );
		return result;
	}

	/**
	 * Un push cuenta como bueno solo si GitHub reporta OK o UP_TO_DATE en cada
	 * referencia. Salidas múltiples a propósito: hay dos bucles anidados sobre las
	 * referencias remotas y el primer rechazo debe abandonarlos los dos.
	 */
	private static PushCheckResult pushOnce( Git git, UsernamePasswordCredentialsProvider credentials ) throws GitAPIException
	{
		Iterable<PushResult> pushResults = git.push().setCredentialsProvider( credentials ).setTimeout( REMOTE_GIT_TIMEOUT_SECONDS ).call();
		boolean updateFound = false;
		for( PushResult pushResult : pushResults )
		{
			for( RemoteRefUpdate update : pushResult.getRemoteUpdates() )
			{
				updateFound = true;
				RemoteRefUpdate.Status status = update.getStatus();
				if( status != RemoteRefUpdate.Status.OK && status != RemoteRefUpdate.Status.UP_TO_DATE )
				{
					app.Log.event( "GIT_BACKUP",
							"Git push rejected for " + update.getRemoteName() + ": " + status + " " + update.getMessage() );
					String detail = update.getMessage() == null ? "" : " (" + update.getMessage() + ")";
					return new PushCheckResult( false, "GitHub rejected " + update.getRemoteName() + ": " + status + detail + ".",
							status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD );
				}
			}
		}
		// Sin ninguna referencia actualizada no hay confirmacion que valga: se trata
		// como fallo para no dar por salvado un mundo que nadie recibio
		return new PushCheckResult( updateFound,
				updateFound ? "GitHub accepted the push." : "Git did not report a remote branch update.", false );
	}

	/** Todo lo que Git ve distinto, menos lo que el preflight considera de runtime. */
	private static Set<String> backupPaths( Status status )
	{
		Set<String> paths = new LinkedHashSet<>();
		paths.addAll( status.getAdded() );
		paths.addAll( status.getChanged() );
		paths.addAll( status.getModified() );
		paths.addAll( status.getMissing() );
		paths.addAll( status.getRemoved() );
		paths.addAll( status.getUntracked() );
		paths.addAll( status.getConflicting() );
		paths.removeIf( path -> GitBackupPreflight.isRuntimeOnly( Path.of( path ) ) );
		return paths;
	}

	private static boolean hasStagedChanges( Status status )
	{
		return !status.getAdded().isEmpty() || !status.getChanged().isEmpty() || !status.getRemoved().isEmpty();
	}

	private static String portablePath( Path path )
	{
		return path.toString().replace( '\\', '/' );
	}

	// ---- FASE 6 — Autosave en caliente -------------------------------------

	public static void setAutoSaveInterval( int seconds )
	{
		autoSaveSecondsInterval = seconds;
		saveAutoSaveInterval();
		if( serverAutoSaveIsActive )
		{
			// Parar y ESPERAR al hilo anterior: si no, el viejo sigue vivo y se
			// duplican los ciclos de save-off/push por cada cambio de intervalo
			stopAutoSaveAndWait();
			activeAutoSave();
		}
	}

	public static void saveAutoSaveInterval()
	{
		saveAutoSaveInterval( autoSaveSecondsInterval );
	}

	public static void saveAutoSaveInterval( int seconds )
	{
		// Por debajo de 2 minutos el ciclo save-off/push se solapa consigo mismo
		if( seconds != 0 && seconds < 2 * 60 )
			throw new RuntimeException( "Autosave interval can not be lower than 2 minutes" );

		do
		{
			Path networkNamePath = app.AppPaths.dataFile( "networkName.properties" );
			if( !(Files.exists( networkNamePath )) )
				break;

			Properties props = new Properties();
			try (FileInputStream in = new FileInputStream( networkNamePath.toFile() );
					FileOutputStream out = new FileOutputStream( networkNamePath.toFile() ))
			{
				props.load( in );

				int savedSeconds = -1;
				if( props.containsKey( "autoSaveInterval" ) )
					savedSeconds = Integer.parseInt( props.getProperty( "autoSaveInterval" ) );
				// Sin cambio real no se reescribe el fichero ni se toca el campo vivo
				if( savedSeconds == seconds )
					break;

				props.setProperty( "autoSaveInterval", Integer.toString( seconds ) );
				props.store( out, "Updated seconds interval" );
			}
			catch( IOException writeFailure )
			{
				app.Log.event( "GIT_BACKUP", "No se pudo guardar el intervalo de autosave en " + networkNamePath, writeFailure );
				JOptionPane.showMessageDialog( null, "File not found or inaccessible " + networkNamePath, "Error",
						JOptionPane.ERROR_MESSAGE );
			}

			if( autoSaveSecondsInterval != seconds )
				autoSaveSecondsInterval = seconds;
		} while( false );
	}

	public static int getSavedAutoSaveInteval()
	{
		int result;
		do
		{
			Path networkNamePath = app.AppPaths.dataFile( "networkName.properties" );
			if( !(Files.exists( networkNamePath )) )
			{
				result = autoSaveSecondsInterval;
				break;
			}

			Properties props = new Properties();
			try (FileInputStream in = new FileInputStream( networkNamePath.toFile() ))
			{
				props.load( in );
				if( props.containsKey( "autoSaveInterval" ) )
					autoSaveSecondsInterval = Integer.parseInt( props.getProperty( "autoSaveInterval" ) );
				result = autoSaveSecondsInterval;
				break;
			}
			catch( IOException readFailure )
			{
				app.Log.event( "GIT_BACKUP", "No se pudo leer el intervalo de autosave de " + networkNamePath, readFailure );
				JOptionPane.showMessageDialog( null, "File not found or inaccessible " + networkNamePath, "Error",
						JOptionPane.ERROR_MESSAGE );
			}

			// Fallo de lectura: se sigue con el intervalo que ya tuviera en memoria
			result = autoSaveSecondsInterval;
		} while( false );
		return result;
	}

	/** Serializes live saves against the stop backup so both never commit at once. */
	private static final Object LIVE_SAVE_MUTEX = new Object();
	static long saveConfirmationTimeoutSeconds = 60;

	public static void activeAutoSave()
	{
		if( serverAutoSaveIsActive )
			return;
		if( autoSaveSecondsInterval <= 0 )
			return;

		if( MainFrame.serverIsOn && MainFrame.serverProcess != null && MainFrame.serverWriter != null )
		{
			autoSaveProcess = new Thread( () ->
			{
				serverAutoSaveIsActive = true;
				// El arranque acaba de pushear el mundo al día: el primer guardado espera un intervalo entero
				while( serverAutoSaveIsActive && MainFrame.serverProcess != null && MainFrame.serverProcess.isAlive() )
				{
					try
					{
						Thread.sleep( autoSaveSecondsInterval * 1000L );
					}
					catch( InterruptedException interrupted )
					{
						// La interrupcion llega desde stopAutoSaveAndWait: se restaura el
						// flag y se sale, el backup de parada se encarga del resto
						Thread.currentThread().interrupt();
						break;
					}
					if( !serverAutoSaveIsActive || MainFrame.serverProcess == null || !MainFrame.serverProcess.isAlive() )
						break;
					performLiveSave();
				}
				serverAutoSaveIsActive = false;
			}, "p2pmss-live-autosave" );
			autoSaveProcess.setDaemon( true );
			autoSaveProcess.start();
		}
	}

	/**
	 * Una foto en caliente: congelar escrituras, volcar a disco, esperar el "Saved
	 * the game" del propio servidor, commitear el árbol entero y reactivar SIEMPRE
	 * el guardado. Un push fallido reintenta en el siguiente ciclo en vez de matar
	 * el bucle: un corte transitorio de red o del antivirus no debe dejar el mundo
	 * sin protección en caliente.
	 */
	static void performLiveSave()
	{
		synchronized( LIVE_SAVE_MUTEX )
		{
			boolean pushed = false;
			try
			{
				ForgeUtils.sendCommand( "/save-off", MainFrame.serverProcess, MainFrame.serverWriter );
				boolean flushed = ForgeUtils.flushWorldToDisk( MainFrame.serverProcess, MainFrame.serverWriter,
						saveConfirmationTimeoutSeconds );
				if( flushed )
				{
					ForgeUtils.sendCommand( "/say Saving world, creating backup...", MainFrame.serverProcess, MainFrame.serverWriter );
					pushed = autoCommitAndPush();
				}
			}
			finally
			{
				// Pase lo que pase, el server recupera su guardado normal
				ForgeUtils.sendCommand( "/save-on", MainFrame.serverProcess, MainFrame.serverWriter );
			}
			if( MainFrame.window != null )
			{
				MainFrame.window.appendDashboardActivity( pushed
						? "Live world backup confirmed on GitHub"
						: "Live world backup could not be confirmed; retrying on the next interval" );
			}
		}
	}

	/**
	 * Para el bucle de autosave y espera (con límite) a la foto en curso antes de
	 * que corra el backup de parada. El join se acota por encima del timeout de red
	 * para que una red colgada no congele el botón STOP para siempre.
	 */
	public static void stopAutoSaveAndWait()
	{
		serverAutoSaveIsActive = false;
		Thread process = autoSaveProcess;
		if( process != null )
		{
			process.interrupt();
			try
			{
				process.join( (REMOTE_GIT_TIMEOUT_SECONDS + 120) * 1000L );
			}
			catch( InterruptedException interrupted )
			{
				Thread.currentThread().interrupt();
			}
		}
		autoSaveProcess = null;
	}

	// ---- FASE 7 — Sincronizacion con el remoto -----------------------------

	/**
	 * true si el remoto va por delante, false si es el local, null si divergen o
	 * la comparación no pudo hacerse.
	 */
	public static Boolean isRemoteRepoHeadFordward( Path repoPath )
	{
		Boolean result = null;
		do
		{
			Map<String, String> userdata;
			try
			{
				userdata = TokenStore.getSavedUserData();
			}
			catch( Exception invalidSession )
			{
				result = false;
				break;
			}

			UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider( userdata.get( "nickname" ),
					userdata.get( "token" ) );

			try (Git git = Git.open( repoPath.toFile() ))
			{
				git.fetch().setCredentialsProvider( credentials ).setTimeout( REMOTE_GIT_TIMEOUT_SECONDS ).call();

				Repository repo = git.getRepository();
				String branch = repo.getBranch();
				ObjectId local = repo.resolve( "HEAD" );
				ObjectId remote = repo.resolve( "refs/remotes/origin/" + branch );

				try (RevWalk walk = new RevWalk( repo ))
				{
					RevCommit localCommit = walk.parseCommit( local );
					RevCommit remoteCommit = walk.parseCommit( remote );

					if( walk.isMergedInto( localCommit, remoteCommit ) )
					{
						result = true;
						break;
					}
					if( walk.isMergedInto( remoteCommit, localCommit ) )
					{
						result = false;
						break;
					}
				}
			}
			catch( Exception compareFailure )
			{
				// Historias divergentes o remoto inalcanzable: null significa "no lo se",
				// y quien llama debe tratarlo distinto de un true/false
				app.Log.event( "GIT_BACKUP", "No se pudo comparar el HEAD local con el remoto en " + repoPath, compareFailure );
			}
		} while( false );
		return result;
	}

	/** Solo hace pull con el árbol limpio: un merge sobre cambios locales podría perderlos. */
	public static boolean pull( Path repoPath )
	{
		return pull( repoPath, false );
	}

	/**
	 * Variante para llamadores que ACABAN de dejar el árbol limpio (un
	 * commitAndPush confirmado): con {@code treeConfirmedClean} se ahorra el
	 * status de JGit, que recorre el mundo entero y es caro justo al arrancar.
	 */
	public static boolean pull( Path repoPath, boolean treeConfirmedClean )
	{
		boolean result;
		do
		{
			Map<String, String> userdata;
			try
			{
				userdata = TokenStore.getSavedUserData();
			}
			catch( Exception invalidSession )
			{
				result = false;
				break;
			}

			UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider( userdata.get( "nickname" ),
					userdata.get( "token" ) );

			try (Git git = Git.open( repoPath.toFile() ))
			{
				if( !hasRemoteOrigin( repoPath )
						|| (!treeConfirmedClean && !git.status().call().isClean()) )
				{
					result = false;
					break;
				}
				PullResult pullResult = git.pull()
						.setCredentialsProvider( credentials )
						.setTimeout( REMOTE_GIT_TIMEOUT_SECONDS )
						.call();
				result = pullResult.isSuccessful();
			}
			catch( Exception pullFailure )
			{
				app.Log.event( "GIT_BACKUP", "No se pudo actualizar " + repoPath + " desde GitHub", pullFailure );
				result = false;
			}
		} while( false );
		return result;
	}

	/** El pull manual se niega con el árbol sucio; esto deja al llamador explicar el porqué. */
	public static boolean hasLocalChanges( Path repoPath )
	{
		boolean result = false;
		try (Git git = Git.open( repoPath.toFile() ))
		{
			result = !git.status().call().isClean();
		}
		catch( Exception statusFailure )
		{
			app.Log.event( "GIT_BACKUP", "No se pudo comprobar el estado local de " + repoPath, statusFailure );
		}
		return result;
	}

	/**
	 * Rescate del pull: cuando el mundo local tiene cambios que nunca llegaron a
	 * respaldarse (una sesión cerrada a las bravas, por ejemplo), el estado actual
	 * se conserva ÍNTEGRO en una rama local de snapshot y la rama de trabajo
	 * vuelve al último mundo confirmado en GitHub. No se borra nada: el snapshot
	 * queda en el propio repositorio por si hiciera falta recuperarlo.
	 *
	 * @return el nombre de la rama snapshot creada (o cadena vacía si el árbol ya
	 *         estaba limpio), o {@code null} si la operación no pudo completarse.
	 */
	public static String snapshotLocalChangesAndTakeRemote( Path repoPath )
	{
		String result = null;
		do
		{
			Map<String, String> userdata;
			try
			{
				userdata = TokenStore.getSavedUserData();
			}
			catch( Exception invalidSession )
			{
				break;
			}

			UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider( userdata.get( "nickname" ),
					userdata.get( "token" ) );

			try (Git git = Git.open( repoPath.toFile() ))
			{
				git.fetch().setCredentialsProvider( credentials ).setTimeout( REMOTE_GIT_TIMEOUT_SECONDS ).call();

				Repository repo = git.getRepository();
				String branch = repo.getBranch();
				if( repo.resolve( "refs/remotes/origin/" + branch ) == null )
					break;

				String snapshotBranch = "";
				if( !git.status().call().isClean() )
				{
					// Todo lo local (nuevo, modificado y borrado) queda en un commit...
					git.add().addFilepattern( "." ).call();
					git.add().addFilepattern( "." ).setUpdate( true ).call();
					git.commit()
							.setAuthor( userdata.get( "nickname" ), userdata.get( "email" ) )
							.setCommitter( userdata.get( "nickname" ), userdata.get( "email" ) )
							.setMessage( "Local snapshot before taking the remote world on " + LocalDate.now() )
							.call();
					// ...apuntado por una rama local que nunca se sube al remoto
					snapshotBranch = "p2pmss-local-snapshot-" + java.time.LocalDateTime.now()
							.format( java.time.format.DateTimeFormatter.ofPattern( "yyyyMMdd-HHmmss" ) );
					git.branchCreate().setName( snapshotBranch ).call();
				}

				// La rama de trabajo vuelve al último mundo confirmado del remoto; lo
				// recién commiteado sobrevive en la rama snapshot
				git.reset().setMode( org.eclipse.jgit.api.ResetCommand.ResetType.HARD )
						.setRef( "refs/remotes/origin/" + branch )
						.call();
				result = snapshotBranch;
			}
			catch( Exception rescueFailure )
			{
				app.Log.event( "GIT_BACKUP", "El rescate del pull en " + repoPath + " no pudo completarse", rescueFailure );
			}
		} while( false );
		return result;
	}

	// ---- FASE 8 — Utilidades HTTP y de rutas -------------------------------

	static HttpRequest.Builder authenticatedRequest( String url, String token )
	{
		return HttpRequest.newBuilder()
				.uri( URI.create( url ) )
				.timeout( REQUEST_TIMEOUT )
				.header( "Authorization", "Bearer " + token )
				.header( "User-Agent", "Peer_To_Peer_Minecraft_Server_System/1.0" )
				.header( "Accept", "application/vnd.github+json" )
				.header( "X-GitHub-Api-Version", "2022-11-28" );
	}

	/** La base se puede sobreescribir por propiedad para poder apuntar a un servidor de pruebas. */
	static String githubApiBase()
	{
		String base = System.getProperty( GITHUB_API_PROPERTY, "https://api.github.com" );
		return base.endsWith( "/" ) ? base.substring( 0, base.length() - 1 ) : base;
	}

	private static Path joinedReposPath()
	{
		return app.AppPaths.dataFile( "joined_repos.properties" );
	}

	/** URLEncoder codifica el espacio como '+', que en una ruta no vale. */
	static String encodePathSegment( String value )
	{
		return URLEncoder.encode( value, StandardCharsets.UTF_8 ).replace( "+", "%20" );
	}

	private static boolean isBlank( String value )
	{
		return value == null || value.trim().isEmpty();
	}
}
