package endershare.link.net;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;

/**
 * Mira los primeros 4 bytes de cada conexion nueva. "GET " no puede ser un
 * handshake de Minecraft (empieza por longitud varint) ni el legacy ping
 * (0xFE), asi que identifica HTTP sin ambiguedad: se desmonta el pipeline
 * vanilla y se monta la rama HTTP/WebSocket. Cualquier otro primer byte deja
 * el pipeline vanilla intacto y el sniffer se retira sin coste posterior.
 */
public final class ProtocolSniffer extends ByteToMessageDecoder
{

	public static final String NAME = "endershare_link_sniffer";
	private static final byte[] HTTP_PREFIX = { 'G', 'E', 'T', ' ' };

	@Override
	protected void decode( ChannelHandlerContext context, ByteBuf in, List<Object> out )
	{
		if( !in.isReadable() )
			return;

		if( in.getUnsignedByte( in.readerIndex() ) != HTTP_PREFIX[0] )
		{
			context.pipeline().remove( this );
			return;
		}

		if( in.readableBytes() < HTTP_PREFIX.length )
			return;

		for( int i = 0; i < HTTP_PREFIX.length; i++ )
		{
			if( in.getUnsignedByte( in.readerIndex() + i ) != HTTP_PREFIX[i] )
			{
				context.pipeline().remove( this );
				return;
			}
		}

		switchToHttp( context );
	}

	private void switchToHttp( ChannelHandlerContext context )
	{
		ChannelPipeline pipeline = context.pipeline();

		for( String name : new ArrayList<>( pipeline.names() ) )
		{
			if( NAME.equals( name ) )
				continue;
			try
			{
				pipeline.remove( name );
			}
			catch( Exception ignored )
			{
				// head/tail o handlers ya retirados por otro hilo
			}
		}

		pipeline.addLast( "es_idle", new IdleStateHandler( 120, 0, 0 ) );
		pipeline.addLast( "es_http_codec", new HttpServerCodec() );
		pipeline.addLast( "es_http_aggregator", new HttpObjectAggregator( 65536 ) );
		pipeline.addLast( "es_ws_protocol", new WebSocketServerProtocolHandler( "/ws", null, true, 65536 ) );
		pipeline.addLast( "es_http_router", new HttpRouter() );
		pipeline.addLast( "es_ws_handler", new WsSessionHandler() );

		// Al retirarse, ByteToMessageDecoder reinyecta los bytes ya leidos
		// ("GET ...") que ahora atraviesan el codec HTTP recien montado
		pipeline.remove( this );
	}

}
