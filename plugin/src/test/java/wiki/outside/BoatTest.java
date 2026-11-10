package wiki.outside;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Player;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * On a boat (11/10/2026, Jace: "I notice there are no players on their boats at sea"): the plugin's place for the player
 * (OutsidePlugin.here, onMainWorld) is where the boat is on the main world, never the deck's own world view. RuneLite's
 * interfaces stood in for by proxies that answer only what's asked.
 */
public class BoatTest
{
	/** An interface's stand-in: each method named answers as given; anything else is a mistake in the test. */
	@SuppressWarnings("unchecked")
	static <T> T fake(Class<T> type, Map<String, java.util.function.Function<Object[], Object>> answers)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, m, args) ->
		{
			java.util.function.Function<Object[], Object> a = answers.get(m.getName());
			if (a == null)
			{
				throw new AssertionError(type.getSimpleName() + "." + m.getName() + " wasn't expected");
			}
			return a.apply(args);
		});
	}

	static final int BOAT = 7;
	/** the main world's scene: its corner at the Great Conch's waters, not an instance */
	static final int BASE_X = 3136, BASE_Y = 2368;

	/** The main world's view, with one boat (its world view BOAT) whose deck's points go to `sea` on the main world
	 *  (null: transformToMainWorld gives nothing, and the boat's own place is `boatAt`). */
	static WorldView top(LocalPoint sea, LocalPoint boatAt)
	{
		Map<String, java.util.function.Function<Object[], Object>> boat = new HashMap<>();
		boat.put("transformToMainWorld", a -> sea);
		boat.put("getLocalLocation", a -> boatAt);
		WorldEntity we = fake(WorldEntity.class, boat);
		Map<String, java.util.function.Function<Object[], Object>> set = new HashMap<>();
		set.put("byIndex", a -> (int) a[0] == BOAT ? we : null);
		IndexedObjectSet<?> boats = fake(IndexedObjectSet.class, set);
		Map<String, java.util.function.Function<Object[], Object>> v = new HashMap<>();
		v.put("worldEntities", a -> boats);
		v.put("isInstance", a -> false);
		v.put("getPlane", a -> 0);
		v.put("getBaseX", a -> BASE_X);
		v.put("getBaseY", a -> BASE_Y);
		v.put("getId", a -> WorldView.TOPLEVEL);
		v.put("isTopLevel", a -> true);
		return fake(WorldView.class, v);
	}

	static Client client(WorldView top)
	{
		Map<String, java.util.function.Function<Object[], Object>> c = new HashMap<>();
		c.put("getTopLevelWorldView", a -> top);
		c.put("getWorldView", a -> (int) a[0] == WorldView.TOPLEVEL ? top : null);
		return fake(Client.class, c);
	}

	static Player player(LocalPoint at)
	{
		Map<String, java.util.function.Function<Object[], Object>> p = new HashMap<>();
		p.put("getLocalLocation", a -> at);
		return fake(Player.class, p);
	}

	/** a tile's middle, in local units (128 a tile) of a world view */
	static LocalPoint tile(int x, int y, int view)
	{
		return new LocalPoint(x * 128 + 64, y * 128 + 64, view);
	}

	@Test
	public void onDeckThePlaceIsWhereTheBoatIsAtSea()
	{
		// (on the deck, tile 3,5 of the boat's own view; the boat at scene tile 44,67 of the main world: 3180, 2435)
		LocalPoint deck = tile(3, 5, BOAT);
		WorldView top = top(tile(44, 67, WorldView.TOPLEVEL), null);
		LocalPoint on = OutsidePlugin.onMainWorld(top, deck);
		assertEquals(WorldView.TOPLEVEL, on.getWorldView());
		WorldPoint at = OutsidePlugin.here(client(top), player(deck));
		assertEquals(new WorldPoint(3180, 2435, 0), at);
		// (and so it goes as the sea's square, by the Great Conch)
		assertArrayEquals(new int[]{3136, 2496}, Follow.place(at.getX(), at.getY()));
	}

	@Test
	public void withNoPointFromTheDeckTheBoatsOwnPlace()
	{
		// (as Group Ironmen Tracker and Shortest Path read it: the boat's place on the main world)
		WorldView top = top(null, tile(10, 20, WorldView.TOPLEVEL));
		assertEquals(new WorldPoint(3146, 2388, 0), OutsidePlugin.here(client(top), player(tile(1, 1, BOAT))));
	}

	@Test
	public void aDeckWithNoBoatOnTheMainWorldIsNowhere()
	{
		// (a world view that isn't a boat on the main world, as while a hop sets one up: nothing, never the deck's tile)
		WorldView top = top(tile(44, 67, WorldView.TOPLEVEL), null);
		assertNull(OutsidePlugin.onMainWorld(top, tile(3, 5, BOAT + 1)));
		assertNull(OutsidePlugin.here(client(top), player(tile(3, 5, BOAT + 1))));
		assertNull(OutsidePlugin.onMainWorld(null, tile(3, 5, BOAT)));
		assertNull(OutsidePlugin.here(client(top), null));
		assertNull(OutsidePlugin.here(client(top), player(null)));
	}

	@Test
	public void onLandNothingChanges()
	{
		// (a point on the main world is its own place: the same point, read as ever)
		LocalPoint land = tile(20, 30, WorldView.TOPLEVEL);
		WorldView top = top(null, null);
		assertSame(land, OutsidePlugin.onMainWorld(top, land));
		assertEquals(new WorldPoint(3156, 2398, 0), OutsidePlugin.here(client(top), player(land)));
	}
}
