package endershare.link.net;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import endershare.link.EndershareLink;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;

/** Ciclo de vida y mensajes de cada peer WebSocket. */
public final class WsSessionHandler extends SimpleChannelInboundHandler<TextWebSocketFrame>
{

	private static final int MAX_CHAT_LENGTH = 500;
	private static final int MAX_NICK_LENGTH = 24;
	private static final int MAX_STATE_LENGTH = 40;

	@Override
	public void userEventTriggered( ChannelHandlerContext context, Object event )
	{
		if( event instanceof WebSocketServerProtocolHandler.HandshakeComplete )
		{
			WsSessions.Session session = WsSessions.add( context.channel() );
			session.keepalive = context.executor().scheduleAtFixedRate(
					() -> context.channel().writeAndFlush( new PingWebSocketFrame() ), 20, 20, TimeUnit.SECONDS );
			return;
		}

		if( event instanceof IdleStateEvent )
		{
			context.close();
			return;
		}

		context.fireUserEventTriggered( event );
	}

	@Override
	protected void channelRead0( ChannelHandlerContext context, TextWebSocketFrame frame )
	{
		WsSessions.Session session = WsSessions.get( context.channel() );
		if( session == null )
			return;

		JsonObject message;
		String type;
		try
		{
			message = JsonParser.parseString( frame.text() ).getAsJsonObject();
			type = message.get( "type" ).getAsString();
		}
		catch( Exception exception )
		{
			context.writeAndFlush( new TextWebSocketFrame( Messages.error( "bad-json" ) ) );
			return;
		}

		JsonObject payload = message.has( "payload" ) && message.get( "payload" ).isJsonObject()
				? message.getAsJsonObject( "payload" )
				: new JsonObject();

		switch( type )
		{
			case "hello" -> handleHello( context, session, message, payload );
			case "chat" -> handleChat( context, session, payload );
			case "presence" -> handlePresence( session, payload );
			default -> context.writeAndFlush( new TextWebSocketFrame( Messages.error( "unknown-type" ) ) );
		}
	}

	private static void handleHello( ChannelHandlerContext context, WsSessions.Session session, JsonObject message, JsonObject payload )
	{
		String nick = message.has( "from" ) ? message.get( "from" ).getAsString() : "anon";
		session.nick = truncate( nick.replaceAll( "[^A-Za-z0-9_\\-. ]", "" ), MAX_NICK_LENGTH );
		if( session.nick.isBlank() )
			session.nick = "anon";
		if( payload.has( "client" ) )
			session.client = truncate( payload.get( "client" ).getAsString(), 16 );

		String token = payload.has( "token" ) ? payload.get( "token" ).getAsString() : "";
		String expected = EndershareLink.config.token == null ? "" : EndershareLink.config.token;
		session.authed = !expected.isEmpty() && MessageDigest.isEqual(
				token.getBytes( StandardCharsets.UTF_8 ), expected.getBytes( StandardCharsets.UTF_8 ) );
		session.helloReceived = true;

		if( !session.authed && !EndershareLink.config.allowAnonymousRead )
		{
			context.writeAndFlush( new TextWebSocketFrame( Messages.error( "auth-required" ) ) )
					.addListener( future -> context.close() );
			WsSessions.remove( context.channel() );
			return;
		}

		context.writeAndFlush( new TextWebSocketFrame( Messages.helloReply( session.authed, !session.authed ) ) );
		WsSessions.broadcast( Messages.presenceRoster() );
	}

	private static void handleChat( ChannelHandlerContext context, WsSessions.Session session, JsonObject payload )
	{
		if( !session.authed )
		{
			context.writeAndFlush( new TextWebSocketFrame( Messages.error( "read-only" ) ) );
			return;
		}
		if( !payload.has( "text" ) )
			return;
		String text = truncate( payload.get( "text" ).getAsString(), MAX_CHAT_LENGTH ).trim();
		if( text.isEmpty() )
			return;
		WsSessions.broadcast( Messages.chat( session.nick, text ) );

		// Puente WS -> juego, apagado por defecto (config.chatBridge)
		net.minecraft.server.MinecraftServer server = EndershareLink.server;
		if( EndershareLink.config.chatBridge && server != null )
		{
			String nick = session.nick;
			EndershareLink.LOGGER.info( "[link] chat web->juego de {}", nick );
			server.execute( () -> server.getPlayerManager().getPlayerList().forEach( player ->
					player.sendMessage( net.minecraft.text.Text.literal( "[web] " + nick + ": " + text ) ) ) );
		}
	}

	private static void handlePresence( WsSessions.Session session, JsonObject payload )
	{
		if( payload.has( "state" ) )
			session.state = truncate( payload.get( "state" ).getAsString(), MAX_STATE_LENGTH );
		WsSessions.broadcast( Messages.presenceRoster() );
	}

	private static String truncate( String value, int max )
	{
		return value.length() <= max ? value : value.substring( 0, max );
	}

	@Override
	public void channelInactive( ChannelHandlerContext context )
	{
		WsSessions.Session session = WsSessions.get( context.channel() );
		boolean announced = session != null && session.helloReceived;
		WsSessions.remove( context.channel() );
		if( announced )
			WsSessions.broadcast( Messages.presenceRoster() );
		context.fireChannelInactive();
	}

	@Override
	public void exceptionCaught( ChannelHandlerContext context, Throwable cause )
	{
		WsSessions.remove( context.channel() );
		context.close();
	}

}
