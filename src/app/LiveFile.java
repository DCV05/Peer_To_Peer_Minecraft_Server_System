package app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Los ficheros que el visor del mapa consulta solo y que nosotros reescribimos
 * cada pocos segundos: posiciones de jugador y actividad de bloques.
 *
 * <p>Dos cuidados, y los dos salen de fallos reales:</p>
 *
 * <ul>
 * <li><b>No se reescribe lo que ya esta escrito.</b> Los vigilantes van por
 * reloj, no por novedades, asi que sin esto se reescribe lo mismo una y otra vez
 * durante horas. Comprobar cuanto ocupa el fichero sale muchisimo mas barato.</li>
 * <li><b>Se escribe al lado y se mueve encima.</b> El visor lee estos ficheros
 * por su cuenta cada segundo; escribiendo en el sitio le tocaria leer medio
 * fichero cada dos por tres.</li>
 * </ul>
 *
 * <p>El tamaño basta para saber si nos han pisado: el unico que escribe aqui
 * aparte de nosotros es el renderizador, que guarda su estado cada dos minutos y
 * siempre deja lo mismo, <code>{}</code>.</p>
 */
final class LiveFile
{
	private static final Map<Path, String> lastWritten = new ConcurrentHashMap<>();

	private LiveFile()
	{
	}

	/**
	 * Deja el contenido en el fichero si no estaba ya.
	 *
	 * @return true si ha hecho falta escribir
	 */
	static boolean write( Path file, String content, String logTag )
	{
		if( alreadyWritten( file, content ) )
			return false;
		try
		{
			Files.createDirectories( file.getParent() );
			Path temporary = file.resolveSibling( file.getFileName() + ".tmp" );
			Files.writeString( temporary, content, StandardCharsets.UTF_8 );
			Files.move( temporary, file, StandardCopyOption.REPLACE_EXISTING );
			lastWritten.put( file, content );
			return true;
		}
		catch( IOException notWritten )
		{
			lastWritten.remove( file );
			Log.event( logTag, "No se pudo escribir " + file, notWritten );
			return false;
		}
	}

	private static boolean alreadyWritten( Path file, String content )
	{
		if( !content.equals( lastWritten.get( file ) ) )
			return false;
		try
		{
			return Files.size( file ) == content.getBytes( StandardCharsets.UTF_8 ).length;
		}
		catch( IOException gone )
		{
			// Ya no esta, o no se puede mirar: se escribe y punto
			return false;
		}
	}

	/** Los tests trabajan sobre carpetas nuevas en cada caso. */
	static void forgetForTests()
	{
		lastWritten.clear();
	}
}
