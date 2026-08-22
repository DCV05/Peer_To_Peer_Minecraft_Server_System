package app;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Comprueba que todo recurso que el codigo pide por su nombre existe de verdad.
 *
 * <p>Nace de un fallo real: al renombrar el proyecto, el codigo paso a pedir
 * {@code EndershareIcon-16.png} mientras el fichero seguia llamandose con el
 * nombre viejo. El compilador no dice nada porque es una cadena de texto, la
 * suite entera pasaba, y la aplicacion **no abria**: se caia al construir la
 * ventana. Un nombre mal escrito no puede volver a llegar tan lejos.</p>
 */
class BundledResourcesTest
{
	private static final Pattern RESOURCE_CALL = Pattern
			.compile( "getResource(?:AsStream)?\\s*\\(\\s*\"([^\"]+)\"" );

	/**
	 * Las credenciales de Google Drive son un secreto que no se versiona: quien
	 * quiera esa integracion pone las suyas al compilar.
	 */
	private static final Set<String> DELIBERATELY_ABSENT = Set.of( "/credentials/GoolgeDriveCredentials.json" );

	@Test
	void everyResourceTheCodeAsksForIsActuallyBundled() throws IOException
	{
		Set<String> requested = resourcesRequestedInSources();
		assertTrue( requested.size() >= 3, "No se ha encontrado ningun recurso: el test no esta mirando donde debe" );

		List<String> missing = new ArrayList<>();
		for( String resource : requested )
		{
			if( DELIBERATELY_ABSENT.contains( resource ) )
				continue;
			// Se mira en los dos sitios a proposito. Solo el classpath no vale: una
			// compilacion anterior deja copias en target y el recurso parece estar
			// aunque ya no exista en el proyecto
			boolean onClasspath = BundledResourcesTest.class.getResource( resource ) != null;
			boolean inSources = Files.isRegularFile( Path.of( "src", "resources" ).resolve( resource.substring( 1 ) ) );
			if( !onClasspath || !inSources )
				missing.add( resource );
		}

		assertTrue( missing.isEmpty(), "El codigo pide recursos que no existen, y eso impide que la aplicacion abra: "
				+ missing );
	}

	@Test
	void theWindowIconsAreThere()
	{
		// Estos tres son los que se cargan al construir la ventana principal: si
		// falta uno, la aplicacion no llega ni a pintarse
		for( int size : new int[] { 16, 32, 64 } )
		{
			String name = "/icons/EndershareIcon-" + size + ".png";
			assertNotNull( BundledResourcesTest.class.getResource( name ), "Falta el icono de " + size + " px" );
			assertTrue( Files.isRegularFile( Path.of( "src", "resources", "icons", "EndershareIcon-" + size + ".png" ) ),
					"El icono de " + size + " px no esta en el proyecto, solo en una compilacion vieja" );
		}
	}

	private static Set<String> resourcesRequestedInSources() throws IOException
	{
		Path sources = Path.of( "src" );
		Set<String> found = new TreeSet<>();
		if( !Files.isDirectory( sources ) )
			return found;
		try (Stream<Path> tree = Files.walk( sources ))
		{
			for( Path file : tree.filter( path -> path.toString().endsWith( ".java" ) ).toList() )
			{
				Matcher matcher = RESOURCE_CALL.matcher( Files.readString( file, StandardCharsets.UTF_8 ) );
				while( matcher.find() )
					found.add( matcher.group( 1 ) );
			}
		}
		return found;
	}
}
