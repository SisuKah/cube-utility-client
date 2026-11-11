package dev.cubeutilityclient.settings;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cubeutilityclient.CubeUtilityClient;
import dev.cubeutilityclient.render.Fonts;
import dev.cubeutilityclient.render.LineIcon;
import dev.cubeutilityclient.render.Motion;
import dev.cubeutilityclient.render.Paint;
import dev.cubeutilityclient.render.QuadBatch;
import dev.cubeutilityclient.render.Rgb;
import dev.cubeutilityclient.settings.model.Setting;
import dev.cubeutilityclient.settings.model.SettingsModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.FontDescription;

/**
 * A mod's settings drawn by the page itself, every mod in the same style: each setting's name
 * and what it does on the left, and a switch, slider, choice or text field on the right. A mod
 * with several categories gets a row of chips to switch between them. Changes go to the mod
 * and are saved as soon as they're made, like Minecraft's own options.
 */
final class OptionsPanel {
    private static final int TEXT = 0xE9EFEE, TEXT_2 = 0x9AABA9, TEXT_3 = 0x7E8F8D, GOLD = 0xE4C084, GOLD_DIM = 0xCDA76A, GOLD_LIGHT = 0xF7E6C2, INK = 0x15100A,
            RED = 0xE58E7C;
    private static final String OPEN = "OPEN";
    /** The category each mod was left on, so coming back to its tab shows the same one. */
    private static final Map<String, Integer> PAGES = new HashMap<>();

    private enum Kind {
        HEADER,
        BANNER,
        ROW,
        MORE
    }

    /**
     * One line of the list, laid out: a heading, a setting, or a note. {@code cut} is true when
     * its description was too long to show whole; hovering it shows the rest.
     */
    private record Item(Kind kind, Setting setting, String text, List<String> lines, float top, float height, boolean cut) {
    }

    private record Chip(int page, String title, float x, float y, float w, float h) {
    }

    final SettingsModel model;
    private final String key;
    private final Runnable openFull;
    /** Every setting needs a restart (BadOptimizations'): one note says so instead of a tag on each. */
    private final boolean allRestart;
    private final List<Item> items = new ArrayList<>();
    private final List<Chip> chips = new ArrayList<>();
    private final Map<Setting, Motion> knobs = new IdentityHashMap<>();
    private int page;
    private boolean stale = true;
    private float scroll, scrollGoal;
    private long lastFrame = System.nanoTime();

    // Where the panel is and how big its parts are, in screen pixels.
    private float x, y, w, h, s, g;
    private float listTop, listBottom, contentHeight;
    private FontDescription nameFont, descFont, headFont, valueFont, chipFont, buttonFont, badgeFont;
    private float padX, padY, gap, gutter, controlW, controlH, toggleW, toggleH, headH, lineGap, chipH, chipPad, buttonPad, spacing;

    private Setting.Slider dragging;
    private float dragFrom, dragTo;
    private Setting.Text editing;
    private String draft = "", lastGood = "", before = "";
    private boolean draftValid = true, selectAll;
    private long caretFrom = System.nanoTime();

    OptionsPanel(SettingsModel model, String key, Runnable openFull) {
        this.model = model;
        this.key = key;
        this.openFull = openFull;
        boolean any = false, all = true;
        for (SettingsModel.Page page : model.pages()) {
            for (SettingsModel.Section section : page.sections()) {
                for (Setting setting : section.settings()) {
                    if (!(setting instanceof Setting.Note) && !(setting instanceof Setting.Action)) {
                        any = true;
                        all &= setting.restart;
                    }
                }
            }
        }
        this.allRestart = any && all;
        this.page = Math.max(0, Math.min(model.pages().size() - 1, PAGES.getOrDefault(key, 0)));
    }

    // ------------------------------------------------------------------ layout

    /** Puts the panel in a rectangle of the page, in screen pixels. */
    void place(float x, float y, float w, float h, float s, float guiScale) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.s = s;
        this.g = guiScale;
        this.nameFont = Fonts.label(Math.max(11f, 14f * s));
        this.descFont = Fonts.label(Math.max(9f, 11.5f * s));
        this.headFont = Fonts.strong(Math.max(8f, 10f * s));
        this.valueFont = Fonts.label(Math.max(10f, 12.5f * s));
        this.chipFont = Fonts.label(Math.max(10f, 12f * s));
        this.buttonFont = Fonts.strong(Math.max(8f, 10f * s));
        this.badgeFont = Fonts.strong(8f * Math.max(1f, s));
        this.spacing = Math.max(1.2f, 1.8f * s);
        this.padX = Math.round(Math.max(8f, 14f * s));
        this.padY = Math.round(Math.max(7f, 11f * s));
        this.gap = Math.round(Math.max(10f, 20f * s));
        this.gutter = Math.round(Math.max(8f, 12f * s));
        this.controlH = Math.round(Math.max(20f, 30f * s));
        this.toggleH = Math.round(Math.max(16f, 22f * s));
        this.toggleW = Math.round(this.toggleH * 1.8f);
        this.headH = Math.round(Math.max(26f, 40f * s));
        this.lineGap = Math.round(Math.max(2f, 4f * s));
        this.chipH = Math.round(Math.max(22f, 30f * s));
        this.chipPad = Math.round(Math.max(10f, 14f * s));
        this.buttonPad = Math.round(Math.max(12f, 18f * s));
        this.controlW = Math.round(Math.max(120f, Math.min((w - this.gutter) * 0.38f, 280f * s)));
        this.stale = true;
    }

    private void relayout() {
        this.stale = false;
        this.layoutChips();
        this.items.clear();
        float rowW = this.w - this.gutter;
        float top = 0f;
        if (this.allRestart) {
            List<String> lines = wrap("These settings take effect the next time Minecraft starts.", this.descFont, rowW - 2f * this.padX, 3);
            float height = Math.round(2f * this.padY + this.textHeight(this.descFont, lines.size()));
            this.items.add(new Item(Kind.BANNER, null, null, lines, top, height, false));
            top += height + Math.round(Math.max(6f, 10f * this.s));
        }
        for (SettingsModel.Section section : this.model.pages().get(this.page).sections()) {
            if (!section.title().isBlank()) {
                this.items.add(new Item(Kind.HEADER, null, section.title().toUpperCase(Locale.ROOT), List.of(), top, this.headH, false));
                top += this.headH;
            }
            for (Setting setting : section.settings()) {
                Item row = this.row(setting, top, rowW);
                this.items.add(row);
                top += row.height;
            }
        }
        if (!this.model.pages().get(this.page).complete()) {
            String text = "A few more settings are only on the mod's own screen.";
            List<String> lines = wrap(text, this.descFont, rowW - 2f * this.padX - this.buttonWidth(OPEN) - this.gap, 3);
            top += Math.round(Math.max(8f, 14f * this.s));
            float height = Math.round(2f * this.padY + Math.max(this.controlH, this.textHeight(this.descFont, lines.size())));
            this.items.add(new Item(Kind.MORE, null, text, lines, top, height, false));
            top += height;
        }
        this.contentHeight = top + this.padY;
    }

    private void layoutChips() {
        this.chips.clear();
        this.listTop = this.y;
        this.listBottom = this.y + this.h;
        List<SettingsModel.Page> pages = this.model.pages();
        if (pages.size() < 2) {
            return;
        }
        float cx = this.x, cy = this.y, between = Math.round(Math.max(6f, 8f * this.s)), right = this.x + this.w - this.gutter;
        for (int i = 0; i < pages.size(); i++) {
            String title = pages.get(i).title().isBlank() ? "Page " + (i + 1) : pages.get(i).title();
            float cw = Math.round(Paint.measure(title, this.chipFont, 0f) + 2f * this.chipPad);
            if (cx > this.x && cx + cw > right) {
                cx = this.x;
                cy += this.chipH + between;
            }
            this.chips.add(new Chip(i, title, cx, cy, cw, this.chipH));
            cx += cw + between;
        }
        this.listTop = Math.round(cy + this.chipH + Math.max(10f, 16f * this.s));
    }

    private Item row(Setting setting, float top, float rowW) {
        if (setting instanceof Setting.Note) {
            List<String> lines = wrap(setting.name, this.descFont, rowW - 2f * this.padX, 8);
            return new Item(Kind.ROW, setting, null, lines, top, Math.round(2f * this.padY + this.textHeight(this.descFont, lines.size())), false);
        }
        float textW = rowW - 2f * this.padX - this.controlWidth(setting) - this.gap;
        // Up to three lines; the whole description shows on hover.
        List<String> lines = wrap(setting.description, this.descFont, textW, 3);
        boolean cut = !String.join(" ", lines).equals(setting.description);
        return new Item(Kind.ROW, setting, null, lines, top, Math.round(2f * this.padY + Math.max(this.textBlock(lines), this.controlH)), cut);
    }

    /** The name and description lines' height together. */
    private float textBlock(List<String> lines) {
        return Fonts.size(this.nameFont) + (lines.isEmpty() ? 0f : this.lineGap + 2f + this.textHeight(this.descFont, lines.size()));
    }

    private float textHeight(FontDescription font, int lines) {
        return lines <= 0 ? 0f : lines * Fonts.size(font) + (lines - 1) * this.lineGap;
    }

    private float controlWidth(Setting setting) {
        if (setting instanceof Setting.Toggle) {
            return this.toggleW;
        }
        return setting instanceof Setting.Action action ? this.buttonWidth(action.button) : this.controlW;
    }

    private float buttonWidth(String label) {
        return Math.round(Paint.measure(label, this.buttonFont, this.spacing) + 2f * this.buttonPad);
    }

    /** A slider's track runs between these two x positions (the knob's centre at either end). */
    private float[] track(float cx) {
        float knob = this.knobSize(), valueW = Math.round(this.controlW * 0.34f), between = Math.round(Math.max(8f, 12f * this.s));
        return new float[] {cx + knob / 2f, cx + this.controlW - valueW - between - knob / 2f};
    }

    private float knobSize() {
        return Math.round(Math.max(12f, 16f * this.s));
    }

    // ------------------------------------------------------------------ drawing

    void extract(GuiGraphicsExtractor graphics, Paint paint, float mx, float my, float k) {
        if (this.stale) {
            this.relayout();
        }
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - this.lastFrame) / 1e9f);
        this.lastFrame = now;
        float view = this.listBottom - this.listTop;
        float maxScroll = Math.max(0f, this.contentHeight - view);
        this.scrollGoal = Math.max(0f, Math.min(maxScroll, this.scrollGoal));
        this.scroll += (this.scrollGoal - this.scroll) * Math.min(1f, dt * 14f);
        this.scroll = Math.max(0f, Math.min(maxScroll, this.scroll));

        this.extractChips(paint, mx, my, k);
        Item tip = null;
        graphics.enableScissor((int) Math.floor(this.x / this.g), (int) Math.floor(this.listTop / this.g), (int) Math.ceil((this.x + this.w) / this.g),
                (int) Math.ceil(this.listBottom / this.g));
        try {
            // Every shape goes in one batch made before any text, so text always sits on top.
            QuadBatch shapes = paint.solid();
            boolean overList = mx >= this.x && mx < this.x + this.w && my >= this.listTop && my < this.listBottom;
            Item previous = null;
            for (Item item : this.items) {
                float top = this.listTop - this.scroll + item.top;
                if (top + item.height >= this.listTop && top <= this.listBottom) {
                    boolean hover = overList && this.dragging == null && my >= top && my < top + item.height;
                    if (hover && item.cut && mx < this.x + this.w - this.gutter - this.padX - this.controlWidth(item.setting) - this.gap) {
                        tip = item;
                    }
                    switch (item.kind) {
                        case HEADER -> this.extractHeader(paint, shapes, item, top, k);
                        case BANNER -> this.extractBanner(paint, shapes, item, top, k);
                        case MORE -> this.extractMore(paint, shapes, item, top, mx, hover, k);
                        case ROW -> this.extractRow(paint, shapes, item, previous, top, mx, my, hover, k);
                    }
                }
                previous = item;
            }
        } finally {
            graphics.disableScissor();
        }
        if (maxScroll > 0f) {
            float thumb = Math.max(24f, view * view / this.contentHeight);
            float ty = this.listTop + (view - thumb) * (this.scroll / maxScroll);
            Paint.roundedRect(paint.solid(), this.x + this.w - 4f, ty, 3f, thumb, 1.5f, 0f, Rgb.alpha(GOLD, 0.35f * k), 0);
        }
        if (tip != null && this.editing == null) {
            this.extractTooltip(paint, tip.setting.description, mx, my, k);
        }
    }

    /** The whole of a description that was cut short, in a box by the mouse. */
    private void extractTooltip(Paint paint, String text, float mx, float my, float k) {
        float pad = Math.round(Math.max(8f, 11f * this.s));
        float maxW = Math.round(Math.min(420f * Math.max(1f, this.s), this.w * 0.7f));
        List<String> lines = wrap(text, this.descFont, maxW - 2f * pad, 16);
        float widest = 0f;
        for (String line : lines) {
            widest = Math.max(widest, Paint.measure(line, this.descFont, 0f));
        }
        float bw = Math.round(widest + 2f * pad), bh = Math.round(this.textHeight(this.descFont, lines.size()) + 2f * pad);
        float bx = Math.round(mx + 12f * this.s), by = Math.round(my + 18f * this.s);
        if (bx + bw > paint.width - 6f) {
            bx = Math.round(paint.width - 6f - bw);
        }
        if (by + bh > paint.height - 6f) {
            by = Math.round(my - 10f * this.s - bh);
        }
        bx = Math.max(6f, bx);
        by = Math.max(6f, by);
        Paint.roundedRect(paint.solid(), bx, by, bw, bh, Math.max(6f, 8f * this.s), 1f, Rgb.alpha(0x0A1011, 0.97f * k), Rgb.alpha(GOLD, 0.28f * k));
        this.extractLines(paint, lines, bx + pad, by + pad, Rgb.alpha(TEXT_2, k));
    }

    private void extractChips(Paint paint, float mx, float my, float k) {
        if (this.chips.isEmpty()) {
            return;
        }
        QuadBatch shapes = paint.solid();
        for (Chip chip : this.chips) {
            boolean chosen = chip.page == this.page;
            boolean over = !chosen && mx >= chip.x && mx < chip.x + chip.w && my >= chip.y && my < chip.y + chip.h;
            int fill = chosen ? Rgb.alpha(GOLD, 0.15f * k) : Rgb.alpha(0xFFFFFF, (over ? 0.07f : 0.035f) * k);
            int border = chosen ? Rgb.alpha(GOLD, 0.6f * k) : Rgb.alpha(0xFFFFFF, (over ? 0.2f : 0.09f) * k);
            Paint.roundedRect(shapes, chip.x, chip.y, chip.w, chip.h, chip.h / 2f, 1f, fill, border);
            int color = chosen ? Rgb.alpha(GOLD, k) : Rgb.alpha(over ? TEXT : TEXT_2, k);
            paint.text(chip.title, this.chipFont, Math.round(chip.x + this.chipPad), Math.round(chip.y + chip.h / 2f + Fonts.size(this.chipFont) * 0.36f), color, 0f);
        }
    }

    private void extractHeader(Paint paint, QuadBatch shapes, Item item, float top, float k) {
        float right = this.x + this.w - this.gutter - this.padX;
        float tx = this.x + this.padX, base = Math.round(top + item.height - Math.max(9f, 13f * this.s));
        String text = fit(item.text, this.headFont, right - tx, this.spacing);
        paint.text(text, this.headFont, tx, base, Rgb.alpha(GOLD_DIM, 0.9f * k), this.spacing);
        // A faint rule from the heading to the right edge.
        float lineX = Math.round(tx + Paint.measure(text, this.headFont, this.spacing) + Math.max(8f, 12f * this.s));
        float lineY = Math.round(base - Fonts.size(this.headFont) * 0.35f);
        if (lineX < right) {
            shapes.rect(lineX, lineY, right, lineY + 1f, Rgb.alpha(GOLD, 0.14f * k));
        }
    }

    private void extractBanner(Paint paint, QuadBatch shapes, Item item, float top, float k) {
        float right = this.x + this.w - this.gutter;
        Paint.roundedRect(shapes, this.x, top, right - this.x, item.height, Math.max(6f, 10f * this.s), 1f, Rgb.alpha(GOLD, 0.06f * k), Rgb.alpha(GOLD, 0.22f * k));
        this.extractLines(paint, item.lines, this.x + this.padX, top + this.padY, Rgb.alpha(GOLD_DIM, k));
    }

    private void extractMore(Paint paint, QuadBatch shapes, Item item, float top, float mx, boolean hover, float k) {
        float right = this.x + this.w - this.gutter;
        Paint.roundedRect(shapes, this.x, top, right - this.x, item.height, Math.max(6f, 10f * this.s), 1f, Rgb.alpha(0xFFFFFF, 0.025f * k),
                Rgb.alpha(0xFFFFFF, 0.07f * k));
        float textTop = top + (item.height - this.textHeight(this.descFont, item.lines.size())) / 2f;
        this.extractLines(paint, item.lines, this.x + this.padX, textTop, Rgb.alpha(TEXT_2, k));
        float bw = this.buttonWidth(OPEN), bx = right - this.padX - bw;
        this.extractButton(paint, shapes, OPEN, bx, top + item.height / 2f, hover && mx >= bx && mx < bx + bw, true, k);
    }

    private void extractLines(Paint paint, List<String> lines, float x, float top, int color) {
        float size = Fonts.size(this.descFont);
        float baseline = top + size * 0.8f;
        for (String line : lines) {
            paint.text(line, this.descFont, Math.round(x), Math.round(baseline), color, 0f);
            baseline += size + this.lineGap;
        }
    }

    private void extractRow(Paint paint, QuadBatch shapes, Item item, Item previous, float top, float mx, float my, boolean hover, float k) {
        Setting setting = item.setting;
        float left = this.x, right = this.x + this.w - this.gutter;
        if (previous != null && previous.kind == Kind.ROW) {
            shapes.rect(left + this.padX, top, right - this.padX, top + 1f, Rgb.alpha(0xFFFFFF, 0.05f * k));
        }
        if (setting instanceof Setting.Note) {
            this.extractLines(paint, item.lines, left + this.padX, top + this.padY, Rgb.alpha(TEXT_2, k));
            return;
        }
        boolean enabled = setting.enabled();
        float a = enabled ? 1f : 0.42f;
        if (hover && enabled) {
            Paint.roundedRect(shapes, left, top + 1f, right - left, item.height - 2f, Math.max(6f, 10f * this.s), 0f, Rgb.alpha(0xFFFFFF, 0.03f * k), 0);
        }

        // The name, a restart tag when it needs one, and what it does.
        float controlW = this.controlWidth(setting);
        float tx = left + this.padX, textW = right - left - 2f * this.padX - controlW - this.gap;
        float nameSize = Fonts.size(this.nameFont), descSize = Fonts.size(this.descFont);
        float textTop = top + (item.height - this.textBlock(item.lines)) / 2f;
        boolean tag = setting.restart && !this.allRestart;
        float tagPad = Math.round(Math.max(5f, 6f * this.s)), tagW = tag ? Math.round(Paint.measure("RESTART", this.badgeFont, 1f) + 2f * tagPad) : 0f;
        String name = SettingsScreen.fit(setting.name, this.nameFont, textW - (tag ? tagW + 8f * this.s : 0f));
        float nameBase = Math.round(textTop + nameSize * 0.8f);
        paint.text(name, this.nameFont, tx, nameBase, Rgb.alpha(TEXT, a * k), 0f);
        if (tag) {
            float tagH = Math.round(Math.max(13f, 16f * this.s));
            float bx = Math.round(tx + Paint.measure(name, this.nameFont, 0f) + Math.max(6f, 9f * this.s)), by = Math.round(textTop + (nameSize - tagH) / 2f);
            Paint.roundedRect(shapes, bx, by, tagW, tagH, tagH / 2f, 1f, Rgb.alpha(GOLD, 0.06f * a * k), Rgb.alpha(GOLD_DIM, 0.5f * a * k));
            paint.text("RESTART", this.badgeFont, bx + tagPad, Math.round(by + tagH / 2f + Fonts.size(this.badgeFont) * 0.36f), Rgb.alpha(GOLD_DIM, a * k), 1f);
        }
        float baseline = textTop + nameSize + this.lineGap + 2f + descSize * 0.8f;
        for (String line : item.lines) {
            paint.text(line, this.descFont, tx, Math.round(baseline), Rgb.alpha(TEXT_3, a * k), 0f);
            baseline += descSize + this.lineGap;
        }

        // The control, on the right.
        float cx = Math.round(right - this.padX - controlW), cy = top + item.height / 2f;
        try {
            if (setting instanceof Setting.Toggle toggle) {
                this.extractToggle(shapes, toggle, cx, cy, hover && enabled, a, k);
            } else if (setting instanceof Setting.Slider slider) {
                this.extractSlider(paint, shapes, slider, cx, cy, hover && enabled && mx >= cx && mx < cx + this.controlW, a, k);
            } else if (setting instanceof Setting.Choice choice) {
                boolean over = hover && enabled && mx >= cx && mx < cx + this.controlW && Math.abs(my - cy) <= this.controlH / 2f;
                this.extractChoice(paint, shapes, choice, cx, cy, over, mx < cx + this.controlW / 2f, a, k);
            } else if (setting instanceof Setting.Text text) {
                this.extractText(paint, shapes, text, cx, cy, hover && enabled && mx >= cx && mx < cx + this.controlW, a, k);
            } else if (setting instanceof Setting.Action action) {
                this.extractButton(paint, shapes, action.button, cx, cy, hover && enabled && mx >= cx && mx < cx + controlW, enabled, k);
            }
        } catch (Throwable error) {
            // A value the mod can't give right now: a dash, rather than a broken page.
            paint.text("—", this.valueFont, cx, Math.round(cy + Fonts.size(this.valueFont) * 0.36f), Rgb.alpha(TEXT_3, k), 0f);
        }
    }

    private void extractToggle(QuadBatch shapes, Setting.Toggle toggle, float cx, float cy, boolean over, float a, float k) {
        boolean value = toggle.on();
        float on = this.knobs.computeIfAbsent(toggle, t -> new Motion(value ? 1f : 0f)).towards(value ? 1f : 0f, 0.15f);
        float tx = Math.round(cx), ty = Math.round(cy - this.toggleH / 2f);
        int fill = Rgb.lerp(Rgb.alpha(0xFFFFFF, (over ? 0.12f : 0.08f) * a * k), Rgb.alpha(GOLD, 0.95f * a * k), on);
        int border = Rgb.lerp(Rgb.alpha(0xFFFFFF, (over ? 0.26f : 0.15f) * a * k), Rgb.alpha(GOLD_LIGHT, 0.9f * a * k), on);
        Paint.capsule(shapes, tx, ty, this.toggleW, this.toggleH, 1f, (px, py) -> fill, border, false);
        float inset = Math.max(2f, Math.round(3f * this.s)), knob = this.toggleH - 2f * inset;
        float kx = tx + inset + (this.toggleW - 2f * inset - knob) * on;
        int knobColor = Rgb.lerp(Rgb.alpha(0xC9D3D1, a * k), Rgb.alpha(INK, a * k), on);
        Paint.capsule(shapes, kx, ty + inset, knob, knob, 0f, (px, py) -> knobColor, 0, false);
    }

    private void extractSlider(Paint paint, QuadBatch shapes, Setting.Slider slider, float cx, float cy, boolean over, float a, float k) {
        float[] track = this.track(cx);
        float knob = this.knobSize(), line = Math.max(3f, Math.round(4f * this.s));
        float f = (float) Math.max(0, Math.min(1, slider.fraction()));
        float at = track[0] + (track[1] - track[0]) * f;
        float ty = Math.round(cy - line / 2f);
        int rest = Rgb.alpha(0xFFFFFF, 0.12f * a * k), filled = Rgb.alpha(GOLD, 0.9f * a * k);
        Paint.capsule(shapes, track[0] - line / 2f, ty, track[1] - track[0] + line, line, 0f, (px, py) -> rest, 0, false);
        if (at - track[0] > 0.5f) {
            Paint.capsule(shapes, track[0] - line / 2f, ty, at - track[0] + line, line, 0f, (px, py) -> filled, 0, false);
        }
        boolean active = this.dragging == slider;
        if (over || active) {
            float halo = knob + Math.round(8f * this.s);
            int glow = Rgb.alpha(GOLD, 0.16f * k);
            Paint.capsule(shapes, Math.round(at - halo / 2f), Math.round(cy - halo / 2f), halo, halo, 0f, (px, py) -> glow, 0, false);
        }
        int knobColor = Rgb.alpha(GOLD_LIGHT, a * k);
        Paint.capsule(shapes, Math.round(at - knob / 2f), Math.round(cy - knob / 2f), knob, knob, 0f, (px, py) -> knobColor, 0, false);
        float valueW = Math.round(this.controlW * 0.34f);
        String value = SettingsScreen.fit(slider.shown(), this.valueFont, valueW);
        float vw = Paint.measure(value, this.valueFont, 0f);
        paint.text(value, this.valueFont, Math.round(cx + this.controlW - vw), Math.round(cy + Fonts.size(this.valueFont) * 0.36f), Rgb.alpha(active ? GOLD : TEXT, a * k), 0f);
    }

    private void extractChoice(Paint paint, QuadBatch shapes, Setting.Choice choice, float cx, float cy, boolean over, boolean leftHalf, float a, float k) {
        float top = Math.round(cy - this.controlH / 2f);
        int fill = Rgb.alpha(0xFFFFFF, (over ? 0.075f : 0.045f) * a * k);
        int border = over ? Rgb.alpha(GOLD, 0.55f * k) : Rgb.alpha(0xFFFFFF, 0.11f * a * k);
        Paint.roundedRect(shapes, cx, top, this.controlW, this.controlH, this.controlH / 2f, 1f, fill, border);
        float icon = Math.round(this.controlH * 0.42f), inset = Math.round(this.controlH * 0.32f);
        int dim = Rgb.alpha(TEXT_3, a * k), lit = Rgb.alpha(GOLD, k);
        float iy = Math.round(cy - icon / 2f);
        Paint.icon(shapes, LineIcon.LEFT, cx + inset, iy, icon, over && leftHalf ? lit : dim);
        Paint.icon(shapes, LineIcon.RIGHT, cx + this.controlW - inset - icon, iy, icon, over && !leftHalf ? lit : dim);
        String value = SettingsScreen.fit(choice.shown(), this.valueFont, this.controlW - 2f * (inset + icon + 4f));
        float vw = Paint.measure(value, this.valueFont, 0f);
        paint.text(value, this.valueFont, Math.round(cx + (this.controlW - vw) / 2f), Math.round(cy + Fonts.size(this.valueFont) * 0.36f), Rgb.alpha(TEXT, a * k), 0f);
    }

    private void extractText(Paint paint, QuadBatch shapes, Setting.Text text, float cx, float cy, boolean over, float a, float k) {
        float top = Math.round(cy - this.controlH / 2f);
        boolean typing = this.editing == text;
        int border = typing ? (this.draftValid ? Rgb.alpha(GOLD, 0.65f * k) : Rgb.alpha(RED, 0.85f * k)) : Rgb.alpha(0xFFFFFF, (over ? 0.22f : 0.11f) * a * k);
        Paint.roundedRect(shapes, cx, top, this.controlW, this.controlH, this.controlH / 2f, 1f, Rgb.alpha(0xFFFFFF, (typing ? 0.07f : 0.045f) * a * k), border);
        float inner = Math.round(this.controlH * 0.45f), maxW = this.controlW - 2f * inner;
        String shown = typing ? this.draft : text.value();
        if (typing) {
            // While typing, the end of the text shows: that's where the caret is.
            while (shown.length() > 1 && Paint.measure(shown, this.valueFont, 0f) > maxW) {
                shown = shown.substring(1);
            }
        } else {
            shown = SettingsScreen.fit(shown, this.valueFont, maxW);
        }
        float size = Fonts.size(this.valueFont);
        float tx = cx + inner, base = Math.round(cy + size * 0.36f), tw = Paint.measure(shown, this.valueFont, 0f);
        if (typing && this.selectAll && !shown.isEmpty()) {
            shapes.rect(tx - 2f, Math.round(base - size * 0.85f), tx + tw + 2f, Math.round(base + size * 0.25f), Rgb.alpha(GOLD, 0.3f * k));
        }
        paint.text(shown, this.valueFont, tx, base, Rgb.alpha(TEXT, a * k), 0f);
        if (typing && ((System.nanoTime() - this.caretFrom) / 530_000_000L) % 2 == 0) {
            float caret = Math.round(tx + tw + 1f);
            shapes.rect(caret, Math.round(base - size * 0.82f), caret + Math.max(1f, Math.round(this.s)), Math.round(base + size * 0.18f), Rgb.alpha(GOLD, k));
        }
    }

    private void extractButton(Paint paint, QuadBatch shapes, String label, float bx, float cy, boolean over, boolean enabled, float k) {
        float bw = this.buttonWidth(label), top = Math.round(cy - this.controlH / 2f);
        float a = enabled ? 1f : 0.42f;
        int border = over ? Rgb.alpha(GOLD, 0.75f * k) : Rgb.alpha(0xFFFFFF, 0.16f * a * k);
        Paint.roundedRect(shapes, bx, top, bw, this.controlH, this.controlH / 2f, 1f, Rgb.alpha(0xFFFFFF, (over ? 0.09f : 0.04f) * a * k), border);
        int color = over ? Rgb.alpha(0xF6E3BC, k) : Rgb.alpha(TEXT, a * k);
        paint.text(label, this.buttonFont, Math.round(bx + this.buttonPad), Math.round(cy + Fonts.size(this.buttonFont) * 0.36f), color, this.spacing);
    }

    // ------------------------------------------------------------------ input

    boolean contains(double mx, double my) {
        return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
    }

    boolean isTyping() {
        return this.editing != null;
    }

    boolean isDragging() {
        return this.dragging != null;
    }

    private Item itemAt(double my) {
        for (Item item : this.items) {
            float top = this.listTop - this.scroll + item.top;
            if (my >= top && my < top + item.height) {
                return item;
            }
        }
        return null;
    }

    /** A click at (mx, my) in screen pixels; right-click steps choices backwards. */
    boolean mouseClicked(double mx, double my, int button) {
        if (this.stale) {
            this.relayout();
        }
        boolean back = button == InputConstants.MOUSE_BUTTON_RIGHT;
        if (button != InputConstants.MOUSE_BUTTON_LEFT && !back) {
            return this.contains(mx, my);
        }
        for (Chip chip : this.chips) {
            if (mx >= chip.x && mx < chip.x + chip.w && my >= chip.y && my < chip.y + chip.h) {
                this.commitEdit();
                this.showPage(chip.page);
                return true;
            }
        }
        Item item = my >= this.listTop && my < this.listBottom ? this.itemAt(my) : null;
        if (item == null || item.setting == this.editing && this.editing != null && !this.onControl(item, mx, my)) {
            this.commitEdit();
            return this.contains(mx, my);
        }
        float right = this.x + this.w - this.gutter;
        if (item.kind == Kind.MORE) {
            this.commitEdit();
            float bw = this.buttonWidth(OPEN), bx = right - this.padX - bw;
            if (mx >= bx && mx < bx + bw) {
                this.openFull.run();
            }
            return true;
        }
        Setting setting = item.setting;
        if (setting != this.editing) {
            this.commitEdit();
        }
        if (setting == null || setting instanceof Setting.Note || !setting.enabled()) {
            return true;
        }
        float cw = this.controlWidth(setting), cx = Math.round(right - this.padX - cw);
        boolean onControl = this.onControl(item, mx, my);
        try {
            if (setting instanceof Setting.Toggle toggle) {
                // The whole row flips a switch, not just the switch itself.
                toggle.set(!toggle.on());
                this.save();
            } else if (setting instanceof Setting.Choice choice && onControl) {
                choice.step(back || mx < cx + cw / 2f ? -1 : 1);
                this.save();
            } else if (setting instanceof Setting.Slider slider && mx >= cx - 4f && mx < this.track(cx)[1] + this.knobSize()) {
                float[] track = this.track(cx);
                this.dragging = slider;
                this.dragFrom = track[0];
                this.dragTo = track[1];
                this.drag(mx);
            } else if (setting instanceof Setting.Text text && onControl && this.editing != text) {
                this.startEdit(text);
            } else if (setting instanceof Setting.Action action && onControl) {
                action.run();
            }
        } catch (Throwable error) {
            CubeUtilityClient.LOGGER.warn("Changing \"{}\" failed", setting.name, error);
        }
        return true;
    }

    private boolean onControl(Item item, double mx, double my) {
        if (item.setting == null) {
            return false;
        }
        float right = this.x + this.w - this.gutter;
        float cw = this.controlWidth(item.setting), cx = Math.round(right - this.padX - cw);
        float cy = this.listTop - this.scroll + item.top + item.height / 2f;
        return mx >= cx && mx < cx + cw && Math.abs(my - cy) <= this.controlH / 2f + 4f;
    }

    private void drag(double mx) {
        this.dragging.setFraction((mx - this.dragFrom) / Math.max(1f, this.dragTo - this.dragFrom));
    }

    boolean mouseDragged(double mx) {
        if (this.dragging == null) {
            return false;
        }
        try {
            this.drag(mx);
        } catch (Throwable error) {
            CubeUtilityClient.LOGGER.warn("Changing \"{}\" failed", this.dragging.name, error);
            this.dragging = null;
        }
        return true;
    }

    boolean mouseReleased() {
        if (this.dragging == null) {
            return false;
        }
        this.dragging = null;
        this.save();
        return true;
    }

    boolean mouseScrolled(double amount) {
        this.scrollGoal -= (float) amount * Math.max(40f, 64f * this.s);
        return true;
    }

    /** The previous or next category, from the keyboard. */
    void stepPage(int by) {
        int count = this.model.pages().size();
        if (count > 1) {
            this.commitEdit();
            this.showPage(Math.floorMod(this.page + by, count));
        }
    }

    /** Scrolls most of a screenful, from the keyboard. */
    void scrollView(int direction) {
        this.scrollGoal += direction * (this.listBottom - this.listTop) * 0.85f;
    }

    private void showPage(int page) {
        if (page != this.page) {
            this.page = page;
            PAGES.put(this.key, page);
            this.scroll = 0f;
            this.scrollGoal = 0f;
            this.stale = true;
        }
    }

    private void startEdit(Setting.Text text) {
        this.editing = text;
        this.before = text.value();
        this.draft = this.before;
        this.lastGood = this.before;
        this.draftValid = true;
        this.selectAll = true;
        this.caretFrom = System.nanoTime();
    }

    /** Keeps what was typed (or the last thing that made sense) and saves it. */
    void commitEdit() {
        if (this.editing == null) {
            return;
        }
        Setting.Text text = this.editing;
        this.editing = null;
        try {
            if (!this.draftValid) {
                text.set(this.lastGood);
            }
        } catch (Throwable error) {
            CubeUtilityClient.LOGGER.warn("Changing \"{}\" failed", text.name, error);
        }
        this.save();
    }

    private void cancelEdit() {
        if (this.editing == null) {
            return;
        }
        Setting.Text text = this.editing;
        this.editing = null;
        try {
            text.set(this.before);
        } catch (Throwable error) {
            CubeUtilityClient.LOGGER.warn("Changing \"{}\" failed", text.name, error);
        }
        this.save();
    }

    private void type(String next) {
        this.draft = next;
        this.selectAll = false;
        this.caretFrom = System.nanoTime();
        try {
            this.draftValid = this.editing.set(next);
        } catch (Throwable error) {
            this.draftValid = false;
        }
        if (this.draftValid) {
            this.lastGood = next;
        }
    }

    /** Keys while a text field is being typed in; everything else is left to the page. */
    boolean keyPressed(KeyEvent event) {
        if (this.editing == null) {
            return false;
        }
        if (event.isEscape()) {
            this.cancelEdit();
        } else if (event.isConfirmation()) {
            this.commitEdit();
        } else if (event.isSelectAll()) {
            this.selectAll = true;
        } else if (event.isPaste()) {
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard().replaceAll("[\\r\\n\\t]+", " ");
            this.type(((this.selectAll ? "" : this.draft) + clip).substring(0, Math.min(64, (this.selectAll ? 0 : this.draft.length()) + clip.length())));
        } else if (event.key() == InputConstants.KEY_BACKSPACE) {
            if (this.selectAll) {
                this.type("");
            } else if (!this.draft.isEmpty()) {
                this.type(event.hasControlDown() ? this.draft.replaceAll("\\S*\\s*$", "") : this.draft.substring(0, this.draft.offsetByCodePoints(this.draft.length(), -1)));
            }
        }
        // While typing, keys belong to the text: Right Shift and the arrows don't leave the field.
        return true;
    }

    boolean charTyped(CharacterEvent event) {
        if (this.editing == null) {
            return false;
        }
        if (event.isAllowedChatCharacter()) {
            String base = this.selectAll ? "" : this.draft;
            if (base.length() < 64) {
                this.type(base + event.codepointAsString());
            }
        }
        return true;
    }

    /** Saves what changed; the page calls this when the tab closes, too. */
    void save() {
        try {
            this.model.save();
        } catch (Throwable error) {
            CubeUtilityClient.LOGGER.warn("Saving the settings of {} failed", this.key, error);
        }
    }

    /** The tab is closing: finish any typing and save. */
    void close() {
        this.commitEdit();
        this.dragging = null;
        this.save();
    }

    // ------------------------------------------------------------------ text

    /** Breaks text into lines that fit; past {@code maxLines} the last line ends with "…". */
    private static List<String> wrap(String text, FontDescription font, float maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank() || maxWidth <= 0f) {
            return lines;
        }
        String[] words = text.trim().split("\\s+");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String attempt = line.isEmpty() ? words[i] : line + " " + words[i];
            if (line.isEmpty() || Paint.measure(attempt, font, 0f) <= maxWidth) {
                line.setLength(0);
                line.append(attempt);
                continue;
            }
            if (lines.size() == maxLines - 1) {
                // No room for more lines: this one takes the rest, cut short.
                StringBuilder rest = new StringBuilder(line);
                for (int j = i; j < words.length; j++) {
                    rest.append(' ').append(words[j]);
                }
                lines.add(SettingsScreen.fit(rest.toString(), font, maxWidth));
                return lines;
            }
            lines.add(SettingsScreen.fit(line.toString(), font, maxWidth));
            line.setLength(0);
            line.append(words[i]);
        }
        if (!line.isEmpty()) {
            lines.add(SettingsScreen.fit(line.toString(), font, maxWidth));
        }
        return lines;
    }

    /** {@link SettingsScreen#fit} for letter-spaced text. */
    private static String fit(String text, FontDescription font, float maxWidth, float spacing) {
        if (maxWidth <= 0f || Paint.measure(text, font, spacing) <= maxWidth) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && Paint.measure(cut + "…", font, spacing) > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut.stripTrailing() + "…";
    }
}
