package vpn;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Lado servidor del descubrimiento por UDP: escucha los broadcast "DISCOVER" de
 * la red y contesta "HERE" mientras el servidor de Minecraft siga levantado.
 * El estado (jugadores conectados) se pide por Supplier en cada respuesta para
 * no publicar datos rancios, y un host viejo puede seguir contestando "HERE" a
 * secas porque el sufijo es opcional.
 */
public final class DiscoveryResponder
{

	private final String myNetworkName;
	private final Supplier<String> statusPayload;
	private volatile DatagramSocket socket;

	public DiscoveryResponder( String name )
	{
		this( name, () -> "" );
	}

	public DiscoveryResponder( String name, Supplier<String> statusPayload )
	{
		this.myNetworkName = name;
		this.statusPayload = statusPayload == null ? () -> "" : statusPayload;
	}

	public void listen( int port ) throws Exception
	{
		socket = new DatagramSocket( port );

		byte[] buffer = new byte[512];

		while( true )
		{
			app.Log.event( "NETWORK_DISCOVERY", "Escuchando en el puerto " + port );
			DatagramPacket packet = new DatagramPacket( buffer, buffer.length );
			socket.receive( packet );

			String message = new String( packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8 );
			String expectedMessage = "DISCOVER: " + myNetworkName;

			if( message.equals( expectedMessage ) )
			{
				// El puerto cerrado es la senal de que el servidor ya no esta: se deja
				// de responder y se sale del bucle para no anunciar un host muerto
				if( !isPortActive( port ) )
				{
					app.Log.event( "NETWORK_DISCOVERY", "Puerto " + port + " cerrado: se deja de anunciar el host" );
					break;
				}

				String payload;
				try
				{
					payload = "HERE" + statusPayload.get();
				}
				catch( RuntimeException statusUnavailable )
				{
					// Sin estado se responde el "HERE" pelado: perder la lista de
					// jugadores es preferible a no aparecer en el descubrimiento
					payload = "HERE";
				}
				byte[] response = payload.getBytes( StandardCharsets.UTF_8 );
				DatagramPacket responsePacket = new DatagramPacket(
						response, response.length,
						packet.getAddress(), packet.getPort() );

				socket.send( responsePacket );
				app.Log.event( "NETWORK_DISCOVERY", "Respondido a " + packet.getAddress() );
			}
		}
	}

	public void closeListeningSocket()
	{
		if( socket != null )
			socket.close();
		socket = null;
	}

	public DiscoveryResponder listenAsync( int port )
	{
		new Thread( () ->
		{
			try
			{
				listen( port );
			}
			catch( SocketException socketClosed )
			{
				// closeListeningSocket() cierra el socket con receive() bloqueado: la
				// excepcion es el final normal del hilo, no un fallo
			}
			catch( Exception listenFailure )
			{
				app.Log.event( "NETWORK_DISCOVERY", "El respondedor del puerto " + port + " se detuvo", listenFailure );
			}
		} ).start();
		return this;
	}

	/** El servidor de Minecraft se da por vivo si acepta una conexion en su puerto. */
	private boolean isPortActive( int port )
	{
		boolean result;
		try (Socket probe = new Socket( "localhost", port ))
		{
			result = true;
		}
		catch( Exception portUnreachable )
		{
			result = false;
		}
		return result;
	}
}
