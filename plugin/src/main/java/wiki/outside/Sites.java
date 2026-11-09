/*
 * The landing sites below are Shooting Star Info's (github.com/pwatts6060/runelite-plugins, its Location.java, at
 * commit 38fc77770ad0a5c07e74e7491975a90e40e62173), less its Wilderness sites and Prifddinas's (off the main map),
 * under its licence:
 *
 * BSD 2-Clause License
 *
 * Copyright (c) 2022, Cute Rock
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package wiki.outside;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A shooting star's landing site by name (11/09/2026, Jace: "named landing sites: yes"): the tile a crashed star stands
 * on, as Shooting Star Info has them (the licence above; plugin/README.md, "Credits"). The plugin sends the name with the
 * star's square; the relays take only these names, each with its own square (game.ts's STAR_SITES, relay.py's;
 * relay-py/test_relay.py checks the three match). A star on a tile not here goes with its square alone.
 */
final class Sites
{
	/** "x,y" (the star's tile) -> its site's name, in Star Info's order */
	static final Map<String, String> BY_TILE;
	private static final Map<String, String> sites = new LinkedHashMap<>();

	private static void site(int x, int y, String name)
	{
		sites.put(x + "," + y, name);
	}

	static
	{
		site(2974, 3241, "Rimmington mine");
		site(2940, 3280, "Crafting guild");
		site(2906, 3355, "West Falador mine");
		site(3030, 3348, "East Falador bank");
		site(3018, 3443, "North Dwarven Mine entrance");
		site(2882, 3474, "Taverley house portal");
		site(2736, 3221, "Brimhaven northwest gold mine");
		site(2742, 3143, "Brimhaven south dungeon entrance");
		site(2845, 3037, "Nature Altar mine north of Shilo");
		site(2827, 2999, "Shilo Village gem mine");
		site(2835, 3296, "North Crandor");
		site(2822, 3238, "South Crandor");
		site(3296, 3298, "Al Kharid mine");
		site(3276, 3164, "Al Kharid bank");
		site(3351, 3281, "Mage Training Arena entrance");
		site(3424, 3160, "Nw of Uzer (Eagle's Eyrie)");
		site(3434, 2889, "Nardah bank");
		site(3316, 2867, "Agility Pyramid mine");
		site(3171, 2910, "Desert Quarry mine");
		site(2567, 2858, "Corsair Cove bank");
		site(2483, 2886, "Corsair Resource Area");
		site(2468, 2842, "Myths' Guild");
		site(2571, 2964, "Feldip Hills (aks fairy ring)");
		site(2630, 2993, "Rantz cave");
		site(2200, 2792, "Soul Wars south mine");
		site(3818, 3801, "Fossil Island Volcanic Mine entrance");
		site(3774, 3814, "Fossil Island rune rocks");
		site(3686, 2969, "Mos Le'Harmless west bank");
		site(2727, 3683, "Keldagrim entrance mine");
		site(2683, 3699, "Rellekka mine");
		site(2393, 3814, "Jatizso mine entrance");
		site(2375, 3832, "Neitiznot south of rune rock");
		site(2528, 3887, "Miscellania mine (cip fairy ring)");
		site(2139, 3938, "Lunar Isle mine entrance");
		site(2602, 3086, "Yanille bank");
		site(2624, 3141, "Port Khazard mine");
		site(2608, 3233, "Ardougne Monastery");
		site(2705, 3333, "South of Legends' Guild");
		site(2804, 3434, "Catherby bank");
		site(2589, 3478, "Coal Trucks west of Seers'");
		site(1778, 3493, "Hosidius mine");
		site(1769, 3709, "Port Piscarilius mine in Kourend");
		site(1597, 3648, "Shayzien mine south of Kourend Castle");
		site(1534, 3747, "South Lovakengj bank");
		site(1437, 3840, "Lovakite mine");
		site(1760, 3853, "Arceuus dense essence mine");
		site(1322, 3816, "Mount Karuulm bank");
		site(1279, 3817, "Mount Karuulm mine");
		site(1210, 3651, "Kebos Swamp mine");
		site(1258, 3564, "Chambers of Xeric bank");
		site(3258, 3408, "Varrock east bank");
		site(3290, 3353, "South-east Varrock mine");
		site(3175, 3362, "Champions' Guild mine");
		site(3094, 3235, "Draynor Village");
		site(3153, 3150, "West Lumbridge Swamp mine");
		site(3230, 3155, "East Lumbridge Swamp mine");
		site(3635, 3340, "Darkmeyer ess. mine entrance");
		site(3650, 3214, "Theatre of Blood bank");
		site(3505, 3485, "Canifis bank");
		site(3500, 3219, "Burgh de Rott bank");
		site(3451, 3233, "Abandoned Mine west of Burgh");
		site(2444, 3490, "West of Grand Tree");
		site(2448, 3436, "Gnome Stronghold spirit tree");
		site(2341, 3635, "Piscatoris (akq fairy ring)");
		site(2329, 3163, "Lletya");
		site(2269, 3158, "Isafdar runite rocks");
		site(2318, 3269, "Arandar mine north of Lleyta");
		site(2173, 3409, "Mynydd nw of Prifddinas");
		site(1742, 2954, "Varlamore South East mine");
		site(1771, 3102, "Varlamore colosseum entrance bank");
		site(1486, 3089, "Mine north-west of hunter guild");
		BY_TILE = Collections.unmodifiableMap(sites);
	}

	private Sites()
	{
	}

	/** The site a star on this tile landed at, or null for one not on the list. */
	static String name(int x, int y)
	{
		return BY_TILE.get(x + "," + y);
	}
}
