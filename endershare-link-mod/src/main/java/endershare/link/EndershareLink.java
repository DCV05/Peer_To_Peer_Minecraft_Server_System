package endershare.link;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	private int tickCounter = 0;

	@Override
	public void onInitialize()
	{
		ServerLifecycleEvents.SERVER_STARTED.register( startedServer ->
		{
			server = startedServer;
			startedAtMillis = System.currentTimeMillis();
			config = LinkConfig.load();
			LOGGER.info( "endershare-link activo en el puerto del juego (HTTP /ping, /ws y pagina web). Token en config/endershare-link.json" );
		} );

		ServerLifecycleEvents.SERVER_STOPPING.register( stoppingServer ->
		{
			WsSessions.broadcast( Messages.event( "server-stopping", null ) );
			WsSessions.closeAll();
			server = null;
		} );

		ServerTickEvents.END_SERVER_TICK.register( tickingServer ->
		{
			if( ++tickCounter % 20 != 0 || WsSessions.count() == 0 )
				return;
			WsSessions.broadcast( Messages.players( tickingServer ) );
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

	public static long uptimeSeconds()
	{
		return startedAtMillis == 0 ? 0 : ( System.currentTimeMillis() - startedAtMillis ) / 1000;
	}

}
