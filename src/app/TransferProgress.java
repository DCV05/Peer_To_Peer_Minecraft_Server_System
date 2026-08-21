package app;

import java.util.function.Consumer;

import org.eclipse.jgit.lib.ProgressMonitor;

/**
 * Progreso de las operaciones largas con GitHub (clonar, traer el mundo,
 * respaldar). JGit sabe cuantos objetos lleva transferidos, pero esa
 * informacion se perdia: aqui se recoge y se publica para que la interfaz
 * enseñe una barra abajo a la derecha, como un IDE cuando descarga cosas.
 *
 * <p>Un solo listener global y un {@link ProgressMonitor} por operacion. Los
 * avisos van limitados en frecuencia: JGit llama a update() miles de veces y
 * repintar Swing a ese ritmo es peor que no tener barra.</p>
 */
public final class TransferProgress
{
	/** Foto del progreso; {@code percent} -1 = indeterminado (no se sabe el total). */
	public record Snapshot( String title, String detail, int percent, boolean active )
	{
	}

	private static final long MIN_PUBLISH_INTERVAL_MILLIS = 120;

	private static volatile Consumer<Snapshot> listener;

	private TransferProgress()
	{
	}

	/** La interfaz se apunta aqui; el listener corre en el hilo que hace el trabajo. */
	public static void setListener( Consumer<Snapshot> newListener )
	{
		listener = newListener;
	}

	public static void publish( String title, String detail, int percent )
	{
		Consumer<Snapshot> current = listener;
		if( current != null )
			current.accept( new Snapshot( title, detail, percent, true ) );
	}

	/** Fin de la operacion: la barra se esconde. */
	public static void done()
	{
		Consumer<Snapshot> current = listener;
		if( current != null )
			current.accept( new Snapshot( "", "", 0, false ) );
	}

	/** Monitor para pasar a clone/fetch/pull/push de JGit. */
	public static ProgressMonitor monitorFor( String title )
	{
		return new JGitMonitor( title );
	}

	private static final class JGitMonitor implements ProgressMonitor
	{
		private final String title;
		private String task = "";
		private int totalWork;
		private int completed;
		private long lastPublishMillis;

		private JGitMonitor( String title )
		{
			this.title = title;
		}

		@Override
		public void start( int totalTasks )
		{
			publish( title, "Starting…", -1 );
		}

		@Override
		public void beginTask( String taskTitle, int total )
		{
			task = taskTitle == null ? "" : taskTitle;
			totalWork = total;
			completed = 0;
			lastPublishMillis = 0;
			publishThrottled( true );
		}

		@Override
		public void update( int completedUnits )
		{
			completed += completedUnits;
			publishThrottled( false );
		}

		@Override
		public void endTask()
		{
			// Sin publicar el 100% de cada subtarea: el fin real lo marca quien
			// llama, cuando toda la operacion ha terminado
			task = "";
		}

		@Override
		public boolean isCancelled()
		{
			return false;
		}

		@Override
		public void showDuration( boolean enabled )
		{
			// La duracion la enseña la propia interfaz si hace falta
		}

		private void publishThrottled( boolean force )
		{
			long now = System.currentTimeMillis();
			if( !force && now - lastPublishMillis < MIN_PUBLISH_INTERVAL_MILLIS )
				return;
			lastPublishMillis = now;
			int percent = totalWork > 0 && totalWork != UNKNOWN
					? (int) Math.min( 100, Math.round( completed * 100.0 / totalWork ) )
					: -1;
			publish( title, task, percent );
		}
	}
}
