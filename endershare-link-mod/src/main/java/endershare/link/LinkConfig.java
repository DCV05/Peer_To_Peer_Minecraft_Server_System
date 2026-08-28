package endershare.link;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Config en config/endershare-link.json. Viaja en el repo del mundo, asi que
 * el token que genera el primer host queda compartido con todos los peers.
 */
public final class LinkConfig
{

	public String token = "";
	public boolean allowAnonymousRead = true;
	public boolean webPage = true;
	public boolean chatBridge = false;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static LinkConfig load()
	{
		Path file = FabricLoader.getInstance().getConfigDir().resolve( "endershare-link.json" );
		LinkConfig config = null;

		do
		{
			if( !Files.exists( file ) )
				break;
			try
			{
				config = GSON.fromJson( Files.readString( file, StandardCharsets.UTF_8 ), LinkConfig.class );
			}
			catch( Exception exception )
			{
				EndershareLink.LOGGER.warn( "Config ilegible, se regenera: {}", exception.toString() );
			}
		} while( false );

		if( config == null )
			config = new LinkConfig();

		if( config.token == null || config.token.isBlank() )
		{
			byte[] random = new byte[16];
			new SecureRandom().nextBytes( random );
			config.token = HexFormat.of().formatHex( random );
			try
			{
				Files.writeString( file, GSON.toJson( config ), StandardCharsets.UTF_8 );
			}
			catch( Exception exception )
			{
				EndershareLink.LOGGER.warn( "No se pudo escribir la config: {}", exception.toString() );
			}
		}

		return config;
	}

}
