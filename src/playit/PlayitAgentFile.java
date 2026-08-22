package playit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared playit agent state, committed INSIDE the world repo so the public
 * address survives host changes: whoever holds the host lock reuses the same
 * tunnel. The private repo is already the trust boundary of the world.
 */
public final class PlayitAgentFile
{

	// El nombre de esta ruta NO se renombra con el resto del proyecto: vive
	// dentro del repositorio del mundo y la leen los DOS peers. Cambiarla
	// dejaria ciego al que todavia no haya actualizado, y con el candado
	// invisible los dos podrian arrancar el mismo mundo a la vez
	public static final String RELATIVE_PATH = "p2pmss/playit-agent.json";

	private static final ObjectMapper JSON = new ObjectMapper()
			.configure( DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false )
			.setSerializationInclusion( JsonInclude.Include.NON_NULL );

	public boolean enabled;
	public String secret_key;
	public String tunnel_address;

	public static Path pathIn( Path serverDirectory )
	{
		return serverDirectory.resolve( RELATIVE_PATH );
	}

	/** Returns the stored state, or null when the world has no playit setup. */
	public static PlayitAgentFile load( Path serverDirectory )
	{
		PlayitAgentFile result = null;
		do
		{
			Path file = pathIn( serverDirectory );
			if( !Files.isRegularFile( file ) )
				break;
			try
			{
				String storedJson = Files.readString( file, StandardCharsets.UTF_8 );
				result = JSON.readValue( storedJson, PlayitAgentFile.class );
			}
			catch( IOException broken )
			{
				// Un fichero corrupto o ilegible degrada a "sin configuracion de playit":
				// el mundo tiene que poder abrirse igual, solo que sin URL publica
				app.Log.event( "PLAYIT", "Stored playit agent state could not be read", broken );
			}
		} while( false );
		return result;
	}

	public void save( Path serverDirectory ) throws IOException
	{
		Path file = pathIn( serverDirectory );
		Files.createDirectories( file.getParent() );
		Files.writeString( file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString( this ) + "\n",
				StandardCharsets.UTF_8 );
	}

	public boolean readyToStart()
	{
		return enabled && secret_key != null && secret_key.length() >= 32;
	}
}
