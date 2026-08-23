package app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Renderizadores que quedan colgados de una sesion anterior.
 *
 * <p>Esto sale de un caso real: al cerrar la aplicacion su renderizador siguio
 * vivo cinco horas, comiendo un nucleo, y cuando se abrio la aplicacion otra vez
 * los dos se pusieron a dibujar la misma carpeta a la vez. El tiempo estimado
 * pasaba de diez minutos a dos horas y media mientras se miraba.</p>
 */
class OrphanRendererTest
{
	@TempDir
	Path temporary;

	@BeforeEach
	void setUp()
	{
		System.setProperty( "endershare.dataDirectory", temporary.resolve( "data" ).toString() );
	}

	@AfterEach
	void tearDown()
	{
		System.clearProperty( "endershare.dataDirectory" );
	}

	@Test
	void withoutANoteThereIsNothingToClean() throws Exception
	{
		Path map = Files.createDirectories( temporary.resolve( "mapa" ) );

		assertFalse( WorldMap.killOrphanRenderer( map ) );
	}

	@Test
	void aNoteAboutSomethingThatIsNotTheRendererIsLeftAlone() throws Exception
	{
		Path map = Files.createDirectories( temporary.resolve( "mapa" ) );
		// Un proceso vivo cualquiera: los numeros de proceso se reciclan y seria muy
		// facil cargarse algo de otro
		Process innocent = new ProcessBuilder( "sleep", "30" ).start();
		try
		{
			Files.writeString( WorldMap.pidFileIn( map ), Long.toString( innocent.pid() ) );

			assertFalse( WorldMap.killOrphanRenderer( map ), "Ha matado un proceso que no era suyo" );
			assertTrue( innocent.isAlive(), "Ha matado un proceso que no era suyo" );
		}
		finally
		{
			innocent.destroyForcibly();
			innocent.waitFor( 10, TimeUnit.SECONDS );
		}
	}

	@Test
	void theNoteIsThrownAwayOnceItIsUseless() throws Exception
	{
		Path map = Files.createDirectories( temporary.resolve( "mapa" ) );
		Files.writeString( WorldMap.pidFileIn( map ), "esto no es un numero" );

		assertFalse( WorldMap.killOrphanRenderer( map ) );
		assertFalse( Files.exists( WorldMap.pidFileIn( map ) ), "La nota inservible se queda para siempre" );
	}

	@Test
	void anOrphanRendererIsKilled() throws Exception
	{
		org.junit.jupiter.api.Assumptions.assumeTrue( Files.isExecutable( Path.of( "/usr/bin/tail" ) ),
				"Hace falta tail para hacerse pasar por el renderizador" );
		Path map = Files.createDirectories( temporary.resolve( "mapa" ) );
		// Un proceso de verdad, vivo, con el renderizador en su linea de comandos,
		// que es justo lo que se mira para decidir si matarlo.
		//
		// Ni copiar un binario del sistema con otro nombre (macOS lo mata por la
		// firma) ni lanzarlo desde un shell (deja un nieto vivo colgado de las
		// tuberias y el que se queda colgado es el test)
		Path named = temporary.resolve( "bluemap-3.13-cli.jar" );
		Files.writeString( named, "nada" );
		Process orphan = new ProcessBuilder( "/usr/bin/tail", "-f", named.toString() ).start();
		try
		{
			Files.writeString( WorldMap.pidFileIn( map ), Long.toString( orphan.pid() ) );

			assertTrue( WorldMap.killOrphanRenderer( map ), "No ha matado al huerfano" );

			assertTrue( orphan.waitFor( 10, TimeUnit.SECONDS ), "Sigue vivo comiendose un nucleo" );
			assertFalse( Files.exists( WorldMap.pidFileIn( map ) ), "La nota se queda apuntando a un muerto" );
		}
		finally
		{
			orphan.destroyForcibly();
			orphan.waitFor( 10, TimeUnit.SECONDS );
		}
	}
}
