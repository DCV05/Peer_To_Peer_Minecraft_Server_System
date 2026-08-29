package endershare.link;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import endershare.link.net.MapTileWatcher;
import endershare.link.net.Messages;
import endershare.link.net.WsSessions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;

public final class EndershareLink implements ModInitializer
{

	public static final Logger LOGGER = LoggerFactory.getLogger( "endershare-link" );

	public static volatile MinecraftServer server = null;
	public static volatile LinkConfig config = new LinkConfig();

	private static volatile long startedAtMillis = 0;
	private static volatile MapTileWatcher mapTileWatcher = null;
	private int tickCounter = 0;

	@Override
	public void onInitialize()
	{
		ServerLifecycleEvents.SERVER_STARTED.register( startedServer ->
		{
			server = startedServer;
			startedAtMillis = System.currentTimeMillis();
			config = LinkConfig.load();
			deployViewerScript();
			java.nio.file.Path webroot = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
					.resolve( "bluemap" ).resolve( "web" );
			mapTileWatcher = new MapTileWatcher( webroot );
			mapTileWatcher.start();
			LOGGER.info( "endershare-link activo en el puerto del juego (HTTP /ping, /ws, /map y pagina web). Token en config/endershare-link.json" );
		} );

		ServerLifecycleEvents.SERVER_STOPPING.register( stoppingServer ->
		{
			WsSessions.broadcast( Messages.event( "server-stopping", null ) );
			WsSessions.closeAll();
			endershare.link.net.ChunkFeed.reset();
			if( mapTileWatcher != null )
				mapTileWatcher.stop();
			server = null;
		} );

		ServerTickEvents.END_SERVER_TICK.register( tickingServer ->
		{
			if( ++tickCounter % 20 != 0 || WsSessions.count() == 0 )
				return;
			WsSessions.broadcast( Messages.players( tickingServer ) );
			endershare.link.net.ChunkFeed.tick( tickingServer );
		} );

		ServerPlayConnectionEvents.JOIN.register( ( handler, sender, joinedServer ) ->
				WsSessions.broadcast( Messages.event( "player-join", handler.player.getGameProfile().getName() ) ) );

		ServerPlayConnectionEvents.DISCONNECT.register( ( handler, leftServer ) ->
				WsSessions.broadcast( Messages.event( "player-leave", handler.player.getGameProfile().getName() ) ) );

		// Puente chat juego -> WS, apagado por defecto (config.chatBridge)
		ServerMessageEvents.CHAT_MESSAGE.register( ( message, sender, typeKey ) ->
		{
			if( !config.chatBridge || WsSessions.count() == 0 )
				return;
			WsSessions.broadcast( Messages.chat( sender.getGameProfile().getName(),
					message.raw().getContent().getString() ) );
		} );

	}

	/**
	 * Deja el guion del visor 3D dentro del webroot de BlueMap. La activacion
	 * real la hace la entrada scripts de config/bluemap/webapp.conf, que viaja
	 * con el mundo.
	 */
	private static void deployViewerScript()
	{
		try
		{
			java.nio.file.Path target = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
					.resolve( "bluemap" ).resolve( "web" ).resolve( "assets" ).resolve( "endershare-live.js" );
			if( !java.nio.file.Files.isDirectory( target.getParent() ) )
				return;
			byte[] script;
			try( java.io.InputStream stream = EndershareLink.class
					.getResourceAsStream( "/endershare-link/web/endershare-live.js" ) )
			{
				if( stream == null )
					return;
				script = stream.readAllBytes();
			}
			if( !java.nio.file.Files.exists( target )
					|| !java.util.Arrays.equals( script, java.nio.file.Files.readAllBytes( target ) ) )
				java.nio.file.Files.write( target, script );
		}
		catch( Exception failed )
		{
			LOGGER.warn( "No se pudo dejar el guion del visor: {}", failed.toString() );
		}
	}

	public static long uptimeSeconds()
	{
		return startedAtMillis == 0 ? 0 : ( System.currentTimeMillis() - startedAtMillis ) / 1000;
	}

}
