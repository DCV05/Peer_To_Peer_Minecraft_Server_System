package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Lo que le cuesta al equipo dibujar el mapa.
 *
 * <p>Los numeros de aqui no son de catalogo: salen de dibujar los mismos 3894
 * tiles del mundo real una y otra vez cambiando un solo parametro cada vez, y de
 * comprobar despues que los 3849 tiles resultantes son identicos entre pasadas
 * salvo un identificador aleatorio que el renderizador estampa en cada uno.</p>
 */
class WorldMapCostTest
{
	@Test
	void lookingAtTheMapDrawsNothing()
	{
		// Ver el mapa lanzaba -ruw, y la r es una pasada de dibujo entera: mirar el
		// mapa dejaba el equipo con carga 59
		assertEquals( "-w", WorldMap.Mode.SERVE.flags() );
		assertFalse( WorldMap.Mode.SERVE.flags().contains( "r" ), "Ver el mapa no puede dibujarlo" );
		assertFalse( WorldMap.Mode.SERVE.draws() );
	}

	@Test
	void updatingRedrawsOnlyWhatChanged()
	{
		assertEquals( "-ruw", WorldMap.Mode.UPDATE.flags() );
		assertFalse( WorldMap.Mode.UPDATE.flags().contains( "f" ),
			"Rehacer el mapa entero son horas de disco: no puede pasar sin que alguien lo pida" );
		assertTrue( WorldMap.Mode.UPDATE.draws() );
	}

	@Test
	void redrawingEverythingIsAskedForExplicitly()
	{
		assertEquals( "-ruwf", WorldMap.Mode.REDRAW_EVERYTHING.flags() );
		assertTrue( WorldMap.Mode.REDRAW_EVERYTHING.draws() );
	}

	@Test
	void onlyOneOfTheThreeModesRedrawsEverything()
	{
		long redrawing = java.util.Arrays.stream( WorldMap.Mode.values() )
				.filter( mode -> mode.flags().contains( "f" ) ).count();

		assertEquals( 1, redrawing, "Solo el boton de rehacer puede llegar a rehacerlo todo" );
	}

	// ---- prioridad ----------------------------------------------------------

	@Test
	void withNobodyPlayingTheDiskIsNeverThrottled()
	{
		List<String> prefix = WorldMap.lowPriorityPrefix( false ).orElse( List.of() );

		assertFalse( WorldMap.throttlesDisk( prefix ),
			"Medido: frenar el disco sin partida multiplica por casi trece lo que tarda el mapa" );
	}

	@Test
	void whileTheGameIsRunningTheDiskIsThrottled()
	{
		List<String> prefix = WorldMap.lowPriorityPrefix( true ).orElse( List.of() );

		// Solo donde el sistema lo ofrece; donde no, al menos no se afirma lo contrario
		if( java.nio.file.Files.isExecutable( java.nio.file.Path.of( "/usr/sbin/taskpolicy" ) ) )
			assertTrue( WorldMap.throttlesDisk( prefix ),
				"El guardado del mundo perdio el disco y el vigilante de Minecraft tumbo el servidor" );
	}

	@Test
	void evenWithoutThrottlingTheMachineStaysUsable()
	{
		List<String> prefix = WorldMap.lowPriorityPrefix( false ).orElse( List.of() );

		assertFalse( prefix.isEmpty(), "Sin ninguna rebaja de prioridad, dibujar deja el equipo a trompicones" );
	}

	// ---- memoria ------------------------------------------------------------

	@Test
	void justLookingAtTheMapBarelyCostsMemory()
	{
		// Medido: sirviendo se queda en 65 MB de verdad, se le ponga el tope que se
		// le ponga. Pedir medio giga era pedir por pedir
		assertEquals( 128, WorldMap.heapMegabytesFor( WorldMap.Mode.SERVE ) );
	}

	@Test
	void drawingDoesNotAskForFourTimesWhatItUses()
	{
		// Medido con doce hilos: con tope de 4 GB hace pico en 1040 MB y tarda 76 s;
		// con tope de 1 GB hace pico en 624 MB y tarda 83 s. El tope grande no compra
		// velocidad, compra que el recolector se vuelva perezoso
		int drawing = WorldMap.heapMegabytesFor( WorldMap.Mode.UPDATE );

		assertTrue( drawing <= 1024, "Cuatro veces lo que de verdad usa es lo que manda el equipo al swap" );
		assertTrue( drawing >= 768, "Por debajo de esto el recolector se lo come todo" );
	}

	@Test
	void drawingAlwaysNeedsMoreThanServing()
	{
		assertTrue( WorldMap.heapMegabytesFor( WorldMap.Mode.SERVE )
				< WorldMap.heapMegabytesFor( WorldMap.Mode.REDRAW_EVERYTHING ) );
	}

	// ---- hilos --------------------------------------------------------------

	@Test
	void withTheGameRunningOnlyOneThreadDraws()
	{
		// Cada hilo no es solo procesador: es otro escribiendo al disco a la vez, y
		// con el disco ocupado el guardado del mundo tardaba 45 segundos hasta que el
		// vigilante de Minecraft tumbo el servidor
		assertEquals( 1, WorldMapConfig.threadCountFor( true ) );
	}

	@Test
	void withNobodyPlayingTheMachineIsUsedProperly()
	{
		int cores = Runtime.getRuntime().availableProcessors();
		int threads = WorldMapConfig.threadCountFor( false );

		assertTrue( threads >= 1 );
		assertTrue( threads <= cores - 2 || cores <= 3,
			"Hay que dejar dos nucleos para que el equipo siga usable" );
		if( cores >= 8 )
			assertTrue( threads > cores / 2,
				"Usar la mitad de la maquina era una cifra puesta a ojo: medido, 12 hilos van casi 4 veces mas rapido que 2" );
	}
}
