package app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

/**
 * Puente con el launcher oficial de Minecraft: prepara un perfil con la version
 * exacta del mundo y abre el launcher, para que "jugar a este mundo" no exija
 * saber que version toca ni buscarla a mano.
 *
 * <p>Decision de diseño: NUNCA tocamos credenciales ni lanzamos el juego
 * directamente — de la cuenta se encarga el launcher oficial. Nosotros solo
 * dejamos el perfil correcto seleccionable y la direccion del server copiada
 * al portapapeles.</p>
 */
public final class MinecraftLauncher
{

	// ---- FASE 1 — Perfil en launcher_profiles.json -------------------------

	static final String PROFILE_ID = "p2pmss-world";
	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private MinecraftLauncher()
	{
	}

	/**
	 * Crea o actualiza el perfil del mundo en {@code launcher_profiles.json}.
	 * Devuelve false si el launcher no esta instalado (sin fichero de perfiles no
	 * hay donde escribir) o el fichero no se pudo tocar.
	 */
	public static boolean upsertProfile( Path profilesFile, String worldName, String minecraftVersion )
	{
		boolean result = false;
		do
		{
			if( profilesFile == null || !Files.isRegularFile( profilesFile ) )
				break;
			if( minecraftVersion == null || minecraftVersion.isBlank() )
				break;

			try
			{
				JsonNode root = JSON_MAPPER.readTree( Files.readString( profilesFile ) );
				if( !(root instanceof ObjectNode rootObject) )
					break;
				JsonNode profilesNode = rootObject.path( "profiles" );
				ObjectNode profiles = profilesNode instanceof ObjectNode existing
						? existing
						: rootObject.putObject( "profiles" );

				// Un unico perfil reutilizado: cada PLAY lo apunta al mundo actual en
				// vez de sembrar el launcher de perfiles huerfanos
				ObjectNode profile = profiles.has( PROFILE_ID ) && profiles.get( PROFILE_ID ) instanceof ObjectNode reused
						? reused
						: profiles.putObject( PROFILE_ID );
				profile.put( "name", "Endershare · " + worldName );
				profile.put( "type", "custom" );
				profile.put( "icon", "Grass" );
				profile.put( "lastVersionId", minecraftVersion );
				profile.put( "lastUsed", Instant.now().toString() );
				if( !profile.has( "created" ) )
					profile.put( "created", Instant.now().toString() );

				Files.writeString( profilesFile, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString( rootObject ) );
				result = true;
			}
			catch( IOException profileFailure )
			{
				// Un fichero de perfiles corrupto o bloqueado no debe romper PLAY: se
				// abre el launcher igualmente y el usuario elige version a mano
				Log.event( "MC_LAUNCHER", "No se pudo actualizar " + profilesFile, profileFailure );
			}
		} while( false );
		return result;
	}

	/** Ruta estandar de launcher_profiles.json en este sistema, exista o no. */
	public static Path defaultProfilesFile()
	{
		return defaultMinecraftDirectory().resolve( "launcher_profiles.json" );
	}

	/** Carpeta .minecraft del CLIENTE en este sistema (no la del server). */
	public static Path defaultMinecraftDirectory()
	{
		String os = System.getProperty( "os.name", "" ).toLowerCase( Locale.ROOT );
		String home = System.getProperty( "user.home", "." );
		Path minecraftDirectory;
		if( os.contains( "mac" ) )
			minecraftDirectory = Path.of( home, "Library", "Application Support", "minecraft" );
		else if( os.contains( "win" ) )
		{
			String appData = System.getenv( "APPDATA" );
			minecraftDirectory = appData == null ? Path.of( home, ".minecraft" ) : Path.of( appData, ".minecraft" );
		}
		else
			minecraftDirectory = Path.of( home, ".minecraft" );
		return minecraftDirectory;
	}

	// ---- FASE 3 — Version para el JOIN: candidatas, eleccion y quick play ---

	public static final String QUICK_PLAY_VERSION_ID = "endershare-join";
	private static final String JOIN_CHOICES_FILE = "join_choices.properties";

	/**
	 * Versiones con las que se puede entrar a un mundo de {@code minecraftVersion}:
	 * SIEMPRE la vanilla exacta (el launcher la descarga solo si falta), y ademas
	 * las instaladas en {@code versions/} cuyo id la contenga (el Fabric/OptiFine
	 * del jugador, por si quiere sus mods de cliente o shaders).
	 */
	public static java.util.List<String> installedVersionCandidates( Path minecraftDirectory, String minecraftVersion )
	{
		java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
		candidates.add( minecraftVersion );
		Path versions = minecraftDirectory.resolve( "versions" );
		if( Files.isDirectory( versions ) )
		{
			try (var listing = Files.list( versions ))
			{
				listing.filter( Files::isDirectory )
						.map( directory -> directory.getFileName().toString() )
						.filter( id -> id.contains( minecraftVersion ) )
						.filter( id -> !QUICK_PLAY_VERSION_ID.equals( id ) )
						.sorted()
						.forEach( candidates::add );
			}
			catch( IOException listFailure )
			{
				// Sin listado la vanilla basta: nunca es motivo para frenar el JOIN
			}
		}
		return new java.util.ArrayList<>( candidates );
	}

	/**
	 * Escribe la version {@code endershare-join}: hereda de la elegida y añade
	 * {@code --quickPlayMultiplayer <direccion>}, el mecanismo OFICIAL (1.20+)
	 * para que el juego arranque ya dentro del server, sin mods. Se reescribe en
	 * cada JOIN porque la direccion del host puede cambiar.
	 */
	public static boolean writeQuickPlayVersion( Path minecraftDirectory, String baseVersionId, String address )
	{
		boolean result = false;
		try
		{
			Path directory = minecraftDirectory.resolve( "versions" ).resolve( QUICK_PLAY_VERSION_ID );
			Files.createDirectories( directory );
			ObjectNode version = JSON_MAPPER.createObjectNode();
			version.put( "id", QUICK_PLAY_VERSION_ID );
			version.put( "inheritsFrom", baseVersionId );
			version.put( "type", "release" );
			version.put( "time", Instant.now().toString() );
			version.put( "releaseTime", Instant.now().toString() );
			version.putObject( "arguments" ).putArray( "game" )
					.add( "--quickPlayMultiplayer" )
					.add( address );
			version.putArray( "libraries" );
			Files.writeString( directory.resolve( QUICK_PLAY_VERSION_ID + ".json" ),
					JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString( version ) );
			result = true;
		}
		catch( IOException versionFailure )
		{
			Log.event( "MC_LAUNCHER", "No se pudo escribir la version quick-play", versionFailure );
		}
		return result;
	}

	/** Version elegida para un mundo en un JOIN anterior, o null. */
	public static String rememberedJoinVersion( String repoFullName )
	{
		String result = null;
		Path choices = AppPaths.dataFile( JOIN_CHOICES_FILE );
		if( Files.isRegularFile( choices ) )
		{
			java.util.Properties properties = new java.util.Properties();
			try (var input = Files.newInputStream( choices ))
			{
				properties.load( input );
				result = properties.getProperty( repoFullName );
			}
			catch( IOException readFailure )
			{
				// Sin memoria de eleccion simplemente se vuelve a preguntar
			}
		}
		return result;
	}

	/** Recuerda la version elegida para no volver a preguntar por este mundo. */
	public static void rememberJoinVersion( String repoFullName, String versionId )
	{
		try
		{
			Path choices = AppPaths.dataFile( JOIN_CHOICES_FILE );
			java.util.Properties properties = new java.util.Properties();
			if( Files.isRegularFile( choices ) )
			{
				try (var input = Files.newInputStream( choices ))
				{
					properties.load( input );
				}
			}
			properties.setProperty( repoFullName, versionId );
			Files.createDirectories( choices.getParent() );
			try (var output = Files.newOutputStream( choices ))
			{
				properties.store( output, "Endershare join version per world" );
			}
		}
		catch( IOException writeFailure )
		{
			Log.event( "MC_LAUNCHER", "No se pudo guardar la eleccion de version", writeFailure );
		}
	}

	// ---- FASE 2 — Apertura del launcher ------------------------------------

	/** Abre el launcher oficial; devuelve false si no se encontro forma de abrirlo. */
	public static boolean openLauncher()
	{
		boolean result = false;
		String os = System.getProperty( "os.name", "" ).toLowerCase( Locale.ROOT );
		try
		{
			if( os.contains( "mac" ) )
			{
				result = new ProcessBuilder( "open", "-a", "Minecraft" ).start().waitFor() == 0;
			}
			else if( os.contains( "win" ) )
			{
				// El launcher moderno registra este alias de ejecucion; si no existe,
				// cae al acceso clasico de Program Files
				Path aliasedLauncher = Path.of( System.getenv().getOrDefault( "LOCALAPPDATA", "" ),
						"Microsoft", "WindowsApps", "Minecraft.exe" );
				Path legacyLauncher = Path.of( System.getenv().getOrDefault( "ProgramFiles(x86)", "C:/Program Files (x86)" ),
						"Minecraft Launcher", "MinecraftLauncher.exe" );
				Path launcher = Files.exists( aliasedLauncher )
						? aliasedLauncher
						: Files.exists( legacyLauncher ) ? legacyLauncher : null;
				if( launcher != null )
				{
					new ProcessBuilder( "cmd", "/c", "start", "", launcher.toString() ).start();
					result = true;
				}
			}
			else
			{
				new ProcessBuilder( "minecraft-launcher" ).start();
				result = true;
			}
		}
		catch( Exception launchFailure )
		{
			Log.event( "MC_LAUNCHER", "No se pudo abrir el launcher de Minecraft", launchFailure );
		}
		return result;
	}
}
