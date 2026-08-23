package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * El guion del muñeco 3D, comprobado con Node.
 *
 * <p>Lo que mide es a que trozo de la skin va a parar cada cara: es lo que diria
 * una foto —"¿tiene la cara en la cara o en la nuca?"— pero sin depender de que
 * alguien mire bien la imagen ni de que haya un navegador delante.</p>
 *
 * <p>Si no hay Node en la maquina se salta en vez de fallar: es una comprobacion
 * de mas, no un requisito para construir la aplicacion.</p>
 */
class PlayerModelScriptTest
{
	private static final Path TEST_SCRIPT = Path.of( "tests", "webapp", "players3d.test.js" );

	@Test
	void theModelIsBuiltWithEveryFaceWhereItBelongs() throws Exception
	{
		assumeTrue( Files.isRegularFile( TEST_SCRIPT ), "No esta el guion de prueba" );
		assumeTrue( nodeAvailable(), "Sin Node instalado" );

		ProcessBuilder builder = new ProcessBuilder( "node", TEST_SCRIPT.toString() );
		builder.redirectErrorStream( true );
		Process process = builder.start();
		String output = new String( process.getInputStream().readAllBytes() );
		process.waitFor( 60, TimeUnit.SECONDS );

		assertEquals( 0, process.exitValue(), output );
	}

	private static boolean nodeAvailable()
	{
		try
		{
			Process process = new ProcessBuilder( "node", "--version" ).redirectErrorStream( true ).start();
			return process.waitFor( 20, TimeUnit.SECONDS ) && process.exitValue() == 0;
		}
		catch( IOException | InterruptedException notThere )
		{
			if( notThere instanceof InterruptedException )
				Thread.currentThread().interrupt();
			return false;
		}
	}
}
