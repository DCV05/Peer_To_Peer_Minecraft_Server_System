package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * El renderizador solo cuenta como va por lineas de texto. Si el formato no se
 * lee bien, la barra de progreso se queda muerta durante una hora sin que nadie
 * sepa si el mapa avanza o se ha colgado.
 */
class WorldMapProgressTest
{
	@Test
	void readsPercentAndRemainingTimeFromAnUpdateLine()
	{
		Optional<WorldMapProgress.Step> step = WorldMapProgress
				.parse( "[INFO] Update map 'overworld': 26.431% (ETA: 00:47:19)" );

		assertTrue( step.isPresent() );
		assertEquals( 26, step.get().percent() );
		assertFalse( step.get().finished() );
		assertTrue( step.get().detail().contains( "00:47:19" ), "Se pierde la ETA: " + step.get().detail() );
	}

	@Test
	void readsAnUpdateLineWithoutRemainingTime()
	{
		Optional<WorldMapProgress.Step> step = WorldMapProgress.parse( "[INFO] Update map 'end': 5.0%" );

		assertTrue( step.isPresent() );
		assertEquals( 5, step.get().percent() );
		assertFalse( step.get().finished() );
	}

	@Test
	void recognisesTheFinishedLine()
	{
		Optional<WorldMapProgress.Step> step = WorldMapProgress.parse( "[INFO] Your maps are now all up-to-date!" );

		assertTrue( step.isPresent() );
		assertTrue( step.get().finished() );
		assertEquals( 100, step.get().percent() );
	}

	@Test
	void announcesHowMuchWorldIsAboutToBeRead()
	{
		Optional<WorldMapProgress.Step> step = WorldMapProgress
				.parse( "[INFO] Start updating 3 maps (1448 regions, ~1482752 chunks)..." );

		assertTrue( step.isPresent() );
		assertEquals( -1, step.get().percent(), "Al empezar no se sabe el porcentaje" );
		assertTrue( step.get().detail().contains( "1448" ) );
	}

	@Test
	void ignoresLinesThatSayNothingAboutProgress()
	{
		assertTrue( WorldMapProgress.parse( "[INFO] WebServer started." ).isEmpty() );
		assertTrue( WorldMapProgress.parse( "" ).isEmpty() );
		assertTrue( WorldMapProgress.parse( null ).isEmpty() );
	}

	@Test
	void neverReportsMoreThanAHundredPercent()
	{
		Optional<WorldMapProgress.Step> step = WorldMapProgress.parse( "[INFO] Update map 'overworld': 100.0%" );

		assertTrue( step.isPresent() );
		assertEquals( 100, step.get().percent() );
	}
}
