package dev.cubeutilityclient.settings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the Mods page says about the pack's mods, in plain words: a short line for the list, a
 * sentence for the top of the mod's page, and for some a few points under "What it does". Mods
 * that aren't here (new ones) are described from their own fabric.mod.json, so adding a mod to
 * the pack needs nothing here; an entry only makes its page read better.
 */
final class ModInfo {
    /** The name to show, when the mod's own isn't the one players know (null: the mod's own). */
    final String name;
    /** A few words for the list on the left. */
    final String line;
    /** One sentence for the top of the mod's page. */
    final String summary;
    /** What it does, one point each, shown above its settings. */
    final List<String> about;
    final ModEntry.Group group;
    /** More words the search finds it by. */
    final String keywords;
    /** The mod whose page holds this one's settings (Sodium Extra's are in Sodium's), or null. */
    final String settingsIn;
    /** Where it sits in its group. */
    final int order;

    private ModInfo(String name, String line, String summary, List<String> about, ModEntry.Group group, String keywords, String settingsIn, int order) {
        this.name = name;
        this.line = line;
        this.summary = summary;
        this.about = about;
        this.group = group;
        this.keywords = keywords;
        this.settingsIn = settingsIn;
        this.order = order;
    }

    private static final Map<String, ModInfo> KNOWN = new HashMap<>();

    static ModInfo of(String id) {
        return KNOWN.get(id);
    }

    private static void add(String id, ModEntry.Group group, String name, String line, String summary, String keywords, String settingsIn, String... about) {
        KNOWN.put(id, new ModInfo(name, line, summary, List.of(about), group, keywords, settingsIn, KNOWN.size()));
    }

    static {
        // CubeCraft: my own mods for the server.
        add("cubeutility", ModEntry.Group.CUBECRAFT, null, "CubeCraft helpers, HUDs and zoom",
                "An all-in-one CubeCraft toolkit, plus zoom, freelook and fullbright on any server.",
                "zoom freelook fullbright armour armor hud ping chat history timer map team build limit bridge spawn protection cooldowns durability drop coach clutch "
                        + "supply shop discount briefing afk alt chest party alerts stats leaderboards discord clips obs vote auto gg play kit cubepanion",
                null,
                "Helps in every game: a game timer, the map and team layout, build limit and bridge warnings, spawn protection and item cooldowns, durability and "
                        + "drop protection, an EggWars coach and clutch cues.",
                "Around your games: shop discounts, map briefings, an AFK alarm, an alt marker, a hidden chest finder, party tools, chat alerts, stats and "
                        + "leaderboards.",
                "Does things for you once you switch them on: auto GG, auto play, and your kit for each map.",
                "On any server: zoom, freelook, fullbright, an armour HUD, ping numbers and a longer chat history.",
                "Every feature switches on and off by itself. Edit HUD, under General, moves its widgets around the screen.");
        add("ewoptimizer", ModEntry.Group.CUBECRAFT, null, "Smoother, cleaner Eggwars",
                "Makes CubeCraft Eggwars run smoother, and keeps its chat and screen clean.",
                "eggwars particles generators dropped items stacks chat spam ads boss bar holograms preset fps performance optimizer optimization", null,
                "Generator particles: CubeCraft forces the generators' sparkles on, so they show even when your Particles setting is Minimal. Here they "
                        + "follow your Particles setting instead, only part of them are kept (Keep forced particles), and the ones farther away than "
                        + "Forced particle distance aren't drawn at all.",
                "Dropped items: Minecraft draws up to five copies of every dropped stack, and the piles at the generators are mostly full stacks. "
                        + "Each stack is drawn once, so standing at a generator costs far fewer frames.",
                "Holograms: floating text and display models farther away than Display entity distance can be hidden (0 leaves them as they are).",
                "Chat, on CubeCraft only: the same line sent again and again becomes one line with a count, like (x3). Shop messages such as \"You need "
                        + "10 more Diamonds\" move to the action bar above your hotbar, and rank ads, recruiting messages, the hub's welcome banner, "
                        + "blank lines and join and leave lines are hidden.",
                "Boss bar: the \"Playing EggWars on PLAY.CUBECRAFT.NET\" bar at the top of the screen is hidden.",
                "Type /ewo preset in chat for the tested Eggwars video settings (Particles: Decreased, render distance 10, no clouds, shadows or view "
                        + "bobbing, unlimited FPS); /ewo preset restore puts yours back, and /ewo stats shows what it has cut so far.");
        add("cubeautovote", ModEntry.Group.CUBECRAFT, "CubeCraft AutoVote", "Votes for you before games",
                "Votes for your favourite game options in CubeCraft's waiting lobbies.", "vote voting autovote auto map size health time lobby", null,
                "When a game's voting item shows up in your hotbar, it opens the voting menus and picks what you chose for that game: map size, "
                        + "health, time of day and the rest.",
                "With Vote without showing the menus on, it all happens out of sight; nothing pops up while you wait.",
                "Pick your options for each game under Choices for each game. If CubeCraft moves its menus around, the layout editor there fixes the slots.");
        add("lvl_rank", ModEntry.Group.CUBECRAFT, "CubeCraft Level + Rank", "Levels and ranks next to names",
                "Shows CubeCraft players' levels and rank colours on nametags and in the tab list.", "level levels rank ranks nametag nametags tab list who",
                null,
                "Puts each player's CubeCraft level next to their name, above their head and in the tab list, with a bar in their rank's colour.",
                "Levels come from CubeCraft's /who list. It can send /who for you when a lobby loads and keep the reply out of your chat, and it "
                        + "remembers players between sessions.");
        add("spectatorinside", ModEntry.Group.CUBECRAFT, null, "Watch from inside a player's head",
                "In spectator mode, watch from inside another player's head, with their HUD.", "spectator spectate watch possession camera hud", null,
                "In spectator mode, left-click any player or mob, even far away, to go inside their head. The arrow keys hop between players, and "
                        + "Sneak leaves.",
                "You see their hands, health, keystrokes, combos, reach, opponent and kills on a HUD you can rearrange (press J).",
                "Your body follows them with the spectator menu's teleport, so they never drop out of view and you hear what they hear.");

        // Video and performance.
        add("sodium", ModEntry.Group.VIDEO, null, "Video settings, faster drawing",
                "Draws the world much faster than Minecraft does, and holds the video settings.",
                "video graphics render distance simulation brightness gui scale fullscreen vsync fps frame rate framerate max particles animations fog clouds "
                        + "quality performance options biome blend entity shadows", null);
        add("sodium-extra", ModEntry.Group.VIDEO, null, "More video settings", "Adds more video settings to Sodium's page.",
                "video animations particles fog clouds fps counter", "sodium",
                "Adds pages to Sodium's video settings: Animations, Particles, Details, Render and Extras, including the FPS counter in the top-right corner.");
        add("reeses-sodium-options", ModEntry.Group.VIDEO, null, "A tidier video settings screen", "Gives Sodium's video settings screen tabs and a search.",
                "video options", "sodium",
                "Changes how the Video Settings screen in Minecraft's Options looks. In this menu, those settings are on Sodium's page.");
        add("entityculling", ModEntry.Group.VIDEO, "Entity Culling", "Skips mobs you can't see",
                "Skips drawing mobs, players and chests that are hidden behind blocks.", "culling entities mobs hidden fps", null);
        add("moreculling", ModEntry.Group.VIDEO, null, "Skips hidden leaves and signs",
                "Skips drawing leaves, signs, item frames and block faces you can't see.", "culling leaves signs item frames blocks fps", null);
        add("dynamic_fps", ModEntry.Group.VIDEO, null, "Saves power in the background",
                "Lowers the frame rate while Minecraft is in the background, or while you're away.", "background frame rate fps power battery idle unfocused",
                null);
        add("badoptimizations", ModEntry.Group.VIDEO, null, "Speed-ups besides drawing",
                "Optimizations for things other than drawing, like lighting and the sky's colour.", "optimizations lighting sky", null);
        add("immediatelyfast", ModEntry.Group.VIDEO, null, "Faster text and HUD drawing", "Draws text, HUDs, signs and maps much faster.",
                "hud text signs maps fps", null);
        add("lithium", ModEntry.Group.VIDEO, null, "Faster game logic",
                "Speeds up the game's own logic, like physics and mobs, for steadier frames.", "logic physics ticks", null);
        add("ferritecore", ModEntry.Group.VIDEO, null, "Uses less memory", "Makes Minecraft use a lot less memory.", "memory ram", null);
        add("krypton", ModEntry.Group.VIDEO, null, "Lighter networking", "Makes Minecraft's networking faster and lighter.", "network networking", null);
        add("fastipping", ModEntry.Group.VIDEO, "Fast IP Ping", "Faster server list pings",
                "Servers added by their IP address show up in the server list straight away.", "server list ping ip", null);
        add("textlayoutcache", ModEntry.Group.VIDEO, null, "Faster scoreboard and tab list",
                "Remembers how text is laid out, so the scoreboard, tab list and HUDs draw faster.", "text scoreboard tab list hud", null,
                "Remembers how each line of text is laid out, so the scoreboard, tab list and HUD text aren't worked out again every frame.",
                "It changes nothing on screen; /textcache shows how often it helps.");

        // Extras.
        add("cubeutilityclient", ModEntry.Group.EXTRAS, null, "This menu and the client's look",
                "The client itself: this menu, the space title screen, the menus' style and the dark window.", "client menu key title screen look", null,
                "This menu, the space title screen, the client's style in every Minecraft menu, and the dark game window with the client's name and icon.");
        add("cwb", ModEntry.Group.EXTRAS, "Cubes Without Borders", "Borderless fullscreen",
                "Borderless fullscreen that's quick to switch in and out of.", "fullscreen borderless window", null);
        add("noxesium", ModEntry.Group.EXTRAS, null, "Server extras and HUD sizes",
                "Extras servers can switch on, plus sizes and positions for the boss bar, scoreboard and more.", "server boss bar scoreboard map sizes positions",
                null);
        add("variants-cit", ModEntry.Group.EXTRAS, "Variants CIT", "Custom item textures",
                "Shows the custom item textures of resource packs made for it.", "textures items resource packs cit", null);
        add("resourcepackchecker", ModEntry.Group.EXTRAS, "Server Resource Pack Checker", "Quicker server resource packs",
                "Loads a server's resource pack only when it's actually needed, so joining is quicker.", "resource pack server", null);

        // Tools.
        add("kbdelay", ModEntry.Group.TOOLS, null, "How late knockback lands",
                "Measures how long each opponent's knockback takes to show up after you hit them.", "knockback kb delay ping pvp", null,
                "Times how long each opponent's knockback takes to show up after your hit: a live read on their real connection delay.",
                "Read-only: it only looks at packets your game already gets, and changes nothing you send.");
        add("packetlogger", ModEntry.Group.TOOLS, null, "Logs network packets",
                "Logs the network packets you pick, for tracking down problems.", "packets network log debug", null,
                "Logs the network packets you pick, to chat or to log files that never grow too big.",
                "Press F6 in the game for the packet picker, or type /packetlogger.");
        add("assistantbridge", ModEntry.Group.TOOLS, null, "A link to your AI assistant",
                "Lets an AI assistant on this PC read performance numbers and try video settings.", "assistant ai bridge stats", null,
                "A link between this game and an AI assistant on the same PC: live performance numbers, video settings it can change, and timed A/B tests.",
                "It only listens on this PC (127.0.0.1), needs a secret token, sends nothing to servers and never touches your controls.");

        // Libraries.
        add("fabric-api", ModEntry.Group.LIBRARIES, null, "Lets mods hook into the game", "The toolkit almost every Fabric mod is built on.", "", null);
        add("cloth-config", ModEntry.Group.LIBRARIES, "Cloth Config API", "Settings screens for mods", "Builds settings screens for other mods.", "", null);
        add("yet_another_config_lib_v3", ModEntry.Group.LIBRARIES, "YetAnotherConfigLib", "Settings screens for mods", "Builds settings screens for other mods.",
                "yacl", null);
        add("modmenu", ModEntry.Group.LIBRARIES, null, "Gives mods a settings button",
                "Gives every mod a settings button; this menu uses those to show their settings.", "", null);
        add("placeholder-api", ModEntry.Group.LIBRARIES, "Text Placeholder API", "Text formatting for mods", "Text formatting that other mods use.", "", null);
        add("fabric-language-kotlin", ModEntry.Group.LIBRARIES, null, "Runs mods written in Kotlin", "Lets mods written in Kotlin run.", "", null);
    }
}
