package view.dashboard;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPasswordField;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;

/** Applies dashboard tokens to legacy dialogs and reveals them only when complete. */
public final class DashboardDialogSupport {
    private DashboardDialogSupport() {}

    public static void show(JDialog dialog) {
        dialog.getContentPane().setBackground(DashboardTheme.APP_BACKGROUND);
        styleTree(dialog.getContentPane());
        dialog.validate();
        dialog.setLocationRelativeTo(dialog.getOwner());
        dialog.setVisible(true);
    }

    private static void styleTree(Component component) {
        if(component instanceof AbstractButton button) {
            String label = button.getText() == null ? "" : button.getText().toUpperCase();
            DashboardTheme.styleButton(button,
                    label.contains("CANCEL") || label.contains("CLOSE")
                            ? DashboardTheme.ButtonKind.SECONDARY
                            : DashboardTheme.ButtonKind.PRIMARY);
        } else if(component instanceof JTextComponent text && text.isEditable()) {
            DashboardTheme.styleInput(text);
            if(text instanceof JPasswordField) text.putClientProperty("JPasswordField.showRevealButton", true);
        } else if(component instanceof JComboBox<?> combo) {
            DashboardTheme.styleInput(combo);
        } else if(component instanceof JComponent swing) {
            swing.setForeground(DashboardTheme.TEXT);
        }

        if(component instanceof Container container) {
            for(Component child : container.getComponents()) styleTree(child);
        }
    }
}
