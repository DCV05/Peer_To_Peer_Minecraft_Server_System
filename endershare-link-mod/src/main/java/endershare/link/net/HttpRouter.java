package endershare.link.net;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

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
			if( uri.startsWith( "/map" ) )
			{
				serveMap( context, uri );
				break;
			}

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

	private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
			Map.entry( "html", "text/html; charset=utf-8" ),
			Map.entry( "js", "application/javascript; charset=utf-8" ),
			Map.entry( "css", "text/css; charset=utf-8" ),
			Map.entry( "json", "application/json" ),
			Map.entry( "png", "image/png" ),
			Map.entry( "svg", "image/svg+xml" ),
			Map.entry( "ttf", "font/ttf" ),
			Map.entry( "ico", "image/x-icon" ) );

	/**
	 * Sirve el visor 3D de BlueMap (bluemap/web) por el puerto del juego. Los
	 * .gz de disco viajan tal cual con Content-Encoding: gzip, como hace el
	 * MapWebServer de la aplicacion. players.json se genera al vuelo para que
	 * el visor tenga a los jugadores en vivo aunque BlueMap no lo refresque.
	 */
	private static void serveMap( ChannelHandlerContext context, String uri )
	{
		String inside = uri.substring( "/map".length() );
		if( inside.isEmpty() || "/".equals( inside ) )
			inside = "/index.html";

		if( inside.matches( "/maps/[^/]+/live/players\\.json" ) )
		{
			String mapId = inside.split( "/" )[2];
			respond( context, HttpResponseStatus.OK, "application/json",
					livePlayers( mapId ).getBytes( StandardCharsets.UTF_8 ) );
			return;
		}

		Path webroot = FabricLoader.getInstance().getGameDir().resolve( "bluemap" ).resolve( "web" ).normalize();
		Path file = webroot.resolve( inside.substring( 1 ) ).normalize();
		byte[] body;
		if( !file.startsWith( webroot ) || !Files.isRegularFile( file ) )
		{
			// Un tile sin renderizar no es un error: 204 como el webserver de
			// BlueMap, para no llenar la consola del navegador de 404
			if( inside.contains( "/tiles/" ) )
				respond( context, HttpResponseStatus.NO_CONTENT, "application/octet-stream", new byte[0] );
			else
				respond( context, HttpResponseStatus.NOT_FOUND, "text/plain; charset=utf-8",
						"map: not found".getBytes( StandardCharsets.UTF_8 ) );
			return;
		}
		try
		{
			body = Files.readAllBytes( file );
		}
		catch( Exception unreadable )
		{
			respond( context, HttpResponseStatus.NOT_FOUND, "text/plain; charset=utf-8",
					"map: unreadable".getBytes( StandardCharsets.UTF_8 ) );
			return;
		}

		String name = file.getFileName().toString().toLowerCase( Locale.ROOT );
		String encoding = null;
		if( name.endsWith( ".gz" ) )
		{
			encoding = "gzip";
			name = name.substring( 0, name.length() - 3 );
		}
		int dot = name.lastIndexOf( '.' );
		String contentType = CONTENT_TYPES.getOrDefault( dot < 0 ? "" : name.substring( dot + 1 ),
				"application/octet-stream" );

		FullHttpResponse response = new DefaultFullHttpResponse( HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
				Unpooled.wrappedBuffer( body ) );
		response.headers().set( HttpHeaderNames.CONTENT_TYPE, contentType );
		if( encoding != null )
			response.headers().set( HttpHeaderNames.CONTENT_ENCODING, encoding );
		response.headers().set( HttpHeaderNames.CONTENT_LENGTH, body.length );
		response.headers().set( HttpHeaderNames.CONNECTION, "close" );
		response.headers().set( HttpHeaderNames.CACHE_CONTROL, "no-cache" );
		context.writeAndFlush( response ).addListener( ChannelFutureListener.CLOSE );
	}

	/** players.json con el formato que espera el visor de BlueMap. */
	private static String livePlayers( String mapId )
	{
		JsonArray players = new JsonArray();
		MinecraftServer server = EndershareLink.server;
		if( server != null )
		{
			for( ServerPlayerEntity player : server.getPlayerManager().getPlayerList() )
			{
				JsonObject entry = new JsonObject();
				entry.addProperty( "uuid", player.getUuidAsString() );
				entry.addProperty( "name", player.getGameProfile().getName() );
				String dimension = player.getWorld().getRegistryKey().getValue().getPath().replace( "the_", "" );
				entry.addProperty( "foreign", !dimension.equals( mapId ) );
				JsonObject position = new JsonObject();
				position.addProperty( "x", player.getX() );
				position.addProperty( "y", player.getY() );
				position.addProperty( "z", player.getZ() );
				entry.add( "position", position );
				JsonObject rotation = new JsonObject();
				rotation.addProperty( "pitch", player.getPitch() );
				rotation.addProperty( "yaw", player.getYaw() );
				rotation.addProperty( "roll", 0 );
				entry.add( "rotation", rotation );
				players.add( entry );
			}
		}
		JsonObject body = new JsonObject();
		body.add( "players", players );
		return body.toString();
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
