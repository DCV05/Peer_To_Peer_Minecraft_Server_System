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
				profile.put( "name", "P2PMSS · " + worldName );
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
		return minecraftDirectory.resolve( "launcher_profiles.json" );
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
