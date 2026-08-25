package app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Punto único de registro de la aplicación. Cada evento lleva una etiqueta
 * estable en mayúsculas (para poder grepear el log) y un mensaje humano; la
 * causa, si existe, se vuelca completa para no cegar el diagnóstico.
 *
 * <p>Además de la consola, el evento se escribe en
 * {@code ~/.endershare/data/logs/endershare.log}, rotado por tamaño. Sin
 * fichero, una incidencia en la máquina de otra persona sólo se podía
 * diagnosticar por capturas de pantalla del panel, que corta los mensajes por
 * el ancho del label: un backup rechazado costó tres horas de ida y vuelta
 * porque el motivo real del fallo estaba en la mitad que no se veía.</p>
 *
 * <p>El fichero es una comodidad, nunca un motivo de caída: si el disco falla,
 * el evento sigue saliendo por consola y la aplicación no se entera.</p>
 */
public final class Log
{
	/** Cinco ficheros de 2 MiB: suficiente para varias sesiones sin comerse el disco. */
	static final long MAX_LOG_BYTES = 2L * 1024 * 1024;
	static final int ROTATED_COPIES = 5;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss" );
	/** Se apaga en los tests que no quieren tocar el disco del usuario. */
	private static volatile boolean fileLoggingEnabled = true;
	private static final Object FILE_LOCK = new Object();

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
		appendToFile( line, cause );
	}

	/** Ruta del log activo. Pública para que la UI pueda ofrecerla al copiar un error. */
	public static Path logFile()
	{
		return AppPaths.dataFile( "logs" ).resolve( "endershare.log" );
	}

	/**
	 * Últimas {@code lines} líneas del log activo, para adjuntarlas a un error
	 * copiado desde el panel. Devuelve lista vacía si aún no hay fichero: quien
	 * llama está construyendo un mensaje de ayuda, no puede fallar por esto.
	 */
	public static List<String> tail( int lines )
	{
		List<String> result = List.of();
		do
		{
			if( lines <= 0 )
				break;
			Path file = logFile();
			if( !Files.exists( file ) )
				break;
			try
			{
				List<String> all = Files.readAllLines( file, StandardCharsets.UTF_8 );
				int from = Math.max( 0, all.size() - lines );
				result = List.copyOf( all.subList( from, all.size() ) );
			}
			catch( IOException unreadable )
			{
				// Sin log que adjuntar se devuelve vacio: el mensaje principal ya
				// lleva la informacion importante
				System.err.println( "[LOG] No se pudo leer " + file + ": " + unreadable.getMessage() );
			}
		}
		while( false );
		return result;
	}

	/** Apaga la escritura a disco. Sólo para tests: en producción siempre está viva. */
	static void setFileLoggingEnabled( boolean enabled )
	{
		fileLoggingEnabled = enabled;
	}

	// ---- FASE 2 — Escritura y rotación -------------------------------------

	private static void appendToFile( String line, Throwable cause )
	{
		if( !fileLoggingEnabled )
			return;
		String stamped = LocalDateTime.now().format( STAMP ) + "  " + line + System.lineSeparator() + traceOf( cause );
		synchronized( FILE_LOCK )
		{
			try
			{
				Path file = logFile();
				Files.createDirectories( file.getParent() );
				rotateIfNeeded( file, stamped.getBytes( StandardCharsets.UTF_8 ).length );
				Files.writeString( file, stamped, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND );
			}
			catch( IOException notWritten )
			{
				// Degradar sin ruido recursivo: llamar a event() aqui se realimentaria
				System.err.println( "[LOG] No se pudo escribir el log en disco: " + notWritten.getMessage() );
			}
		}
	}

	private static String traceOf( Throwable cause )
	{
		if( cause == null )
			return "";
		StringWriter buffer = new StringWriter();
		cause.printStackTrace( new PrintWriter( buffer ) );
		return buffer + System.lineSeparator();
	}

	/**
	 * Rota cuando el evento que entra no cabe. Se comprueba ANTES de escribir para
	 * que el tope sea real: comprobando después, un evento con traza larga podía
	 * dejar el fichero al doble del límite hasta la siguiente llamada.
	 */
	private static void rotateIfNeeded( Path file, int incomingBytes ) throws IOException
	{
		if( !Files.exists( file ) || Files.size( file ) + incomingBytes <= MAX_LOG_BYTES )
			return;
		// El más viejo se pierde; los demás corren un puesto. Se recorre de atrás
		// hacia delante para no pisar un fichero antes de haberlo movido
		for( int copy = ROTATED_COPIES - 1; copy >= 1; copy-- )
		{
			Path older = file.resolveSibling( file.getFileName() + "." + copy );
			if( !Files.exists( older ) )
				continue;
			Files.move( older, file.resolveSibling( file.getFileName() + "." + (copy + 1) ),
					StandardCopyOption.REPLACE_EXISTING );
		}
		Files.move( file, file.resolveSibling( file.getFileName() + ".1" ), StandardCopyOption.REPLACE_EXISTING );
	}
}
