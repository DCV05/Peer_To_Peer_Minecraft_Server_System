package app;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lista de servers de Multiplayer del juego ({@code .minecraft/servers.dat}):
 * NBT SIN comprimir con una lista "servers" de compounds {ip, name, icon...}.
 * Aqui vive un lector/escritor NBT generico minimo (tipos 0-12) para hacer
 * upsert de UNA entrada preservando intactas todas las demas (icons incluidos).
 *
 * <p>Regla de oro: ante cualquier cosa que no se entienda, NO se toca el
 * fichero — mejor que el jugador pegue la IP a mano que romperle su lista.
 * Antes de escribir se deja copia en {@code servers.dat.bak}.</p>
 */
public final class ServersDat
{
	private ServersDat()
	{
	}

	/**
	 * Crea o actualiza la entrada {@code name} apuntando a {@code address}.
	 * Devuelve false si el fichero existe pero no se pudo parsear o escribir.
	 */
	public static boolean upsertServer( Path serversDatFile, String name, String address )
	{
		boolean result = false;
		try
		{
			Map<String, Object> root;
			if( Files.isRegularFile( serversDatFile ) )
			{
				try (DataInputStream input = new DataInputStream( Files.newInputStream( serversDatFile ) ))
				{
					root = readRoot( input );
				}
			}
			else
			{
				root = new LinkedHashMap<>();
			}

			NbtList servers = root.get( "servers" ) instanceof NbtList existing
					? existing
					: new NbtList( TAG_COMPOUND, new ArrayList<>() );
			root.put( "servers", servers );

			Map<String, Object> entry = null;
			for( Object candidate : servers.items() )
			{
				if( candidate instanceof Map<?, ?> compound && name.equals( compound.get( "name" ) ) )
				{
					@SuppressWarnings("unchecked")
					Map<String, Object> found = (Map<String, Object>) compound;
					entry = found;
					break;
				}
			}
			if( entry == null )
			{
				entry = new LinkedHashMap<>();
				entry.put( "name", name );
				servers.items().add( entry );
			}
			entry.put( "ip", address );

			byte[] encoded = writeRoot( root );
			if( Files.isRegularFile( serversDatFile ) )
				Files.copy( serversDatFile, serversDatFile.resolveSibling( serversDatFile.getFileName() + ".bak" ),
						StandardCopyOption.REPLACE_EXISTING );
			else if( serversDatFile.getParent() != null )
				Files.createDirectories( serversDatFile.getParent() );
			Files.write( serversDatFile, encoded );
			result = true;
		}
		catch( Exception nbtFailure )
		{
			Log.event( "SERVERS_DAT", "No se pudo actualizar la lista de servers en " + serversDatFile, nbtFailure );
		}
		return result;
	}

	// ---- NBT generico minimo -----------------------------------------------

	static final int TAG_END = 0, TAG_BYTE = 1, TAG_SHORT = 2, TAG_INT = 3, TAG_LONG = 4, TAG_FLOAT = 5,
			TAG_DOUBLE = 6, TAG_BYTE_ARRAY = 7, TAG_STRING = 8, TAG_LIST = 9, TAG_COMPOUND = 10,
			TAG_INT_ARRAY = 11, TAG_LONG_ARRAY = 12;

	/** Lista NBT: los elementos comparten tipo y van sin nombre. */
	record NbtList( int elementType, List<Object> items )
	{
	}

	static Map<String, Object> readRoot( DataInputStream input ) throws IOException
	{
		int type = input.readUnsignedByte();
		if( type != TAG_COMPOUND )
			throw new IOException( "Root tag is not a compound: " + type );
		input.readUTF();
		return readCompound( input );
	}

	private static Map<String, Object> readCompound( DataInputStream input ) throws IOException
	{
		Map<String, Object> compound = new LinkedHashMap<>();
		while( true )
		{
			int type = input.readUnsignedByte();
			if( type == TAG_END )
				break;
			String tagName = input.readUTF();
			compound.put( tagName, readPayload( input, type ) );
		}
		return compound;
	}

	private static Object readPayload( DataInputStream input, int type ) throws IOException
	{
		return switch( type )
		{
			case TAG_BYTE -> input.readByte();
			case TAG_SHORT -> input.readShort();
			case TAG_INT -> input.readInt();
			case TAG_LONG -> input.readLong();
			case TAG_FLOAT -> input.readFloat();
			case TAG_DOUBLE -> input.readDouble();
			case TAG_BYTE_ARRAY ->
			{
				byte[] bytes = new byte[input.readInt()];
				input.readFully( bytes );
				yield bytes;
			}
			case TAG_STRING -> input.readUTF();
			case TAG_LIST ->
			{
				int elementType = input.readUnsignedByte();
				int count = input.readInt();
				List<Object> items = new ArrayList<>( Math.max( 0, count ) );
				for( int index = 0; index < count; index++ )
				{
					items.add( readPayload( input, elementType ) );
				}
				yield new NbtList( elementType, items );
			}
			case TAG_COMPOUND -> readCompound( input );
			case TAG_INT_ARRAY ->
			{
				int[] values = new int[input.readInt()];
				for( int index = 0; index < values.length; index++ )
				{
					values[index] = input.readInt();
				}
				yield values;
			}
			case TAG_LONG_ARRAY ->
			{
				long[] values = new long[input.readInt()];
				for( int index = 0; index < values.length; index++ )
				{
					values[index] = input.readLong();
				}
				yield values;
			}
			default -> throw new IOException( "Unknown NBT tag type: " + type );
		};
	}

	static byte[] writeRoot( Map<String, Object> root ) throws IOException
	{
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		DataOutputStream output = new DataOutputStream( buffer );
		output.writeByte( TAG_COMPOUND );
		output.writeUTF( "" );
		writeCompoundPayload( output, root );
		output.flush();
		return buffer.toByteArray();
	}

	private static void writeCompoundPayload( DataOutputStream output, Map<String, Object> compound ) throws IOException
	{
		for( Map.Entry<String, Object> tag : compound.entrySet() )
		{
			int type = typeOf( tag.getValue() );
			output.writeByte( type );
			output.writeUTF( tag.getKey() );
			writePayload( output, type, tag.getValue() );
		}
		output.writeByte( TAG_END );
	}

	private static void writePayload( DataOutputStream output, int type, Object value ) throws IOException
	{
		switch( type )
		{
			case TAG_BYTE -> output.writeByte( (Byte) value );
			case TAG_SHORT -> output.writeShort( (Short) value );
			case TAG_INT -> output.writeInt( (Integer) value );
			case TAG_LONG -> output.writeLong( (Long) value );
			case TAG_FLOAT -> output.writeFloat( (Float) value );
			case TAG_DOUBLE -> output.writeDouble( (Double) value );
			case TAG_BYTE_ARRAY ->
			{
				byte[] bytes = (byte[]) value;
				output.writeInt( bytes.length );
				output.write( bytes );
			}
			case TAG_STRING -> output.writeUTF( (String) value );
			case TAG_LIST ->
			{
				NbtList list = (NbtList) value;
				// Una lista recien vaciada mantiene su tipo declarado; una nueva sin
				// elementos declara TAG_END como hace el propio juego
				output.writeByte( list.items().isEmpty() && list.elementType() == TAG_END ? TAG_END : list.elementType() );
				output.writeInt( list.items().size() );
				for( Object item : list.items() )
				{
					writePayload( output, list.elementType(), item );
				}
			}
			case TAG_COMPOUND ->
			{
				@SuppressWarnings("unchecked")
				Map<String, Object> compound = (Map<String, Object>) value;
				writeCompoundPayload( output, compound );
			}
			case TAG_INT_ARRAY ->
			{
				int[] values = (int[]) value;
				output.writeInt( values.length );
				for( int item : values )
				{
					output.writeInt( item );
				}
			}
			case TAG_LONG_ARRAY ->
			{
				long[] values = (long[]) value;
				output.writeInt( values.length );
				for( long item : values )
				{
					output.writeLong( item );
				}
			}
			default -> throw new IOException( "Unwritable NBT value: " + value );
		}
	}

	private static int typeOf( Object value ) throws IOException
	{
		if( value instanceof Byte )
			return TAG_BYTE;
		if( value instanceof Short )
			return TAG_SHORT;
		if( value instanceof Integer )
			return TAG_INT;
		if( value instanceof Long )
			return TAG_LONG;
		if( value instanceof Float )
			return TAG_FLOAT;
		if( value instanceof Double )
			return TAG_DOUBLE;
		if( value instanceof byte[] )
			return TAG_BYTE_ARRAY;
		if( value instanceof String )
			return TAG_STRING;
		if( value instanceof NbtList )
			return TAG_LIST;
		if( value instanceof Map )
			return TAG_COMPOUND;
		if( value instanceof int[] )
			return TAG_INT_ARRAY;
		if( value instanceof long[] )
			return TAG_LONG_ARRAY;
		throw new IOException( "Unknown NBT value class: " + value.getClass() );
	}
}
