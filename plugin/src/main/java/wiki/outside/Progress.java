package wiki.outside;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * Quests, achievement diaries, combat achievements and the slayer task (11/15/2026, Jace: "add quests, achievement
 * diaries, combat achievements and slayer tasks to the plugin"), read as the plugins that already read them do:
 * - quests: RuneLite's own Quest list and each one's state (the quest list plugin's way), and the quest points varp;
 * - diaries: each tier's completion varbit, Karamja's first three counting at 2 (0 not started, 1 started, 2 its tasks
 *   done) and the rest at 1, and each area's tasks done and in all from the game's own summary scripts, as Dink reads
 *   them (github.com/pajlads/DinkPlugin, DiaryNotifier and AchievementDiary: "Credits" in the README);
 * - combat achievements: the points varbit, each tier's tasks done and the points that tier needs;
 * - the slayer task: as RuneLite's own slayer plugin reads it, the creature's name from the game's slayer task table
 *   (a boss task's through the boss table), the area a master named from the area table, the amount given with the
 *   game's later changes to it, the points and the streak.
 * What reads the client runs on the client thread. The shapes are docs/protocol.md's ("quests", "diaries", "combat",
 * "slayer").
 */
final class Progress
{
	static final String[] AREAS = {"Ardougne", "Desert", "Falador", "Fremennik", "Kandarin", "Karamja", "Kourend & Kebos",
		"Lumbridge & Draynor", "Morytania", "Varrock", "Western Provinces", "Wilderness"};
	/** each area's id for the game's diary summary scripts, as Dink has them */
	static final int[] AREA_IDS = {1, 5, 2, 3, 4, 0, 11, 6, 7, 8, 10, 9};
	static final int[][] DIARY = {
		{VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE},
		{VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE, VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE},
		{VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE, VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE},
		{VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE, VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE},
		{VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE, VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE},
		{VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE, VarbitID.ATJUN_HARD_DONE, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE},
		{VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE},
		{VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE, VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE},
		{VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE},
		{VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE},
		{VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE, VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE},
		{VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE, VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE},
	};
	/** the game's diary summary scripts: an area's tasks done, and in all (Dink's COMPLETED_AREA_TASKS_SCRIPT_ID, TOTAL_) */
	static final int AREA_DONE_SCRIPT = 4072, AREA_TOTAL_SCRIPT = 4073;

	static final String[] CA_TIERS = {"easy", "medium", "hard", "elite", "master", "grandmaster"};
	static final int[] CA_DONE = {VarbitID.CA_TOTAL_TASKS_COMPLETED_EASY, VarbitID.CA_TOTAL_TASKS_COMPLETED_MEDIUM, VarbitID.CA_TOTAL_TASKS_COMPLETED_HARD,
		VarbitID.CA_TOTAL_TASKS_COMPLETED_ELITE, VarbitID.CA_TOTAL_TASKS_COMPLETED_MASTER, VarbitID.CA_TOTAL_TASKS_COMPLETED_GRANDMASTER};
	static final int[] CA_NEEDS = {VarbitID.CA_THRESHOLD_EASY, VarbitID.CA_THRESHOLD_MEDIUM, VarbitID.CA_THRESHOLD_HARD,
		VarbitID.CA_THRESHOLD_ELITE, VarbitID.CA_THRESHOLD_MASTER, VarbitID.CA_THRESHOLD_GRANDMASTER};

	/** RuneLite's slayer plugin's tables and columns: the task table (its name), the boss table, the area table */
	static final int TASK_TABLE = 113, BOSS_TABLE = 116, AREA_TABLE = 115, BOSS_TASK = 98;
	static final int NAME_MAX = 80;

	private Progress()
	{
	}

	/** A tier done, from its varbit's value: Karamja's easy, medium and hard at 2, the rest at 1 (Dink's isComplete). */
	static boolean diaryDone(int varbit, int value)
	{
		if (varbit == VarbitID.ATJUN_EASY_DONE || varbit == VarbitID.ATJUN_MED_DONE || varbit == VarbitID.ATJUN_HARD_DONE)
		{
			return value > 1;
		}
		return value > 0;
	}

	/** One area: its four tiers (1 done, 0 not), then its tasks done and in all. */
	static JsonArray area(int[] varbits, int[] values, int done, int total)
	{
		JsonArray a = new JsonArray();
		for (int i = 0; i < 4; i++)
		{
			a.add(diaryDone(varbits[i], values[i]) ? 1 : 0);
		}
		a.add(Math.max(0, done));
		a.add(Math.max(0, total));
		return a;
	}

	/** Combat achievements: the points, then each tier's [tasks done, points it needs]. */
	static JsonObject combat(int points, int[] done, int[] needs)
	{
		JsonObject o = new JsonObject();
		o.addProperty("points", Math.max(0, points));
		JsonArray tiers = new JsonArray();
		for (int i = 0; i < CA_TIERS.length; i++)
		{
			JsonArray t = new JsonArray();
			t.add(Math.max(0, done[i]));
			t.add(Math.max(0, needs[i]));
			tiers.add(t);
		}
		o.add("tiers", tiers);
		return o;
	}

	/** Quests: the points, how many there are, and the names of those done, those started and the rest (the game's
	 *  quest list, green, yellow and red). */
	static JsonObject quests(int points, int total, List<String> done, List<String> started, List<String> todo)
	{
		JsonObject o = new JsonObject();
		o.addProperty("points", Math.max(0, points));
		o.addProperty("total", Math.max(0, total));
		o.add("done", names(done));
		o.add("started", names(started));
		o.add("todo", names(todo));
		return o;
	}

	/** The slayer task: null task when there's none (the points and streak still go). */
	static JsonObject slayer(String task, int left, int of, String area, int points, int streak)
	{
		JsonObject o = new JsonObject();
		boolean on = task != null && !task.isEmpty() && left > 0;
		o.add("task", on ? new com.google.gson.JsonPrimitive(clip(task)) : JsonNull.INSTANCE);
		o.addProperty("left", on ? left : 0);
		o.addProperty("of", on ? Math.max(left, of) : 0);
		o.add("area", on && area != null && !area.isEmpty() ? new com.google.gson.JsonPrimitive(clip(area)) : JsonNull.INSTANCE);
		o.addProperty("points", Math.max(0, points));
		o.addProperty("streak", Math.max(0, streak));
		return o;
	}

	private static JsonArray names(List<String> list)
	{
		JsonArray a = new JsonArray();
		for (String s : list)
		{
			a.add(clip(s));
		}
		return a;
	}

	private static String clip(String s)
	{
		String one = s.replaceAll("\\s+", " ").trim();
		return one.length() > NAME_MAX ? one.substring(0, NAME_MAX) : one;
	}

	// ---- read from the client (the client thread only) ----

	/** Everything the quest and diary reads depend on, cheaply: when it changes, they're read again. */
	static long signature(Client c)
	{
		long h = c.getVarpValue(VarPlayerID.QP);
		for (int[] tiers : DIARY)
		{
			for (int v : tiers)
			{
				h = h * 31 + c.getVarbitValue(v);
			}
		}
		return h;
	}

	static JsonObject readQuests(Client c)
	{
		List<String> done = new java.util.ArrayList<>(), started = new java.util.ArrayList<>(), todo = new java.util.ArrayList<>();
		Quest[] all = Quest.values();
		for (Quest q : all)
		{
			QuestState s = q.getState(c);
			if (s == QuestState.FINISHED)
			{
				done.add(q.getName());
			}
			else if (s == QuestState.IN_PROGRESS)
			{
				started.add(q.getName());
			}
			else
			{
				todo.add(q.getName());
			}
		}
		return quests(c.getVarpValue(VarPlayerID.QP), all.length, done, started, todo);
	}

	static JsonObject readDiaries(Client c)
	{
		JsonObject o = new JsonObject();
		for (int i = 0; i < AREAS.length; i++)
		{
			int[] values = new int[4];
			for (int t = 0; t < 4; t++)
			{
				values[t] = c.getVarbitValue(DIARY[i][t]);
			}
			c.runScript(AREA_DONE_SCRIPT, AREA_IDS[i]);
			int done = c.getIntStack()[0];
			c.runScript(AREA_TOTAL_SCRIPT, AREA_IDS[i]);
			int total = c.getIntStack()[0];
			o.add(AREAS[i], area(DIARY[i], values, done, total));
		}
		return o;
	}

	static JsonObject readCombat(Client c)
	{
		int[] done = new int[CA_TIERS.length], needs = new int[CA_TIERS.length];
		for (int i = 0; i < CA_TIERS.length; i++)
		{
			done[i] = c.getVarbitValue(CA_DONE[i]);
			needs[i] = c.getVarbitValue(CA_NEEDS[i]);
		}
		return combat(c.getVarbitValue(VarbitID.CA_POINTS), done, needs);
	}

	/** What the slayer reads depend on, as a key: the names are looked up again only when it changes. */
	static String slayerKey(Client c)
	{
		return c.getVarpValue(VarPlayerID.SLAYER_COUNT) + ":" + c.getVarpValue(VarPlayerID.SLAYER_TARGET) + ":" + c.getVarpValue(VarPlayerID.SLAYER_AREA)
			+ ":" + c.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID) + ":" + c.getVarpValue(4258) + ":" + c.getVarbitValue(15776) + ":" + c.getVarbitValue(15777)
			+ ":" + c.getVarbitValue(15778) + ":" + c.getVarbitValue(VarbitID.SLAYER_POINTS) + ":" + c.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED);
	}

	/** RuneLite's slayer plugin's updateTask, read the same way. */
	static JsonObject readSlayer(Client c)
	{
		int left = c.getVarpValue(VarPlayerID.SLAYER_COUNT);
		int points = c.getVarbitValue(VarbitID.SLAYER_POINTS), streak = c.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED);
		if (left <= 0)
		{
			return slayer(null, 0, 0, null, points, streak);
		}
		int target = c.getVarpValue(VarPlayerID.SLAYER_TARGET);
		int row;
		if (target == BOSS_TASK)
		{
			List<Integer> boss = c.getDBRowsByValue(BOSS_TABLE, 1, 0, c.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID));
			if (boss.isEmpty())
			{
				return slayer(null, 0, 0, null, points, streak);
			}
			row = (Integer) c.getDBTableField(boss.get(0), 4, 0)[0];
		}
		else
		{
			List<Integer> rows = c.getDBRowsByValue(TASK_TABLE, 0, 0, target);
			if (rows.isEmpty())
			{
				return slayer(null, 0, 0, null, points, streak);
			}
			row = rows.get(0);
		}
		String name = (String) c.getDBTableField(row, 10, 0)[0];
		String area = null;
		int areaId = c.getVarpValue(VarPlayerID.SLAYER_AREA);
		if (areaId > 0)
		{
			List<Integer> a = c.getDBRowsByValue(AREA_TABLE, 0, 0, areaId);
			if (!a.isEmpty())
			{
				area = (String) c.getDBTableField(a.get(0), 3, 0)[0];
			}
		}
		// (the amount given, with the game's later changes to it: the slayer plugin's initialAmount)
		int of = c.getVarpValue(4258);
		if (c.getVarbitValue(15776) == 2)
		{
			int by = c.getVarbitValue(15777);
			of += c.getVarbitValue(15778) == 1 ? -by : by;
		}
		return slayer(name, left, of, area, points, streak);
	}
}
