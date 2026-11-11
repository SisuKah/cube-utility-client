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
import dev.cubeutilityclient.screen.PillButton;
import dev.cubeutilityclient.settings.model.Models;
import dev.cubeutilityclient.settings.model.SettingsModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/**
 * One settings page for the whole client, opened with Right Shift (and from Mods). The left
 * lists tabs named for what they change, each with a line saying what it does. On the right,
 * mods built with a settings library (YACL, Cloth Config, Sodium's) or a plain settings file get
 * their settings drawn by the page itself, all alike; the others show their own settings screen
 * inside the page. Either way every option works and saves as the mod intends. Mods with
 * nothing to change don't get a tab.
 */
public final class SettingsScreen extends Screen {
    private static final int TEXT = 0xE9EFEE, TEXT_2 = 0x9AABA9, TEXT_3 = 0x7E8F8D, GOLD = 0xE4C084, GOLD_DIM = 0xCDA76A, TEAL = 0x46C2B8;
    /** Settings screens are laid out at least this wide (GUI units) when the window allows. */
    private static final int CHILD_MIN_WIDTH = 316;
    /** The tab last looked at, so the page opens where the player left it. */
    private static String remembered = "cubeutility";

    private final Screen parent;
    private final List<ModEntry> tabs = ModEntry.all();
    private final List<Row> rows = new ArrayList<>();
    private final long opened = System.nanoTime();
    private long lastFrame = System.nanoTime();
    private ModEntry selected;
    private EmbeddedScreen child;
    /** The chosen mod's settings, drawn by the page itself. */
    private OptionsPanel panel;
    /** The mouse went down inside the mod's settings, so drags and keys go there too. */
    private boolean childFocused;
    /** The chosen mod's settings failed to show inside the page. */
    private boolean failed;
    /** A settings screen is being opened; if the page is set up again meanwhile, it switched away. */
    private boolean opening, reentered;
    /** The page whose settings screen is opening right now, and what that screen switched to. */
    private static SettingsScreen adopting;
    private Screen replacement;
    private IconButton close;
    private PillButton openFull;
    private String query = "";
    private boolean searchFocused;
    /** The window's text input is on, for the search or a settings text field. */
    private boolean textInput;
    private long caretFrom = System.nanoTime();
    private float scroll, scrollGoal;

    // The layout, in screen pixels.
    private float g, s, px, py, pw, ph, pad, sideW, listTop, listBottom, rowH, headerH, contentX, bodyX, bodyY, bodyW, bodyH;
    private float searchX, searchY, searchW, searchH;

    private record Row(ModEntry tab, ModEntry.Group header) {
    }

    public SettingsScreen(Screen parent) {
        super(Component.literal("Cube Utility Client settings"));
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
        this.openFull = this.addRenderableWidget(new PillButton(PillButton.Kind.GLASS, Component.literal("Open settings"), "OPEN SETTINGS", true, this::openFullScreen));
        this.openFull.place(0, 0, 1, this.s, this.g, Fonts.label(Math.max(10f, 12f * this.s)), Math.max(1.5f, 2.2f * this.s));
        this.openFull.visible = false;
        this.filter();
        if (this.opening) {
            // A mod's settings switched straight back to this page while opening (BadOptimizations
            // opens a text file this way). Showing it again would only repeat that.
            this.reentered = true;
        } else if (this.selected == null) {
            this.select(this.tabs.stream().filter(m -> m.id.equals(remembered)).findFirst().orElse(this.tabs.isEmpty() ? null : this.tabs.get(0)));
        } else if (this.child == null && this.panel == null) {
            // Back from the mod's own Done or Cancel: show its settings fresh.
            this.openChild();
        } else {
            this.placeChild();
            this.placePanel();
        }
        this.placeOpenFull();
    }

    private void filter() {
        String words = this.query.trim().toLowerCase(Locale.ROOT);
        this.rows.clear();
        for (ModEntry.Group group : ModEntry.Group.values()) {
            List<ModEntry> found = this.tabs.stream().filter(m -> m.group == group && m.matches(words)).toList();
            if (!found.isEmpty()) {
                this.rows.add(new Row(null, group));
                found.forEach(m -> this.rows.add(new Row(m, null)));
            }
        }
    }

    private void select(ModEntry tab) {
        if (tab == this.selected && (this.child != null || this.panel != null || this.opening)) {
            return;
        }
        this.closeChild();
        this.selected = tab;
        if (tab != null) {
            remembered = tab.id;
            this.openChild();
            this.revealSelected();
        }
        this.placeOpenFull();
    }

    private void openChild() {
        this.failed = false;
        this.child = null;
        this.panel = null;
        this.childFocused = false;
        if (this.selected == null) {
            return;
        }
        // Settings the page can read without opening the mod's screen (Sodium's and the pages
        // mods add to it, and BadOptimizations' text file, whose screen opens Notepad).
        String id = this.selected.id;
        Set<String> otherTabs = this.tabs.stream().map(t -> t.id).filter(other -> !other.equals(id)).collect(Collectors.toSet());
        SettingsModel model = Models.forMod(id, otherTabs, () -> this);
        if (model != null) {
            this.showPanel(model);
            return;
        }
        if (this.selected.opensElsewhere()) {
            return;
        }
        this.opening = true;
        this.reentered = false;
        this.replacement = null;
        adopting = this;
        try {
            Screen screen = this.selected.settings(this);
            if (screen != null) {
                model = Models.fromScreen(screen);
                if (model != null) {
                    // Built with YACL or Cloth Config: drawn by the page, like the rest.
                    this.showPanel(model);
                } else {
                    this.child = new EmbeddedScreen(screen);
                    this.placeChild();
                }
                // It switched to another screen while setting up: show that one here instead.
                for (int hops = 0; hops < 3 && this.replacement != null && !this.reentered; hops++) {
                    Screen next = this.replacement;
                    this.replacement = null;
                    EmbeddedScreen first = this.child;
                    this.child = null;
                    this.panel = null;
                    if (first != null) {
                        first.removed();
                    }
                    model = Models.fromScreen(next);
                    if (model != null) {
                        this.showPanel(model);
                    } else {
                        this.child = new EmbeddedScreen(next);
                        this.placeChild();
                    }
                }
            } else {
                this.failed = true;
            }
        } finally {
            this.opening = false;
            adopting = null;
            this.replacement = null;
        }
        if (this.reentered) {
            this.selected.markOpensElsewhere();
            this.closeChild();
        }
    }

    /**
     * Called for every screen change: while a mod's settings are opening inside this page, a
     * switch to another screen (Reese's Sodium Options swaps its own in for Sodium's) is kept here
     * to show inside the page, instead of taking over the window. Returns whether it was kept.
     */
    public static boolean adopt(Screen screen) {
        SettingsScreen page = adopting;
        if (page == null || screen == page || screen == page.parent || screen instanceof SettingsScreen) {
            return false;
        }
        page.replacement = screen;
        return true;
    }

    private void placeChild() {
        if (this.child == null) {
            return;
        }
        int x = (int) Math.ceil(this.bodyX / this.g), y = (int) Math.ceil(this.bodyY / this.g);
        int w = (int) Math.floor((this.bodyX + this.bodyW) / this.g) - x, h = (int) Math.floor((this.bodyY + this.bodyH) / this.g) - y;
        try {
            this.child.place(x, y, Math.max(40, w), Math.max(40, h));
        } catch (Throwable error) {
            CubeUtilityClient.LOGGER.warn("{}'s settings couldn't be shown here", this.selected.id, error);
            this.child = null;
            this.failed = true;
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

    /** The button under the message for settings that can't show inside the page. */
    private void placeOpenFull() {
        if (this.openFull == null) {
            return;
        }
        boolean show = this.selected != null && this.child == null && this.panel == null && (this.failed || this.selected.opensElsewhere());
        this.openFull.visible = show;
        this.openFull.active = show;
        if (show) {
            float h = Math.round(Math.max(30f, 40f * this.s));
            this.openFull.place(0, 0, h, this.s, this.g, Fonts.label(Math.max(10f, 12f * this.s)), Math.max(1.5f, 2.2f * this.s));
            float x = Math.round(this.bodyX + (this.bodyW - (this.openFull.right())) / 2f);
            this.openFull.place(x, Math.round(this.bodyY + this.bodyH * 0.38f + Math.max(40f, 58f * this.s)), h, this.s, this.g, Fonts.label(Math.max(10f, 12f * this.s)), Math.max(1.5f, 2.2f * this.s));
        }
    }

    /** Opens the mod's settings the ordinary way, on the whole window; Done comes back here. */
    private void openFullScreen() {
        if (this.selected == null) {
            return;
        }
        Screen screen = this.selected.settings(this);
        if (screen != null) {
            this.minecraft.gui.setScreen(screen);
        }
    }

    /** Lets the mod's settings save what they keep when closed, and lets go of them. */
    private void closeChild() {
        if (this.panel != null) {
            OptionsPanel leaving = this.panel;
            this.panel = null;
            leaving.close();
            this.updateTextInput();
        }
        if (this.child != null) {
            EmbeddedScreen leaving = this.child;
            this.child = null;
            Saving.saveIfEdited(leaving.screen);
            try {
                leaving.removed();
            } catch (Throwable error) {
                CubeUtilityClient.LOGGER.warn("Closing {}'s settings failed", this.selected == null ? "?" : this.selected.id, error);
            }
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

    @Override
    public void tick() {
        if (this.child != null) {
            try {
                this.child.tick();
            } catch (Throwable error) {
                this.dropChild(error);
            }
        }
    }

    /** The page's own drawing of a mod's settings failed: show the mod's own screen instead. */
    private void dropPanel(Throwable error) {
        CubeUtilityClient.LOGGER.warn("{}'s settings couldn't be drawn by the page", this.selected == null ? "?" : this.selected.id, error);
        this.panel = null;
        this.updateTextInput();
        this.failed = true;
        this.placeOpenFull();
    }

    private void dropChild(Throwable error) {
        CubeUtilityClient.LOGGER.warn("{}'s settings stopped working inside the page", this.selected == null ? "?" : this.selected.id, error);
        this.child = null;
        this.failed = true;
        this.placeOpenFull();
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
        if (this.panel != null) {
            try {
                this.panel.extract(graphics, paint, mx, my, k);
            } catch (Throwable error) {
                this.dropPanel(error);
            }
        } else if (this.child != null) {
            try {
                this.child.extract(graphics, mouseX, mouseY, a);
            } catch (Throwable error) {
                // A mod's screen that can't draw inside the page mustn't take the game down.
                this.dropChild(error);
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
        paint.text("Settings", title, x, Math.round(this.searchY - Math.max(10f, 16f * this.s)), Rgb.alpha(TEXT, k), 0f);
        this.extractSearch(paint, k);

        // The tabs, clipped to their area and scrolled.
        float contentHeight = 0f;
        for (Row row : this.rows) {
            contentHeight += row.tab == null ? this.headerH : this.rowH;
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
            if (row.tab == null) {
                if (y + this.headerH > this.listTop && y < this.listBottom) {
                    paint.text(row.header.label, groupFont, this.px + this.pad, Math.round(y + this.headerH - Math.max(7f, 9f * this.s)), Rgb.alpha(GOLD_DIM, 0.85f * k), Math.max(1.2f, 1.8f * this.s));
                }
                y += this.headerH;
                continue;
            }
            if (y + this.rowH > this.listTop && y < this.listBottom) {
                boolean chosen = row.tab == this.selected;
                boolean hover = !chosen && mx >= rowLeft && mx < rowRight && my >= Math.max(y, this.listTop) && my < Math.min(y + this.rowH, this.listBottom);
                if (chosen || hover) {
                    Paint.roundedRect(rowsBatch, rowLeft, y + 1f, rowRight - rowLeft, this.rowH - 2f, 11f * this.s, chosen ? 1f : 0f,
                            Rgb.alpha(0xFFFFFF, (chosen ? 0.07f : 0.035f) * k), Rgb.alpha(GOLD, 0.28f * k));
                }
                float icon = Math.round(Math.max(20f, 26f * this.s));
                float ix = Math.round(this.px + this.pad), iy = Math.round(y + (this.rowH - icon) / 2f);
                this.extractIcon(paint, row.tab, ix, iy, icon, k);
                float textX = ix + icon + Math.round(Math.max(9f, 12f * this.s));
                float maxW = rowRight - textX - 6f;
                float nameSize = Fonts.size(nameFont), blurbSize = Fonts.size(blurbFont);
                float gap = Math.max(4f, 5f * this.s);
                float top = y + (this.rowH - (nameSize + gap + blurbSize)) / 2f;
                paint.text(fit(row.tab.title, nameFont, maxW), nameFont, textX, Math.round(top + nameSize * 0.8f), Rgb.alpha(chosen ? GOLD : TEXT, k), 0f);
                paint.text(fit(row.tab.blurb, blurbFont, maxW), blurbFont, textX, Math.round(top + nameSize + gap + blurbSize * 0.8f), Rgb.alpha(TEXT_3, k), 0f);
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

    private void extractHeader(Paint paint, float k) {
        if (this.selected == null) {
            return;
        }
        ModEntry tab = this.selected;
        float icon = Math.round(Math.max(36f, 56f * this.s));
        float x = Math.round(this.contentX + this.pad), y = Math.round(this.py + this.pad);
        this.extractIcon(paint, tab, x, y, icon, k);
        float textX = x + icon + Math.round(Math.max(12f, 18f * this.s));
        float textW = this.close.getX() * this.g - textX - this.pad;
        FontDescription name = Fonts.label(Math.max(14f, 24f * this.s));
        FontDescription body = Fonts.label(Math.max(10f, 13f * this.s));
        FontDescription small = Fonts.label(Math.max(9f, 11f * this.s));
        float nameBase = Math.round(y + Fonts.size(name) * 0.95f);
        paint.text(fit(tab.title, name, textW), name, textX, nameBase, Rgb.alpha(TEXT, k), 0f);
        float blurbBase = Math.round(nameBase + Fonts.size(body) + Math.max(6f, 8f * this.s));
        paint.text(fit(tab.blurb, body, textW), body, textX, blurbBase, Rgb.alpha(TEXT_2, k), 0f);
        // The mod behind the tab, in small print.
        String credit = tab.modName + " " + tab.version;
        paint.text(fit(credit, small, textW), small, textX, Math.round(blurbBase + Fonts.size(small) + Math.max(5f, 7f * this.s)), Rgb.alpha(TEXT_3, 0.8f * k), 0f);
        // The line between the tab's name and its settings.
        float line = this.bodyY - Math.round(Math.max(8f, 11f * this.s));
        paint.solid().rect(this.contentX + 1f, line, this.px + this.pw - 1f, line + 1f, Rgb.alpha(0xFFFFFF, 0.06f * k));
    }

    /** What the right side shows when a tab's settings can't show inside the page. */
    private void extractMessage(Paint paint, float k) {
        if (this.selected == null) {
            String first = this.tabs.isEmpty() ? "No mods with settings are installed." : "Pick a tab on the left.";
            FontDescription big = Fonts.label(Math.max(12f, 16f * this.s));
            paint.text(first, big, Math.round(this.bodyX + (this.bodyW - Paint.measure(first, big, 0f)) / 2f), Math.round(this.bodyY + this.bodyH * 0.38f), Rgb.alpha(TEXT, k), 0f);
            return;
        }
        String first, second;
        if (this.selected.opensElsewhere()) {
            first = this.selected.title + " keeps its settings in a text file.";
            second = "Open it to change them; they take effect the next time Minecraft starts.";
        } else {
            first = "These settings couldn't open inside this page.";
            second = "Open them on their own screen instead; Done brings you back here.";
        }
        FontDescription big = Fonts.label(Math.max(12f, 16f * this.s));
        FontDescription small = Fonts.label(Math.max(10f, 13f * this.s));
        float cx = this.bodyX + this.bodyW / 2f, cy = this.bodyY + this.bodyH * 0.38f;
        paint.text(first, big, Math.round(cx - Paint.measure(first, big, 0f) / 2f), Math.round(cy), Rgb.alpha(TEXT, k), 0f);
        paint.text(second, small, Math.round(cx - Paint.measure(second, small, 0f) / 2f), Math.round(cy + Fonts.size(small) + Math.max(10f, 14f * this.s)), Rgb.alpha(TEXT_2, k), 0f);
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
    private static String monogram(String name) {
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
            this.updateTextInput();
        }
    }

    /** Starts or stops the window's text input, as Minecraft's own text boxes do. */
    private void updateTextInput() {
        boolean wanted = this.searchFocused || this.panel != null && this.panel.isTyping();
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

    private ModEntry rowAt(double mx, double my) {
        if (mx < this.px || mx >= this.contentX || my < this.listTop || my >= this.listBottom) {
            return null;
        }
        float y = this.listTop - this.scroll;
        for (Row row : this.rows) {
            float h = row.tab == null ? this.headerH : this.rowH;
            if (my >= y && my < y + h) {
                return row.tab;
            }
            y += h;
        }
        return null;
    }

    private void revealSelected() {
        float y = 0f;
        for (Row row : this.rows) {
            if (row.tab == this.selected) {
                float view = this.listBottom - this.listTop;
                if (y < this.scrollGoal) {
                    this.scrollGoal = Math.max(0f, y - this.headerH);
                } else if (y + this.rowH > this.scrollGoal + view) {
                    this.scrollGoal = y + this.rowH - view;
                }
                return;
            }
            y += row.tab == null ? this.headerH : this.rowH;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x() * this.g, my = event.y() * this.g;
        boolean onSearch = mx >= this.searchX && mx < this.searchX + this.searchW && my >= this.searchY && my < this.searchY + this.searchH;
        this.setSearchFocused(onSearch);
        if (onSearch) {
            this.childFocused = false;
            return true;
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
        if (this.child != null && this.child.contains(event.x(), event.y()) && !this.close.isMouseOver(event.x(), event.y())) {
            this.setFocused(null);
            this.childFocused = true;
            try {
                return this.child.mouseClicked(event, doubleClick);
            } catch (Throwable error) {
                this.dropChild(error);
                return true;
            }
        }
        this.childFocused = false;
        ModEntry row = this.rowAt(mx, my);
        if (row != null) {
            this.setFocused(null);
            this.select(row);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.panel != null && this.panel.mouseReleased()) {
            return true;
        }
        if (this.childFocused && this.child != null) {
            try {
                return this.child.mouseReleased(event);
            } catch (Throwable error) {
                this.dropChild(error);
                return true;
            }
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.panel != null && this.panel.mouseDragged(event.x() * this.g)) {
            return true;
        }
        if (this.childFocused && this.child != null) {
            try {
                return this.child.mouseDragged(event, dx, dy);
            } catch (Throwable error) {
                this.dropChild(error);
                return true;
            }
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
        if (this.panel != null && this.panel.contains(mx, mouseY * this.g)) {
            return this.panel.mouseScrolled(scrollY);
        }
        if (this.child != null && this.child.contains(mouseX, mouseY)) {
            try {
                return this.child.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
            } catch (Throwable error) {
                this.dropChild(error);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (this.child != null) {
            try {
                this.child.mouseMoved(mouseX, mouseY);
            } catch (Throwable error) {
                this.dropChild(error);
            }
        }
        super.mouseMoved(mouseX, mouseY);
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
            if (!this.childFocused || this.searchFocused) {
                List<ModEntry> shown = this.rows.stream().map(Row::tab).filter(t -> t != null).toList();
                if (!shown.isEmpty()) {
                    int at = shown.indexOf(this.selected);
                    int next = at < 0 ? 0 : Math.max(0, Math.min(shown.size() - 1, at + (key == InputConstants.KEY_UP ? -1 : 1)));
                    this.select(shown.get(next));
                }
                return true;
            }
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
        if (this.childFocused && this.child != null) {
            try {
                return this.child.keyPressed(event);
            } catch (Throwable error) {
                this.dropChild(error);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (this.childFocused && this.child != null && !this.searchFocused) {
            try {
                return this.child.keyReleased(event);
            } catch (Throwable error) {
                this.dropChild(error);
                return true;
            }
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (this.panel != null && this.panel.charTyped(event)) {
            return true;
        }
        if (this.searchFocused) {
            if (event.isAllowedChatCharacter() && this.query.length() < 40) {
                this.setQuery(this.query + event.codepointAsString());
            }
            return true;
        }
        if (this.childFocused && this.child != null) {
            try {
                return this.child.charTyped(event);
            } catch (Throwable error) {
                this.dropChild(error);
                return true;
            }
        }
        return super.charTyped(event);
    }
}
