package view.dashboard;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPasswordField;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;

/**
 * Aplica los tokens visuales del dashboard a los dialogos heredados y solo los
 * muestra cuando el arbol entero esta reestilado: si se hiciera visible antes,
 * el usuario veria el look-and-feel antiguo parpadear durante un frame.
 */
public final class DashboardDialogSupport
{
	private DashboardDialogSupport()
	{
	}

	public static void show( JDialog dialog )
	{
		dialog.getContentPane().setBackground( DashboardTheme.APP_BACKGROUND );
		styleTree( dialog.getContentPane() );
		dialog.validate();
		dialog.setLocationRelativeTo( dialog.getOwner() );
		dialog.setVisible( true );
	}

	private static void styleTree( Component component )
	{
		if( component instanceof AbstractButton button )
		{
			// Los dialogos viejos no declaran cual es su accion principal: se deduce
			// del texto, y todo lo que no sea cancelar/cerrar se considera primario
			String label = button.getText() == null ? "" : button.getText().toUpperCase();
			boolean dismissButton = label.contains( "CANCEL" ) || label.contains( "CLOSE" );
			DashboardTheme.ButtonKind kind = dismissButton
					? DashboardTheme.ButtonKind.SECONDARY
					: DashboardTheme.ButtonKind.PRIMARY;
			DashboardTheme.styleButton( button, kind );
		}
		else if( component instanceof JTextComponent text && text.isEditable() )
		{
			DashboardTheme.styleInput( text );
			if( text instanceof JPasswordField )
				text.putClientProperty( "JPasswordField.showRevealButton", true );
		}
		else if( component instanceof JComboBox<?> combo )
		{
			DashboardTheme.styleInput( combo );
		}
		else if( component instanceof JComponent swing )
		{
			swing.setForeground( DashboardTheme.TEXT );
		}

		// El recorrido va despues del estilado y no en un else: un JComponent puede ser
		// a la vez contenedor (paneles con fondo propio y con hijos que reestilar)
		if( component instanceof Container container )
		{
			for( Component child : container.getComponents() )
				styleTree( child );
		}
	}
}
