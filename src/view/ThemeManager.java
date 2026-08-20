package view;

import view.dashboard.DashboardTheme;
import javax.swing.*;
import java.awt.*;

/**
 * Aplica el tema del dashboard a la aplicacion entera. Se llama con ventanas ya
 * abiertas, asi que ademas de instalar el look and feel hay que repintar y
 * redimensionar lo que ya estaba en pantalla.
 */
public final class ThemeManager
{

	public static void setupSystemTheme()
	{
		DashboardTheme.install();
		for( Window window : Window.getWindows() )
		{
			// Sin pack() las ventanas ya abiertas conservan el tamano calculado con
			// el look and feel anterior y los componentes salen recortados
			SwingUtilities.updateComponentTreeUI( window );
			window.pack();
		}
	}
}
