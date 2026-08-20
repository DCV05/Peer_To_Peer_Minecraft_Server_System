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

public class TokenStore
{
	private static final String DATA_DIRECTORY_PROPERTY = "p2pmss.dataDirectory";

	public static boolean saveUserData( String nickname, String email, String token )
	{
		if( !ensureDataDirectory() )
			return false;
		if( isBlank( nickname ) || isBlank( email ) || isBlank( token ) )
			return false;

		Path userDataFile = userDataFile();
		Path tokenFile = tokenFile();
		Path userDataTemp = userDataFile.resolveSibling( userDataFile.getFileName() + ".tmp" );
		Path tokenTemp = tokenFile.resolveSibling( tokenFile.getFileName() + ".tmp" );
		byte[] previousUserData;
		byte[] previousToken;
		try
		{
			previousUserData = readExistingFile( userDataFile );
			previousToken = readExistingFile( tokenFile );
		}
		catch( IOException e )
		{
			return false;
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
			return true;
		}
		catch( Exception e )
		{
			restoreFile( userDataFile, previousUserData );
			restoreFile( tokenFile, previousToken );
			try
			{
				Files.deleteIfExists( userDataTemp );
			}
			catch( IOException ignored )
			{
			}
			try
			{
				Files.deleteIfExists( tokenTemp );
			}
			catch( IOException ignored )
			{
			}
			return false;
		}
	}

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
			if( isBlank( nickname ) || isBlank( email ) || isBlank( token ) )
				throw invalidSessionException();
			userData.put( "nickname", nickname.trim() );
			userData.put( "email", email.trim() );
			userData.put( "token", token.trim() );
		}
		catch( Exception e )
		{
			throw invalidSessionException();
		}

		return userData;
	}

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

			byte[] computedHmac = hmac( encrypted, key );
			if( !MessageDigest.isEqual( storeHmac, computedHmac ) )
			{
				throw new SecurityException( "Credenciales manipuladas" );
			}

			byte[] decrypted = decrypt( encrypted, key );
			return new String( decrypted, StandardCharsets.UTF_8 );
		}
	}

	//If we need to sign out.
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
		catch( IOException e )
		{
			JOptionPane.showMessageDialog( null, "There is no session open.", "Error", JOptionPane.ERROR_MESSAGE );
		}
	}

	public static void invalidateSession()
	{
		try
		{
			deleteSessionFiles();
		}
		catch( IOException ignored )
		{
		}
	}

	public static boolean sessionIsOpened()
	{
		try
		{
			return !getSavedUserData().isEmpty();
		}
		catch( Exception e )
		{
			return false;
		}
	}

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
		try
		{
			Files.createDirectories( dataDirectory() );
			return true;
		}
		catch( IOException e )
		{
			return false;
		}
	}

	private static void moveAtomically( Path source, Path destination ) throws IOException
	{
		try
		{
			Files.move( source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE );
		}
		catch( AtomicMoveNotSupportedException e )
		{
			Files.move( source, destination, StandardCopyOption.REPLACE_EXISTING );
		}
	}

	private static byte[] readExistingFile( Path path ) throws IOException
	{
		return Files.exists( path ) ? Files.readAllBytes( path ) : null;
	}

	private static void restoreFile( Path path, byte[] data )
	{
		try
		{
			if( data == null )
				Files.deleteIfExists( path );
			else
				Files.write( path, data );
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
