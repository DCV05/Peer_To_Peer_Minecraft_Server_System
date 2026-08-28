package link;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Cliente del WebSocket que endershare-link sirve en el MISMO puerto del juego.
 * Envoltorio fino sobre java.net.http: conectar, hello con token, escuchar y
 * mandar chat/presencia. Los callbacks llegan en hilos de red: quien pinte
 * Swing debe saltar al EDT por su cuenta.
 */
public final class LinkClient implements WebSocket.Listener
{

	public interface Listener
	{
		void onConnected( boolean authed, String world );

		void onChat( String from, String text );

		void onPresence( String peersLine );

		void onPlayers( String playersLine );

		void onEvent( String description );

		void onClosed();
	}

	private static final ObjectMapper JSON = new ObjectMapper();

	private final Listener listener;
	private final StringBuilder partial = new StringBuilder();
	private volatile WebSocket webSocket;
	private volatile boolean closed = false;

	private LinkClient( Listener listener )
	{
		this.listener = listener;
	}

	/** Conecta y manda el hello. Lanza excepcion si no hay WebSocket al otro lado. */
	public static LinkClient connect( String address, String nick, String token, Listener listener ) throws Exception
	{
		LinkClient client = new LinkClient( listener );
		WebSocket socket = HttpClient.newBuilder().connectTimeout( Duration.ofSeconds( 4 ) ).build()
				.newWebSocketBuilder()
				.buildAsync( URI.create( "ws://" + address + "/ws" ), client )
				.get( 6, TimeUnit.SECONDS );
		client.webSocket = socket;

		ObjectNode payload = JSON.createObjectNode();
		payload.put( "token", token == null ? "" : token );
		payload.put( "client", "endershare" );
		client.send( "hello", nick, payload );
		return client;
	}

	private void send( String type, String from, ObjectNode payload )
	{
		WebSocket socket = webSocket;
		if( socket == null || closed )
			return;
		ObjectNode message = JSON.createObjectNode();
		message.put( "type", type );
		if( from != null )
			message.put( "from", from );
		message.put( "ts", System.currentTimeMillis() );
		message.set( "payload", payload );
		socket.sendText( message.toString(), true );
	}

	public void sendChat( String text )
	{
		ObjectNode payload = JSON.createObjectNode();
		payload.put( "text", text );
		send( "chat", null, payload );
	}

	public void sendPresence( String state )
	{
		ObjectNode payload = JSON.createObjectNode();
		payload.put( "state", state );
		send( "presence", null, payload );
	}

	public void close()
	{
		closed = true;
		WebSocket socket = webSocket;
		if( socket != null )
			socket.sendClose( WebSocket.NORMAL_CLOSURE, "bye" );
	}

	// ---- WebSocket.Listener ------------------------------------------------

	@Override
	public CompletionStage<?> onText( WebSocket socket, CharSequence data, boolean last )
	{
		partial.append( data );
		if( last )
		{
			String whole = partial.toString();
			partial.setLength( 0 );
			handle( whole );
		}
		socket.request( 1 );
		return null;
	}

	private void handle( String raw )
	{
		try
		{
			JsonNode message = JSON.readTree( raw );
			String type = message.path( "type" ).asText( "" );
			JsonNode payload = message.path( "payload" );

			switch( type )
			{
				case "hello" -> listener.onConnected( payload.path( "authed" ).asBoolean( false ),
						payload.path( "world" ).asText( "?" ) );
				case "chat" -> listener.onChat( message.path( "from" ).asText( "?" ), payload.path( "text" ).asText( "" ) );
				case "presence" ->
				{
					StringBuilder line = new StringBuilder();
					for( JsonNode peer : payload.path( "peers" ) )
					{
						if( line.length() > 0 )
							line.append( ", " );
						line.append( peer.path( "nick" ).asText( "?" ) );
						if( !peer.path( "authed" ).asBoolean( false ) )
							line.append( " (ro)" );
					}
					listener.onPresence( line.length() == 0 ? "—" : line.toString() );
				}
				case "players" ->
				{
					StringBuilder line = new StringBuilder();
					int count = 0;
					for( JsonNode player : payload.path( "players" ) )
					{
						count++;
						if( line.length() > 0 )
							line.append( ", " );
						line.append( player.path( "nick" ).asText( "?" ) ).append( " (" )
								.append( player.path( "x" ).asInt() ).append( ", " )
								.append( player.path( "y" ).asInt() ).append( ", " )
								.append( player.path( "z" ).asInt() ).append( ")" );
					}
					listener.onPlayers( count == 0 ? "nadie dentro" : line.toString() );
				}
				case "event" -> listener.onEvent( payload.path( "event" ).asText( "" )
						+ (payload.hasNonNull( "who" ) ? ": " + payload.path( "who" ).asText() : "") );
				default ->
				{
					// errores y tipos futuros: sin efecto en la UI
				}
			}
		}
		catch( Exception ignored )
		{
			// un mensaje malformado jamas tumba el cliente
		}
	}

	@Override
	public CompletionStage<?> onClose( WebSocket socket, int statusCode, String reason )
	{
		if( !closed )
			listener.onClosed();
		closed = true;
		return null;
	}

	@Override
	public void onError( WebSocket socket, Throwable error )
	{
		if( !closed )
			listener.onClosed();
		closed = true;
	}

}
