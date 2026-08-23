package view;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * El ritmo con el que se le piden guardados al servidor mientras el mapa esta
 * en marcha.
 *
 * <p>Esto sale de una caida real: se pedia un guardado cada minuto, en un mundo
 * grande cada guardado tardaba 45 segundos, y el servidor se pasaba la vida
 * guardando hasta que su propio vigilante lo dio por colgado y lo mato con el
 * jugador dentro.</p>
 */
class WorldMapSaveRhythmTest
{
	private static final long SECOND = 1000L;

	@Test
	void theRhythmLeavesRoomForTheSaveToFinish()
	{
		// Medido en el mundo real: 45 segundos por guardado. Y el vigilante de
		// Minecraft mata al servidor si un tick pasa de 60
		assertTrue( MainFrame.MAP_LIVE_SAVE_SECONDS >= 300,
				"Con menos de cinco minutos se encadenan guardados y el servidor se cae" );
	}

	@Test
	void withNothingRunningTheSaveIsAsked()
	{
		assertTrue( MainFrame.mayAskForAnotherSave( false, 0, 1_000_000 ) );
	}

	@Test
	void whileOneSaveIsRunningNoOtherIsAsked()
	{
		long asked = 1_000_000;
		long tenSecondsLater = asked + 10 * SECOND;

		assertFalse( MainFrame.mayAskForAnotherSave( true, asked, tenSecondsLater ),
				"Se encadenaria un guardado sobre otro, que es justo lo que tumbo el servidor" );
	}

	@Test
	void aSaveStillRunningAfterAWhileIsStillRespected()
	{
		long asked = 1_000_000;
		// Un guardado de cinco minutos es lento pero posible en un mundo grande
		long fiveMinutesLater = asked + 300 * SECOND;

		assertFalse( MainFrame.mayAskForAnotherSave( true, asked, fiveMinutesLater ) );
	}

	@Test
	void aSaveThatNeverAnswersDoesNotFreezeTheMapForever()
	{
		long asked = 1_000_000;
		long tooLate = asked + (MainFrame.MAP_LIVE_SAVE_TIMEOUT_SECONDS + 1) * SECOND;

		assertTrue( MainFrame.mayAskForAnotherSave( true, asked, tooLate ),
				"Un solo aviso perdido dejaria el mapa congelado para siempre" );
	}
}
