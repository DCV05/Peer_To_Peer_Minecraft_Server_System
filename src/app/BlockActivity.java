package app;

import java.time.Instant;

/**
 * Un bloque puesto o roto por alguien.
 *
 * <p>Es lo que el mapa pinta como marcador y lo que viaja a los demas por el
 * canal de eventos. Se queda con lo justo para contar que paso y donde: el
 * resto (estado del bloque, datos extra) engorda el mensaje sin aportar nada a
 * quien lo mira.</p>
 *
 * @param id identificador incremental de la base de Ledger, que sirve de cursor
 * @param at cuando ocurrio
 * @param player quien lo hizo, o cadena vacia si no fue una persona
 * @param action {@link #PLACED} o {@link #BROKEN}
 * @param block bloque afectado, p. ej. {@code minecraft:obsidian}
 * @param world dimension, p. ej. {@code minecraft:overworld}
 */
public record BlockActivity( long id, Instant at, String player, String action, String block, String world, int x,
		int y, int z )
{
	public static final String PLACED = "block-place";
	public static final String BROKEN = "block-break";

	/** Nombre corto del bloque, sin el espacio de nombres, para enseñarlo. */
	public String blockName()
	{
		int separator = block.indexOf( ':' );
		return separator >= 0 ? block.substring( separator + 1 ).replace( '_', ' ' ) : block;
	}

	public boolean wasPlaced()
	{
		return PLACED.equals( action );
	}

	/** Una linea legible: "Victor broke obsidian at 120, 64, -340". */
	public String describe()
	{
		String who = player == null || player.isBlank() ? "Someone" : player;
		return who + (wasPlaced() ? " placed " : " broke ") + blockName() + " at " + x + ", " + y + ", " + z;
	}
}
