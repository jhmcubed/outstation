package wiki.outside;

import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;

/**
 * The Outside button in the collection log's header row (update 7, round two, 11/22/2026), drawn and placed as
 * WikiSync's is (github.com/weirdgloop/WikiSync, SyncButtonManager, at 1ff4ea0; BSD 2-Clause, Copyright (c) 2021,
 * andmcadams): nine sprites (a background, four corners, four edges) and a text over them that takes the hover and the
 * press, anchored from the log's right edge, the height and row of the log's own Search button. Theirs sits right
 * beside the close button; TempleOSRS's left of WikiSync's; Outside's left of whatever is already in that row, five
 * pixels from it, so it never covers another plugin's. Added after the log's setup script and the other plugins' own
 * listeners have run (OutsidePlugin's invokeLater), and again whenever another plugin's clearing of the row took it.
 */
final class ClogButton
{
	/** every widget of ours is named so (none takes a menu option but the text's own) */
	static final String NAME = "Outside";
	static final int CLOSE_OFFSET = 28, GAP = 5, WIDTH = 71, CORNER = 9, SIDE_HEIGHT = 4;
	static final int TEXT_OUT = 0xd6d6d6, TEXT_IN = 0xffffff;
	// (WikiSync's sprites, by RuneLite's gameval names: its DIALOG_BACKGROUND and WORLD_MAP_BUTTON_* are these; hovered,
	// its RESIZEABLE_MODE_SIDE_PANEL_BACKGROUND and EQUIPMENT_BUTTON_*_HOVERED)
	static final int[] OUT = {SpriteID.TRADEBACKING, SpriteID.V2StoneButtonOut.A_TOP_LEFT, SpriteID.V2StoneButtonOut.A_TOP_RIGHT,
		SpriteID.V2StoneButtonOut.A_BOTTOM_LEFT, SpriteID.V2StoneButtonOut.A_BOTTOM_RIGHT, SpriteID.V2StoneButtonOut.A_MAP_EDGE_LEFT,
		SpriteID.V2StoneButtonOut.A_MAP_EDGE_TOP, SpriteID.V2StoneButtonOut.A_MAP_EDGE_RIGHT, SpriteID.V2StoneButtonOut.A_MAP_EDGE_BOTTOM};
	static final int[] IN = {SpriteID.TRADEBACKING_DARK, SpriteID.V2StoneButtonIn.A_TOP_LEFT, SpriteID.V2StoneButtonIn.A_TOP_RIGHT,
		SpriteID.V2StoneButtonIn.A_BOTTOM_LEFT, SpriteID.V2StoneButtonIn.A_BOTTOM_RIGHT, SpriteID.V2StoneButtonIn.A_LEFT,
		SpriteID.V2StoneButtonIn.A_TOP, SpriteID.V2StoneButtonIn.A_RIGHT, SpriteID.V2StoneButtonIn.A_BOTTOM};

	/** Where ours goes, from the log's right edge: left of every [x from the right, width] already in the row. */
	static int place(int[][] taken)
	{
		int x = CLOSE_OFFSET + GAP;
		for (int[] t : taken)
		{
			x = Math.max(x, t[0] + t[1] + GAP);
		}
		return x;
	}

	/** The button added to the open log (the client's thread), unless it's there already or the log isn't open. */
	static void add(Client client, Runnable press)
	{
		Widget parent = client.getWidget(InterfaceID.Collection.UNIVERSE);
		Widget search = client.getWidget(InterfaceID.Collection.SEARCH_TOGGLE);
		Widget box = client.getWidget(InterfaceID.Collection.INFINITY);
		Widget[] boxChildren = box == null ? null : box.getChildren();
		Widget bar = boxChildren == null || boxChildren.length == 0 ? null : boxChildren[0];
		if (parent == null || search == null || bar == null)
		{
			return;
		}
		Widget[] there = parent.getChildren();
		java.util.List<int[]> taken = new java.util.ArrayList<>();
		for (Widget c : there == null ? new Widget[0] : there)
		{
			if (c == null || c.isHidden() || c.getXPositionMode() != WidgetPositionMode.ABSOLUTE_RIGHT)
			{
				continue;
			}
			if (NAME.equals(c.getName()))
			{
				return;   // (ours is there)
			}
			taken.add(new int[]{c.getOriginalX(), c.getOriginalWidth()});
		}
		final int w = WIDTH, h = search.getOriginalHeight(), x = place(taken.toArray(new int[0][])), y = search.getOriginalY();
		// (as WikiSync's: [x, y, width, height] of each sprite, from the right; 1-4 the corners, 5 and 7 the sides, 6 and 8
		// the top and the bottom)
		int[][] at = {{x, y, w, h}, {x + w - CORNER, y, CORNER, CORNER}, {x, y, CORNER, CORNER},
			{x + w - CORNER, y + h - CORNER, CORNER, CORNER}, {x, y + h - CORNER, CORNER, CORNER},
			{x + w - CORNER, y + CORNER, CORNER, SIDE_HEIGHT}, {x + CORNER, y, w - 2 * CORNER, CORNER},
			{x, y + CORNER, CORNER, SIDE_HEIGHT}, {x + CORNER, y + h - CORNER, w - 2 * CORNER, CORNER}};
		Widget[] sprites = new Widget[at.length];
		for (int i = 0; i < at.length; i++)
		{
			sprites[i] = parent.createChild(-1, WidgetType.GRAPHIC)
				.setSpriteId(OUT[i])
				.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT)
				.setPos(at[i][0], at[i][1])
				.setSize(at[i][2], at[i][3]);
			if (i == 0)
			{
				sprites[i].setYPositionMode(search.getYPositionMode());
			}
			sprites[i].setName(NAME);
			sprites[i].revalidate();
		}
		Widget text = parent.createChild(-1, WidgetType.TEXT)
			.setText(NAME)
			.setTextColor(TEXT_OUT)
			.setFontId(FontID.PLAIN_11)
			.setTextShadowed(true)
			.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT)
			.setXTextAlignment(WidgetTextAlignment.CENTER)
			.setYTextAlignment(WidgetTextAlignment.CENTER)
			.setPos(x, y)
			.setSize(w, h)
			.setYPositionMode(search.getYPositionMode());
		text.revalidate();
		text.setHasListener(true);
		text.setOnMouseOverListener((JavaScriptCallback) ev -> hover(sprites, text, true));
		text.setOnMouseLeaveListener((JavaScriptCallback) ev -> hover(sprites, text, false));
		text.setAction(0, "Send your collection log to your Outside page");
		text.setOnOpListener((JavaScriptCallback) ev -> press.run());
		// (the log's draggable top bar kept short of ours, as WikiSync shortens it for its own: only where its width is
		// its own, and only ever shorter)
		if (bar.getWidthMode() == 0 && box.getWidth() > 0)
		{
			int over = bar.getRelativeX() + bar.getWidth() - (box.getWidth() - (x + w));
			if (over > 0)
			{
				bar.setOriginalWidth(Math.max(0, bar.getOriginalWidth() - over));
				bar.revalidate();
			}
		}
		parent.revalidate();
	}

	/** Ours hidden (the switch turned off while the log's open; the client's thread). */
	static void hide(Client client)
	{
		Widget parent = client.getWidget(InterfaceID.Collection.UNIVERSE);
		Widget[] there = parent == null ? null : parent.getChildren();
		for (Widget c : there == null ? new Widget[0] : there)
		{
			if (c != null && (NAME.equals(c.getName()) || (c.getType() == WidgetType.TEXT && NAME.equals(c.getText()))))
			{
				c.setHidden(true);
			}
		}
	}

	private static void hover(Widget[] sprites, Widget text, boolean in)
	{
		for (int i = 0; i < sprites.length; i++)
		{
			sprites[i].setSpriteId(in ? IN[i] : OUT[i]);
		}
		text.setTextColor(in ? TEXT_IN : TEXT_OUT);
	}
}
