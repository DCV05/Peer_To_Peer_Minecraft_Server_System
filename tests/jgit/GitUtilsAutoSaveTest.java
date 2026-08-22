package jgit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import minecraftServerManagement.ForgeUtils;
import view.MainFrame;

class GitUtilsAutoSaveTest
{
	@TempDir
	Path temporaryDirectory;

	private StringWriter capturedCommands;
	private long originalTimeout;

	/** A live "server" that only needs to look alive and swallow streams. */
	private static final class FakeServerProcess extends Process
	{
		@Override
		public OutputStream getOutputStream()
		{
			return OutputStream.nullOutputStream();
		}
		@Override
		public InputStream getInputStream()
		{
			return new ByteArrayInputStream( new byte[0] );
		}
		@Override
		public InputStream getErrorStream()
		{
			return new ByteArrayInputStream( new byte[0] );
		}
		@Override
		public int waitFor()
		{
			return 0;
		}
		@Override
		public int exitValue()
		{
			throw new IllegalThreadStateException( "still running" );
		}
		@Override
		public void destroy()
		{
		}
		@Override
		public boolean isAlive()
		{
			return true;
		}
	}

	@BeforeEach
	void configureFakeServer() throws Exception
	{
		System.setProperty( "endershare.dataDirectory", temporaryDirectory.resolve( "data" ).toString() );
		assertTrue( TokenStore.saveUserData( "hoster", "hoster@example.test", "test-token" ) );
		capturedCommands = new StringWriter();
		originalTimeout = GitUtils.saveConfirmationTimeoutSeconds;
		MainFrame.serverProcess = new FakeServerProcess();
		MainFrame.serverWriter = new BufferedWriter( capturedCommands );
		MainFrame.serverOpenedDirectory = Files.createDirectories( temporaryDirectory.resolve( "server" ) ).toFile();
	}

	@AfterEach
	void clearFakeServer()
	{
		GitUtils.saveConfirmationTimeoutSeconds = originalTimeout;
		GitUtils.serverAutoSaveIsActive = false;
		MainFrame.serverProcess = null;
		MainFrame.serverWriter = null;
		MainFrame.serverOpenedDirectory = null;
		TokenStore.invalidateSession();
		System.clearProperty( "endershare.dataDirectory" );
	}

	@Test
	void liveSaveWaitsForTheConfirmationAndAlwaysReenablesSaving() throws Exception
	{
		GitUtils.saveConfirmationTimeoutSeconds = 10;
		CountDownLatch finished = new CountDownLatch( 1 );
		Thread saver = new Thread( () ->
		{
			GitUtils.performLiveSave();
			finished.countDown();
		} );
		saver.start();

		// El guardado debe estar bloqueado esperando la confirmacion de la consola
		Thread.sleep( 300 );
		assertTrue( capturedCommands.toString().contains( "/save-all flush" ) );
		assertFalse( finished.await( 200, TimeUnit.MILLISECONDS ) );

		ForgeUtils.noteConsoleLine( "[Server thread/INFO] [minecraft/MinecraftServer]: Saved the game" );
		assertTrue( finished.await( 10, TimeUnit.SECONDS ) );

		String commands = capturedCommands.toString();
		int saveOff = commands.indexOf( "/save-off" );
		int flush = commands.indexOf( "/save-all flush" );
		int saveOn = commands.indexOf( "/save-on" );
		assertTrue( saveOff >= 0 && flush > saveOff && saveOn > flush,
				"Expected /save-off -> /save-all flush -> /save-on in order, got: " + commands );
	}

	@Test
	void liveSaveReenablesSavingWhenTheConfirmationNeverArrives()
	{
		GitUtils.saveConfirmationTimeoutSeconds = 1;
		GitUtils.performLiveSave();

		String commands = capturedCommands.toString();
		assertTrue( commands.contains( "/save-on" ),
				"Saving must be re-enabled even when the flush confirmation times out: " + commands );
		// Sin confirmacion no hay commit: no se anuncia el backup a los jugadores
		assertFalse( commands.contains( "/say" ) );
	}

	@Test
	void flushConfirmationOnlyReactsToTheSavedTheGameLine() throws Exception
	{
		CountDownLatch result = new CountDownLatch( 1 );
		boolean[] flushed = new boolean[1];
		Thread waiter = new Thread( () ->
		{
			flushed[0] = ForgeUtils.flushWorldToDisk( MainFrame.serverProcess, MainFrame.serverWriter, 10 );
			result.countDown();
		} );
		waiter.start();

		Thread.sleep( 200 );
		ForgeUtils.noteConsoleLine( "[Server thread/INFO]: Player joined the game" );
		assertFalse( result.await( 200, TimeUnit.MILLISECONDS ) );

		ForgeUtils.noteConsoleLine( "[Server thread/INFO]: Saved the game" );
		assertTrue( result.await( 10, TimeUnit.SECONDS ) );
		assertTrue( flushed[0] );
	}
}
