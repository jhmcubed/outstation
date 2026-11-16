package wiki.outside;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
// hub:cut model,talk
import java.util.List;
// hub:end model,talk
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
// hub:cut model
import javax.swing.ButtonGroup;
// hub:end model
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
// hub:cut model
import javax.swing.JRadioButton;
// hub:end model
// hub:cut model,talk
import javax.swing.JTextField;
// hub:end model,talk
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The plugin's side tab (11/15/2026). It shows:
 * <ul>
 * <li>whose page it is and a way to open it; making a page for the character logged in, and proving it theirs;</li>
 * <li>the station key, copied for the outstation program;</li>
// hub:cut model
 * <li>capturing the character (the next emote done within 8 seconds, else them and their pet standing), the motions
 * on their page, which one it shows first, taking one away, and taking their model off their page;</li>
// hub:end model
// hub:cut year
 * <li>opening their year;</li>
// hub:end year
// hub:cut talk
 * <li>their emotes;</li>
// hub:end talk
 * </ul>
 * Everything it does goes through the plugin (OutsidePlugin's panel hooks); this only draws.
 */
public class OutsidePanel extends PluginPanel
{
	/** what the panel asks the plugin to do */
	public interface Hooks
	{
		// hub:cut model
		void capture(String name);
		// hub:end model

		void refresh();

		// hub:cut model
		void first(String name);

		void forget(String name);

		void removeModel();
		// hub:end model

		void openPage();

		void claim();

		// hub:cut year
		void openYear();
		// hub:end year

		void copyKey();

		// hub:cut talk
		void addEmote(String name);

		void removeEmote(String name);
		// hub:end talk
	}

	// hub:cut model
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
	// hub:end model

	private static final Color LIME = new Color(0xb9, 0xff, 0x00);
	private final Hooks hooks;
	private final JLabel who = new JLabel(" ");
	private final JLabel status = new JLabel(" ");
	// hub:cut model
	private final JTextField name = new JTextField();
	private final JButton capture = new JButton("Capture my character");
	private final JPanel motions = new JPanel();
	// hub:end model
	private final JPanel start = new JPanel();
	private final JPanel page = new JPanel();
	private final JButton claim = new JButton("Make my Outside page");
	private final JLabel claimStatus = new JLabel(" ");
	private final JLabel proofLine = small(" ");
	private final JLabel otherLine = small(" ");
	// hub:cut year
	private final JLabel yearStatus = small(" ");
	// hub:end year
	private final JLabel keyStatus = small(" ");
	// hub:cut talk
	private final JTextField emoteName = new JTextField();
	private final JPanel emotes = new JPanel();
	private final JLabel emoteStatus = small(" ");
	// hub:end talk

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
		start.add(left(small("<html><div style='width:155px'>No page yet. Log in, then make one: it's named after the character you're logged in as,"
			+ " and shows what you wear and carry and your stats, live. Each character has a page and a key of its own.</div></html>")));
		otherLine.setVisible(false);
		start.add(left(otherLine));
		start.add(Box.createVerticalStrut(4));
		claim.addActionListener(e -> hooks.claim());
		start.add(wide(claim));
		claimStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		start.add(left(claimStatus));
		start.add(left(small("<html><div style='width:155px'>Have an outstation or a friend's invite? Paste its station key in the plugin's settings instead.</div></html>")));
		start.add(Box.createVerticalStrut(10));
		start.setVisible(false);
		col.add(start);
		// (what needs a page: only when this character has one; 11/15/2026, Jace: on his alt, sburb's motions still showed)
		page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
		page.add(wide(button("Open my page", hooks::openPage)));
		page.add(Box.createVerticalStrut(14));

		// hub:cut model
		page.add(left(heading("Your character")));
		page.add(left(small("<html><div style='width:155px'>Press capture, then do an emote within 8 seconds: it's recorded from its start to its end, your pet's"
			+ " with it. Do nothing, and it records you and your pet standing. Your page shows it, and keeps it under its name.</div></html>")));
		page.add(Box.createVerticalStrut(6));
		JPanel named = new JPanel(new BorderLayout(6, 0));
		JLabel nl = small("Name");
		named.add(nl, BorderLayout.WEST);
		name.setToolTipText("a name for it on your page (blank: \"idle\" if you stand, \"motion\" if you do something)");
		named.add(name, BorderLayout.CENTER);
		named.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		page.add(named);
		page.add(Box.createVerticalStrut(6));
		capture.addActionListener(e -> hooks.capture(name.getText().trim()));
		page.add(wide(capture));
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		page.add(left(status));
		page.add(Box.createVerticalStrut(14));

		JPanel head = new JPanel(new BorderLayout());
		head.add(heading("On your page"), BorderLayout.WEST);
		JButton again = new JButton("Refresh");
		again.addActionListener(e -> hooks.refresh());
		head.add(again, BorderLayout.EAST);
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		page.add(head);
		page.add(left(small("<html><div style='width:155px'>The one picked shows first to everyone who opens your page.</div></html>")));
		motions.setLayout(new GridLayout(0, 1, 0, 4));
		page.add(motions);
		page.add(Box.createVerticalStrut(14));
		page.add(wide(button("Take my model off my page", hooks::removeModel)));
		page.add(Box.createVerticalStrut(14));
		// hub:end model
		// hub:only status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		// hub:only page.add(left(status));
		// hub:only page.add(wide(button("Refresh", hooks::refresh)));
		// hub:only page.add(Box.createVerticalStrut(14));
		// hub:cut year
		// (11/15/2026: your year, kept by this plugin; docs/record.md)
		page.add(left(heading("Your year")));
		page.add(left(small("<html><div style='width:155px'>Where you've been, what you did and what you found, kept on this PC"
			+ " and shown only to you. To see it, an encrypted copy goes to the relay: only your computers hold its key.</div></html>")));
		page.add(Box.createVerticalStrut(6));
		page.add(wide(button("Open my year", hooks::openYear)));
		yearStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		page.add(left(yearStatus));

		page.add(Box.createVerticalStrut(14));
		// hub:end year
		// hub:cut talk
		// (11/16/2026: the page chat's emotes, from here as from the outstation's settings; Emotes)
		page.add(left(heading("Your emotes")));
		page.add(left(small("<html><div style='width:155px'>Pictures your page's chat shows for :name:. Copy a picture"
			+ " (from a browser, a screenshot), name it, then paste it here: 256 KB at most, 30 in all. One of the same name is"
			+ " replaced.</div></html>")));
		page.add(Box.createVerticalStrut(6));
		JPanel en = new JPanel(new BorderLayout(6, 0));
		en.add(small("Name"), BorderLayout.WEST);
		emoteName.setToolTipText("2 to 24 letters, numbers or _");
		en.add(emoteName, BorderLayout.CENTER);
		en.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		page.add(en);
		page.add(Box.createVerticalStrut(6));
		page.add(wide(button("Paste a picture as an emote", () -> hooks.addEmote(emoteName.getText().trim()))));
		emoteStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		page.add(left(emoteStatus));
		emotes.setLayout(new GridLayout(0, 1, 0, 4));
		page.add(emotes);
		page.add(Box.createVerticalStrut(14));
		// hub:end talk
		// (11/15/2026, "Open your own" led by the plugin: an outstation transmits to the same page with the same key, and a
		// key made here is kept with the account, not in the settings, so this is how it's handed over)
		page.add(left(heading("A transmission")));
		page.add(left(small("<html><div style='width:155px'>The outstation program adds your game itself to your page: a few frames"
			+ " a second, its sound if you want, a chat. It takes this page's key: copy it, and paste it into its first screen."
			+ " Keep it to yourself: whoever has it can send to your page.</div></html>")));
		page.add(Box.createVerticalStrut(6));
		page.add(wide(button("Copy my station key", hooks::copyKey)));
		keyStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		page.add(left(keyStatus));

		page.setVisible(false);
		col.add(page);

		add(col, BorderLayout.NORTH);
		// hub:cut model
		showMotions(null, "");
		// hub:end model
	}

	/** Whose page it is (their channel), or that there's no key yet. */
	public void who(String channel)
	{
		SwingUtilities.invokeLater(() ->
		{
			boolean changed = !who.getText().equals(channel == null ? "no page yet" : "outside.wiki/" + channel);
			who.setText(channel == null ? "no page yet" : "outside.wiki/" + channel);
			start.setVisible(channel == null);
			page.setVisible(channel != null);
			if (changed)
			{
				// (another page, or none: what the last one said goes)
				status.setText(" ");
				claimStatus.setText(" ");
				// hub:cut model
				motions.removeAll();
				motions.add(small("(not read yet)"));
				// hub:end model
			}
			revalidate();
		});
	}

	// hub:cut talk
	/** The page's emotes by name (null: not read), each with its ✕. */
	public void showEmotes(List<String> names)
	{
		SwingUtilities.invokeLater(() ->
		{
			emotes.removeAll();
			if (names == null)
			{
				emotes.add(small("(not read yet)"));
			}
			else if (names.isEmpty())
			{
				emotes.add(small("none yet"));
			}
			else
			{
				for (String n : names)
				{
					JPanel r = new JPanel(new BorderLayout(4, 0));
					r.setBackground(ColorScheme.DARKER_GRAY_COLOR);
					r.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
					r.add(new JLabel(":" + n + ":"), BorderLayout.CENTER);
					JButton x = new JButton("✕");
					x.setToolTipText("take :" + n + ": off your page");
					x.setMargin(new java.awt.Insets(0, 4, 0, 4));
					x.addActionListener(e -> hooks.removeEmote(n));
					r.add(x, BorderLayout.EAST);
					emotes.add(r);
				}
			}
			emotes.revalidate();
			emotes.repaint();
		});
	}
	// hub:end talk


	// hub:cut talk
	/** A line under the paste button; a done one clears the name. */
	public void emoteStatus(String words, boolean done)
	{
		SwingUtilities.invokeLater(() ->
		{
			emoteStatus.setText(words == null ? " " : "<html><div style='width:155px'>" + esc(words) + "</div></html>");
			if (done)
			{
				emoteName.setText("");
			}
		});
	}
	// hub:end talk


	/** A line under "Copy my station key". */
	public void keyStatus(String words)
	{
		SwingUtilities.invokeLater(() -> keyStatus.setText(words == null ? " " : "<html><div style='width:155px'>" + esc(words) + "</div></html>"));
	}

	// hub:cut year
	/** A line under "Open my year". */
	public void yearStatus(String words)
	{
		SwingUtilities.invokeLater(() -> yearStatus.setText(words == null ? " " : "<html><div style='width:155px'>" + esc(words) + "</div></html>"));
	}
	// hub:end year


	/** When the key in the settings is another character's (its page), and who's logged in: said under the button. */
	public void otherKey(String theirs, String me)
	{
		SwingUtilities.invokeLater(() ->
		{
			otherLine.setText(theirs == null ? " " : "<html><div style='width:155px'>The station key in the plugin's settings is outside.wiki/" + esc(theirs)
				+ "'s, so nothing of " + esc(me) + "'s goes there. Make " + esc(me) + " a page of its own here.</div></html>");
			otherLine.setVisible(theirs != null);
			revalidate();
		});
	}

	/** Whether the page is proven theirs (11/15/2026): "unproven" says how it's proven; anything else, nothing. */
	public void proof(String proof, String why)
	{
		SwingUtilities.invokeLater(() ->
		{
			boolean un = "unproven".equals(proof) || "challenging".equals(proof);
			proofLine.setText("challenging".equals(proof)
				? "<html><div style='width:155px'>Someone claimed this name first and hasn't proven it. Gain any experience, wait a minute,"
				+ " then log out: when the official hiscores show it, the page is yours, and their key stops working." + whyLine(why) + "</div></html>"
				: un ? "<html><div style='width:155px'>Your page is marked unproven. Gain any experience, wait a minute, then log out:"
				+ " within a few minutes the official hiscores show it, and your page is proven yours." + whyLine(why) + "</div></html>" : " ");
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
			claimStatus.setText(words == null ? " " : "<html><div style='width:155px'>" + esc(words) + "</div></html>");
		});
	}

	/** A line under the capture button. */
	public void status(String words)
	{
		SwingUtilities.invokeLater(() -> status.setText("<html><div style='width:155px'>" + esc(words) + "</div></html>"));
	}

	// hub:cut model
	/** While a capture runs, the button waits. */
	public void capturing(boolean on)
	{
		SwingUtilities.invokeLater(() ->
		{
			capture.setEnabled(!on);
			capture.setText(on ? "Recording…" : "Capture my character");
		});
	}
	// hub:end model


	// hub:cut model
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
	// hub:end model


	// hub:cut model
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
	// hub:end model


	/** What the proof last did (the relay's words), under the line. */
	private static String whyLine(String why)
	{
		return why == null || why.isEmpty() ? "" : "<br><br>Last: " + esc(why);
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
