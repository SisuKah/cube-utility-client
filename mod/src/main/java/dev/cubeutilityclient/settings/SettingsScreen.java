package dev.cubeutilityclient.settings;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cubeutilityclient.CubeUtilityClient;
import dev.cubeutilityclient.render.Fonts;
import dev.cubeutilityclient.render.LineIcon;
import dev.cubeutilityclient.render.Paint;
import dev.cubeutilityclient.render.QuadBatch;
import dev.cubeutilityclient.render.Rgb;
import dev.cubeutilityclient.render.SpaceTextures;
import dev.cubeutilityclient.screen.IconButton;
import dev.cubeutilityclient.settings.model.ModPage;
import dev.cubeutilityclient.settings.model.Models;
import dev.cubeutilityclient.settings.model.Setting;
import dev.cubeutilityclient.settings.model.SettingsModel;
import dev.cubeutilityclient.shelf.Shelf;
import dev.cubeutilityclient.shelf.ShelfMod;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/**
 * The Mods page, opened with Right Shift and with Mods on the title screen: every installed mod
 * on the left under its own name, with a line saying what it does, grouped by what it's for.
 * Picking one shows, on the right, the same page for every mod: what it does, then its settings
 * as switches, sliders and choices drawn by the page itself, whichever library the mod built them
 * with. A mod whose settings the page can't read gets a button to its own screen; a mod with
 * nothing to set up says so. Changes save as they're made, through the mod's own saving.
 *
 * <p>At the top of the list, Get more offers popular mods that come with the client asleep
 * ({@link ShelfPanel}): one click switches a mod on, with no restart, and it gets a row of its
 * own under "Added mods". Under it, Browse Modrinth ({@link ModrinthPanel}) searches Modrinth and
 * adds any mod through the launcher, ready the next time the game starts.
 */
public final class SettingsScreen extends Screen {
    private static final int TEXT = 0xE9EFEE, TEXT_2 = 0x9AABA9, TEXT_3 = 0x7E8F8D, GOLD = 0xE4C084, GOLD_DIM = 0xCDA76A, TEAL = 0x46C2B8;
    /** The mods' settings are laid out at least this wide (GUI units) when the window allows. */
    private static final int CHILD_MIN_WIDTH = 316;
    /** Stand for Get more and Browse Modrinth where a tab's id goes. */
    private static final String SHELF = "get-more", MODRINTH = "modrinth";
    /** How long a tab that was just got glows in the list, in seconds. */
    private static final float ARRIVAL = 1.6f;
    private static final Identifier GLOW = SpaceTextures.texture("glow");
    /** The tab last looked at (or Get more), so the page opens where the player left it. */
    private static String remembered = "cubeutility";

    private final Screen parent;
    /** The installed mods, minus those still asleep in Get more. */
    private List<ModEntry> tabs = visibleTabs();
    private final List<Row> rows = new ArrayList<>();
    /** Get more, while it shows on the right instead of a tab's settings. */
    private ShelfPanel shelf;
    /** Browse Modrinth, likewise. */
    private ModrinthPanel modrinth;
    /** When the tab of each mod just got appeared in the list, for a short glow. */
    private final Map<String, Long> arrived = new HashMap<>();
    private final long opened = System.nanoTime();
    private long lastFrame = System.nanoTime();
    private ModEntry selected;
    /** The chosen mod's page: what it does and its settings, drawn by the page itself. */
    private OptionsPanel panel;
    /** A mod's settings screen is being read; if the page is set up again meanwhile, it switched away. */
    private boolean opening, reentered;
    /** The page whose mod's settings screen is being read right now, and what that screen switched to. */
    private static SettingsScreen adopting;
    /** While above zero, nothing may change the screen (a mod's screen closing quietly, to save). */
    private static int quiet;
    private Screen replacement;
    private IconButton close;
    private String query = "";
    private boolean searchFocused;
    /** The window's text input is on, for the search or a settings text field. */
    private boolean textInput;
    private long caretFrom = System.nanoTime();
    private float scroll, scrollGoal;

    // The layout, in screen pixels.
    private float g, s, px, py, pw, ph, pad, sideW, listTop, listBottom, rowH, headerH, contentX, bodyX, bodyY, bodyW, bodyH;
    private float searchX, searchY, searchW, searchH;

    /** One line of the list: a tab, a group's heading, Get more or Browse Modrinth. */
    private record Row(ModEntry tab, ModEntry.Group header, boolean shelf, boolean modrinth) {
    }

    public SettingsScreen(Screen parent) {
        super(Component.literal("Mods"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ layout

    private void layout() {
        this.g = this.minecraft.getWindow().getGuiScale();
        float w = this.width * this.g, h = this.height * this.g;
        this.s = Math.min(w / 1280f, h / 720f);
        float margin = Math.round(Math.max(12f, 28f * this.s));
        this.pad = Math.round(Math.max(14f, 24f * this.s));
        this.sideW = Math.round(Math.max(214f, Math.min(330f, 280f * this.s)));
        // Mods lay their settings out for a certain width: in small windows the page gives up
        // some of its own margins first.
        float need = CHILD_MIN_WIDTH * this.g;
        if (w - 2f * margin - this.sideW - 2f * this.pad < need) {
            margin = 8f;
            this.pad = 10f;
            this.sideW = Math.round(Math.max(176f, Math.min(this.sideW, w - 2f * margin - 2f * this.pad - need)));
        }
        this.px = margin;
        this.py = margin;
        this.pw = w - 2f * margin;
        this.ph = h - 2f * margin;
        this.searchH = Math.round(Math.max(28f, 38f * this.s));
        this.searchX = this.px + this.pad;
        this.searchW = this.sideW - 2f * this.pad;
        this.searchY = Math.round(this.py + this.pad + Math.max(46f, 74f * this.s));
        this.listTop = this.searchY + this.searchH + Math.round(Math.max(6f, 12f * this.s));
        this.listBottom = this.py + this.ph - this.pad * 0.6f;
        this.rowH = Math.round(Math.max(38f, 52f * this.s));
        this.headerH = Math.round(Math.max(22f, 30f * this.s));
        this.contentX = this.px + this.sideW;
        float iconSize = Math.round(Math.max(36f, 56f * this.s));
        this.bodyX = this.contentX + this.pad;
        this.bodyY = Math.round(this.py + this.pad + iconSize + Math.max(14f, 22f * this.s));
        this.bodyW = this.px + this.pw - this.pad - this.bodyX;
        this.bodyH = this.py + this.ph - this.pad * 0.6f - this.bodyY;
    }

    @Override
    protected void init() {
        this.layout();
        float size = Math.round(Math.max(30f, 38f * this.s));
        this.close = this.addRenderableWidget(new IconButton(LineIcon.CLOSE, Component.literal("Close"), this::onClose));
        this.close.place(Math.round(this.px + this.pw - this.pad - size), Math.round(this.py + this.pad), size, Math.round(size * 0.42f), this.g);
        this.filter();
        if (this.opening) {
            // A mod's settings switched straight back to this page while opening (BadOptimizations
            // opens a text file this way). Showing it again would only repeat that.
            this.reentered = true;
        } else if (this.shelf != null) {
            this.placeShelf();
        } else if (this.modrinth != null) {
            this.placeModrinth();
        } else if (this.selected == null) {
            if (SHELF.equals(remembered) && !Shelf.mods().isEmpty()) {
                this.openShelf();
            } else if (MODRINTH.equals(remembered)) {
                this.openModrinth();
            } else {
                this.select(this.tabs.stream().filter(m -> m.id.equals(remembered)).findFirst().orElse(this.tabs.isEmpty() ? null : this.tabs.get(0)));
            }
        } else if (this.panel == null) {
            // Back from the mod's own screen (its Done or Cancel): show its page fresh.
            this.openChild();
        } else {
            this.placePanel();
        }
    }

    private void filter() {
        String words = this.query.trim().toLowerCase(Locale.ROOT);
        this.rows.clear();
        if (shelfMatches(words)) {
            this.rows.add(new Row(null, null, true, false));
        }
        if (modrinthMatches(words)) {
            this.rows.add(new Row(null, null, false, true));
        }
        for (ModEntry.Group group : ModEntry.Group.values()) {
            List<ModEntry> found = this.tabs.stream().filter(m -> m.group == group && m.matches(words)).toList();
            if (!found.isEmpty()) {
                this.rows.add(new Row(null, group, false, false));
                found.forEach(m -> this.rows.add(new Row(m, null, false, false)));
            }
        }
    }

    /** Browse Modrinth shows in the list unless a search is clearly about something else. */
    private static boolean modrinthMatches(String words) {
        String all = "browse modrinth search find download install add more mods";
        for (String word : words.split("\\s+")) {
            if (!word.isEmpty() && !all.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private static List<ModEntry> visibleTabs() {
        return ModEntry.all().stream().filter(tab -> !Shelf.asleep(tab.id)).toList();
    }

    /** Get more shows in the list unless a search matches neither it nor a mod still to get. */
    private static boolean shelfMatches(String words) {
        if (Shelf.mods().isEmpty()) {
            return false;
        }
        if (words.isEmpty()) {
            return true;
        }
        StringBuilder text = new StringBuilder("get more mods add install new");
        for (ShelfMod mod : Shelf.mods()) {
            if (!Shelf.isOn(mod)) {
                text.append(' ').append(mod.title).append(' ').append(mod.about).append(' ').append(mod.id);
            }
        }
        String all = text.toString().toLowerCase(Locale.ROOT);
        for (String word : words.split("\\s+")) {
            if (!all.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /** Shows Get more on the right. Returns it, so a test can press Get. */
    ShelfPanel openShelf() {
        if (this.shelf == null) {
            this.closeChild();
            this.selected = null;
            remembered = SHELF;
            this.shelf = new ShelfPanel(this);
            this.placeShelf();
        }
        return this.shelf;
    }

    private void placeShelf() {
        if (this.shelf != null) {
            this.shelf.place(this.bodyX, this.bodyY, this.bodyW, this.bodyH, this.s, this.g);
        }
    }

    /** Shows Browse Modrinth on the right, its search ready for typing. */
    void openModrinth() {
        if (this.modrinth == null) {
            this.closeChild();
            this.selected = null;
            remembered = MODRINTH;
            this.setSearchFocused(false);
            this.modrinth = new ModrinthPanel();
            this.placeModrinth();
            this.updateTextInput();
        }
    }

    private void placeModrinth() {
        if (this.modrinth != null) {
            this.modrinth.place(this.bodyX, this.bodyY, this.bodyW, this.bodyH, this.s, this.g);
        }
    }

    /** A mod was got or removed in Get more: its tab comes or goes. */
    void shelfChanged(ShelfMod mod, boolean on) {
        if (on) {
            this.arrived.put(mod.id, System.nanoTime());
        } else {
            this.arrived.remove(mod.id);
        }
        this.tabs = visibleTabs();
        this.filter();
    }

    boolean hasTab(String id) {
        for (ModEntry tab : this.tabs) {
            if (tab.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    void openTab(String id) {
        this.tabs.stream().filter(tab -> tab.id.equals(id)).findFirst().ifPresent(this::select);
    }

    private void select(ModEntry tab) {
        if (tab == this.selected && (this.panel != null || this.opening)) {
            return;
        }
        this.closeChild();
        this.selected = tab;
        if (tab != null) {
            remembered = tab.id;
            this.openChild();
            this.revealSelected();
        }
    }

    /** The mod picked, by id, or null when it isn't listed. */
    private ModEntry tab(String id) {
        return this.tabs.stream().filter(tab -> tab.id.equals(id)).findFirst().orElse(null);
    }

    /**
     * Builds the chosen mod's page: what it does, then its settings when the page can read them,
     * or else a row saying where they are (its own screen, another mod's page) or that there's
     * nothing to set up.
     */
    private void openChild() {
        this.panel = null;
        if (this.selected == null) {
            return;
        }
        ModEntry mod = this.selected;
        SettingsModel settings = null;
        List<Setting> instead = new ArrayList<>();
        ModEntry home = mod.settingsIn == null ? null : this.tab(mod.settingsIn);
        if (home != null) {
            instead.add(new Setting.Action(home.title + "'s page", mod.title + "'s settings are on " + home.title + "'s page, with the rest of them.", null, "OPEN",
                    () -> this.openTab(home.id)));
        } else if (mod.isLibrary()) {
            instead.add(new Setting.Note("Nothing to set up: other mods use it to run."));
        } else {
            settings = Models.forMod(mod.id, mod.others(this.tabs), () -> this, this::openFullScreen);
            if (settings == null && mod.hasSettingsScreen() && !mod.opensElsewhere()) {
                settings = this.readScreen(mod);
            }
            if (settings == null) {
                instead.add(mod.hasSettingsScreen() ? this.ownScreenRow(mod) : new Setting.Note("Nothing to set up: it works on its own."));
            }
        }
        this.showPanel(ModPage.of(mod.about, settings, instead));
    }

    /** A row with a button to a mod's own settings screen, for settings the page can't draw. */
    private Setting ownScreenRow(ModEntry mod) {
        if (mod.opensElsewhere()) {
            return new Setting.Action("Its settings file", mod.title + " keeps its settings in a text file. Open it to change them; they take effect the next "
                    + "time Minecraft starts.", null, "OPEN", this::openFullScreen);
        }
        return new Setting.Action("Its own settings screen", mod.title + " keeps its settings on a screen of its own. When you close it, you're back here.",
                null, "OPEN", this::openFullScreen);
    }

    /**
     * Reads a mod's settings from its own settings screen, set up out of sight, when it's built
     * with a library the page knows. A switch to another screen meanwhile (Reese's Sodium Options
     * swaps its own in for Sodium's) is read instead, and doesn't take over the window.
     */
    private SettingsModel readScreen(ModEntry mod) {
        this.opening = true;
        this.reentered = false;
        this.replacement = null;
        adopting = this;
        SettingsModel model = null;
        try {
            Screen screen = mod.settings(this);
            if (screen != null) {
                model = Models.fromScreen(screen, SettingsScreen::quietly);
            }
            for (int hops = 0; hops < 3 && model == null && this.replacement != null && !this.reentered; hops++) {
                Screen next = this.replacement;
                this.replacement = null;
                model = Models.fromScreen(next, SettingsScreen::quietly);
            }
        } catch (Throwable error) {
            CubeUtilityClient.LOGGER.warn("{}'s settings couldn't be read", mod.id, error);
            model = null;
        } finally {
            this.opening = false;
            adopting = null;
            this.replacement = null;
        }
        if (this.reentered) {
            // It switched straight back to this page while opening (BadOptimizations opens a text file this way).
            mod.markOpensElsewhere();
            return null;
        }
        return model;
    }

    /**
     * Called for every screen change: while a mod's settings screen is being read, a switch to
     * another screen is kept here to read instead of taking over the window, and while a mod's
     * screen closes quietly every switch is held back. Returns whether it was held back.
     */
    public static boolean adopt(Screen screen) {
        if (quiet > 0) {
            return true;
        }
        SettingsScreen page = adopting;
        if (page == null) {
            return false;
        }
        if (screen == null) {
            // Closing every screen while a mod's settings are only being read: held back.
            return true;
        }
        if (screen == page || screen == page.parent || screen instanceof SettingsScreen) {
            return false;
        }
        page.replacement = screen;
        return true;
    }

    /** Runs something that would change the screen (a mod's screen closing, to save) without it doing so. */
    static void quietly(Runnable action) {
        quiet++;
        try {
            action.run();
        } finally {
            quiet--;
        }
    }

    private void showPanel(SettingsModel model) {
        this.panel = new OptionsPanel(model, this.selected.id, this::openFullScreen);
        this.placePanel();
    }

    private void placePanel() {
        if (this.panel != null) {
            this.panel.place(this.bodyX, this.bodyY, this.bodyW, this.bodyH, this.s, this.g);
        }
    }

    /** Opens the mod's settings the ordinary way, on the whole window; closing them comes back here. */
    private void openFullScreen() {
        if (this.selected == null) {
            return;
        }
        Screen screen = this.selected.settings(this);
        if (screen != null) {
            this.minecraft.gui.setScreen(screen);
        }
    }

    /** Lets the mod's page save what it keeps, and lets go of it (or of Get more, or Browse Modrinth). */
    private void closeChild() {
        this.shelf = null;
        if (this.modrinth != null) {
            this.modrinth = null;
            this.updateTextInput();
        }
        if (this.panel != null) {
            OptionsPanel leaving = this.panel;
            this.panel = null;
            leaving.close();
            this.updateTextInput();
        }
    }

    @Override
    public void removed() {
        this.closeChild();
        this.setSearchFocused(false);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    /** The page's own drawing of a mod's settings failed: a button to the mod's own screen instead. */
    private void dropPanel(Throwable error) {
        CubeUtilityClient.LOGGER.warn("{}'s settings couldn't be drawn by the page", this.selected == null ? "?" : this.selected.id, error);
        this.panel = null;
        this.updateTextInput();
        if (this.selected != null) {
            List<Setting> instead = List.of(this.selected.hasSettingsScreen() ? this.ownScreenRow(this.selected) : new Setting.Note("Nothing to set up: it works on its own."));
            this.showPanel(ModPage.of(this.selected.about, null, instead));
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        Paint paint = new Paint(graphics);
        float k = this.appear();
        QuadBatch panel = paint.solid();
        Paint.roundedRect(panel, this.px, this.py, this.pw, this.ph, 22f * this.s, 1f, Rgb.alpha(0x060B0C, 0.86f * k), Rgb.alpha(0xFFFFFF, 0.08f * k));
        // The sidebar's own shade and the hairline between it and the settings.
        panel.rect(this.px + 1f, this.py + 1f, this.contentX, this.py + this.ph - 1f, Rgb.alpha(0xFFFFFF, 0.018f * k));
        panel.rect(this.contentX, this.py + 1f, this.contentX + 1f, this.py + this.ph - 1f, Rgb.alpha(0xFFFFFF, 0.07f * k));
        // A faint gold glow along the top edge.
        paint.texture(SpaceTextures.HAZE, true).rect(this.px + this.pw * 0.18f, this.py - 120f * this.s, this.px + this.pw * 0.82f, this.py + 90f * this.s, Rgb.alpha(GOLD, 0.06f * k));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - this.lastFrame) / 1e9f);
        this.lastFrame = now;
        this.scroll += (this.scrollGoal - this.scroll) * Math.min(1f, dt * 14f);

        Paint paint = new Paint(graphics);
        float k = this.appear();
        float mx = mouseX * this.g, my = mouseY * this.g;
        this.extractSidebar(graphics, paint, mx, my, k);
        this.extractHeader(paint, k);
        if (this.shelf != null) {
            try {
                this.shelf.extract(graphics, paint, mx, my, k);
            } catch (Throwable error) {
                CubeUtilityClient.LOGGER.warn("Get more couldn't be drawn", error);
                this.shelf = null;
            }
        } else if (this.modrinth != null) {
            try {
                this.modrinth.extract(graphics, paint, mx, my, k);
            } catch (Throwable error) {
                CubeUtilityClient.LOGGER.warn("Browse Modrinth couldn't be drawn", error);
                this.modrinth = null;
                this.updateTextInput();
            }
        } else if (this.panel != null) {
            try {
                this.panel.extract(graphics, paint, mx, my, k);
            } catch (Throwable error) {
                this.dropPanel(error);
            }
        } else {
            this.extractMessage(paint, k);
        }
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    private void extractSidebar(GuiGraphicsExtractor graphics, Paint paint, float mx, float my, float k) {
        float x = this.px + this.pad;
        FontDescription brand = Fonts.strong(Math.max(9f, 11f * this.s));
        paint.text("CUBE UTILITY CLIENT", brand, x, Math.round(this.py + this.pad + Math.max(9f, 11f * this.s)), Rgb.alpha(GOLD, k), Math.max(1.5f, 2.2f * this.s));
        FontDescription title = Fonts.title(Math.max(32f, 38f * this.s));
        float titleBase = Math.round(this.searchY - Math.max(10f, 16f * this.s));
        paint.text("Mods", title, x, titleBase, Rgb.alpha(TEXT, k), 0f);
        // How many are installed, next to the title (mods still asleep in Get more aren't counted).
        FontDescription small = Fonts.label(Math.max(10f, 12f * this.s));
        String count = this.tabs.size() + " installed";
        paint.text(count, small, Math.round(this.px + this.sideW - this.pad - Paint.measure(count, small, 0f)), titleBase, Rgb.alpha(TEXT_3, k), 0f);
        this.extractSearch(paint, k);

        // The tabs, clipped to their area and scrolled.
        float contentHeight = 0f;
        for (Row row : this.rows) {
            contentHeight += this.height(row);
        }
        float maxScroll = Math.max(0f, contentHeight - (this.listBottom - this.listTop));
        this.scrollGoal = Math.max(0f, Math.min(maxScroll, this.scrollGoal));
        this.scroll = Math.max(0f, Math.min(maxScroll, this.scroll));
        graphics.enableScissor((int) Math.floor(this.px / this.g), (int) Math.floor(this.listTop / this.g), (int) Math.ceil(this.contentX / this.g), (int) Math.ceil(this.listBottom / this.g));
        QuadBatch rowsBatch = paint.solid();
        float y = this.listTop - this.scroll;
        FontDescription groupFont = Fonts.strong(Math.max(9f, 10f * this.s));
        FontDescription nameFont = Fonts.label(Math.max(10f, 14f * this.s));
        FontDescription blurbFont = Fonts.label(Math.max(9f, 11f * this.s));
        float rowLeft = this.px + this.pad * 0.55f, rowRight = this.contentX - this.pad * 0.55f;
        for (Row row : this.rows) {
            if (row.header != null) {
                if (y + this.headerH > this.listTop && y < this.listBottom) {
                    paint.text(row.header.label, groupFont, this.px + this.pad, Math.round(y + this.headerH - Math.max(7f, 9f * this.s)), Rgb.alpha(GOLD_DIM, 0.85f * k), Math.max(1.2f, 1.8f * this.s));
                }
                y += this.headerH;
                continue;
            }
            if (y + this.rowH > this.listTop && y < this.listBottom) {
                boolean chosen = row.shelf ? this.shelf != null : row.modrinth ? this.modrinth != null : row.tab == this.selected;
                boolean hover = !chosen && mx >= rowLeft && mx < rowRight && my >= Math.max(y, this.listTop) && my < Math.min(y + this.rowH, this.listBottom);
                if (chosen || hover) {
                    Paint.roundedRect(rowsBatch, rowLeft, y + 1f, rowRight - rowLeft, this.rowH - 2f, 11f * this.s, chosen ? 1f : 0f,
                            Rgb.alpha(0xFFFFFF, (chosen ? 0.07f : 0.035f) * k), Rgb.alpha(GOLD, 0.28f * k));
                }
                // A tab that just came from Get more glows gold for a moment.
                Long at = row.tab == null ? null : this.arrived.get(row.tab.id);
                float glow = at == null ? 0f : Math.max(0f, 1f - (System.nanoTime() - at) / (ARRIVAL * 1e9f));
                if (glow > 0f) {
                    Paint.roundedRect(rowsBatch, rowLeft, y + 1f, rowRight - rowLeft, this.rowH - 2f, 11f * this.s, 1f, Rgb.alpha(GOLD, 0.14f * glow * k), Rgb.alpha(GOLD, 0.6f * glow * k));
                }
                float icon = Math.round(Math.max(20f, 26f * this.s));
                float ix = Math.round(this.px + this.pad), iy = Math.round(y + (this.rowH - icon) / 2f);
                float textX = ix + icon + Math.round(Math.max(9f, 12f * this.s));
                float maxW = rowRight - textX - 6f;
                String name, blurb;
                if (row.shelf) {
                    int left = Shelf.available();
                    this.extractShelfIcon(paint, ix, iy, icon, left > 0, k);
                    name = "Get more";
                    blurb = left > 0 ? "Popular mods, on in one click." : "Every mod here is on.";
                    if (left > 0) {
                        maxW -= this.extractBadge(paint, String.valueOf(left), rowRight - Math.round(this.pad * 0.45f), y + this.rowH / 2f, k);
                    }
                } else if (row.modrinth) {
                    this.extractModrinthIcon(paint, ix, iy, icon, k);
                    name = "Browse Modrinth";
                    blurb = "Add any mod from Modrinth.";
                } else {
                    this.extractIcon(paint, row.tab, ix, iy, icon, k);
                    name = row.tab.title;
                    blurb = row.tab.line;
                }
                float nameSize = Fonts.size(nameFont), blurbSize = Fonts.size(blurbFont);
                float gap = Math.max(4f, 5f * this.s);
                float top = y + (this.rowH - (nameSize + gap + blurbSize)) / 2f;
                paint.text(fit(name, nameFont, maxW), nameFont, textX, Math.round(top + nameSize * 0.8f), Rgb.alpha(chosen ? GOLD : TEXT, k), 0f);
                paint.text(fit(blurb, blurbFont, maxW), blurbFont, textX, Math.round(top + nameSize + gap + blurbSize * 0.8f), Rgb.alpha(TEXT_3, k), 0f);
            }
            y += this.rowH;
        }
        if (this.rows.isEmpty()) {
            paint.text("Nothing matches.", blurbFont, this.px + this.pad, Math.round(this.listTop + this.headerH), Rgb.alpha(TEXT_3, k), 0f);
        }
        graphics.disableScissor();
        // A slim scroll thumb when the list is longer than its space.
        if (maxScroll > 0f) {
            float track = this.listBottom - this.listTop, thumb = Math.max(24f, track * track / contentHeight);
            float ty = this.listTop + (track - thumb) * (this.scroll / maxScroll);
            Paint.roundedRect(paint.solid(), this.contentX - 6f, ty, 3f, thumb, 1.5f, 0f, Rgb.alpha(GOLD, 0.35f * k), 0);
        }
    }

    private void extractSearch(Paint paint, float k) {
        QuadBatch pill = paint.solid();
        int border = this.searchFocused ? Rgb.alpha(GOLD, 0.6f * k) : Rgb.alpha(0xFFFFFF, 0.11f * k);
        Paint.roundedRect(pill, this.searchX, this.searchY, this.searchW, this.searchH, this.searchH / 2f, 1f, Rgb.alpha(0xFFFFFF, 0.045f * k), border);
        float lens = Math.round(this.searchH * 0.42f);
        Paint.icon(pill, LineIcon.SEARCH, Math.round(this.searchX + this.searchH * 0.38f), Math.round(this.searchY + (this.searchH - lens) / 2f), lens, Rgb.alpha(this.searchFocused ? GOLD : TEXT_3, k));
        FontDescription font = Fonts.label(Math.max(10f, 13f * this.s));
        float textX = Math.round(this.searchX + this.searchH * 0.95f), baseline = Math.round(this.searchY + this.searchH / 2f + Fonts.size(font) * 0.36f);
        float maxW = this.searchX + this.searchW - this.searchH * 0.45f - textX;
        if (this.query.isEmpty()) {
            paint.text("Search", font, textX, baseline, Rgb.alpha(TEXT_3, k), 0f);
        } else {
            // Long searches show their end, where the typing is.
            String shown = this.query;
            while (shown.length() > 1 && Paint.measure(shown, font, 0f) > maxW) {
                shown = shown.substring(1);
            }
            paint.text(shown, font, textX, baseline, Rgb.alpha(TEXT, k), 0f);
            textX += Paint.measure(shown, font, 0f);
        }
        if (this.searchFocused && ((System.nanoTime() - this.caretFrom) / 530_000_000L) % 2 == 0) {
            float size = Fonts.size(font);
            pill.rect(Math.round(textX + 1f), Math.round(baseline - size * 0.82f), Math.round(textX + 1f) + Math.max(1f, Math.round(this.s)), Math.round(baseline + size * 0.18f), Rgb.alpha(GOLD, k));
        }
    }

    private void extractIcon(Paint paint, ModEntry tab, float x, float y, float size, float k) {
        Identifier icon = tab.icon();
        if (icon != null) {
            paint.texture(icon, false).rect(x, y, x + size, y + size, Rgb.alpha(0xFFFFFF, k));
            return;
        }
        boolean own = tab.group == ModEntry.Group.CUBECRAFT;
        Paint.roundedRect(paint.solid(), x, y, size, size, size * 0.26f, 0f, Rgb.alpha(own ? GOLD : TEAL, (own ? 0.16f : 0.13f) * k), 0);
        FontDescription font = Fonts.strong(Math.max(8f, size * 0.38f));
        String letters = monogram(tab.title);
        float tw = Paint.measure(letters, font, 0f);
        paint.text(letters, font, Math.round(x + (size - tw) / 2f), Math.round(y + size / 2f + Fonts.size(font) * 0.36f), Rgb.alpha(own ? GOLD : TEAL, k), 0f);
    }

    /** Get more's icon: a gold sparkle on a gold tile, glowing softly while there are mods to get. */
    private void extractShelfIcon(Paint paint, float x, float y, float size, boolean twinkle, float k) {
        if (twinkle) {
            float t = (System.nanoTime() - this.opened) / 1e9f;
            float breathe = 0.5f + 0.5f * (float) Math.sin(t * Math.PI * 2 / 2.4f);
            float r = size * 0.95f;
            paint.texture(GLOW, true).rect(x + size / 2f - r, y + size / 2f - r, x + size / 2f + r, y + size / 2f + r, Rgb.alpha(GOLD, (0.10f + 0.12f * breathe) * k));
        }
        QuadBatch tile = paint.solid();
        Paint.roundedRect(tile, x, y, size, size, size * 0.26f, 0f, Rgb.alpha(GOLD, 0.16f * k), 0);
        float mark = Math.round(size * 0.66f);
        Paint.icon(tile, LineIcon.SPARKLE, Math.round(x + (size - mark) / 2f), Math.round(y + (size - mark) / 2f), mark, Rgb.alpha(GOLD, k));
    }

    /** Browse Modrinth's icon: a search mark on a teal tile. */
    private void extractModrinthIcon(Paint paint, float x, float y, float size, float k) {
        QuadBatch tile = paint.solid();
        Paint.roundedRect(tile, x, y, size, size, size * 0.26f, 0f, Rgb.alpha(TEAL, 0.16f * k), 0);
        float mark = Math.round(size * 0.56f);
        Paint.icon(tile, LineIcon.SEARCH, Math.round(x + (size - mark) / 2f), Math.round(y + (size - mark) / 2f), mark, Rgb.alpha(TEAL, k));
    }

    /** A small gold count, its right edge at {@code right}, centred on {@code cy}. Returns the room it takes. */
    private float extractBadge(Paint paint, String text, float right, float cy, float k) {
        FontDescription font = Fonts.strong(Math.max(9f, 10f * this.s));
        float h = Math.round(Math.max(16f, 20f * this.s));
        float w = Math.round(Math.max(h, Paint.measure(text, font, 0f) + h * 0.8f));
        float x = right - w, y = Math.round(cy - h / 2f);
        Paint.capsule(paint.solid(), x, y, w, h, 0f, (px, py) -> Rgb.alpha(GOLD, 0.9f * k), 0, false);
        paint.text(text, font, Math.round(x + (w - Paint.measure(text, font, 0f)) / 2f), Math.round(y + h / 2f + Fonts.size(font) * 0.36f), Rgb.alpha(0x15100A, k), 0f);
        return w + 6f;
    }

    private void extractHeader(Paint paint, float k) {
        float icon = Math.round(Math.max(36f, 56f * this.s));
        float x = Math.round(this.contentX + this.pad), y = Math.round(this.py + this.pad);
        String title, blurb, credit;
        if (this.shelf != null) {
            this.extractShelfIcon(paint, x, y, icon, false, k);
            title = "Get more";
            blurb = "Popular mods that come with the client. Get one and it's on straight away.";
            credit = "No download and no restart. Each mod you get joins the list, under Added mods.";
        } else if (this.modrinth != null) {
            this.extractModrinthIcon(paint, x, y, icon, k);
            title = "Browse Modrinth";
            blurb = "Any mod for this Minecraft, from Modrinth. Add one and it's ready the next time you start the game.";
            credit = "Added through the launcher, with the libraries a mod needs. They show in its Mods screen too.";
        } else if (this.selected != null) {
            ModEntry tab = this.selected;
            this.extractIcon(paint, tab, x, y, icon, k);
            title = tab.title;
            blurb = tab.summary;
            // Its version and who made it, in small print.
            credit = tab.credit;
        } else {
            return;
        }
        float textX = x + icon + Math.round(Math.max(12f, 18f * this.s));
        float textW = this.close.getX() * this.g - textX - this.pad;
        FontDescription name = Fonts.label(Math.max(14f, 24f * this.s));
        FontDescription body = Fonts.label(Math.max(10f, 13f * this.s));
        FontDescription small = Fonts.label(Math.max(9f, 11f * this.s));
        float nameBase = Math.round(y + Fonts.size(name) * 0.95f);
        paint.text(fit(title, name, textW), name, textX, nameBase, Rgb.alpha(TEXT, k), 0f);
        float blurbBase = Math.round(nameBase + Fonts.size(body) + Math.max(6f, 8f * this.s));
        paint.text(fit(blurb, body, textW), body, textX, blurbBase, Rgb.alpha(TEXT_2, k), 0f);
        paint.text(fit(credit, small, textW), small, textX, Math.round(blurbBase + Fonts.size(small) + Math.max(5f, 7f * this.s)), Rgb.alpha(TEXT_3, 0.8f * k), 0f);
        // The line between the tab's name and its settings.
        float line = this.bodyY - Math.round(Math.max(8f, 11f * this.s));
        paint.solid().rect(this.contentX + 1f, line, this.px + this.pw - 1f, line + 1f, Rgb.alpha(0xFFFFFF, 0.06f * k));
    }

    /** What the right side shows with no mod picked. */
    private void extractMessage(Paint paint, float k) {
        String first = this.tabs.isEmpty() ? "No mods are installed." : "Pick a mod on the left.";
        FontDescription big = Fonts.label(Math.max(12f, 16f * this.s));
        paint.text(first, big, Math.round(this.bodyX + (this.bodyW - Paint.measure(first, big, 0f)) / 2f), Math.round(this.bodyY + this.bodyH * 0.38f), Rgb.alpha(TEXT, k), 0f);
    }

    /** How far the page is through its quick fade-in. */
    private float appear() {
        float t = (System.nanoTime() - this.opened) / 1e9f;
        float k = Math.max(0f, Math.min(1f, t / 0.25f));
        return 1f - (1f - k) * (1f - k);
    }

    static String fit(String text, FontDescription font, float maxWidth) {
        if (maxWidth <= 0f || Paint.measure(text, font, 0f) <= maxWidth) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && Paint.measure(cut + "…", font, 0f) > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut.stripTrailing() + "…";
    }

    /** Two letters for a tab without an icon: "Packet logger" → "PL". */
    static String monogram(String name) {
        List<String> parts = new ArrayList<>();
        for (String word : name.split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) {
                parts.add(word);
            }
        }
        if (parts.size() >= 2) {
            return (parts.get(0).substring(0, 1) + parts.get(1).substring(0, 1)).toUpperCase(Locale.ROOT);
        }
        String one = parts.isEmpty() ? "?" : parts.get(0);
        return one.substring(0, Math.min(2, one.length())).toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ input

    private void setSearchFocused(boolean focused) {
        if (this.searchFocused != focused) {
            this.searchFocused = focused;
            this.caretFrom = System.nanoTime();
            if (focused && this.panel != null) {
                this.panel.commitEdit();
            }
            if (focused && this.modrinth != null) {
                this.modrinth.blur();
            }
            this.updateTextInput();
        }
    }

    /** Starts or stops the window's text input, as Minecraft's own text boxes do. */
    private void updateTextInput() {
        boolean wanted = this.searchFocused || this.panel != null && this.panel.isTyping() || this.modrinth != null && this.modrinth.isTyping();
        if (wanted != this.textInput) {
            this.textInput = wanted;
            this.minecraft.onTextInputFocusChange(this, wanted);
        }
    }

    private void setQuery(String query) {
        this.query = query;
        this.caretFrom = System.nanoTime();
        this.scrollGoal = 0f;
        this.filter();
    }

    private float height(Row row) {
        return row.header != null ? this.headerH : this.rowH;
    }

    private Row rowAt(double mx, double my) {
        if (mx < this.px || mx >= this.contentX || my < this.listTop || my >= this.listBottom) {
            return null;
        }
        float y = this.listTop - this.scroll;
        for (Row row : this.rows) {
            float h = this.height(row);
            if (my >= y && my < y + h) {
                return row;
            }
            y += h;
        }
        return null;
    }

    private void revealSelected() {
        float y = 0f;
        for (Row row : this.rows) {
            if (row.header == null && (row.shelf ? this.shelf != null : row.modrinth ? this.modrinth != null : row.tab == this.selected)) {
                float view = this.listBottom - this.listTop;
                if (y < this.scrollGoal) {
                    this.scrollGoal = Math.max(0f, y - this.headerH);
                } else if (y + this.rowH > this.scrollGoal + view) {
                    this.scrollGoal = y + this.rowH - view;
                }
                return;
            }
            y += this.height(row);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x() * this.g, my = event.y() * this.g;
        boolean onSearch = mx >= this.searchX && mx < this.searchX + this.searchW && my >= this.searchY && my < this.searchY + this.searchH;
        this.setSearchFocused(onSearch);
        if (onSearch) {
            return true;
        }
        if (this.shelf != null && this.shelf.contains(mx, my) && !this.close.isMouseOver(event.x(), event.y())) {
            this.setFocused(null);
            return this.shelf.mouseClicked(mx, my);
        }
        if (this.modrinth != null) {
            if (this.modrinth.contains(mx, my) && !this.close.isMouseOver(event.x(), event.y())) {
                this.setFocused(null);
                boolean used = this.modrinth.mouseClicked(mx, my);
                this.updateTextInput();
                return used;
            }
            this.modrinth.blur();
            this.updateTextInput();
        }
        if (this.panel != null) {
            if (this.panel.contains(mx, my) && !this.close.isMouseOver(event.x(), event.y())) {
                this.setFocused(null);
                try {
                    boolean used = this.panel.mouseClicked(mx, my, event.button());
                    this.updateTextInput();
                    return used;
                } catch (Throwable error) {
                    this.dropPanel(error);
                    return true;
                }
            }
            this.panel.commitEdit();
            this.updateTextInput();
        }
        Row row = this.rowAt(mx, my);
        if (row != null && row.header == null) {
            this.setFocused(null);
            if (row.shelf) {
                this.openShelf();
            } else if (row.modrinth) {
                this.openModrinth();
                if (this.modrinth != null) {
                    this.modrinth.focus();
                    this.updateTextInput();
                }
            } else {
                this.select(row.tab);
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.panel != null && this.panel.mouseReleased()) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.panel != null && this.panel.mouseDragged(event.x() * this.g)) {
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        float mx = (float) (mouseX * this.g);
        if (mx >= this.px && mx < this.contentX) {
            this.scrollGoal -= (float) scrollY * this.rowH * 1.4f;
            return true;
        }
        if (this.shelf != null && this.shelf.contains(mx, mouseY * this.g)) {
            return this.shelf.mouseScrolled(scrollY);
        }
        if (this.modrinth != null && this.modrinth.contains(mx, mouseY * this.g)) {
            return this.modrinth.mouseScrolled(scrollY);
        }
        if (this.panel != null && this.panel.contains(mx, mouseY * this.g)) {
            return this.panel.mouseScrolled(scrollY);
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (this.panel != null && this.panel.isTyping()) {
            // Typing in one of the settings: Escape only stops typing, Right Shift stays a key.
            this.panel.keyPressed(event);
            this.updateTextInput();
            return true;
        }
        if (this.modrinth != null && this.modrinth.isTyping()) {
            // Typing a Modrinth search: Escape clears it or stops typing, Right Shift stays a key.
            this.modrinth.keyPressed(event);
            this.updateTextInput();
            return true;
        }
        if (event.isEscape()) {
            if (this.searchFocused && !this.query.isEmpty()) {
                this.setQuery("");
            } else {
                this.onClose();
            }
            return true;
        }
        if (!this.searchFocused && CubeUtilityClient.SETTINGS_KEY.matches(event)) {
            this.onClose();
            return true;
        }
        if (this.panel != null && !this.searchFocused) {
            // Left and Right switch between a mod's categories; Page Up and Down scroll.
            if (event.isLeft() || event.isRight()) {
                this.panel.stepPage(event.isLeft() ? -1 : 1);
                return true;
            }
            if (key == InputConstants.KEY_PAGEUP || key == InputConstants.KEY_PAGEDOWN) {
                this.panel.scrollView(key == InputConstants.KEY_PAGEDOWN ? 1 : -1);
                return true;
            }
        }
        if (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN) {
            // Get more and Browse Modrinth sit above the first mod.
            List<Row> stops = this.rows.stream().filter(row -> row.header == null).toList();
            boolean up = key == InputConstants.KEY_UP;
            int at = -1;
            for (int i = 0; i < stops.size(); i++) {
                Row row = stops.get(i);
                if (row.shelf ? this.shelf != null : row.modrinth ? this.modrinth != null : row.tab == this.selected) {
                    at = i;
                    break;
                }
            }
            if (!stops.isEmpty()) {
                Row next = stops.get(at < 0 ? 0 : Math.max(0, Math.min(stops.size() - 1, at + (up ? -1 : 1))));
                if (next.shelf) {
                    this.openShelf();
                } else if (next.modrinth) {
                    this.openModrinth();
                } else {
                    this.select(next.tab);
                }
                this.revealSelected();
            }
            return true;
        }
        if (this.searchFocused) {
            if (key == InputConstants.KEY_BACKSPACE && !this.query.isEmpty()) {
                boolean word = event.hasControlDown();
                String next = word ? this.query.replaceAll("\\S*\\s*$", "") : this.query.substring(0, this.query.offsetByCodePoints(this.query.length(), -1));
                this.setQuery(next);
            } else if (key == InputConstants.KEY_RETURN) {
                this.setSearchFocused(false);
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (this.panel != null && this.panel.charTyped(event)) {
            return true;
        }
        if (this.modrinth != null && this.modrinth.charTyped(event)) {
            return true;
        }
        if (this.searchFocused) {
            if (event.isAllowedChatCharacter() && this.query.length() < 40) {
                this.setQuery(this.query + event.codepointAsString());
            }
            return true;
        }
        return super.charTyped(event);
    }
}
