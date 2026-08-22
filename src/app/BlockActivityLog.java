package app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lo que ha pasado en el mundo, guardado en el equipo.
 *
 * <p>Se queda con una ventana de las ultimas horas, no con todo: el interes de
 * esto es "que esta pasando ahora", y guardar meses de bloques convertiria un
 * fichero de apoyo en un problema de disco. Lo de antes sigue en la base del
 * mod, que es quien lleva el historial completo.</p>
 *
 * <p>Vive en la carpeta de datos de la aplicacion, <b>nunca dentro del
 * repositorio del mundo</b>: cambia cada pocos segundos y arruinaria los
 * respaldos.</p>
 */
public final class BlockActivityLog
{
	/** Cuanto se conserva. Mas alla de esto ya no es "lo que esta pasando". */
	public static final Duration RETENTION = Duration.ofHours( 12 );
	/** Techo duro de sucesos guardados, por si alguien se pone a minar en serio. */
	static final int MAX_ENTRIES = 2000;

	private static final ObjectMapper JSON = new ObjectMapper();

	private final Path file;
	private final Deque<BlockActivity> entries = new ArrayDeque<>();
	private long lastSeenId = 0;

	public BlockActivityLog( Path worldRepository )
	{
		this.file = WorldMap.directoryFor( worldRepository ).resolve( "activity.json" );
		load();
	}

	/** Identificador mas alto ya procesado, que sirve de cursor contra la base del mod. */
	public synchronized long lastSeenId()
	{
		return lastSeenId;
	}

	public synchronized void rememberCursor( long id )
	{
		if( id > lastSeenId )
			lastSeenId = id;
	}

	/** Añade lo nuevo, poda lo viejo y deja el resultado en disco. */
	public synchronized List<BlockActivity> add( List<BlockActivity> incoming )
	{
		List<BlockActivity> accepted = new ArrayList<>();
		for( BlockActivity activity : incoming )
		{
			if( activity.id() <= lastSeenId )
				continue;
			entries.addLast( activity );
			lastSeenId = activity.id();
			accepted.add( activity );
		}
		if( accepted.isEmpty() )
			return accepted;
		prune();
		save();
		return accepted;
	}

	/** Lo guardado, de lo mas reciente a lo mas antiguo. */
	public synchronized List<BlockActivity> recent( int limit )
	{
		List<BlockActivity> newestFirst = new ArrayList<>();
		java.util.Iterator<BlockActivity> backwards = entries.descendingIterator();
		while( backwards.hasNext() && newestFirst.size() < limit )
			newestFirst.add( backwards.next() );
		return newestFirst;
	}

	public synchronized int size()
	{
		return entries.size();
	}

	private void prune()
	{
		Instant cutoff = Instant.now().minus( RETENTION );
		while( !entries.isEmpty() && entries.peekFirst().at().isBefore( cutoff ) )
			entries.removeFirst();
		while( entries.size() > MAX_ENTRIES )
			entries.removeFirst();
	}

	private void load()
	{
		if( !Files.isRegularFile( file ) )
			return;
		try
		{
			JsonNode root = JSON.readTree( Files.readString( file, StandardCharsets.UTF_8 ) );
			lastSeenId = root.path( "lastSeenId" ).asLong( 0 );
			for( JsonNode node : root.path( "entries" ) )
			{
				entries.addLast( new BlockActivity( node.path( "id" ).asLong(),
						Instant.ofEpochMilli( node.path( "at" ).asLong() ), node.path( "player" ).asText( "" ),
						node.path( "action" ).asText( "" ), node.path( "block" ).asText( "" ),
						node.path( "world" ).asText( "" ), node.path( "x" ).asInt(), node.path( "y" ).asInt(),
						node.path( "z" ).asInt() ) );
			}
			prune();
		}
		catch( IOException | RuntimeException unreadable )
		{
			// Fichero a medio escribir o de otra version: se empieza de cero en vez
			// de dejar la aplicacion sin registro de actividad
			Log.event( "ACTIVITY", "No se pudo leer " + file + ", se empieza de cero", unreadable );
			entries.clear();
			lastSeenId = 0;
		}
	}

	private void save()
	{
		try
		{
			Files.createDirectories( file.getParent() );
			ObjectNode root = JSON.createObjectNode();
			root.put( "lastSeenId", lastSeenId );
			ArrayNode array = root.putArray( "entries" );
			for( BlockActivity activity : entries )
			{
				ObjectNode node = array.addObject();
				node.put( "id", activity.id() );
				node.put( "at", activity.at().toEpochMilli() );
				node.put( "player", activity.player() );
				node.put( "action", activity.action() );
				node.put( "block", activity.block() );
				node.put( "world", activity.world() );
				node.put( "x", activity.x() );
				node.put( "y", activity.y() );
				node.put( "z", activity.z() );
			}
			// Se escribe al lado y se mueve encima: si el equipo se apaga a media
			// escritura, el fichero bueno sigue estando entero
			Path temporary = file.resolveSibling( file.getFileName() + ".tmp" );
			Files.writeString( temporary, JSON.writeValueAsString( root ), StandardCharsets.UTF_8 );
			Files.move( temporary, file, StandardCopyOption.REPLACE_EXISTING );
		}
		catch( IOException notSaved )
		{
			Log.event( "ACTIVITY", "No se pudo guardar " + file, notSaved );
		}
	}
}
