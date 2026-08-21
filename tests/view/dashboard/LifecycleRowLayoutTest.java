package view.dashboard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

/**
 * Regresion de maquetacion: en la columna estrecha del Overview, el titulo de
 * un paso del ciclo de vida ("PULL WORLD") aparecia ENCIMA de su descripcion.
 */
class LifecycleRowLayoutTest
{
	@Test
	void lifecycleTitleAndDetailNeverOverlapInANarrowColumn() throws Exception
	{
		AtomicReference<JPanel> reference = new AtomicReference<>();
		SwingUtilities.invokeAndWait( () ->
		{
			MinecraftDashboard dashboard = new MinecraftDashboard( new MinecraftDashboard.Actions()
			{
			} );
			JPanel parent = new JPanel();
			parent.setLayout( new BoxLayout( parent, BoxLayout.Y_AXIS ) );
			try
			{
				java.lang.reflect.Method method = MinecraftDashboard.class.getDeclaredMethod( "addLifecycleStep",
						JPanel.class, MinecraftDashboard.Phase.class, String.class, String.class );
				method.setAccessible( true );
				method.invoke( dashboard, parent, MinecraftDashboard.Phase.SYNCING, "PULL WORLD",
						"Fetch the last confirmed GitHub state" );
			}
			catch( Exception reflectionFailure )
			{
				throw new IllegalStateException( reflectionFailure );
			}
			// Ancho tipico de una de las tres columnas del Overview
			parent.setSize( 250, 60 );
			layoutDeeply( parent );
			reference.set( parent );
		} );

		List<JLabel> labels = new ArrayList<>();
		collectLabels( reference.get(), labels );
		JLabel title = labels.stream().filter( label -> "PULL WORLD".equals( label.getText() ) ).findFirst().orElseThrow();
		JLabel detail = labels.stream().filter( label -> label.getText().startsWith( "Fetch the last" ) ).findFirst().orElseThrow();

		Rectangle titleBounds = SwingUtilities.convertRectangle( title.getParent(), title.getBounds(), reference.get() );
		Rectangle detailBounds = SwingUtilities.convertRectangle( detail.getParent(), detail.getBounds(), reference.get() );
		assertTrue( titleBounds.width > 0 && detailBounds.width > 0 );
		assertFalse( titleBounds.intersects( detailBounds ),
				"El titulo pisa a la descripcion: " + titleBounds + " vs " + detailBounds );
	}

	private static void layoutDeeply( Container container )
	{
		container.doLayout();
		for( Component child : container.getComponents() )
		{
			if( child instanceof Container nested )
				layoutDeeply( nested );
		}
	}

	private static void collectLabels( Container container, List<JLabel> found )
	{
		for( Component child : container.getComponents() )
		{
			if( child instanceof JLabel label )
				found.add( label );
			if( child instanceof Container nested )
				collectLabels( nested, found );
		}
	}
}
