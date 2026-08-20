package view.dashboard;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;

/**
 * Visual tokens shared by the Minecraft dashboard. The palette and density are
 * deliberately aligned with dllama-dashboard: quiet surfaces, one-pixel
 * separators, tabular monospace text and colour reserved for operational state.
 */
public final class DashboardTheme
{
	public static final Color APP_BACKGROUND = new Color( 0x0A0A0B );
	public static final Color SIDEBAR_BACKGROUND = new Color( 0x0C0C0D );
	public static final Color PANEL_BACKGROUND = new Color( 0x0D0D0E );
	public static final Color HOVER_BACKGROUND = new Color( 0x141415 );
	public static final Color ACTIVE_BACKGROUND = new Color( 0x18181A );
	public static final Color HAIRLINE = new Color( 0x1C1C1E );
	public static final Color BORDER = new Color( 0x2A2A2C );
	public static final Color TEXT = new Color( 0xEDEDED );
	public static final Color TEXT_MUTED = new Color( 0x6A6A6E );
	public static final Color TEXT_DIM = new Color( 0x48484C );
	public static final Color GREEN = new Color( 0x4ADE80 );
	public static final Color RED = new Color( 0xEF4444 );
	public static final Color AMBER = new Color( 0xF5A524 );
	public static final Color CYAN = new Color( 0x67E8F9 );

	private static final String FONT_FAMILY = detectFontFamily();

	private DashboardTheme()
	{
	}

	public static void install()
	{
		FlatDarkLaf.setup();

		Font regular = font( Font.PLAIN, 13 );
		UIManager.put( "defaultFont", regular );
		UIManager.put( "Panel.background", APP_BACKGROUND );
		UIManager.put( "Label.foreground", TEXT );
		UIManager.put( "TextField.background", PANEL_BACKGROUND );
		UIManager.put( "TextField.foreground", TEXT );
		UIManager.put( "TextField.caretForeground", GREEN );
		UIManager.put( "TextArea.background", PANEL_BACKGROUND );
		UIManager.put( "TextArea.foreground", TEXT );
		UIManager.put( "TextPane.background", PANEL_BACKGROUND );
		UIManager.put( "TextPane.foreground", TEXT );
		UIManager.put( "ComboBox.background", PANEL_BACKGROUND );
		UIManager.put( "ComboBox.foreground", TEXT );
		UIManager.put( "ComboBox.selectionBackground", ACTIVE_BACKGROUND );
		UIManager.put( "ComboBox.selectionForeground", TEXT );
		UIManager.put( "ComboBox.buttonBackground", PANEL_BACKGROUND );
		UIManager.put( "PopupMenu.background", PANEL_BACKGROUND );
		UIManager.put( "List.background", PANEL_BACKGROUND );
		UIManager.put( "List.foreground", TEXT );
		UIManager.put( "List.selectionBackground", ACTIVE_BACKGROUND );
		UIManager.put( "List.selectionForeground", TEXT );
		UIManager.put( "Button.background", ACTIVE_BACKGROUND );
		UIManager.put( "Button.foreground", TEXT );
		UIManager.put( "Button.borderColor", BORDER );
		UIManager.put( "OptionPane.background", APP_BACKGROUND );
		UIManager.put( "OptionPane.messageForeground", TEXT );
		UIManager.put( "FileChooser.background", APP_BACKGROUND );
		UIManager.put( "FileChooser.listViewBackground", PANEL_BACKGROUND );
		UIManager.put( "Separator.foreground", HAIRLINE );
		UIManager.put( "ScrollPane.background", PANEL_BACKGROUND );
		UIManager.put( "ScrollBar.thumb", BORDER );
		UIManager.put( "ScrollBar.track", APP_BACKGROUND );
		UIManager.put( "Component.arc", 0 );
		UIManager.put( "Button.arc", 0 );
		UIManager.put( "TextComponent.arc", 0 );
		UIManager.put( "ProgressBar.arc", 0 );
		UIManager.put( "Component.focusWidth", 1 );
		UIManager.put( "Component.innerFocusWidth", 0 );
		UIManager.put( "Button.default.boldText", false );
		UIManager.put( "TitlePane.background", SIDEBAR_BACKGROUND );
		UIManager.put( "TitlePane.foreground", TEXT );
	}

	public static Font font( int style, int size )
	{
		return new Font( FONT_FAMILY, style, size );
	}

	public static JLabel label( String text, Color color, int size, int style )
	{
		JLabel label = new JLabel( text );
		label.setForeground( color );
		label.setFont( font( style, size ) );
		return label;
	}

	public static JLabel eyebrow( String text )
	{
		JLabel label = label( text == null ? "" : text.toUpperCase(), TEXT_MUTED, 11, Font.PLAIN );
		label.putClientProperty( "FlatLaf.style", "font: 11 $semibold.font" );
		return label;
	}

	public static void styleButton( AbstractButton button, ButtonKind kind )
	{
		button.setFont( font( Font.PLAIN, 12 ) );
		button.setFocusPainted( false );
		button.setOpaque( true );
		button.setContentAreaFilled( true );
		button.setBorderPainted( true );
		button.setMargin( new java.awt.Insets( 7, 11, 7, 11 ) );

		switch( kind )
		{
			case PRIMARY ->
			{
				button.setBackground( GREEN );
				button.setForeground( APP_BACKGROUND );
				button.setBorder( BorderFactory.createLineBorder( GREEN ) );
			}
			case DANGER ->
			{
				button.setBackground( PANEL_BACKGROUND );
				button.setForeground( RED );
				button.setBorder( BorderFactory.createLineBorder( RED ) );
			}
			case QUIET ->
			{
				button.setBackground( PANEL_BACKGROUND );
				button.setForeground( TEXT_MUTED );
				button.setBorder( BorderFactory.createLineBorder( HAIRLINE ) );
			}
			case SECONDARY ->
			{
				button.setBackground( ACTIVE_BACKGROUND );
				button.setForeground( TEXT );
				button.setBorder( BorderFactory.createLineBorder( BORDER ) );
			}
		}
	}

	public static void styleInput( JComponent component )
	{
		component.setFont( font( Font.PLAIN, 12 ) );
		component.setForeground( TEXT );
		component.setBackground( PANEL_BACKGROUND );
		component.setBorder( BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder( BORDER ),
				BorderFactory.createEmptyBorder( 7, 9, 7, 9 ) ) );
	}

	public static Border sectionBorder()
	{
		return BorderFactory.createLineBorder( HAIRLINE );
	}

	public static Border paddedSectionBorder( int top, int left, int bottom, int right )
	{
		return BorderFactory.createCompoundBorder(
				sectionBorder(),
				BorderFactory.createEmptyBorder( top, left, bottom, right ) );
	}

	private static String detectFontFamily()
	{
		String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
		if( Arrays.asList( available ).contains( "JetBrains Mono" ) )
			return "JetBrains Mono";
		return Font.MONOSPACED;
	}

	public enum ButtonKind
	{
		PRIMARY, SECONDARY, QUIET, DANGER
	}
}
