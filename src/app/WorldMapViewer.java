package app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Añade al visor del mapa lo que no trae de fabrica.
 *
 * <p>Hoy es una cosa: el muñeco 3D del jugador. El visor solo sabe pintar una
 * chincheta plana con la cara, asi que se le cuelga un guion propio que le pone
 * un modelo de verdad con su skin. No se toca ni una linea del visor: se deja el
 * fichero en su carpeta y se apunta en la configuracion, que es la via que el
 * propio renderizador documenta para esto.</p>
 *
 * <p>El guion va pegado detras de three.js —la libreria 3D, en la MISMA version
 * que usa el visor— en un solo fichero. Separados, el navegador no garantiza en
 * que orden los ejecuta y la mitad de las veces el guion arrancaria sin
 * libreria.</p>
 */
public final class WorldMapViewer
{
	/** Sitio del guion dentro de la carpeta web, tal y como se apunta en la configuracion. */
	public static final String SCRIPT_PATH = "assets/endershare/players3d.js";

	private static final String LIBRARY_RESOURCE = "/webapp/three-r147.min.js";
	private static final String SCRIPT_RESOURCE = "/webapp/endershare-players3d.js";
	/** Cambiar esto obliga a reescribir el guion en los mapas que ya existan. */
	private static final String VERSION = "2";
	private static final String VERSION_MARK = "// endershare-players3d v" + VERSION;

	private WorldMapViewer()
	{
	}

	public static Path scriptFileIn( Path mapDirectory )
	{
		return mapDirectory.resolve( "web" ).resolve( "assets" ).resolve( "endershare" ).resolve( "players3d.js" );
	}

	/**
	 * Deja el guion puesto, o lo actualiza si es de una version anterior.
	 *
	 * @return true si ha hecho falta escribirlo
	 */
	public static boolean install( Path mapDirectory ) throws IOException
	{
		Path target = scriptFileIn( mapDirectory );
		if( alreadyInstalled( target ) )
			return false;

		Files.createDirectories( target.getParent() );
		Path temporary = target.resolveSibling( target.getFileName() + ".tmp" );
		try (OutputStream out = Files.newOutputStream( temporary ))
		{
			out.write( (VERSION_MARK + "\n").getBytes( StandardCharsets.UTF_8 ) );
			copyResource( LIBRARY_RESOURCE, out );
			out.write( "\n;\n".getBytes( StandardCharsets.UTF_8 ) );
			copyResource( SCRIPT_RESOURCE, out );
		}
		Files.move( temporary, target, StandardCopyOption.REPLACE_EXISTING );
		return true;
	}

	private static boolean alreadyInstalled( Path target ) throws IOException
	{
		if( !Files.isRegularFile( target ) )
			return false;
		try (var lines = Files.lines( target, StandardCharsets.UTF_8 ))
		{
			return lines.findFirst().map( VERSION_MARK::equals ).orElse( false );
		}
		catch( java.io.UncheckedIOException unreadable )
		{
			// Fichero a medio escribir o con otra codificacion: se rehace
			return false;
		}
	}

	private static void copyResource( String resource, OutputStream out ) throws IOException
	{
		try (InputStream source = WorldMapViewer.class.getResourceAsStream( resource ))
		{
			if( source == null )
				throw new IOException( "The viewer script is missing from this build: " + resource );
			source.transferTo( out );
		}
	}
}
