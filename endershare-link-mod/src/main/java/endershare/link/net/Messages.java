package endershare.link.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import endershare.link.EndershareLink;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/** Construccion de todos los JSON que emite el server (envelope unico). */
public final class Messages
{

	private Messages()
	{
	}

	private static JsonObject envelope( String type, JsonObject payload )
	{
		JsonObject message = new JsonObject();
		message.addProperty( "type", type );
		message.addProperty( "from", "server" );
		message.addProperty( "ts", System.currentTimeMillis() );
		message.add( "payload", payload );
		return message;
	}

	public static String status()
	{
		MinecraftServer server = EndershareLink.server;
		JsonObject payload = new JsonObject();
		payload.addProperty( "app", "endershare-link" );
		if( server != null )
		{
			payload.addProperty( "world", server.getSaveProperties().getLevelName() );
			payload.addProperty( "version", server.getVersion() );
			payload.addProperty( "players", server.getPlayerManager().getCurrentPlayerCount() );
			payload.addProperty( "max_players", server.getPlayerManager().getMaxPlayerCount() );
			payload.addProperty( "uptime_s", EndershareLink.uptimeSeconds() );
			payload.addProperty( "ws_peers", WsSessions.count() );
		}
		return payload.toString();
	}

	public static String helloReply( boolean authed, boolean readOnly )
	{
		JsonObject payload = new JsonObject();
		payload.addProperty( "ok", true );
		payload.addProperty( "authed", authed );
		payload.addProperty( "read_only", readOnly );
		MinecraftServer server = EndershareLink.server;
		if( server != null )
			payload.addProperty( "world", server.getSaveProperties().getLevelName() );
		return envelope( "hello", payload ).toString();
	}

	public static String error( String reason )
	{
		JsonObject payload = new JsonObject();
		payload.addProperty( "ok", false );
		payload.addProperty( "reason", reason );
		return envelope( "error", payload ).toString();
	}

	public static String presenceRoster()
	{
		JsonArray peers = new JsonArray();
		for( WsSessions.Session session : WsSessions.all() )
		{
			if( !session.helloReceived )
				continue;
			JsonObject peer = new JsonObject();
			peer.addProperty( "nick", session.nick );
			peer.addProperty( "client", session.client );
			peer.addProperty( "authed", session.authed );
			peer.addProperty( "state", session.state );
			peers.add( peer );
		}
		JsonObject payload = new JsonObject();
		payload.add( "peers", peers );
		return envelope( "presence", payload ).toString();
	}

	public static String players( MinecraftServer server )
	{
		JsonArray players = new JsonArray();
		for( ServerPlayerEntity player : server.getPlayerManager().getPlayerList() )
		{
			JsonObject entry = new JsonObject();
			entry.addProperty( "nick", player.getGameProfile().getName() );
			entry.addProperty( "uuid", player.getUuidAsString() );
			entry.addProperty( "x", Math.round( player.getX() * 10.0 ) / 10.0 );
			entry.addProperty( "y", Math.round( player.getY() * 10.0 ) / 10.0 );
			entry.addProperty( "z", Math.round( player.getZ() * 10.0 ) / 10.0 );
			entry.addProperty( "yaw", Math.round( player.getYaw() ) );
			entry.addProperty( "dim", player.getWorld().getRegistryKey().getValue().toString() );
			players.add( entry );
		}
		JsonObject payload = new JsonObject();
		payload.add( "players", players );
		return envelope( "players", payload ).toString();
	}

	public static String chat( String nick, String text )
	{
		JsonObject payload = new JsonObject();
		payload.addProperty( "text", text );
		JsonObject message = envelope( "chat", payload );
		message.addProperty( "from", nick );
		return message.toString();
	}

	public static String event( String name, String who )
	{
		JsonObject payload = new JsonObject();
		payload.addProperty( "event", name );
		if( who != null )
			payload.addProperty( "who", who );
		return envelope( "event", payload ).toString();
	}

}
