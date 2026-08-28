package endershare.link.net;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import endershare.link.EndershareLink;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

/**
 * Rutas HTTP planas. Las peticiones a /ws no llegan aqui: las intercepta el
 * WebSocketServerProtocolHandler anterior en el pipeline.
 */
public final class HttpRouter extends SimpleChannelInboundHandler<FullHttpRequest>
{

	private static volatile byte[] pageCache = null;

	@Override
	protected void channelRead0( ChannelHandlerContext context, FullHttpRequest request )
	{
		String uri = request.uri();
		int query = uri.indexOf( '?' );
		if( query >= 0 )
			uri = uri.substring( 0, query );

		do
		{
			if( "/ping".equals( uri ) )
			{
				respond( context, HttpResponseStatus.OK, "application/json; charset=utf-8",
						Messages.status().getBytes( StandardCharsets.UTF_8 ) );
				break;
			}

			if( ( "/".equals( uri ) || "/index.html".equals( uri ) ) && EndershareLink.config.webPage )
			{
				byte[] page = loadPage();
				if( page != null )
				{
					respond( context, HttpResponseStatus.OK, "text/html; charset=utf-8", page );
					break;
				}
			}

			respond( context, HttpResponseStatus.NOT_FOUND, "text/plain; charset=utf-8",
					"endershare-link: not found".getBytes( StandardCharsets.UTF_8 ) );

		} while( false );
	}

	private static byte[] loadPage()
	{
		byte[] page = pageCache;
		if( page != null )
			return page;
		try( InputStream stream = HttpRouter.class.getResourceAsStream( "/endershare-link/web/index.html" ) )
		{
			if( stream == null )
				return null;
			page = stream.readAllBytes();
			pageCache = page;
			return page;
		}
		catch( Exception exception )
		{
			return null;
		}
	}

	private static void respond( ChannelHandlerContext context, HttpResponseStatus status, String contentType, byte[] body )
	{
		FullHttpResponse response = new DefaultFullHttpResponse( HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer( body ) );
		response.headers().set( HttpHeaderNames.CONTENT_TYPE, contentType );
		response.headers().set( HttpHeaderNames.CONTENT_LENGTH, body.length );
		response.headers().set( HttpHeaderNames.CONNECTION, "close" );
		response.headers().set( HttpHeaderNames.CACHE_CONTROL, "no-store" );
		context.writeAndFlush( response ).addListener( ChannelFutureListener.CLOSE );
	}

	@Override
	public void exceptionCaught( ChannelHandlerContext context, Throwable cause )
	{
		context.close();
	}

}
