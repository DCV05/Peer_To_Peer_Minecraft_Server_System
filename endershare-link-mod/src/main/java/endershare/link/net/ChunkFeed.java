package endershare.link.net;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import endershare.link.mixin.ThreadedAnvilChunkStorageAccessor;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

/**
 * Feed del mapa LIVE: que chunks estan cargados AHORA, por dimension. Corre en
 * el hilo del server (1 vez por segundo, solo con peers mirando): recorre los
 * chunk holders, se queda con los accesibles y manda una foto completa al peer
 * recien llegado y solo los cambios (add/del) a los que ya la tienen. Niveles:
 * 1 = borde (cargado sin tick), 2 = ticking, 3 = entity ticking.
 */
public final class ChunkFeed
{

	private static Map<String, Map<Long, Integer>> lastSent = null;

	private ChunkFeed()
	{
	}

	public static void reset()
	{
		lastSent = null;
	}

	public static void tick( MinecraftServer server )
	{
		if( WsSessions.count() == 0 )
		{
			lastSent = null;
			return;
		}

		Map<String, Map<Long, Integer>> current = collect( server );
		boolean baselineLost = lastSent == null;

		for( Map.Entry<String, Map<Long, Integer>> dimension : current.entrySet() )
		{
			String dim = dimension.getKey();
			Map<Long, Integer> now = dimension.getValue();
			Map<Long, Integer> before = baselineLost ? null : lastSent.getOrDefault( dim, Map.of() );

			String full = null;
			String delta = before == null ? null : buildDelta( dim, before, now );

			for( WsSessions.Session session : WsSessions.all() )
			{
				if( !session.helloReceived || !session.channel.isActive() )
					continue;
				if( session.chunksSynced && delta != null )
					session.channel.writeAndFlush( new TextWebSocketFrame( delta ) );
				else if( !session.chunksSynced || baselineLost )
				{
					if( full == null )
						full = buildFull( dim, now );
					session.channel.writeAndFlush( new TextWebSocketFrame( full ) );
				}
			}
		}

		for( WsSessions.Session session : WsSessions.all() )
			if( session.helloReceived )
				session.chunksSynced = true;

		lastSent = current;
	}

	private static Map<String, Map<Long, Integer>> collect( MinecraftServer server )
	{
		Map<String, Map<Long, Integer>> result = new HashMap<>();
		for( ServerWorld world : server.getWorlds() )
		{
			Map<Long, Integer> chunks = new HashMap<>();
			ThreadedAnvilChunkStorageAccessor storage =
					(ThreadedAnvilChunkStorageAccessor) world.getChunkManager().threadedAnvilChunkStorage;
			for( ChunkHolder holder : storage.endershareLink$entryIterator() )
			{
				int level = switch( holder.getLevelType() )
				{
					case BORDER -> 1;
					case TICKING -> 2;
					case ENTITY_TICKING -> 3;
					default -> 0;
				};
				if( level == 0 || holder.getWorldChunk() == null )
					continue;
				chunks.put( holder.getPos().toLong(), level );
			}
			result.put( world.getRegistryKey().getValue().getPath(), chunks );
		}
		return result;
	}

	private static String buildFull( String dim, Map<Long, Integer> now )
	{
		JsonArray set = new JsonArray();
		for( Map.Entry<Long, Integer> chunk : now.entrySet() )
			set.add( entry( chunk.getKey(), chunk.getValue() ) );
		JsonObject payload = new JsonObject();
		payload.addProperty( "dim", dim );
		payload.addProperty( "mode", "full" );
		payload.add( "set", set );
		return envelope( payload );
	}

	/** Devuelve null si no hay cambios: no se manda nada. */
	private static String buildDelta( String dim, Map<Long, Integer> before, Map<Long, Integer> now )
	{
		JsonArray add = new JsonArray();
		JsonArray del = new JsonArray();
		for( Map.Entry<Long, Integer> chunk : now.entrySet() )
			if( !chunk.getValue().equals( before.get( chunk.getKey() ) ) )
				add.add( entry( chunk.getKey(), chunk.getValue() ) );
		for( Long pos : before.keySet() )
			if( !now.containsKey( pos ) )
				del.add( entry( pos, 0 ) );
		if( add.isEmpty() && del.isEmpty() )
			return null;
		JsonObject payload = new JsonObject();
		payload.addProperty( "dim", dim );
		payload.addProperty( "mode", "delta" );
		payload.add( "add", add );
		payload.add( "del", del );
		return envelope( payload );
	}

	private static JsonArray entry( long packed, int level )
	{
		ChunkPos pos = new ChunkPos( packed );
		JsonArray entry = new JsonArray();
		entry.add( pos.x );
		entry.add( pos.z );
		if( level > 0 )
			entry.add( level );
		return entry;
	}

	private static String envelope( JsonObject payload )
	{
		JsonObject message = new JsonObject();
		message.addProperty( "type", "chunks" );
		message.addProperty( "from", "server" );
		message.addProperty( "ts", System.currentTimeMillis() );
		message.add( "payload", payload );
		return message.toString();
	}

}
