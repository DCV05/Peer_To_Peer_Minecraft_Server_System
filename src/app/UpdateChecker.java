package app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Properties;

/**
 * Checks the GitHub releases of the app and reports when a newer version
 * than the running one is published. The repository to watch and the current
 * version are baked into the jar at build time (see the p2pmss.releasesRepo
 * property in pom.xml and src/resources/p2pmss-update.properties), so every
 * fork points at its own releases just by compiling. Network failures are
 * treated as "no update available" so the startup flow keeps working offline.
 */
public final class UpdateChecker
{

	static final String DEFAULT_RELEASES_REPO = "DCV05/Peer_To_Peer_Minecraft_Server_System";
	static final String DEFAULT_VERSION = "0.0.0";
	/** Canal de las compilaciones publicas: solo ve releases definitivas. */
	public static final String STABLE_CHANNEL = "stable";
	/** Canal de pruebas: solo ve las publicaciones marcadas como preliminares. */
	public static final String DEV_CHANNEL = "dev";
	private static final String BUILD_PROPERTIES_RESOURCE = "/p2pmss-update.properties";
	private static final String GITHUB_API_PROPERTY = "p2pmss.githubApiBase";
	private static final String RELEASES_REPO_PROPERTY = "p2pmss.releasesRepo";
	private static final String CHANNEL_PROPERTY = "p2pmss.channel";
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds( 20 );
	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
	private static volatile Properties buildProperties = null;

	public record ReleaseInfo( String version, String pageUrl, String downloadUrl )
	{
	}

	private UpdateChecker()
	{
	}

	private static String apiBase()
	{
		return System.getProperty( GITHUB_API_PROPERTY, "https://api.github.com" );
	}

	private static Properties loadBuildProperties()
	{
		Properties loaded = buildProperties;
		if( loaded != null )
			return loaded;
		loaded = new Properties();
		try (InputStream in = UpdateChecker.class.getResourceAsStream( BUILD_PROPERTIES_RESOURCE ))
		{
			if( in != null )
				loaded.load( in );
		}
		catch( IOException ignored )
		{
		}
		buildProperties = loaded;
		return loaded;
	}

	/** A baked value is only usable when Maven actually filtered the placeholder. */
	private static String bakedProperty( String key )
	{
		String value = loadBuildProperties().getProperty( key, "" ).trim();
		return value.isEmpty() || value.startsWith( "${" ) ? null : value;
	}

	/** owner/repo whose releases are watched: system property > baked at build > default. */
	public static String releasesRepo()
	{
		String overridden = System.getProperty( RELEASES_REPO_PROPERTY );
		if( overridden != null && !overridden.isBlank() )
			return overridden.trim();
		String baked = bakedProperty( "releases.repo" );
		return baked != null ? baked : DEFAULT_RELEASES_REPO;
	}

	/** Version of the running build, baked from the pom version at compile time. */
	public static String currentVersion()
	{
		String baked = bakedProperty( "app.version" );
		return baked != null ? baked : DEFAULT_VERSION;
	}

	/**
	 * Canal de esta instalacion: {@code stable} o {@code dev}.
	 *
	 * <p>Los canales son estancos a proposito. Una instalacion estable no debe
	 * tragarse nunca una compilacion de pruebas, y una de pruebas no debe volver
	 * atras a la estable: cada una se actualiza solo con las suyas.</p>
	 */
	public static String currentChannel()
	{
		String overridden = System.getProperty( CHANNEL_PROPERTY );
		if( overridden != null && !overridden.isBlank() )
			return overridden.trim();
		String baked = bakedProperty( "app.channel" );
		return DEV_CHANNEL.equalsIgnoreCase( baked ) ? DEV_CHANNEL : STABLE_CHANNEL;
	}

	static boolean isDevChannel()
	{
		return DEV_CHANNEL.equals( currentChannel() );
	}

	/**
	 * Returns the latest published release only when it is strictly newer than
	 * {@link #currentVersion()}. Un solo punto de salida: las validaciones van en
	 * cascada y cada una corta con break; cualquier fallo de red o de formato
	 * degrada a "sin actualizacion" en vez de romper el arranque de la app.
	 */
	public static Optional<ReleaseInfo> findNewerRelease()
	{
		Optional<ReleaseInfo> result = Optional.empty();
		try
		{
			do
			{
				HttpClient client = HttpClient.newBuilder().connectTimeout( REQUEST_TIMEOUT ).build();
				// El canal estable pide /latest, que GitHub define como la ultima
				// release NO preliminar: por construccion no puede ver una de dev.
				// El canal dev pide la lista y se queda con las preliminares.
				String endpoint = isDevChannel() ? "/releases?per_page=30" : "/releases/latest";
				HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
						.uri( URI.create( apiBase() + "/repos/" + releasesRepo() + endpoint ) )
						.timeout( REQUEST_TIMEOUT )
						.header( "Accept", "application/vnd.github+json" )
						.GET();
				// Sin token, GitHub limita a 60 peticiones/hora POR IP: con el chequeo
				// periodico de cada minuto se agota solo y las actualizaciones dejan de
				// ofrecerse en silencio. Con la sesion abierta el limite sube a 5000/h
				String token = sessionToken();
				if( token != null )
					requestBuilder.header( "Authorization", "Bearer " + token );
				HttpRequest request = requestBuilder.build();

				HttpResponse<String> response = client.send( request, HttpResponse.BodyHandlers.ofString() );
				if( response.statusCode() != 200 )
					break;

				JsonNode payload = JSON_MAPPER.readTree( response.body() );
				JsonNode release = isDevChannel() ? newestPreRelease( payload ) : payload;
				if( release == null )
					break;
				String version = normalizeVersion( release.path( "tag_name" ).asText( "" ) );
				if( version.isEmpty() || !isNewer( version, normalizeVersion( currentVersion() ) ) )
					break;

				// La release se publica unos minutos antes de que el CI adjunte los
				// instaladores: hasta que no exista el asset de ESTA plataforma no se
				// ofrece nada; el siguiente chequeo periodico la recogera ya completa
				String pageUrl = release.path( "html_url" ).asText( "https://github.com/" + releasesRepo() + "/releases" );
				String downloadUrl = pickDownloadUrl( release.path( "assets" ), System.getProperty( "os.name", "" ) );
				if( downloadUrl == null )
					break;

				result = Optional.of( new ReleaseInfo( version, pageUrl, downloadUrl ) );
			} while( false );
		}
		catch( Exception checkFailure )
		{
			// Sin red o API caida: silencio, el siguiente chequeo periodico reintenta
			result = Optional.empty();
		}
		return result;
	}

	/**
	 * Token de la sesion de GitHub guardada, o null si no hay ninguna abierta.
	 * Fallo tolerado: sin token el chequeo sigue funcionando, solo que con el
	 * limite anonimo de la API.
	 */
	private static String sessionToken()
	{
		String result = null;
		try
		{
			result = jgit.TokenStore.getSavedUserData().get( "token" );
		}
		catch( Exception noSession )
		{
			// Sin sesion abierta: se consulta la API de forma anonima
		}
		return result;
	}

	/** Picks the release asset that installs best on this platform, falling back to the portable jar. */
	static String pickDownloadUrl( JsonNode assets, String osName )
	{
		for( String extension : preferredAssetExtensions( osName ) )
		{
			for( JsonNode asset : assets )
			{
				if( asset.path( "name" ).asText( "" ).toLowerCase().endsWith( extension ) )
				{
					return asset.path( "browser_download_url" ).asText( null );
				}
			}
		}
		return null;
	}

	/**
	 * Instalador por plataforma, SIN fallback al jar en mac/windows: durante los
	 * minutos en que el CI solo ha subido el jar (compila antes que el dmg/exe),
	 * ofrecerlo actualizaba por el camino degradado — un jar suelto en updates y
	 * la app instalada quedandose vieja. Sin asset nativo no se ofrece nada; el
	 * chequeo periodico recoge la release en cuanto esta completa.
	 */
	static java.util.List<String> preferredAssetExtensions( String osName )
	{
		String os = osName == null ? "" : osName.toLowerCase();
		if( os.contains( "mac" ) || os.contains( "darwin" ) )
			return java.util.List.of( ".dmg", ".pkg" );
		if( os.contains( "win" ) )
			return java.util.List.of( ".exe", ".msi" );
		return java.util.List.of( ".jar" );
	}

	/** Strips the leading "v" and any "-suffix" so "v1.7.1-p2p" compares as "1.7.1". */
	/**
	 * De la lista de publicaciones, la preliminar con version mas alta.
	 *
	 * <p>Se filtran las definitivas y los borradores: una instalacion de pruebas
	 * solo se actualiza con compilaciones de pruebas. Se elige por version y no
	 * por orden de la lista porque GitHub la ordena por fecha de creacion, y una
	 * republicacion puede dejar delante una version mas vieja.</p>
	 */
	static JsonNode newestPreRelease( JsonNode releases )
	{
		if( releases == null || !releases.isArray() )
			return null;
		JsonNode best = null;
		String bestVersion = "";
		for( JsonNode release : releases )
		{
			if( !release.path( "prerelease" ).asBoolean( false ) || release.path( "draft" ).asBoolean( false ) )
				continue;
			String version = normalizeVersion( release.path( "tag_name" ).asText( "" ) );
			if( version.isEmpty() )
				continue;
			if( best == null || isNewer( version, bestVersion ) )
			{
				best = release;
				bestVersion = version;
			}
		}
		return best;
	}

	static String normalizeVersion( String tag )
	{
		if( tag == null )
			return "";
		String cleaned = tag.trim();
		if( cleaned.startsWith( "v" ) || cleaned.startsWith( "V" ) )
			cleaned = cleaned.substring( 1 );
		int suffix = cleaned.indexOf( '-' );
		if( suffix >= 0 )
			cleaned = cleaned.substring( 0, suffix );
		return cleaned.matches( "\\d+(\\.\\d+)*" ) ? cleaned : "";
	}

	/** Numeric segment-by-segment comparison; missing segments count as zero. */
	static boolean isNewer( String candidate, String current )
	{
		if( candidate.isEmpty() || current.isEmpty() )
			return false;
		String[] candidateParts = candidate.split( "\\." );
		String[] currentParts = current.split( "\\." );
		int length = Math.max( candidateParts.length, currentParts.length );
		for( int i = 0; i < length; i++ )
		{
			int left = i < candidateParts.length ? Integer.parseInt( candidateParts[i] ) : 0;
			int right = i < currentParts.length ? Integer.parseInt( currentParts[i] ) : 0;
			if( left != right )
				return left > right;
		}
		return false;
	}
}
