package app;

import java.util.List;
import java.util.Optional;

/**
 * Deteccion del CLIENTE de Minecraft en ejecucion, para el flujo de JOIN: si
 * el jugador ya tiene el juego abierto no se le puede teledirigir (eso pediria
 * un mod), pero si se le puede ofrecer cerrarlo y relanzar directo al server.
 *
 * <p>Un cliente se reconoce porque su linea de comandos lleva
 * {@code --accessToken} (todas las variantes: vanilla, Fabric, Forge); el
 * server no lo lleva jamas. SEGURIDAD: la linea de comandos contiene el token
 * de sesion del jugador — aqui solo se extraen pid y version, y la linea
 * NUNCA se guarda ni se loguea.</p>
 */
public final class RunningMinecraftClient
{
	/** Un cliente vivo: pid para poder cerrarlo y version para compararla. */
	public record Client( long pid, String versionId )
	{
	}

	private RunningMinecraftClient()
	{
	}

	/**
	 * Primer cliente de Minecraft vivo, excluyendo los PID indicados (el server
	 * hosteado por la app NUNCA debe salir aqui). Nunca lanza.
	 */
	public static Optional<Client> find( List<Long> excludedPids )
	{
		try
		{
			return ProcessHandle.allProcesses()
					.filter( process -> !excludedPids.contains( process.pid() ) )
					.map( process -> fromCommandLine( process.pid(),
							process.info().commandLine().orElse( null ) ) )
					.filter( java.util.Objects::nonNull )
					.findFirst();
		}
		catch( Exception detectionFailure )
		{
			return Optional.empty();
		}
	}

	/**
	 * pid + version a partir de una linea de comandos, o null si no es un
	 * cliente de Minecraft. Separado para poder probarse con lineas simuladas.
	 */
	static Client fromCommandLine( long pid, String commandLine )
	{
		Client result = null;
		do
		{
			if( commandLine == null || !commandLine.contains( "--accessToken" ) )
				break;
			// El launcher pasa siempre "--version <id>"; sin el, version desconocida
			String versionId = null;
			String[] parts = commandLine.split( "\\s+" );
			for( int index = 0; index < parts.length - 1; index++ )
			{
				if( "--version".equals( parts[index] ) )
				{
					versionId = parts[index + 1];
					break;
				}
			}
			result = new Client( pid, versionId );
		} while( false );
		return result;
	}

	/**
	 * Cierre del cliente: primero cortes (equivale a cerrar la ventana), y si
	 * en unos segundos sigue vivo, forzado. true si termino muerto.
	 */
	public static boolean close( long pid )
	{
		boolean result = false;
		try
		{
			Optional<ProcessHandle> handle = ProcessHandle.of( pid );
			if( handle.isEmpty() )
				return true;
			ProcessHandle process = handle.get();
			process.destroy();
			long waitedMillis = 0;
			while( process.isAlive() && waitedMillis < 10_000 )
			{
				Thread.sleep( 250 );
				waitedMillis += 250;
			}
			if( process.isAlive() )
				process.destroyForcibly();
			waitedMillis = 0;
			while( process.isAlive() && waitedMillis < 3_000 )
			{
				Thread.sleep( 250 );
				waitedMillis += 250;
			}
			result = !process.isAlive();
		}
		catch( InterruptedException interrupted )
		{
			Thread.currentThread().interrupt();
		}
		catch( Exception closeFailure )
		{
			Log.event( "JOIN", "No se pudo cerrar el cliente de Minecraft (pid " + pid + ")", closeFailure );
		}
		return result;
	}
}
