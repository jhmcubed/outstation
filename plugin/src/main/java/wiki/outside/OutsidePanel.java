package wiki.outside;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The plugin's side tab (11/15/2026, Jace: "can you add the ::outsidemodel and ::outsidemotion commands as buttons… you
 * could also combine the two commands into one button"; RuneProfile's side tab as the pattern): whose page it is and a
 * way to open it; one button that captures the character (the next emote done within 8 seconds, else them and their
 * pet standing), with a name for it; the motions on their page, which one it shows first, and taking one away; taking
 * their model off their page. Everything it does goes through the plugin (OutsidePlugin's panel hooks); this only draws.
 */
public class OutsidePanel extends PluginPanel
{
	/** what the panel asks the plugin to do */
	public interface Hooks
	{
		void capture(String name);

		void refresh();

		void first(String name);

		void forget(String name);

		void removeModel();

		void openPage();

		void claim();
	}

	/** a motion on their page, as the relay lists it */
	public static final class Motion
	{
		final String name;
		final int frames;

		public Motion(String name, int frames)
		{
			this.name = name;
			this.frames = frames;
		}
	}

	private static final Color LIME = new Color(0xb9, 0xff, 0x00);
	private final Hooks hooks;
	private final JLabel who = new JLabel(" ");
	private final JLabel status = new JLabel(" ");
	private final JTextField name = new JTextField();
	private final JButton capture = new JButton("Capture my character");
	private final JPanel motions = new JPanel();
	private final JPanel start = new JPanel();
	private final JButton claim = new JButton("Make my Outside page");
	private final JLabel claimStatus = new JLabel(" ");
	private final JLabel proofLine = small(" ");
	private final JLabel otherLine = small(" ");

	public OutsidePanel(Hooks hooks)
	{
		this.hooks = hooks;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("Outside");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(LIME);
		col.add(left(title));
		who.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		col.add(left(who));
		proofLine.setForeground(new Color(0xff, 0x69, 0xb4));
		proofLine.setVisible(false);
		col.add(left(proofLine));
		col.add(Box.createVerticalStrut(8));
		// (11/15/2026, the plugin first: a player from the Plugin Hub with no outstation and no invite makes their own page
		// here, under the name they're logged in as; docs/protocol.md "Claiming a name")
		start.setLayout(new BoxLayout(start, BoxLayout.Y_AXIS));
		start.add(left(small("<html>No page yet. Log in, then make one: it's named after the character you're logged in as,"
			+ " and shows what you wear and carry and your stats, live. Each character has a page and a key of its own.</html>")));
		otherLine.setVisible(false);
		start.add(left(otherLine));
		start.add(Box.createVerticalStrut(4));
		claim.addActionListener(e -> hooks.claim());
		start.add(wide(claim));
		claimStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		start.add(left(claimStatus));
		start.add(left(small("<html>Have an outstation or a friend's invite? Paste its station key in the plugin's settings instead.</html>")));
		start.add(Box.createVerticalStrut(10));
		start.setVisible(false);
		col.add(start);
		col.add(wide(button("Open my page", hooks::openPage)));
		col.add(Box.createVerticalStrut(14));

		col.add(left(heading("Your character")));
		col.add(left(small("<html>Press capture, then do an emote within 8 seconds: it's recorded from its start to its end, your pet's"
			+ " with it. Do nothing, and it records you and your pet standing. Your page shows it, and keeps it under its name.</html>")));
		col.add(Box.createVerticalStrut(6));
		JPanel named = new JPanel(new BorderLayout(6, 0));
		JLabel nl = small("Name");
		named.add(nl, BorderLayout.WEST);
		name.setToolTipText("a name for it on your page (blank: \"idle\" if you stand, \"motion\" if you do something)");
		named.add(name, BorderLayout.CENTER);
		named.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		col.add(named);
		col.add(Box.createVerticalStrut(6));
		capture.addActionListener(e -> hooks.capture(name.getText().trim()));
		col.add(wide(capture));
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		col.add(left(status));
		col.add(Box.createVerticalStrut(14));

		JPanel head = new JPanel(new BorderLayout());
		head.add(heading("On your page"), BorderLayout.WEST);
		JButton again = new JButton("Refresh");
		again.addActionListener(e -> hooks.refresh());
		head.add(again, BorderLayout.EAST);
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		col.add(head);
		col.add(left(small("<html>The one picked shows first to everyone who opens your page.</html>")));
		motions.setLayout(new GridLayout(0, 1, 0, 4));
		col.add(motions);
		col.add(Box.createVerticalStrut(14));
		col.add(wide(button("Take my model off my page", hooks::removeModel)));

		add(col, BorderLayout.NORTH);
		showMotions(null, "");
	}

	/** Whose page it is (their channel), or that there's no key yet. */
	public void who(String channel)
	{
		SwingUtilities.invokeLater(() ->
		{
			who.setText(channel == null ? "no page yet" : "outside.wiki/" + channel);
			start.setVisible(channel == null);
			revalidate();
		});
	}

	/** When the key in the settings is another character's (its page), and who's logged in: said under the button. */
	public void otherKey(String theirs, String me)
	{
		SwingUtilities.invokeLater(() ->
		{
			otherLine.setText(theirs == null ? " " : "<html>The station key in the plugin's settings is outside.wiki/" + esc(theirs)
				+ "'s, so nothing of " + esc(me) + "'s goes there. Make " + esc(me) + " a page of its own here.</html>");
			otherLine.setVisible(theirs != null);
			revalidate();
		});
	}

	/** Whether the page is proven theirs (11/15/2026): "unproven" says how it's proven; anything else, nothing. */
	public void proof(String proof)
	{
		SwingUtilities.invokeLater(() ->
		{
			boolean un = "unproven".equals(proof) || "challenging".equals(proof);
			proofLine.setText("challenging".equals(proof)
				? "<html>Someone claimed this name first and hasn't proven it. Gain any experience, then log out: when the official"
				+ " hiscores show it, the page is yours, and their key stops working.</html>"
				: un ? "<html>Your page is marked unproven. Gain any experience, then log out: within a few minutes"
				+ " the official hiscores show it, and your page is proven yours.</html>" : " ");
			proofLine.setVisible(un);
			revalidate();
		});
	}

	/** While a claim's asked, the button waits; then a line under it (null: none). */
	public void claiming(boolean on, String words)
	{
		SwingUtilities.invokeLater(() ->
		{
			claim.setEnabled(!on);
			claim.setText(on ? "Making your page…" : "Make my Outside page");
			claimStatus.setText(words == null ? " " : "<html>" + esc(words) + "</html>");
		});
	}

	/** A line under the capture button. */
	public void status(String words)
	{
		SwingUtilities.invokeLater(() -> status.setText("<html>" + esc(words) + "</html>"));
	}

	/** While a capture runs, the button waits. */
	public void capturing(boolean on)
	{
		SwingUtilities.invokeLater(() ->
		{
			capture.setEnabled(!on);
			capture.setText(on ? "Recording…" : "Capture my character");
		});
	}

	/** The motions on their page (null: not read), the one shown first ("" the still model). */
	public void showMotions(List<Motion> list, String first)
	{
		SwingUtilities.invokeLater(() ->
		{
			motions.removeAll();
			if (list == null)
			{
				motions.add(small("(not read yet)"));
			}
			else
			{
				ButtonGroup group = new ButtonGroup();
				motions.add(row("still", -1, first.isEmpty(), group));
				for (Motion m : list)
				{
					motions.add(row(m.name, m.frames, m.name.equals(first), group));
				}
			}
			motions.revalidate();
			motions.repaint();
		});
	}

	private JPanel row(String n, int frames, boolean isFirst, ButtonGroup group)
	{
		JPanel r = new JPanel(new BorderLayout(4, 0));
		r.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		r.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		JRadioButton pick = new JRadioButton(n + (frames > 0 ? "  (" + frames + " frames)" : ""), isFirst);
		pick.setToolTipText("show this first on your page");
		pick.setOpaque(false);
		group.add(pick);
		String key = "still".equals(n) && frames < 0 ? "" : n;
		pick.addActionListener(e -> hooks.first(key));
		r.add(pick, BorderLayout.CENTER);
		if (frames >= 0)
		{
			JButton x = new JButton("✕");
			x.setToolTipText("take \"" + n + "\" off your page");
			x.setMargin(new java.awt.Insets(0, 4, 0, 4));
			x.addActionListener(e -> hooks.forget(n));
			r.add(x, BorderLayout.EAST);
		}
		return r;
	}

	private static JLabel heading(String t)
	{
		JLabel l = new JLabel(t);
		l.setFont(FontManager.getRunescapeBoldFont());
		l.setForeground(Color.WHITE);
		return l;
	}

	private static JLabel small(String t)
	{
		JLabel l = new JLabel(t);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return l;
	}

	private static JButton button(String t, Runnable r)
	{
		JButton b = new JButton(t);
		b.addActionListener(e -> r.run());
		return b;
	}

	private static JPanel left(java.awt.Component c)
	{
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
		p.add(c);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height + 40));
		return p;
	}

	private static JPanel wide(JButton b)
	{
		JPanel p = new JPanel(new GridLayout(1, 1));
		p.add(b);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		return p;
	}

	private static String esc(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
