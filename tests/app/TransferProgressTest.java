package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.eclipse.jgit.lib.ProgressMonitor;

class TransferProgressTest
{
	private final List<TransferProgress.Snapshot> published = new ArrayList<>();

	@AfterEach
	void clearListener()
	{
		TransferProgress.setListener( null );
	}

	@Test
	void reportsPercentageWhileTransferringAndHidesWhenDone() throws Exception
	{
		TransferProgress.setListener( published::add );
		ProgressMonitor monitor = TransferProgress.monitorFor( "Downloading world" );

		monitor.start( 1 );
		monitor.beginTask( "Receiving objects", 200 );
		monitor.update( 100 );
		// El limitador de frecuencia se salta a proposito: dos avisos seguidos
		// llegarian como uno solo, y aqui interesa comprobar el calculo
		Thread.sleep( 200 );
		monitor.update( 100 );

		assertFalse( published.isEmpty() );
		TransferProgress.Snapshot last = published.get( published.size() - 1 );
		assertEquals( "Downloading world", last.title() );
		assertEquals( "Receiving objects", last.detail() );
		assertEquals( 100, last.percent() );
		assertTrue( last.active() );

		TransferProgress.done();
		assertFalse( published.get( published.size() - 1 ).active() );
	}

	@Test
	void fallsBackToIndeterminateWhenTheTotalIsUnknown()
	{
		TransferProgress.setListener( published::add );
		ProgressMonitor monitor = TransferProgress.monitorFor( "Backing up world" );

		monitor.beginTask( "Writing objects", ProgressMonitor.UNKNOWN );

		assertEquals( -1, published.get( published.size() - 1 ).percent() );
	}

	@Test
	void withoutListenerNothingBreaks()
	{
		TransferProgress.setListener( null );
		ProgressMonitor monitor = TransferProgress.monitorFor( "Pulling world" );
		monitor.start( 1 );
		monitor.beginTask( "Receiving objects", 10 );
		monitor.update( 5 );
		monitor.endTask();
		TransferProgress.done();
	}
}
