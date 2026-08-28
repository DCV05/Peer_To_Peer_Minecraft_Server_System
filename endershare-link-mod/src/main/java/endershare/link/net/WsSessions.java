package endershare.link.net;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

/** Registro en memoria de los peers conectados por WebSocket. */
public final class WsSessions
{

	public static final class Session
	{
		public final Channel channel;
		public volatile String nick = "anon";
		public volatile String client = "web";
		public volatile boolean authed = false;
		public volatile boolean helloReceived = false;
		public volatile String state = "conectado";
		volatile ScheduledFuture<?> keepalive = null;

		Session( Channel channel )
		{
			this.channel = channel;
		}
	}

	private static final Map<ChannelId, Session> SESSIONS = new ConcurrentHashMap<>();

	private WsSessions()
	{
	}

	static Session add( Channel channel )
	{
		Session session = new Session( channel );
		SESSIONS.put( channel.id(), session );
		return session;
	}

	static Session get( Channel channel )
	{
		return SESSIONS.get( channel.id() );
	}

	static void remove( Channel channel )
	{
		Session session = SESSIONS.remove( channel.id() );
		if( session != null && session.keepalive != null )
			session.keepalive.cancel( false );
	}

	public static int count()
	{
		return SESSIONS.size();
	}

	public static Collection<Session> all()
	{
		return SESSIONS.values();
	}

	public static void broadcast( String json )
	{
		for( Session session : SESSIONS.values() )
			if( session.channel.isActive() )
				session.channel.writeAndFlush( new TextWebSocketFrame( json ) );
	}

	public static void closeAll()
	{
		for( Session session : SESSIONS.values() )
		{
			if( session.keepalive != null )
				session.keepalive.cancel( false );
			if( session.channel.isActive() )
				session.channel.writeAndFlush( new CloseWebSocketFrame( 1001, "server-stopping" ) )
						.addListener( future -> session.channel.close() );
		}
		SESSIONS.clear();
	}

}
