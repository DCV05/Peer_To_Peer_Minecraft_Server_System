package vpn;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Descubre por UDP el host P2P activo de la red y, cuando el host lo soporta,
 * su lista de jugadores. El protocolo es una sola linea de texto para que un
 * host viejo (que solo responde "HERE") siga siendo compatible con un cliente
 * nuevo: los campos ONLINE/MAX/PLAYERS son opcionales y se ignoran si faltan.
 * Cualquier fallo de red se traduce a "no encontrado" para que la UI no se
 * quede colgada esperando.
 */
public final class NetworkDiscoverClient
{
	public static String discover( String networkName, int targetPort, int timeoutMs ) throws IOException
	{
		return discoverStatus( networkName, targetPort, timeoutMs ).host();
	}

	public static DiscoveryResult discoverStatus( String networkName, int targetPort, int timeoutMs ) throws IOException
	{
		DiscoveryResult result;
		try (DatagramSocket socket = new DatagramSocket())
		{
			socket.setBroadcast( true );
			socket.setSoTimeout( timeoutMs );

			byte[] data = ("DISCOVER: " + networkName).getBytes( StandardCharsets.UTF_8 );
			DatagramPacket packet = new DatagramPacket( data, data.length,
					InetAddress.getByName( "255.255.255.255" ), targetPort );
			socket.send( packet );

			try
			{
				byte[] buffer = new byte[2048];
				DatagramPacket responsePacket = new DatagramPacket( buffer, buffer.length );
				socket.receive( responsePacket );
				String response = new String( responsePacket.getData(), 0, responsePacket.getLength(), StandardCharsets.UTF_8 );
				String hostAddress = responsePacket.getAddress().getHostAddress();
				result = parseResponse( hostAddress, response );
				app.Log.event( "NETWORK_DISCOVERY", "Respuesta recibida de " + hostAddress + ": " + response );
			}
			catch( SocketTimeoutException noHostAnswered )
			{
				// Nadie respondio dentro del timeout: no hay host, no es un error
				result = DiscoveryResult.notFound();
			}
		}
		return result;
	}

	/**
	 * Traduce la respuesta cruda del host a un resultado. Los campos llegan
	 * separados por ';' y en cualquier orden; lo que no se entienda se descarta
	 * en vez de invalidar la respuesta entera.
	 */
	static DiscoveryResult parseResponse( String host, String response )
	{
		DiscoveryResult result = DiscoveryResult.notFound();
		boolean isDiscoveryResponse = response != null && response.startsWith( "HERE" );
		if( isDiscoveryResponse )
		{
			int onlinePlayers = 0;
			int maxPlayers = 0;
			List<String> players = new ArrayList<>();
			for( String field : response.split( ";" ) )
			{
				if( field.startsWith( "ONLINE=" ) )
					onlinePlayers = parseNonNegative( field.substring( "ONLINE=".length() ) );
				else if( field.startsWith( "MAX=" ) )
					maxPlayers = parseNonNegative( field.substring( "MAX=".length() ) );
				else if( field.startsWith( "PLAYERS=" ) )
				{
					for( String candidate : field.substring( "PLAYERS=".length() ).split( "," ) )
					{
						String player = candidate.trim();
						// Solo nombres validos de Minecraft: filtra basura y respuestas manipuladas
						if( player.matches( "[A-Za-z0-9_]{1,16}" ) )
							players.add( player );
					}
				}
			}
			// La lista manda sobre el contador: si el host declara menos jugadores de
			// los que lista, el contador es el que esta mal
			onlinePlayers = Math.max( onlinePlayers, players.size() );
			if( maxPlayers > 0 )
				maxPlayers = Math.max( maxPlayers, onlinePlayers );
			result = new DiscoveryResult( host, players, onlinePlayers, maxPlayers );
		}
		return result;
	}

	/** Igual que {@link #discoverStatus} pero sin obligar a la UI a tratar la IOException. */
	public static DiscoveryResult surroundDiscoverStatus( String networkName, int targetPort, int timeoutMs )
	{
		DiscoveryResult result;
		try
		{
			result = discoverStatus( networkName, targetPort, timeoutMs );
		}
		catch( IOException discoveryFailure )
		{
			// Red caida o interfaz sin broadcast: para la UI equivale a "no hay host"
			result = DiscoveryResult.notFound();
		}
		return result;
	}

	public static String surroundDiscoverIOException( String networkName, int targetPort, int timeoutMs )
	{
		return surroundDiscoverStatus( networkName, targetPort, timeoutMs ).host();
	}

	private static int parseNonNegative( String value )
	{
		int result;
		try
		{
			result = Math.max( 0, Integer.parseInt( value ) );
		}
		catch( NumberFormatException malformedNumber )
		{
			// Campo corrupto: cuenta como cero antes que tirar todo el descubrimiento
			result = 0;
		}
		return result;
	}

	public record DiscoveryResult( String host, List<String> players, int onlinePlayers, int maxPlayers )
	{
		public DiscoveryResult {
			host = host == null || host.isBlank() ? "NotFound" : host;
			players = players == null ? List.of() : List.copyOf( players );
			onlinePlayers = Math.max( onlinePlayers, players.size() );
			maxPlayers = maxPlayers <= 0 ? 0 : Math.max( maxPlayers, onlinePlayers );
		}

		public boolean found()
		{
			return !"NotFound".equals( host );
		}

		/** Un host viejo responde "HERE" a secas: sin MAX no hay lista de jugadores fiable. */
		public boolean rosterAvailable()
		{
			return maxPlayers > 0;
		}

		private static DiscoveryResult notFound()
		{
			return new DiscoveryResult( "NotFound", List.of(), 0, 0 );
		}
	}
}
