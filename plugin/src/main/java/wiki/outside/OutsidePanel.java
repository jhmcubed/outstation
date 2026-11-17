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
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

/**
 * The plugin's side tab (11/15/2026). It shows:
 * <ul>
 * <li>whose page it is and a way to open it; making a page for the character logged in, and proving it theirs;</li>
 * <li>"Update my page" and when it was last updated (11/16/2026, the snapshot);</li>
// hub:cut live
 * <li>the station key, copied for the outstation program;</li>
// hub:end live
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

		/** "Ground under my model": whether it's on, and switching it */
		boolean ground();

		void setGround(boolean on);
		// hub:end model

		void openPage();

		void claim();

		// hub:cut year
		void openYear();
		// hub:end year

		// hub:cut live
		void copyKey();
		// hub:end live

		void update();

		/** "Show what's sent": what "Update my page" would send now, in a window. */
		void showSent();

		/** "Copy my Dink address": the page's address for the Dink plugin's webhooks, to the clipboard. */
		void copyDink();

		void friendsOnly(boolean on);

		void copyFriendsLink();

		void takeDown();

		void tendChat();

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
	private final javax.swing.JCheckBox ground = new javax.swing.JCheckBox("Ground under my model");
	private final JPanel motions = new JPanel();
	// hub:end model
	private final JPanel start = new JPanel();
	private final JPanel page = new JPanel();
	private final JButton claim = new JButton("Make my Outside page");
	private final JLabel claimStatus = new JLabel(" ");
	private final JLabel proofLine = small(" ");
	// (the box at the top, 11/17/2026: what's true now, a line each)
	private final JLabel infoProof = small("…");
	private final JLabel infoAccess = small("…");
	private final JLabel infoUpdated = small("not this session");
	private final JLabel otherLine = small(" ");
	// hub:cut year
	private final JLabel yearStatus = small(" ");
	// hub:end year
	// hub:cut live
	private final JLabel keyStatus = small(" ");
	// hub:end live
	// (11/16/2026, the snapshot: "Update my page", when it was last updated from here, and what the last press did)
	private final JLabel snapStatus = small(" ");
	private long snapAt;
	private final javax.swing.Timer snapClock = new javax.swing.Timer(30_000, e -> showSnap());
	// (11/16/2026, Jace, after a press inside the minute went unnoticed and his page kept a necklace he'd taken off: a press
	// too soon waits, and the button counts the seconds down till it goes by itself)
	private JButton updateButton;
	private final JLabel dinkStatus = small(" ");
	private final JLabel notesLine = small(" ");
	private volatile long updateWaits;
	private final javax.swing.Timer updateClock = new javax.swing.Timer(250, e -> updateCount());
	// (11/16/2026, the permissions review: who sees the page, and taking it down, with the plugin alone)
	private final JLabel access = small(" ");
	private final JButton accessButton = new JButton("Make it friends only");
	private final JPanel friendsLink = new JPanel();
	private final JLabel accessStatus = small(" ");
	private boolean friends;
	// (11/16/2026: the page's chat tended on the page, with the plugin alone)
	private final JLabel tendStatus = small(" ");
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
		col.add(Box.createVerticalStrut(8));
		// (11/15/2026, the plugin first: a player from the Plugin Hub with no outstation and no invite makes their own page
		// here, under the name they're logged in as; docs/protocol.md "Claiming a name")
		start.setLayout(new BoxLayout(start, BoxLayout.Y_AXIS));
		start.add(left(small("<html><div style='width:155px'>No page yet. Log in, then make one: it's named after the character you're logged in as,"
			+ " and shows what you wear and carry, your stats, quests, diaries and slayer task, updated when you ask and"
			+ " hourly while you play. Each character has a page and a key of its own.</div></html>")));
		otherLine.setVisible(false);
		start.add(left(otherLine));
		start.add(Box.createVerticalStrut(4));
		claim.addActionListener(e -> hooks.claim());
		start.add(wide(claim));
		claimStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		start.add(left(claimStatus));
		// hub:cut live
		start.add(left(small("<html><div style='width:155px'>Have an outstation or a friend's invite? Paste its station key in the plugin's settings instead.</div></html>")));
		// hub:end live
		start.add(Box.createVerticalStrut(10));
		start.setVisible(false);
		col.add(start);
		// (what needs a page: only when this character has one; 11/15/2026, Jace: on his alt, sburb's motions still showed)
		// (11/17/2026, Jace: "organize our lime green ✼ sidetab menu ... use runeprofile as your example ... nice, clear,
		// and intuitive": a box of what's true now, the three buttons used most, then tabs: the page, your character, more)
		page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
		JPanel facts = new JPanel(new GridLayout(0, 1, 0, 2));
		facts.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		facts.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		facts.add(fact("Proof", infoProof));
		facts.add(fact("Who sees it", infoAccess));
		facts.add(fact("Last updated", infoUpdated));
		facts.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
		page.add(facts);
		proofLine.setForeground(new Color(0xff, 0x69, 0xb4));
		proofLine.setVisible(false);
		page.add(left(proofLine));
		page.add(Box.createVerticalStrut(8));
		page.add(wide(updateButton = button("Update my page", hooks::update)));
		snapStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		snapStatus.setVisible(false);
		page.add(left(snapStatus));
		page.add(Box.createVerticalStrut(4));
		page.add(wide(button("Open my page", hooks::openPage)));
		page.add(Box.createVerticalStrut(4));
		page.add(wide(button("Show what's sent", () -> hooks.showSent())));
		snapClock.start();
		page.add(Box.createVerticalStrut(10));

		JPanel display = new JPanel();
		display.setLayout(new BorderLayout());
		MaterialTabGroup tabs = new MaterialTabGroup(display);
		tabs.setLayout(new GridLayout(1, 0, 4, 0));
		tabs.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

		// the page: when it goes, and what goes with it
		JPanel pageTab = tab();
		pageTab.add(left(small("<html><div style='width:155px'>Your page updates when you press Update my page, and by itself"
			+ " every hour you're logged in; never from the Wilderness or a PvP world. What goes on it is in the plugin's"
			+ " settings, each part its own switch.</div></html>")));
		// hub:cut year
		// (11/15/2026: your year, kept by this plugin; docs/record.md)
		pageTab.add(Box.createVerticalStrut(12));
		pageTab.add(left(heading("Your year")));
		pageTab.add(left(small("<html><div style='width:155px'>Where you've been, what you did and what you found, kept on this PC"
			+ " and shown only to you. To see it, an encrypted copy goes to the relay: only your computers hold its key.</div></html>")));
		pageTab.add(Box.createVerticalStrut(6));
		pageTab.add(wide(button("Open my year", hooks::openYear)));
		yearStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		pageTab.add(left(yearStatus));
		// hub:end year
		MaterialTab first = new MaterialTab("Page", tabs, pageTab);
		tabs.addTab(first);

		// hub:cut model
		// your character: the model on the page, its motions
		JPanel charTab = tab();
		charTab.add(left(small("<html><div style='width:155px'>Press capture, then do an emote within 8 seconds: it's recorded from its start to its end, your pet's"
			+ " with it. Do nothing, and it records you and your pet standing. Your page shows it, and keeps it under its name.</div></html>")));
		charTab.add(Box.createVerticalStrut(6));
		JPanel named = new JPanel(new BorderLayout(6, 0));
		JLabel nl = small("Name");
		named.add(nl, BorderLayout.WEST);
		name.setToolTipText("a name for it on your page (blank: \"idle\" if you stand, \"motion\" if you do something)");
		named.add(name, BorderLayout.CENTER);
		named.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		charTab.add(named);
		charTab.add(Box.createVerticalStrut(6));
		capture.addActionListener(e -> hooks.capture(name.getText().trim()));
		charTab.add(wide(capture));
		// (11/17/2026, Jace: the ground's switch here, by the capture it goes with, not in the plugin's settings)
		ground.setSelected(hooks.ground());
		ground.setToolTipText("<html><div style='width:220px'>When you capture your character, the ground round you then"
			+ " (about five tiles each way: its paint and its shape, no objects or people) goes with it, and your page draws"
			+ " you standing on it.</div></html>");
		ground.addActionListener(e ->
		{
			boolean on = ground.isSelected();
			if (on && javax.swing.JOptionPane.showConfirmDialog(this, "<html><div style='width:260px'>Each time you capture your"
				+ " character, the ground round you then (about five tiles each way) goes to the Outside relay, a server not"
				+ " controlled or verified by the RuneLite developers, and your Outside page shows it to anyone who opens"
				+ " it.</div></html>", "Ground under my model", javax.swing.JOptionPane.OK_CANCEL_OPTION,
				javax.swing.JOptionPane.WARNING_MESSAGE) != javax.swing.JOptionPane.OK_OPTION)
			{
				ground.setSelected(false);
				return;
			}
			hooks.setGround(on);
		});
		JPanel groundRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 2));
		groundRow.add(ground);
		groundRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		charTab.add(groundRow);
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		charTab.add(left(status));
		charTab.add(Box.createVerticalStrut(12));
		JPanel head = new JPanel(new BorderLayout());
		head.add(heading("On your page"), BorderLayout.WEST);
		JButton again = new JButton("Refresh");
		again.setToolTipText("read the list again (nothing's sent)");
		again.addActionListener(e -> hooks.refresh());
		head.add(again, BorderLayout.EAST);
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		charTab.add(head);
		charTab.add(left(small("<html><div style='width:155px'>The one picked shows first to everyone who opens your page.</div></html>")));
		motions.setLayout(new GridLayout(0, 1, 0, 4));
		charTab.add(motions);
		charTab.add(Box.createVerticalStrut(12));
		charTab.add(wide(button("Take my model off my page", hooks::removeModel)));
		tabs.addTab(new MaterialTab("Model", tabs, charTab));
		// hub:end model

		// more: Dink, who sees it, the page's chat, and taking the page down (last, as it can't be undone)
		JPanel moreTab = tab();
		moreTab.add(left(heading("Field reports with Dink")));
		moreTab.add(left(small("<html><div style='width:155px'>Use the Dink plugin? Paste this on its own line in Dink's Primary"
			+ " Webhook URLs: what you switch on in Dink then shows on your page and the globe as it happens. Nothing from the"
			+ " Wilderness or PvP is ever shown. It holds your page's key: keep it to yourself.</div></html>")));
		moreTab.add(Box.createVerticalStrut(6));
		moreTab.add(wide(button("Copy my Dink address", () -> hooks.copyDink())));
		dinkStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		moreTab.add(left(dinkStatus));
		moreTab.add(Box.createVerticalStrut(12));
		moreTab.add(left(heading("Who sees it")));
		moreTab.add(left(access));
		moreTab.add(Box.createVerticalStrut(4));
		accessButton.addActionListener(e -> hooks.friendsOnly(!friends));
		moreTab.add(wide(accessButton));
		friendsLink.setLayout(new BoxLayout(friendsLink, BoxLayout.Y_AXIS));
		friendsLink.add(Box.createVerticalStrut(4));
		friendsLink.add(wide(button("Copy the friends' link", hooks::copyFriendsLink)));
		friendsLink.setVisible(false);
		moreTab.add(friendsLink);
		accessStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		moreTab.add(left(accessStatus));
		moreTab.add(Box.createVerticalStrut(12));
		// (11/16/2026, Jace: "There is currently no way for someone who doesn't have the outstation to remove comments
		// from people off of their profile": the page opens with a link only this browser has, good for 12 hours)
		// (11/17/2026, Jace: the page's chat, while nothing transmits, is its register: visitors sign it, you read it later)
		moreTab.add(left(heading("Your page's register")));
		moreTab.add(left(small("<html><div style='width:155px'>Visitors sign your page's register. Read it, take back a note or"
			+ " ban whoever wrote it, on your page itself: the link works for 12 hours, in the browser it opens in.</div></html>")));
		notesLine.setForeground(LIME);
		notesLine.setVisible(false);
		moreTab.add(left(notesLine));
		moreTab.add(Box.createVerticalStrut(6));
		moreTab.add(wide(button("Read and tend my register", hooks::tendChat)));
		tendStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		moreTab.add(left(tendStatus));
		moreTab.add(Box.createVerticalStrut(12));
		// hub:cut talk
		// (11/16/2026: the page chat's emotes, from here as from the outstation's settings; Emotes)
		moreTab.add(left(heading("Your emotes")));
		moreTab.add(left(small("<html><div style='width:155px'>Pictures your page's chat shows for :name:. Copy a picture"
			+ " (from a browser, a screenshot), name it, then paste it here: 256 KB at most, 30 in all. One of the same name is"
			+ " replaced.</div></html>")));
		moreTab.add(Box.createVerticalStrut(6));
		JPanel en = new JPanel(new BorderLayout(6, 0));
		en.add(small("Name"), BorderLayout.WEST);
		emoteName.setToolTipText("2 to 24 letters, numbers or _");
		en.add(emoteName, BorderLayout.CENTER);
		en.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		moreTab.add(en);
		moreTab.add(Box.createVerticalStrut(6));
		moreTab.add(wide(button("Paste a picture as an emote", () -> hooks.addEmote(emoteName.getText().trim()))));
		emoteStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		moreTab.add(left(emoteStatus));
		emotes.setLayout(new GridLayout(0, 1, 0, 4));
		moreTab.add(emotes);
		moreTab.add(Box.createVerticalStrut(12));
		// hub:end talk
		// hub:cut live
		// (11/15/2026, "Open your own" led by the plugin: an outstation transmits to the same page with the same key, and a
		// key made here is kept with the account, not in the settings, so this is how it's handed over)
		moreTab.add(left(heading("A transmission")));
		moreTab.add(left(small("<html><div style='width:155px'>The outstation program adds your game itself to your page: a few frames"
			+ " a second, its sound if you want, a chat. It takes this page's key: copy it, and paste it into its first screen."
			+ " Keep it to yourself: whoever has it can send to your page.</div></html>")));
		moreTab.add(Box.createVerticalStrut(6));
		moreTab.add(wide(button("Copy my station key", hooks::copyKey)));
		keyStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		moreTab.add(left(keyStatus));
		moreTab.add(Box.createVerticalStrut(12));
		// hub:end live
		moreTab.add(wide(button("Take my page down", () ->
		{
			int sure = javax.swing.JOptionPane.showConfirmDialog(this,
				"Take your Outside page down? Everything on it goes, and the name is free for anyone to claim."
					// hub:cut year
					+ " Your year's record on this PC stays."
					// hub:end year
					, "Take my page down", javax.swing.JOptionPane.OK_CANCEL_OPTION,
				javax.swing.JOptionPane.WARNING_MESSAGE);
			if (sure == javax.swing.JOptionPane.OK_OPTION)
			{
				hooks.takeDown();
			}
		})));
		tabs.addTab(new MaterialTab("More", tabs, moreTab));

		tabs.select(first);
		page.add(tabs);
		page.add(Box.createVerticalStrut(8));
		page.add(display);
		access(null);

		page.setVisible(false);
		col.add(page);

		add(col, BorderLayout.NORTH);
		// hub:cut model
		showMotions(null, "");
		// hub:end model
	}

	/** A line of the box at the top: what, on the left; its value, on the right. */
	private static JPanel fact(String what, JLabel value)
	{
		JPanel r = new JPanel(new BorderLayout());
		r.setOpaque(false);
		JLabel k = small(what);
		k.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		r.add(k, BorderLayout.WEST);
		value.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		r.add(value, BorderLayout.EAST);
		return r;
	}

	/** A tab's own column. */
	private static JPanel tab()
	{
		JPanel t = new JPanel();
		t.setLayout(new BoxLayout(t, BoxLayout.Y_AXIS));
		return t;
	}

	/** "Last updated" in the box. */
	private void showSnap()
	{
		infoUpdated.setText(snapAt <= 0 ? "not this session" : snapWords(snapAt).replaceFirst("^updated ", ""));
	}

	/** Whose page it is (their channel), or that there's no key yet. */
	public void who(String channel)
	{
		SwingUtilities.invokeLater(() ->
		{
			boolean changed = !who.getText().equals(channel == null ? "no page yet" : "Outside.wiki/" + channel);
			who.setText(channel == null ? "no page yet" : "Outside.wiki/" + channel);
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


	/** Who sees the page: true friends only, false anyone, null not read yet. */
	public void access(Boolean friendsOnly)
	{
		SwingUtilities.invokeLater(() ->
		{
			friends = Boolean.TRUE.equals(friendsOnly);
			access.setText("<html><div style='width:155px'>" + (friendsOnly == null ? "Anyone with the link, unless you've made it friends only."
				: friends ? "Friends only: only those you give the friends' link to. It's off the globe and the feed."
				: "Anyone with the link, and you show on Outside.wiki's globe and feed while you play.") + "</div></html>");
			accessButton.setText(friends ? "Open it to anyone" : "Make it friends only");
			infoAccess.setText(friendsOnly == null ? "…" : friends ? "friends only" : "anyone");
			friendsLink.setVisible(friends);
			revalidate();
		});
	}

	/** A line under "Who sees it". */
	public void accessStatus(String words)
	{
		SwingUtilities.invokeLater(() -> accessStatus.setText(words == null ? " " : "<html><div style='width:155px'>" + esc(words) + "</div></html>"));
	}

	/** How many visitors' notes the register has that haven't been read here (0: the line hidden). */
	public void notes(int n)
	{
		SwingUtilities.invokeLater(() ->
		{
			notesLine.setText(n == 1 ? "1 new note" : n + " new notes");
			notesLine.setVisible(n > 0);
			revalidate();
		});
	}

	/** A line under "Read and tend my register". */
	public void tendStatus(String words)
	{
		SwingUtilities.invokeLater(() -> tendStatus.setText(words == null ? " " : "<html><div style='width:155px'>" + esc(words) + "</div></html>"));
	}

	// hub:cut live
	/** A line under "Copy my station key". */
	public void keyStatus(String words)
	{
		SwingUtilities.invokeLater(() -> keyStatus.setText(words == null ? " " : "<html><div style='width:155px'>" + esc(words) + "</div></html>"));
	}
	// hub:end live

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
			otherLine.setText(theirs == null ? " " : "<html><div style='width:155px'>The station key in the plugin's settings is Outside.wiki/" + esc(theirs)
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
			// (11/17/2026: the steps as they have to go, "stop" among them, and "ready" from the relay's own answer to the update)
			String steps = " Gain some experience, stop, press Update my page, then log out once this says ready.";
			proofLine.setText("challenging".equals(proof)
				? "<html><div style='width:155px'>Someone claimed this name first and hasn't proven it." + steps
				+ " When the official hiscores show it, the page is yours, and their key stops working." + whyLine(why) + "</div></html>"
				: un ? "<html><div style='width:155px'>Your page is marked unproven." + steps
				+ " Within a few minutes the official hiscores show it, and your page is proven yours." + whyLine(why) + "</div></html>" : " ");
			proofLine.setVisible(un);
			infoProof.setText(proof == null ? "…" : un ? proof : "proven");
			infoProof.setForeground(un ? new Color(0xff, 0x69, 0xb4) : proof == null ? ColorScheme.LIGHT_GRAY_COLOR : LIME);
			revalidate();
		});
	}

	/** The plugin's off: the clock stops. */
	public void stop()
	{
		snapClock.stop();
		updateClock.stop();
	}

	/** When the page was last updated from here (ms; 0: not this session). */
	public void snapped(long at)
	{
		SwingUtilities.invokeLater(() ->
		{
			snapAt = at;
			showSnap();
			if (at > 0)
			{
				snapStatus.setText(" ");
				snapStatus.setVisible(false);
			}
		});
	}

	/** A line under "Copy my Dink address". */
	public void dinkStatus(String words)
	{
		SwingUtilities.invokeLater(() -> dinkStatus.setText(words == null ? " " : "<html><div style='width:155px'>" + esc(words) + "</div></html>"));
	}

	/** "Show what's sent": the snapshot as it would go now, its every field, in a window of its own (nothing's sent). */
	public void showSent(String to, String text, boolean sending)
	{
		SwingUtilities.invokeLater(() ->
		{
			javax.swing.JTextArea area = new javax.swing.JTextArea(text);
			area.setEditable(false);
			area.setCaretPosition(0);
			area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
			javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(area);
			scroll.setPreferredSize(new Dimension(460, 420));
			JPanel box = new JPanel(new BorderLayout(0, 8));
			box.add(new JLabel("<html><div style='width:420px'>What \"Update my page\" sends now, to " + esc(to) + ", and nothing else"
				+ " (your connection shows your IP address, as any does). " + (sending ? "" : "Sending is off in the plugin's settings: nothing goes till it's on. ")
				+ "Nothing's sent by opening this.</div></html>"), BorderLayout.NORTH);
			box.add(scroll, BorderLayout.CENTER);
			javax.swing.JOptionPane.showMessageDialog(this, box, "What's sent", javax.swing.JOptionPane.PLAIN_MESSAGE);
		});
	}

	/** A press too soon: the update goes by itself at `until` (ms), the button counting down till then; 0, none waiting. */
	public void updateWait(long until)
	{
		updateWaits = until;
		SwingUtilities.invokeLater(() ->
		{
			updateCount();
			if (until > 0)
			{
				updateClock.start();
			}
		});
	}

	/** The button's words: "Update my page", or while a press waits, how long till it goes. */
	private void updateCount()
	{
		long left = updateWaits - System.currentTimeMillis();
		if (updateButton == null)
		{
			return;
		}
		if (updateWaits <= 0 || left <= 0)
		{
			updateClock.stop();
			updateButton.setText("Update my page");
			return;
		}
		updateButton.setText("Updating in " + (left + 999) / 1000 + " s");
	}

	/** A line under "Update my page" (what the last press did). */
	public void snapStatus(String words)
	{
		SwingUtilities.invokeLater(() ->
		{
			snapStatus.setText(words == null ? " " : "<html><div style='width:155px'>" + esc(words) + "</div></html>");
			snapStatus.setVisible(words != null && !words.trim().isEmpty());   // (no gap under the button when there's nothing to say)
			revalidate();
		});
	}

	/** "updated 14 minutes ago", or nothing before the first this session. */
	static String snapWords(long at)
	{
		if (at <= 0)
		{
			return " ";
		}
		long m = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
		return m < 1 ? "updated just now" : m == 1 ? "updated a minute ago" : m < 60 ? "updated " + m + " minutes ago"
			: m < 120 ? "updated an hour ago" : "updated " + (m / 60) + " hours ago";
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
