package playit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Data path of one public player connection: TCP out to the playit claim
 * address (authenticated with the claim token), then plain byte copying
 * against the local Minecraft server. Unlike the official Bukkit plugin we
 * proxy RAW bytes with no extra header: the vanilla/Fabric server has no
 * playit decoder installed, so any prefix would corrupt the MC protocol.
 * Trade-off: the server sees every player as connecting from localhost.
 */
public final class TcpBridge
{

	private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
	private static final int CLAIM_CONFIRM_BYTES = 8;

	private TcpBridge()
	{
	}

	/** Launches the bridge on a daemon thread; onClose always runs once at the end. */
	public static Thread open( InetSocketAddress claimAddress, byte[] claimToken, int minecraftPort, Runnable onClose )
	{
		Thread bridge = new Thread( () -> run( claimAddress, claimToken, minecraftPort, onClose ), "p2pmss-playit-bridge" );
		bridge.setDaemon( true );
		bridge.start();
		return bridge;
	}

	private static void run( InetSocketAddress claimAddress, byte[] claimToken, int minecraftPort, Runnable onClose )
	{
		try (Socket tunnel = new Socket(); Socket minecraft = new Socket())
		{
			tunnel.connect( claimAddress, CONNECT_TIMEOUT_MILLIS );
			tunnel.setTcpNoDelay( true );
			tunnel.getOutputStream().write( claimToken );
			tunnel.getOutputStream().flush();

			// El servidor de playit confirma el claim con 8 bytes que se descartan
			byte[] confirmation = tunnel.getInputStream().readNBytes( CLAIM_CONFIRM_BYTES );
			if( confirmation.length < CLAIM_CONFIRM_BYTES )
				return;

			minecraft.connect( new InetSocketAddress( "127.0.0.1", minecraftPort ), CONNECT_TIMEOUT_MILLIS );
			minecraft.setTcpNoDelay( true );

			Thread upstream = copyAsync( minecraft.getInputStream(), tunnel.getOutputStream(), tunnel );
			copy( tunnel.getInputStream(), minecraft.getOutputStream(), minecraft );
			upstream.join( CONNECT_TIMEOUT_MILLIS );
		}
		catch( IOException | InterruptedException ignored )
		{
			if( Thread.currentThread().isInterrupted() )
				Thread.currentThread().interrupt();
		}
		finally
		{
			onClose.run();
		}
	}

	private static Thread copyAsync( InputStream in, OutputStream out, Socket toCloseOnEnd )
	{
		Thread pump = new Thread( () -> copy( in, out, toCloseOnEnd ), "p2pmss-playit-bridge-up" );
		pump.setDaemon( true );
		pump.start();
		return pump;
	}

	private static void copy( InputStream in, OutputStream out, Socket toCloseOnEnd )
	{
		byte[] buffer = new byte[16 * 1024];
		try
		{
			int read;
			while( (read = in.read( buffer )) != -1 )
			{
				out.write( buffer, 0, read );
				out.flush();
			}
		}
		catch( IOException ignored )
		{
		}
		finally
		{
			// Cerrar el otro extremo desbloquea la copia contraria y termina el puente
			try
			{
				toCloseOnEnd.close();
			}
			catch( IOException ignored )
			{
			}
		}
	}
}
