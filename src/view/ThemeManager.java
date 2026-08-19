package view;

import view.dashboard.DashboardTheme;
import javax.swing.*;
import java.awt.*;

public class ThemeManager {

    public static void setupSystemTheme() {
		DashboardTheme.install();
    	for (Window w : Window.getWindows()) {
    	    SwingUtilities.updateComponentTreeUI(w);
    	    w.pack();
    	}
    }
}
