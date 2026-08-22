package view.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

/**
 * La pagina del mapa tiene que decir la verdad sobre en que punto esta: un
 * boton de abrir activo sin mapa generado manda al usuario a una pestaña rota,
 * y un boton de construir activo mientras ya se construye lanza dos renders a
 * la vez sobre los mismos ficheros.
 */
class MapPageTest
{
	@Test
	void theMapPageIsOneOfTheServerPages() throws Exception
	{
		java.lang.reflect.Field field = MinecraftDashboard.class.getDeclaredField( "SERVER_PAGES" );
		field.setAccessible( true );
		@SuppressWarnings( "unchecked" )
		java.util.List<MinecraftDashboard.Page> pages = (java.util.List<MinecraftDashboard.Page>) field.get( null );

		assertTrue( pages.contains( MinecraftDashboard.Page.MAP ),
				"El mapa es de cada server, no algo global: tiene que estar en las paginas del server" );
	}

	@Test
	void withoutAMapYouCannotOpenAnythingButYouCanBuildIt() throws Exception
	{
		MinecraftDashboard dashboard = createDashboard();
		onEdt( () -> dashboard.showMapState( false, false, null ) );

		assertEquals( "NOT BUILT YET", labelText( dashboard, "mapStateValue" ) );
		assertFalse( button( dashboard, "openMapButton" ).isEnabled() );
		assertTrue( button( dashboard, "buildMapButton" ).isEnabled() );
		assertEquals( "BUILD MAP", button( dashboard, "buildMapButton" ).getText() );
		assertFalse( button( dashboard, "stopMapButton" ).isVisible() );
	}

	@Test
	void whileBuildingYouCannotLaunchASecondRender() throws Exception
	{
		MinecraftDashboard dashboard = createDashboard();
		onEdt( () -> dashboard.showMapState( false, true, "http://127.0.0.1:8123/" ) );

		assertEquals( "BUILDING", labelText( dashboard, "mapStateValue" ) );
		assertFalse( button( dashboard, "buildMapButton" ).isEnabled(),
				"Dos renders a la vez escribirian sobre los mismos ficheros" );
		assertTrue( button( dashboard, "stopMapButton" ).isVisible() );
		// Se puede mirar mientras se construye: el visor se sirve desde el principio
		assertTrue( button( dashboard, "openMapButton" ).isEnabled() );
		assertTrue( labelText( dashboard, "mapDetail" ).contains( "8123" ) );
	}

	@Test
	void aBuiltMapOffersOpeningItAndRebuilding() throws Exception
	{
		MinecraftDashboard dashboard = createDashboard();
		onEdt( () -> dashboard.showMapState( true, false, null ) );

		assertEquals( "READY", labelText( dashboard, "mapStateValue" ) );
		assertTrue( button( dashboard, "openMapButton" ).isEnabled() );
		assertEquals( "REBUILD MAP", button( dashboard, "buildMapButton" ).getText() );
	}

	@Test
	void buildingUsesTheQualityChosenOnScreen() throws Exception
	{
		AtomicReference<Boolean> requestedDetail = new AtomicReference<>();
		AtomicBoolean called = new AtomicBoolean();
		MinecraftDashboard dashboard = createDashboard( new MinecraftDashboard.Actions()
		{
			@Override
			public void buildWorldMap( boolean fullDetail )
			{
				requestedDetail.set( fullDetail );
				called.set( true );
			}
		} );

		JCheckBox quality = checkBox( dashboard, "fullDetailCheck" );
		AbstractButton build = button( dashboard, "buildMapButton" );
		onEdt( () ->
		{
			quality.setSelected( false );
			build.doClick();
		} );

		assertTrue( called.get() );
		assertEquals( Boolean.FALSE, requestedDetail.get(),
				"Se pediria calidad maxima sin que nadie la haya elegido: son gigabytes de diferencia" );
		assertFalse( dashboard.wantsFullDetailMap() );
	}

	// ---- utilidades ---------------------------------------------------------

	private static MinecraftDashboard createDashboard() throws Exception
	{
		return createDashboard( new MinecraftDashboard.Actions()
		{
		} );
	}

	private static MinecraftDashboard createDashboard( MinecraftDashboard.Actions actions ) throws Exception
	{
		AtomicReference<MinecraftDashboard> reference = new AtomicReference<>();
		SwingUtilities.invokeAndWait( () -> reference.set( new MinecraftDashboard( actions ) ) );
		return reference.get();
	}

	private static void onEdt( Runnable action ) throws Exception
	{
		SwingUtilities.invokeAndWait( action );
	}

	private static AbstractButton button( MinecraftDashboard dashboard, String fieldName ) throws Exception
	{
		return (AbstractButton) field( dashboard, fieldName );
	}

	private static JCheckBox checkBox( MinecraftDashboard dashboard, String fieldName ) throws Exception
	{
		return (JCheckBox) field( dashboard, fieldName );
	}

	private static String labelText( MinecraftDashboard dashboard, String fieldName ) throws Exception
	{
		return ((JLabel) field( dashboard, fieldName )).getText();
	}

	private static Object field( MinecraftDashboard dashboard, String fieldName ) throws Exception
	{
		java.lang.reflect.Field field = MinecraftDashboard.class.getDeclaredField( fieldName );
		field.setAccessible( true );
		return field.get( dashboard );
	}
}
