package minecraftServerManagement;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Fabric server provisioning through the official meta API. Fabric ships the
 * whole server as one launcher jar, so installing is a single atomic download:
 * no external installer process and no startup scripts are required.
 */
public final class FabricInstaller
{

	/** Fixed jar name: the launch fallback and loader detection both key on it. */
	public static final String SERVER_JAR_NAME = "fabric-server.jar";

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
	private static final int DOWNLOAD_TIMEOUT_MILLIS = 30_000;

	public record Catalog( List<String> gameVersions, List<String> loaderVersions )
	{
		public Catalog {
			gameVersions = gameVersions == null ? List.of() : List.copyOf( gameVersions );
			loaderVersions = loaderVersions == null ? List.of() : List.copyOf( loaderVersions );
		}
	}

	private FabricInstaller()
	{
	}

	static String metaBase()
	{
		return System.getProperty( "p2pmss.fabricMetaBase", "https://meta.fabricmc.net" );
	}

	/** Loads stable game and loader versions, newest first, from the Fabric meta API. */
	public static Catalog loadCatalogChecked() throws IOException
	{
		List<String> gameVersions = stableVersions( fetchJson( "/v2/versions/game" ) );
		List<String> loaderVersions = stableVersions( fetchJson( "/v2/versions/loader" ) );
		if( gameVersions.isEmpty() || loaderVersions.isEmpty() )
		{
			throw new IOException( "Fabric returned an empty version catalogue." );
		}
		return new Catalog( gameVersions, loaderVersions );
	}

	/**
	 * Downloads the single-jar Fabric server launcher into the destination and
	 * leaves the folder ready to launch through the jar fallback command.
	 */
	public static Path installServerChecked( Path destination, String gameVersion, String loaderVersion )
			throws IOException
	{
		if( destination == null || !Files.isDirectory( destination ) )
		{
			throw new IOException( "The selected server folder is not accessible." );
		}
		if( !isSafeVersion( gameVersion ) || !isSafeVersion( loaderVersion ) )
		{
			throw new IOException( "Select a valid Fabric version pair." );
		}

		String installerVersion = latestStableInstaller();
		String url = metaBase() + "/v2/versions/loader/" + gameVersion + "/" + loaderVersion
				+ "/" + installerVersion + "/server/jar";
		Path serverJar = destination.resolve( SERVER_JAR_NAME );
		Path partial = destination.resolve( SERVER_JAR_NAME + ".part" );
		URLConnection connection = openConnection( url );
		try (InputStream in = connection.getInputStream())
		{
			Files.copy( in, partial, StandardCopyOption.REPLACE_EXISTING );
			Files.move( partial, serverJar, StandardCopyOption.REPLACE_EXISTING );
		}
		catch( IOException failure )
		{
			Files.deleteIfExists( partial );
			throw new IOException( "Fabric server could not be downloaded. Check the connection and retry.", failure );
		}

		writeDefaultJvmArgs( destination );
		return serverJar;
	}

	/** Machine-local RAM file so the launch fallback and the RAM selector work from day one. */
	private static void writeDefaultJvmArgs( Path destination ) throws IOException
	{
		Path jvmArgs = destination.resolve( "user_jvm_args.txt" );
		if( Files.isRegularFile( jvmArgs ) )
			return;
		Files.writeString( jvmArgs,
				"# JVM arguments for the Fabric server managed by P2PMSS.\n"
						+ "-Xmx4G\n",
				StandardCharsets.UTF_8 );
	}

	private static String latestStableInstaller() throws IOException
	{
		List<String> installers = stableVersions( fetchJson( "/v2/versions/installer" ) );
		if( installers.isEmpty() )
			throw new IOException( "Fabric returned no stable installer version." );
		return installers.get( 0 );
	}

	private static List<String> stableVersions( JsonNode versions )
	{
		List<String> stable = new ArrayList<>();
		if( versions != null && versions.isArray() )
		{
			for( JsonNode version : versions )
			{
				if( version.path( "stable" ).asBoolean( false ) )
					stable.add( version.path( "version" ).asText() );
			}
		}
		return stable;
	}

	private static boolean isSafeVersion( String version )
	{
		return version != null && version.matches( "[0-9A-Za-z._+-]+" );
	}

	private static JsonNode fetchJson( String apiPath ) throws IOException
	{
		URLConnection connection = openConnection( metaBase() + apiPath );
		try (InputStream in = connection.getInputStream())
		{
			return JSON_MAPPER.readTree( in.readAllBytes() );
		}
		catch( IOException failure )
		{
			throw new IOException( "Fabric versions could not be loaded. Check the connection and retry.", failure );
		}
	}

	private static URLConnection openConnection( String url ) throws IOException
	{
		URLConnection connection = URI.create( url ).toURL().openConnection();
		connection.setConnectTimeout( DOWNLOAD_TIMEOUT_MILLIS );
		connection.setReadTimeout( DOWNLOAD_TIMEOUT_MILLIS );
		connection.setRequestProperty( "User-Agent", "Peer_To_Peer_Minecraft_Server_System/1.6" );
		return connection;
	}
}
