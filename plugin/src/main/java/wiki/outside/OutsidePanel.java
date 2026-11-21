package wiki.outside;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
// hub:cut model,talk,goal,encounters
import java.util.List;
// hub:end model,talk,goal,encounters
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
// hub:cut model,goal
import javax.swing.ButtonGroup;
// hub:end model,goal
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
// hub:cut model,goal
import javax.swing.JRadioButton;
// hub:end model,goal
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
// hub:cut encounters
 * <li>their encounters (11/21/2026): recording on or off, one by hand, and the recent ones, each put on their page or
 * not, shown to anyone or kept to themselves, or deleted;</li>
// hub:end encounters
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

		/** "Capture a still": the model as it is now, as the page's still (update 4). */
		void captureStill();
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

		/** "Copy my station key": the page's key, for the outstation or Outside on another computer. */
		void copyKey();

		void update();

		// hub:cut goal
		/** "Add a goal item": the game's item search, the item picked added to the goals on the page (update 3; a list
		 *  since 11/19/2026). */
		void addGoal();

		/** The goal picked beside the others: the one shown as its 3D model on the page (11/19/2026). */
		void showGoal(int id);

		/** The ✕ beside a goal: taken off the page (update 4). */
		void forgetGoal(int id);

		/** "No 3D model": every goal listed on the page with its picture, none turning (11/19/2026). */
		void noGoalModel();
		// hub:end goal

		/** "Show what's sent": what "Update my page" would send now, in a window. */
		void showSent();

		/** "Talk in the aether as me": the page opened with its key, for the aether under the page's own name. */
		void aetherAsMe();

		/** "Copy my address for Dink": the page's address for the Dink plugin's webhooks, to the clipboard. */
		void copyDink();

		void friendsOnly(boolean on);

		void copyFriendsLink();

		void takeDown();

		void tendChat();

		// hub:cut talk
		void addEmote(String name);

		void removeEmote(String name);
		// hub:end talk

		// hub:cut encounters
		/** "Record my encounters" (the setting): whether it's on, and switching it. */
		boolean recordingOn();

		void setRecordingOn(boolean on);

		/** "Record now", or "Stop" while one's running. */
		void recordToggle();

		/** The Encounters tab opened, or its Refresh: the list read again, and the page's own (what's on it, and whether
		 *  the page takes encounters at all): the one request this tab makes by itself, on the press that opens it. */
		void encountersShown();

		/** "Put on my page": the recording stripped, what was taken out said, and sent once that's agreed to. */
		void putFight(String file, boolean shown);

		/** "Shown to anyone" ticked or not: on the page at once if it's there, else the choice for when it goes. */
		void fightShown(String file, boolean shown);

		/** "Open": its page on Outside.wiki, outside.wiki/<name>/fight/<id>. */
		void openFight(String file);

		/** The ✕: the recording's file deleted from this computer (one on the page stays there). */
		void deleteEncounter(String file);

		/** "Copy the folder's place": where the recordings are, for a file manager. */
		void copyEncountersFolder();
		// hub:end encounters
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
	// (in the Hub's version too since 11/19/2026: a player's key for their other computers)
	private final JLabel keyStatus = small(" ");
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
	// hub:cut encounters
	private final javax.swing.JCheckBox recordOn = new javax.swing.JCheckBox("Record my encounters");
	private final JButton recordNow = new JButton("Record now");
	private final JLabel recordLine = small(" ");
	private final JLabel fightsOff = small(" ");
	private final JLabel fightStatus = small(" ");
	private final JPanel fightList = new JPanel();
	/** whether the page takes encounters (null: not read yet), and whether a new one starts shown to anyone */
	private Boolean fightsOn;
	private boolean fightsShownFirst;
	/** the rows as last listed, the character logged in, and the rows whose put is under way */
	private List<EncounterShelf.Row> fightRows;
	private int fightMore;
	private String fightMe;
	private final java.util.Set<String> putting = new java.util.HashSet<>();
	// hub:end encounters

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
		start.add(left(small("<html><div style='width:155px'>Have a page already, on another computer, an outstation or a friend's"
			+ " invite? Paste its station key in the plugin's settings (Advanced, Station key) instead.</div></html>")));
		// hub:end live
		// hub:only start.add(left(small("<html><div style='width:155px'>Have a page already, made on another computer? There,"
		// hub:only 	+ " the Page tab's Copy my station key; then paste it here in the plugin's settings (Advanced, Station key).</div></html>")));
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
		// hub:cut goal
		// (the goal item, 11/17/2026; in the Page tab since 11/18/2026, update 4, Jace: set once and left, not one of
		// the buttons pressed every session, which stay above. A list since 11/19/2026, Cranky: "maybe the button would
		// be 'add a goal item' and it would just add to the list, and you'd just have to x any you don't want anymore";
		// Jace: one of them shown as its 3D model, the rest listed under it)
		pageTab.add(Box.createVerticalStrut(12));
		pageTab.add(left(heading("Your goals")));
		pageTab.add(left(small("<html><div style='width:155px'>Items you're after: your page shows your kill count where each"
			+ " drops and how dry you are for it, and the one picked here as its 3D model (or none).</div></html>")));
		pageTab.add(Box.createVerticalStrut(6));
		goalAdd.addActionListener(ev -> hooks.addGoal());
		pageTab.add(wide(goalAdd));
		goalList.setLayout(new BoxLayout(goalList, BoxLayout.Y_AXIS));
		goalList.setOpaque(false);
		goalList.setVisible(false);
		pageTab.add(Box.createVerticalStrut(4));
		pageTab.add(goalList);
		// hub:end goal
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
		pageTab.add(Box.createVerticalStrut(12));
		// (the More tab's parts, folded into the Page tab 11/18/2026, update 4, Jace: all of them are about the page;
		// in his order: who sees it, Dink, the register, then taking it down last, on its own)
		pageTab.add(left(heading("Who sees it")));
		pageTab.add(left(access));
		pageTab.add(Box.createVerticalStrut(4));
		accessButton.addActionListener(e -> hooks.friendsOnly(!friends));
		pageTab.add(wide(accessButton));
		friendsLink.setLayout(new BoxLayout(friendsLink, BoxLayout.Y_AXIS));
		friendsLink.add(Box.createVerticalStrut(4));
		friendsLink.add(wide(button("Copy the friends' link", hooks::copyFriendsLink)));
		friendsLink.setVisible(false);
		pageTab.add(friendsLink);
		accessStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		pageTab.add(left(accessStatus));
		pageTab.add(Box.createVerticalStrut(12));
		pageTab.add(left(heading("Field reports with Dink")));
		pageTab.add(left(small("<html><div style='width:155px'>Use the Dink plugin? Paste this on its own line in Dink's Primary"
			+ " Webhook URLs: what you switch on in Dink then shows on your page and the globe as it happens. Nothing from the"
			+ " Wilderness or PvP is ever shown. It holds your page's key: keep it to yourself.</div></html>")));
		pageTab.add(Box.createVerticalStrut(6));
		pageTab.add(wide(button("Copy my address for Dink", () -> hooks.copyDink())));
		dinkStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		pageTab.add(left(dinkStatus));
		pageTab.add(Box.createVerticalStrut(12));
		// (11/16/2026, Jace: "There is currently no way for someone who doesn't have the outstation to remove comments
		// from people off of their profile": the page opens with a link only this browser has, good for 12 hours)
		// (11/17/2026, Jace: the page's chat, while nothing transmits, is its register: visitors sign it, you read it later)
		pageTab.add(left(heading("Your page's register")));
		pageTab.add(left(small("<html><div style='width:155px'>Visitors sign your page's register. Read it, take back a note or"
			+ " ban whoever wrote it, on your page itself: the link works for 12 hours, in the browser it opens in.</div></html>")));
		notesLine.setForeground(LIME);
		notesLine.setVisible(false);
		pageTab.add(left(notesLine));
		pageTab.add(Box.createVerticalStrut(6));
		pageTab.add(wide(button("Read and tend my register", hooks::tendChat)));
		tendStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		pageTab.add(left(tendStatus));
		pageTab.add(Box.createVerticalStrut(12));
		// (11/18/2026, Jace: "a button to the plugin that opens up their page and embeds the key into it so they dont have
		// to manually paste it": the aether takes an outstation's own name with its key, asked for on the page otherwise)
		pageTab.add(left(heading("The aether")));
		pageTab.add(left(small("<html><div style='width:155px'>Outside.wiki's chat across every page. Your name is"
			+ " yours there: this opens your page in your browser with your key, kept in that browser, so the aether"
			+ " lets you in under your own name.</div></html>")));
		pageTab.add(Box.createVerticalStrut(6));
		pageTab.add(wide(button("Talk in the aether as me", hooks::aetherAsMe)));
		pageTab.add(Box.createVerticalStrut(12));
		// hub:cut talk
		// (11/16/2026: the page chat's emotes, from here as from the outstation's settings; Emotes)
		pageTab.add(left(heading("Your emotes")));
		pageTab.add(left(small("<html><div style='width:155px'>Pictures your page's chat shows for :name:. Copy a picture"
			+ " (from a browser, a screenshot), name it, then paste it here: 256 KB at most, 30 in all. One of the same name is"
			+ " replaced.</div></html>")));
		pageTab.add(Box.createVerticalStrut(6));
		JPanel en = new JPanel(new BorderLayout(6, 0));
		en.add(small("Name"), BorderLayout.WEST);
		emoteName.setToolTipText("2 to 24 letters, numbers or _");
		en.add(emoteName, BorderLayout.CENTER);
		en.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		pageTab.add(en);
		pageTab.add(Box.createVerticalStrut(6));
		pageTab.add(wide(button("Paste a picture as an emote", () -> hooks.addEmote(emoteName.getText().trim()))));
		emoteStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		pageTab.add(left(emoteStatus));
		emotes.setLayout(new GridLayout(0, 1, 0, 4));
		pageTab.add(emotes);
		pageTab.add(Box.createVerticalStrut(12));
		// hub:end talk
		// hub:cut live
		// (11/15/2026, "Open your own" led by the plugin: an outstation transmits to the same page with the same key, and a
		// key made here is kept with the account, not in the settings, so this is how it's handed over)
		pageTab.add(left(heading("A transmission")));
		pageTab.add(left(small("<html><div style='width:155px'>The outstation program adds your game itself to your page: a few frames"
			+ " a second, its sound if you want, a chat. It takes this page's key: copy it, and paste it into its first screen."
			+ " Keep it to yourself: whoever has it can send to your page.</div></html>")));
		// hub:end live
		// (11/19/2026, Jace: a player with only the plugin needs their key too, for Outside on another computer: his Mac had
		// none, and his PC's went missing; the Hub's version had no way to show it)
		// hub:only pageTab.add(left(heading("Your page's key")));
		// hub:only pageTab.add(left(small("<html><div style='width:155px'>For Outside on another computer: copy it here, then paste"
		// hub:only 	+ " it there in the plugin's settings, Advanced, Station key. Keep it to yourself: whoever has it can change your"
		// hub:only 	+ " page.</div></html>")));
		pageTab.add(Box.createVerticalStrut(6));
		pageTab.add(wide(button("Copy my station key", hooks::copyKey)));
		keyStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		pageTab.add(left(keyStatus));
		pageTab.add(Box.createVerticalStrut(12));
		pageTab.add(wide(button("Take my page down", () ->
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
		MaterialTab first = new MaterialTab("Page", tabs, pageTab);
		tabs.addTab(first);

		// hub:cut model
		// your character: the model on the page, its motions
		JPanel charTab = tab();
		charTab.add(left(small("<html><div style='width:155px'>Press capture, then do an emote within 8 seconds: it's recorded from its start to its end, your pet's"
			+ " with it. Do nothing, and it records you and your pet standing. Your page shows it, and keeps it under its name."
			+ " Capture a still keeps you as you are the moment you press it, as your page's still; until you do, your still is"
			+ " your last capture's first moment.</div></html>")));
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
		// (11/18/2026, update 4, Cranky: "a 'capture still' capturing the frame i click it like runeprofile does": the still
		// was a capture's first moment, each capture's, so it followed whatever was recorded last)
		charTab.add(Box.createVerticalStrut(4));
		JButton stillButton = button("Capture a still", hooks::captureStill);
		stillButton.setToolTipText("<html><div style='width:220px'>You, as you are the moment you press it (mid-emote too),"
			+ " as your page's still. A capture doesn't change it after that.</div></html>");
		charTab.add(wide(stillButton));
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

		// hub:cut encounters
		// (11/21/2026, ghillie's claude/outstation.md, "Encounters on Outside: the night's design": a third tab, beside
		// Page and Model: recording at the top, then the recent ones, newest first)
		JPanel fightTab = tab();
		fightTab.add(left(small("<html><div style='width:155px'>Your fights at bosses and raids, recorded to this computer tick"
			+ " by tick, from the start to the kill, your death or your leaving. Nothing leaves this computer until you put one"
			+ " on your page, other players' names taken out first.</div></html>")));
		fightTab.add(Box.createVerticalStrut(6));
		recordOn.setSelected(hooks.recordingOn());
		recordOn.setToolTipText("<html><div style='width:220px'>On, a recording starts by itself at a boss or a raid, never in"
			+ " the Wilderness or PvP. Also in the plugin's settings, Encounters.</div></html>");
		recordOn.addActionListener(e -> hooks.setRecordingOn(recordOn.isSelected()));
		JPanel onRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
		onRow.add(recordOn);
		onRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		fightTab.add(onRow);
		recordNow.addActionListener(e -> hooks.recordToggle());
		fightTab.add(wide(recordNow));
		recordLine.setForeground(LIME);
		recordLine.setVisible(false);
		fightTab.add(left(recordLine));
		fightTab.add(Box.createVerticalStrut(12));
		JPanel fightHead = new JPanel(new BorderLayout());
		fightHead.add(heading("Recent encounters"), BorderLayout.WEST);
		JButton fightAgain = new JButton("Refresh");
		fightAgain.setToolTipText("read the list again, and what's on your page");
		fightAgain.addActionListener(e -> hooks.encountersShown());
		fightHead.add(fightAgain, BorderLayout.EAST);
		fightHead.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		fightTab.add(fightHead);
		fightsOff.setForeground(new Color(0xff, 0x69, 0xb4));
		fightsOff.setVisible(false);
		fightTab.add(left(fightsOff));
		fightStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		fightStatus.setVisible(false);
		fightTab.add(left(fightStatus));
		fightList.setLayout(new BoxLayout(fightList, BoxLayout.Y_AXIS));
		fightList.add(left(small("(not read yet)")));
		fightTab.add(fightList);
		fightTab.add(Box.createVerticalStrut(8));
		fightTab.add(wide(button("Copy the folder's place", hooks::copyEncountersFolder)));
		MaterialTab fightsTab = new MaterialTab("Encounters", tabs, fightTab);
		fightsTab.setOnSelectEvent(() ->
		{
			hooks.encountersShown();
			return true;
		});
		tabs.addTab(fightsTab);
		// hub:end encounters

		// more: Dink, who sees it, the page's chat, and taking the page down (last, as it can't be undone)

		// (11/21/2026: each tab as wide as its name needs and the rest shared out by that, where they were thirds:
		// "Encounters" is wider than a third, less a tab's own sides)
		java.awt.GridBagLayout bag = new java.awt.GridBagLayout();
		tabs.setLayout(bag);
		for (java.awt.Component c : tabs.getComponents())
		{
			java.awt.GridBagConstraints g = new java.awt.GridBagConstraints();
			g.fill = java.awt.GridBagConstraints.HORIZONTAL;
			g.weightx = c.getPreferredSize().width;
			g.insets = new java.awt.Insets(0, 2, 0, 2);
			bag.setConstraints(c, g);
		}
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

	/** A line under "Copy my address for Dink". */
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

	// hub:cut goal
	private final JButton goalAdd = new JButton("Add a goal item");
	private final JPanel goalList = new JPanel();

	/** A goal as the side tab lists it: the item, its name, whether it's got, and whether it's the one shown. */
	public static final class GoalRow
	{
		final int id;
		final String name;
		final boolean got, shown;

		public GoalRow(int id, String name, boolean got, boolean shown)
		{
			this.id = id;
			this.name = name;
			this.got = got;
			this.shown = shown;
		}
	}

	/** The goals under their button, in the order they were added: each picked to be shown, or taken off with its ✕
	 *  (none: the list hidden); over them, "No 3D model", picked when none's shown as one. Full, the button waits. */
	public void goals(List<GoalRow> rows, int most, boolean model)
	{
		SwingUtilities.invokeLater(() ->
		{
			goalList.removeAll();
			ButtonGroup group = new ButtonGroup();
			if (!rows.isEmpty())
			{
				// (11/19/2026, Jace: "add the option to NOT show a 3D model on your page for your goal/goals list")
				JPanel r = new JPanel(new BorderLayout(4, 0));
				r.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				r.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
				JRadioButton none = new JRadioButton("No 3D model", !model);
				none.setToolTipText("Your page lists every goal with its picture, none turning as a 3D model");
				none.setOpaque(false);
				none.setFont(FontManager.getRunescapeSmallFont());
				group.add(none);
				none.addActionListener(e -> hooks.noGoalModel());
				r.add(none, BorderLayout.CENTER);
				r.setMaximumSize(new Dimension(Integer.MAX_VALUE, r.getPreferredSize().height));
				goalList.add(r);
				goalList.add(Box.createVerticalStrut(2));
			}
			for (GoalRow g : rows)
			{
				goalList.add(goalRow(g, group, rows.size() > 1));
				goalList.add(Box.createVerticalStrut(2));
			}
			goalList.setVisible(!rows.isEmpty());
			boolean full = rows.size() >= most;
			goalAdd.setEnabled(!full);
			goalAdd.setToolTipText(full ? most + " goals is the most: take one off first" : "The game's item search: the item you pick is added to your goals");
			goalList.revalidate();
			goalList.repaint();
		});
	}

	private JPanel goalRow(GoalRow g, ButtonGroup group, boolean choice)
	{
		JPanel r = new JPanel(new BorderLayout(4, 0));
		r.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		r.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
		JRadioButton pick = new JRadioButton("<html><div style='width:110px'>" + esc(g.name + (g.got ? " (got)" : "")) + "</div></html>", g.shown);
		pick.setToolTipText(choice ? (g.shown ? "Shown as its 3D model on your page" : "Show this one as its 3D model on your page, the others listed under it")
			: "Shown as its 3D model on your page");
		pick.setOpaque(false);
		pick.setFont(FontManager.getRunescapeSmallFont());
		group.add(pick);
		pick.addActionListener(e -> hooks.showGoal(g.id));
		r.add(pick, BorderLayout.CENTER);
		JButton x = new JButton();
		// (the game's own font has no ✕: a system font's, or × or x where that has none either)
		x.setFont(new java.awt.Font(java.awt.Font.DIALOG, java.awt.Font.PLAIN, 12));
		x.setText(x.getFont().canDisplay('\u2715') ? "\u2715" : x.getFont().canDisplay('\u00d7') ? "\u00d7" : "x");
		x.setMargin(new java.awt.Insets(0, 6, 0, 6));
		x.setToolTipText("Take " + g.name + " off your goals");
		x.addActionListener(ev -> hooks.forgetGoal(g.id));
		r.add(x, BorderLayout.EAST);
		r.setMaximumSize(new Dimension(Integer.MAX_VALUE, r.getPreferredSize().height));
		return r;
	}
	// hub:end goal

	// hub:cut encounters
	/** The recording running (its words: what, how long, how big), or none: the button says Stop or Record now. */
	public void encounterRecording(String words, boolean running)
	{
		SwingUtilities.invokeLater(() ->
		{
			recordNow.setText(running ? "Stop" : "Record now");
			if (running)
			{
				// (a recording under way: what the last press said is past)
				fightStatus.setText(" ");
				fightStatus.setVisible(false);
			}
			recordLine.setText(words == null ? " " : "<html><div style='width:155px'>recording: " + esc(words) + "</div></html>");
			recordLine.setVisible(running && words != null);
			revalidate();
		});
	}

	/** "Record my encounters" switched in the settings: the box follows. */
	public void recordingOn(boolean on)
	{
		SwingUtilities.invokeLater(() -> recordOn.setSelected(on));
	}

	/** Whether the page takes encounters (the relay's answer: false its fights are off, null not read yet), and whether
	 *  a new one starts shown to anyone there. */
	public void fightsState(Boolean on, boolean shownFirst)
	{
		SwingUtilities.invokeLater(() ->
		{
			fightsOn = on;
			fightsShownFirst = shownFirst;
			fightsOff.setText(Boolean.FALSE.equals(on) ? "<html><div style='width:155px'>Outside.wiki isn't taking encounters on"
				+ " pages yet. Yours are kept on this computer meanwhile.</div></html>" : " ");
			fightsOff.setVisible(Boolean.FALSE.equals(on));
			showFights();
		});
	}

	/** A line over the list: what the last press did (null: none). */
	public void encounterStatus(String words)
	{
		SwingUtilities.invokeLater(() ->
		{
			fightStatus.setText(words == null ? " " : "<html><div style='width:155px'>" + esc(words) + "</div></html>");
			fightStatus.setVisible(words != null);
			revalidate();
		});
	}

	/** A put under way (its button waits), or done. */
	public void putting(String file, boolean on)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (on)
			{
				putting.add(file);
			}
			else
			{
				putting.remove(file);
			}
			showFights();
		});
	}

	/** The recent encounters, newest first (more: how many more the folder holds), and the character logged in (a
	 *  recording of another goes only on that one's page). */
	public void encounters(List<EncounterShelf.Row> rows, int more, String me)
	{
		SwingUtilities.invokeLater(() ->
		{
			fightRows = rows;
			fightMore = more;
			fightMe = me;
			showFights();
		});
	}

	private void showFights()
	{
		fightList.removeAll();
		if (fightRows == null)
		{
			fightList.add(left(small("(not read yet)")));
		}
		else if (fightRows.isEmpty())
		{
			fightList.add(left(small("<html><div style='width:155px'>None yet: they'll be here after your next boss or raid,"
				+ " or press Record now.</div></html>")));
		}
		else
		{
			int year = java.time.LocalDate.now().getYear();
			for (EncounterShelf.Row r : fightRows)
			{
				fightList.add(fightRow(r, year));
				fightList.add(Box.createVerticalStrut(4));
			}
			if (fightMore > 0)
			{
				fightList.add(left(small(fightMore == 1 ? "and 1 older in the folder" : "and " + fightMore + " older in the folder")));
			}
		}
		fightList.revalidate();
		fightList.repaint();
	}

	private static final java.time.format.DateTimeFormatter FIGHT_DAY = java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm");
	private static final java.time.format.DateTimeFormatter FIGHT_YEAR = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy");

	private JPanel fightRow(EncounterShelf.Row r, int year)
	{
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
		// who, and its ✕
		JPanel top = new JPanel(new BorderLayout(4, 0));
		top.setOpaque(false);
		JLabel who = new JLabel("<html><div style='width:140px'>" + esc(r.who == null ? "a fight" : r.who) + "</div></html>");
		who.setFont(FontManager.getRunescapeBoldFont());
		who.setForeground(Color.WHITE);
		top.add(who, BorderLayout.CENTER);
		JButton x = new JButton();
		x.setFont(new java.awt.Font(java.awt.Font.DIALOG, java.awt.Font.PLAIN, 12));
		x.setText(x.getFont().canDisplay('\u2715') ? "\u2715" : x.getFont().canDisplay('\u00d7') ? "\u00d7" : "x");
		x.setMargin(new java.awt.Insets(0, 6, 0, 6));
		x.setToolTipText("Delete this recording from this computer");
		x.addActionListener(e ->
		{
			if (javax.swing.JOptionPane.showConfirmDialog(this, "<html><div style='width:240px'>Delete " + esc(r.who)
				+ " from this computer?" + (r.fight != null ? " It stays on your page; take it down there." : "")
				+ "</div></html>", "Delete a recording", javax.swing.JOptionPane.OK_CANCEL_OPTION,
				javax.swing.JOptionPane.WARNING_MESSAGE) == javax.swing.JOptionPane.OK_OPTION)
			{
				hooks.deleteEncounter(r.file);
			}
		});
		top.add(x, BorderLayout.EAST);
		top.setMaximumSize(new Dimension(Integer.MAX_VALUE, top.getPreferredSize().height));
		box.add(top);
		// when, how long, and how it went
		int s = r.ticks * 6 / 10;
		String when = (r.when.getYear() == year ? FIGHT_DAY : FIGHT_YEAR).format(r.when);
		JLabel facts = small(when + " · " + s / 60 + ":" + String.format("%02d", s % 60));
		JPanel factRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		factRow.setOpaque(false);
		factRow.add(facts);
		if (r.died || r.killed)
		{
			JLabel how = small(r.died ? " · died" : " · killed");
			how.setForeground(r.died ? new Color(0xff, 0x69, 0xb4) : LIME);
			factRow.add(how);
		}
		factRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, factRow.getPreferredSize().height));
		box.add(factRow);
		box.add(Box.createVerticalStrut(4));
		boolean theirs = r.player == null || fightMe == null || OutsidePanel.sameName(r.player, fightMe);
		boolean shown = r.shown != null ? r.shown : fightsShownFirst;
		if (r.fight != null)
		{
			JPanel on = new JPanel(new BorderLayout(4, 0));
			on.setOpaque(false);
			JLabel there = small("on your page");
			there.setForeground(LIME);
			on.add(there, BorderLayout.CENTER);
			JButton open = new JButton("Open");
			open.setToolTipText("Its page on Outside.wiki, in your browser");
			open.addActionListener(e -> hooks.openFight(r.file));
			on.add(open, BorderLayout.EAST);
			on.setMaximumSize(new Dimension(Integer.MAX_VALUE, on.getPreferredSize().height));
			box.add(on);
		}
		else
		{
			boolean busy = putting.contains(r.file);
			JButton put = new JButton(busy ? "Putting it up…" : "Put on my page");
			put.setEnabled(!busy && theirs && !Boolean.FALSE.equals(fightsOn));
			put.setToolTipText(!theirs ? "Recorded on " + r.player + ": log in on that character to put it on its page"
				: Boolean.FALSE.equals(fightsOn) ? "Outside.wiki isn't taking encounters on pages yet"
				: "Other players' names, their chat and your exact experience taken out first; you're shown what before it goes");
			put.addActionListener(e -> hooks.putFight(r.file, Boolean.TRUE.equals(shownBox(put))));
			box.add(wide(put));
		}
		javax.swing.JCheckBox show = new javax.swing.JCheckBox("shown to anyone", shown);
		show.setOpaque(false);
		show.setFont(FontManager.getRunescapeSmallFont());
		show.setToolTipText(r.fight != null ? "Ticked, anyone who opens your page sees it; not, only you do (from Open here)"
			: "Ticked, it goes up for anyone who opens your page; not, only you see it there");
		show.setEnabled(theirs);
		show.addActionListener(e -> hooks.fightShown(r.file, show.isSelected()));
		JPanel showRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		showRow.setOpaque(false);
		showRow.add(show);
		showRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, showRow.getPreferredSize().height));
		box.add(showRow);
		box.putClientProperty("show", show);
		box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
		return box;
	}

	/** The "shown to anyone" box in the same row as a button. */
	private static Boolean shownBox(java.awt.Component c)
	{
		for (java.awt.Component p = c; p != null; p = p.getParent())
		{
			if (p instanceof JPanel && ((JPanel) p).getClientProperty("show") instanceof javax.swing.JCheckBox)
			{
				return ((javax.swing.JCheckBox) ((JPanel) p).getClientProperty("show")).isSelected();
			}
		}
		return null;
	}

	/** Two names the same as the game compares them: any case, spaces, non-breaking spaces, _ and - alike. */
	static boolean sameName(String a, String b)
	{
		return a.replaceAll("[\\s\u00a0_-]+", " ").trim().equalsIgnoreCase(b.replaceAll("[\\s\u00a0_-]+", " ").trim());
	}

	/** "Put on my page" asked: what goes, and what was taken out first, said before it's sent (the Hub's warning, at the
	 *  press that sends). */
	public void confirmPut(String file, String who, int ticks, java.util.Map<String, Integer> removed, boolean shown, long bytes,
		Runnable yes)
	{
		SwingUtilities.invokeLater(() ->
		{
			int s = ticks * 6 / 10;
			StringBuilder taken = new StringBuilder();
			for (java.util.Map.Entry<String, Integer> en : removed.entrySet())
			{
				taken.append("<li>").append(esc(en.getKey())).append(" (").append(en.getValue()).append(")</li>");
			}
			String words = "<html><div style='width:280px'><b>" + esc(who) + "</b>, " + s / 60 + ":" + String.format("%02d", s % 60)
				+ " (" + ticks + " ticks), " + String.format("%,d", Math.max(1, bytes / 1024)) + " KB."
				+ "<br><br>Taken out before it leaves this computer:<ul>" + (taken.length() > 0 ? taken : "<li>nothing needed taking out</li>")
				+ "</ul>What's left (what you and the NPCs did, tick by tick: where everyone stood, your gear, inventory, levels,"
				+ " prayers and hitsplats, other players unnamed) goes to the Outside relay, a server not controlled or verified"
				+ " by the RuneLite developers, and your Outside page shows it "
				+ (shown ? "to anyone who opens it." : "only to you, until you tick shown to anyone.") + "</div></html>";
			if (javax.swing.JOptionPane.showConfirmDialog(this, words, "Put on my page", javax.swing.JOptionPane.OK_CANCEL_OPTION,
				javax.swing.JOptionPane.PLAIN_MESSAGE) == javax.swing.JOptionPane.OK_OPTION)
			{
				yes.run();
			}
			else
			{
				putting.remove(file);
				showFights();
			}
		});
	}
	// hub:end encounters

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
