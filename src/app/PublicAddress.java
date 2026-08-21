package app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * IP publica de esta maquina, para el host que NO usa tunel de playit: la
 * direccion publicada en el candado pasa a ser "mi IP y el puerto del server"
 * (quien elige este camino abre el puerto en su router por su cuenta). La IP se
 * cachea unos minutos porque la foto del host se publica en cada latido y la IP
 * de casa no cambia tan a menudo.
 */
public final class PublicAddress
{
	static final String[] PROVIDERS = {"https://api.ipify.org", "https://checkip.amazonaws.com", "https://ifconfig.me/ip"};
	private static final long CACHE_MILLIS = 10 * 60 * 1000L;

	private static volatile String cachedIp;
	private static volatile long cachedAtMillis;

	private PublicAddress()
	{
	}

	/**
	 * Direccion de conexion a publicar: el tunel manda si existe; sin tunel, la
	 * IP publica con el puerto; sin ninguna de las dos, null (no hay direccion).
	 */
	public static String chooseAddress( String tunnelAddress, String publicIp, int port )
	{
		String result = null;
		if( tunnelAddress != null && !tunnelAddress.isBlank() )
			result = tunnelAddress.trim();
		else if( publicIp != null && !publicIp.isBlank() )
			result = publicIp.trim() + ":" + port;
		return result;
	}

	/** IPv4 publica segun el primer proveedor que conteste algo valido, o null. */
	public static String resolvePublicIp()
	{
		String result = cachedIp;
		do
		{
			if( result != null && System.currentTimeMillis() - cachedAtMillis < CACHE_MILLIS )
				break;
			result = null;
			for( String provider : PROVIDERS )
			{
				try
				{
					HttpClient client = HttpClient.newBuilder().connectTimeout( Duration.ofSeconds( 4 ) ).build();
					HttpResponse<String> response = client.send( HttpRequest.newBuilder()
							.uri( URI.create( provider ) )
							.timeout( Duration.ofSeconds( 4 ) )
							.GET().build(), HttpResponse.BodyHandlers.ofString() );
					String candidate = response.statusCode() == 200 ? response.body().trim() : "";
					if( looksLikeIpv4( candidate ) )
					{
						result = candidate;
						cachedIp = candidate;
						cachedAtMillis = System.currentTimeMillis();
						break;
					}
				}
				catch( Exception providerFailure )
				{
					// Se prueba el siguiente proveedor; sin IP simplemente no hay fallback
				}
			}
		} while( false );
		return result;
	}

	/**
	 * Solo IPv4: una IPv6 necesitaria corchetes en el cliente de Minecraft y los
	 * proveedores pueden devolver HTML de error que jamas debe publicarse.
	 */
	static boolean looksLikeIpv4( String value )
	{
		return value != null && value.matches( "[0-9]{1,3}(\\.[0-9]{1,3}){3}" );
	}
}
