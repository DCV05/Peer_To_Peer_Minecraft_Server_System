package jgit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.JOptionPane;

/**
 * Guarda la sesión de GitHub en disco: el perfil en claro y el token cifrado.
 *
 * El token se cifra con una clave derivada de la máquina (usuario + sistema +
 * home) y se sella con un HMAC, de modo que copiar el fichero a otro equipo lo
 * deja inservible y una manipulación se detecta antes de descifrar. Toda
 * escritura pasa por un fichero .tmp y un movimiento atómico, y ante cualquier
 * fallo se restaura el contenido anterior: quedarse sin sesión por un corte a
 * mitad de guardado obligaría al usuario a reautenticarse.
 */
public final class TokenStore
{
	private static final String DATA_DIRECTORY_PROPERTY = "p2pmss.dataDirectory";

	// ---- FASE 1 — Sesion persistida ----------------------------------------

	/** Guarda perfil y token de forma transaccional: o entran los dos, o no cambia nada. */
	public static boolean saveUserData( String nickname, String email, String token )
	{
		boolean result;
		do
		{
			if( !ensureDataDirectory() )
			{
				result = false;
				break;
			}
			if( isBlank( nickname ) || isBlank( email ) || isBlank( token ) )
			{
				result = false;
				break;
			}

			Path userDataFile = userDataFile();
			Path tokenFile = tokenFile();
			Path userDataTemp = userDataFile.resolveSibling( userDataFile.getFileName() + ".tmp" );
			Path tokenTemp = tokenFile.resolveSibling( tokenFile.getFileName() + ".tmp" );

			// Copia previa en memoria: es la unica forma de deshacer si el segundo
			// movimiento atomico falla despues de que el primero ya haya entrado
			byte[] previousUserData;
			byte[] previousToken;
			try
			{
				previousUserData = readExistingFile( userDataFile );
				previousToken = readExistingFile( tokenFile );
			}
			catch( IOException backupFailure )
			{
				app.Log.event( "GIT_AUTH", "No se pudo respaldar la sesion anterior antes de guardarla", backupFailure );
				result = false;
				break;
			}

			try
			{
				Properties props = new Properties();
				props.setProperty( "nickname", nickname.trim() );
				props.setProperty( "email", email.trim() );
				try (FileOutputStream out = new FileOutputStream( userDataTemp.toFile() ))
				{
					props.store( out, "User data updated" );
				}

				Files.write( tokenTemp, serializeToken( token.trim() ) );
				moveAtomically( userDataTemp, userDataFile );
				moveAtomically( tokenTemp, tokenFile );
				result = true;
			}
			catch( Exception saveFailure )
			{
				app.Log.event( "GIT_AUTH", "Fallo al guardar la sesion; se restaura la anterior", saveFailure );
				restoreFile( userDataFile, previousUserData );
				restoreFile( tokenFile, previousToken );
				deleteQuietly( userDataTemp );
				deleteQuietly( tokenTemp );
				result = false;
			}
		} while( false );
		return result;
	}

	/** Devuelve nickname, email y token de la sesión abierta, o falla si no la hay. */
	public static Map<String, String> getSavedUserData() throws Exception
	{
		if( !ensureDataDirectory() )
			throw invalidSessionException();
		Path userDataFile = userDataFile();
		if( !Files.exists( userDataFile ) || !Files.exists( tokenFile() ) )
			throw invalidSessionException();

		Map<String, String> userData = new HashMap<>();
		Properties props = new Properties();
		try (FileInputStream in = new FileInputStream( userDataFile.toFile() ))
		{
			props.load( in );
			String nickname = props.getProperty( "nickname" );
			String email = props.getProperty( "email" );
			String token = loadToken();
			// Una sesion a medias (token ilegible, perfil sin email) es tan invalida
			// como no tener sesion: se trata igual para no arrastrar estados raros
			if( isBlank( nickname ) || isBlank( email ) || isBlank( token ) )
				throw invalidSessionException();
			userData.put( "nickname", nickname.trim() );
			userData.put( "email", email.trim() );
			userData.put( "token", token.trim() );
		}
		catch( Exception readFailure )
		{
			throw invalidSessionException();
		}

		return userData;
	}

	/** Reemplaza solo el token, dejando intacto el perfil ya guardado. */
	public static void saveToken( String token ) throws Exception
	{
		if( !ensureDataDirectory() )
			throw invalidSessionException();
		if( isBlank( token ) )
			throw invalidSessionException();
		Path tokenFile = tokenFile();
		Path tokenTemp = tokenFile.resolveSibling( tokenFile.getFileName() + ".tmp" );
		Files.write( tokenTemp, serializeToken( token.trim() ) );
		moveAtomically( tokenTemp, tokenFile );
	}

	/** Descifra el token verificando antes su HMAC. Devuelve null si no hay fichero. */
	public static String loadToken() throws Exception
	{
		Path tokenFile = tokenFile();
		if( !Files.exists( tokenFile ) )
			return null;

		SecretKey key = deriveKey();

		try (DataInputStream in = new DataInputStream( Files.newInputStream( tokenFile ) ))
		{
			byte[] encrypted = new byte[in.readInt()];
			in.readFully( encrypted );

			byte[] storeHmac = new byte[in.readInt()];
			in.readFully( storeHmac );

			// Verificar ANTES de descifrar, y con comparacion en tiempo constante:
			// descifrar datos manipulados es regalar un oraculo al atacante
			byte[] computedHmac = hmac( encrypted, key );
			if( !MessageDigest.isEqual( storeHmac, computedHmac ) )
			{
				throw new SecurityException( "Credenciales manipuladas" );
			}

			byte[] decrypted = decrypt( encrypted, key );
			return new String( decrypted, StandardCharsets.UTF_8 );
		}
	}

	/** Cierra la sesión avisando al usuario: es una acción que él ha pedido. */
	public static void clear()
	{
		try
		{
			deleteSessionFiles();
			JOptionPane.showMessageDialog(
					null,
					"Session closed successfully",
					"Git",
					JOptionPane.INFORMATION_MESSAGE );
		}
		catch( IOException noSession )
		{
			JOptionPane.showMessageDialog( null, "There is no session open.", "Error", JOptionPane.ERROR_MESSAGE );
		}
	}

	/** Cierre silencioso, para cuando GitHub ya ha rechazado el token (401). */
	public static void invalidateSession()
	{
		try
		{
			deleteSessionFiles();
		}
		catch( IOException ignored )
		{
			// Si los ficheros no se dejan borrar, la sesion se seguira rechazando
			// igualmente en el proximo uso: no hay nada util que decirle al usuario
		}
	}

	public static boolean sessionIsOpened()
	{
		boolean result;
		try
		{
			result = !getSavedUserData().isEmpty();
		}
		catch( Exception invalidSession )
		{
			result = false;
		}
		return result;
	}

	// ---- FASE 2 — Ficheros y rutas -----------------------------------------

	private static byte[] serializeToken( String token ) throws Exception
	{
		SecretKey key = deriveKey();
		byte[] encrypted = encrypt( token.getBytes( StandardCharsets.UTF_8 ), key );
		byte[] tokenHmac = hmac( encrypted, key );

		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream( bytes ))
		{
			out.writeInt( encrypted.length );
			out.write( encrypted );
			out.writeInt( tokenHmac.length );
			out.write( tokenHmac );
			out.flush();
			return bytes.toByteArray();
		}
	}

	private static void deleteSessionFiles() throws IOException
	{
		Files.deleteIfExists( tokenFile() );
		Files.deleteIfExists( userDataFile() );
	}

	private static Path dataDirectory()
	{
		return app.AppPaths.data();
	}

	private static Path tokenFile()
	{
		return dataDirectory().resolve( "credentials.dat" );
	}

	private static Path userDataFile()
	{
		return dataDirectory().resolve( "userData.properties" );
	}

	private static boolean ensureDataDirectory()
	{
		boolean result;
		try
		{
			Files.createDirectories( dataDirectory() );
			result = true;
		}
		catch( IOException directoryFailure )
		{
			app.Log.event( "GIT_AUTH", "No se pudo crear el directorio de datos " + dataDirectory(), directoryFailure );
			result = false;
		}
		return result;
	}

	/** Sin move atómico (algunos sistemas de ficheros de red) se degrada a un reemplazo normal. */
	private static void moveAtomically( Path source, Path destination ) throws IOException
	{
		try
		{
			Files.move( source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE );
		}
		catch( AtomicMoveNotSupportedException atomicUnsupported )
		{
			Files.move( source, destination, StandardCopyOption.REPLACE_EXISTING );
		}
	}

	private static byte[] readExistingFile( Path path ) throws IOException
	{
		return Files.exists( path ) ? Files.readAllBytes( path ) : null;
	}

	/** Deshace un guardado a medias; null significa "antes no existia, borralo". */
	private static void restoreFile( Path path, byte[] data )
	{
		try
		{
			if( data == null )
				Files.deleteIfExists( path );
			else
				Files.write( path, data );
		}
		catch( IOException restoreFailure )
		{
			// Ya estamos en la ruta de error: registrar y seguir, porque lo unico
			// peor que no restaurar es tragarse tambien el motivo
			app.Log.event( "GIT_AUTH", "No se pudo restaurar " + path + " tras un guardado fallido", restoreFailure );
		}
	}

	/** Los .tmp huérfanos no rompen nada, pero ensucian el directorio de datos. */
	private static void deleteQuietly( Path path )
	{
		try
		{
			Files.deleteIfExists( path );
		}
		catch( IOException ignored )
		{
		}
	}

	private static boolean isBlank( String value )
	{
		return value == null || value.trim().isEmpty();
	}

	private static Exception invalidSessionException()
	{
		return new Exception( "Session closed or invalid, sign in again." );
	}

	// ---- FASE 3 — Criptografia local del token -----------------------------

	/**
	 * Clave atada a la máquina: el token cifrado aquí no sirve en otro equipo.
	 * No es protección contra alguien con acceso a esta cuenta, sino contra la
	 * copia del fichero de credenciales a otro sitio.
	 */
	private static SecretKey deriveKey() throws Exception
	{
		String seed = System.getProperty( "user.name" ) +
				System.getProperty( "os.name" ) +
				System.getProperty( "user.home" );

		MessageDigest sha = MessageDigest.getInstance( "SHA-256" );
		byte[] hash = sha.digest( seed.getBytes( StandardCharsets.UTF_8 ) );

		return new SecretKeySpec( Arrays.copyOf( hash, 16 ), "AES" );
	}

	private static byte[] encrypt( byte[] data, SecretKey key ) throws Exception
	{
		Cipher cipher = Cipher.getInstance( "AES" );
		cipher.init( Cipher.ENCRYPT_MODE, key );

		return cipher.doFinal( data );
	}

	private static byte[] decrypt( byte[] data, SecretKey key ) throws Exception
	{
		Cipher cipher = Cipher.getInstance( "AES" );
		cipher.init( Cipher.DECRYPT_MODE, key );

		return cipher.doFinal( data );
	}

	private static byte[] hmac( byte[] data, SecretKey key ) throws Exception
	{
		Mac mac = Mac.getInstance( "HmacSHA256" );
		mac.init( key );

		return mac.doFinal( data );
	}
}
