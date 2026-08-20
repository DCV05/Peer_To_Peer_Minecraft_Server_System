package app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class LogTest
{

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
}
