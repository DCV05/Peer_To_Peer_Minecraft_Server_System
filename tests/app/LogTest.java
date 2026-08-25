package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * El log en disco es la única pista cuando algo falla en la máquina de otra
 * persona: estos tests fijan que el fichero se escribe, que no crece sin
 * límite y que un fallo de disco no tumba a quien registra.
 */
class LogTest
{
	@TempDir
	Path dataDirectory;

	@BeforeEach
	void pointDataDirectoryToTemp()
	{
		System.setProperty( "endershare.dataDirectory", dataDirectory.toString() );
		Log.setFileLoggingEnabled( true );
	}

	@AfterEach
	void restoreDataDirectory()
	{
		System.clearProperty( "endershare.dataDirectory" );
	}

	@Test
	void eventsCarryTheirTagAndSurviveAnyCause() throws Exception
	{
		PrintStream originalErr = System.err;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		try
		{
			System.setErr( new PrintStream( captured, true, StandardCharsets.UTF_8 ) );
			Log.event( "TEST_TAG", "algo paso" );
			Log.event( "TEST_TAG", "algo fallo", new IllegalStateException( "detalle" ) );
			Log.event( "TEST_TAG", "causa nula tolerada", null );
		}
		finally
		{
			System.setErr( originalErr );
		}
		String output = captured.toString( StandardCharsets.UTF_8 );
		assertTrue( output.contains( "[TEST_TAG] algo paso" ) );
		assertTrue( output.contains( "[TEST_TAG] algo fallo" ) );
		// La traza completa se conserva para poder diagnosticar en remoto
		assertTrue( output.contains( "IllegalStateException" ) );
	}

	@Test
	void elEventoQuedaEnElFicheroDeLog() throws IOException
	{
		Log.event( "GIT_BACKUP", "el mundo no pudo subirse" );

		Path file = Log.logFile();
		assertTrue( Files.exists( file ), "el log deberia haberse creado" );
		String content = Files.readString( file, StandardCharsets.UTF_8 );
		assertTrue( content.contains( "[GIT_BACKUP] el mundo no pudo subirse" ), content );
	}

	@Test
	void laCausaSeVuelcaEnteraParaPoderDiagnosticar() throws IOException
	{
		Log.event( "GIT_BACKUP", "fallo al empujar", new IllegalStateException( "REJECTED_NONFASTFORWARD" ) );

		String content = Files.readString( Log.logFile(), StandardCharsets.UTF_8 );
		assertTrue( content.contains( "REJECTED_NONFASTFORWARD" ), content );
		assertTrue( content.contains( "IllegalStateException" ), content );
	}

	@Test
	void tailDevuelveLasUltimasLineasYNuncaFallaSinFichero()
	{
		assertEquals( List.of(), Log.tail( 10 ), "sin log todavia, tail debe devolver vacio" );

		Log.event( "UI", "primero" );
		Log.event( "UI", "segundo" );
		Log.event( "UI", "tercero" );

		List<String> lastTwo = Log.tail( 2 );
		assertEquals( 2, lastTwo.size() );
		assertTrue( lastTwo.get( 1 ).contains( "tercero" ), lastTwo.toString() );
	}

	@Test
	void elLogRotaYNoCreceSinLimite() throws IOException
	{
		Path file = Log.logFile();
		Files.createDirectories( file.getParent() );
		// Se deja el log justo en el tope: el siguiente evento ya no cabe
		Files.writeString( file, "x".repeat( (int) Log.MAX_LOG_BYTES ), StandardCharsets.UTF_8 );

		Log.event( "GIT_BACKUP", "evento que desborda" );

		Path rotated = file.resolveSibling( file.getFileName() + ".1" );
		assertTrue( Files.exists( rotated ), "el log lleno deberia haberse apartado" );
		assertTrue( Files.size( file ) < Log.MAX_LOG_BYTES, "el log activo deberia empezar de nuevo" );
		assertTrue( Files.readString( file, StandardCharsets.UTF_8 ).contains( "evento que desborda" ) );
	}

	@Test
	void unDiscoQueNoDejaEscribirNoTumbaAQuienRegistra() throws IOException
	{
		// Un fichero donde deberia ir la carpeta: createDirectories fallara
		Files.writeString( dataDirectory.resolve( "logs" ), "no soy una carpeta" );

		Log.event( "GIT_BACKUP", "esto no debe lanzar" );

		assertFalse( Files.isDirectory( dataDirectory.resolve( "logs" ) ) );
	}
}
