package app;

/**
 * Punto único de registro de la aplicación. Cada evento lleva una etiqueta
 * estable en mayúsculas (para poder grepear el log) y un mensaje humano; la
 * causa, si existe, se vuelca completa para no cegar el diagnóstico.
 */
public final class Log
{
	private Log()
	{
	}

	/** Registra un evento informativo con etiqueta estable. */
	public static void event( String tag, String message )
	{
		event( tag, message, null );
	}

	/** Registra un evento con etiqueta estable y causa opcional. */
	public static void event( String tag, String message, Throwable cause )
	{
		String line = "[" + tag + "] " + message;
		System.err.println( line );
		// La traza completa se conserva: es la unica pista real cuando algo
		// falla en la maquina de otro y solo nos mandan la consola
		if( cause != null )
			cause.printStackTrace();
	}
}
