package dev.cubeutilityclient.settings;

import dev.cubeutilityclient.render.Fonts;
import dev.cubeutilityclient.render.LineIcon;
import dev.cubeutilityclient.render.Motion;
import dev.cubeutilityclient.render.Paint;
import dev.cubeutilityclient.render.QuadBatch;
import dev.cubeutilityclient.render.Rgb;
import dev.cubeutilityclient.render.SpaceTextures;
import dev.cubeutilityclient.shelf.Shelf;
import dev.cubeutilityclient.shelf.ShelfMod;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Get more: popular mods that come with the client asleep, as cards. Get switches one on right
 * away, with no download and no restart, in a little burst of gold; from then on it has its own
 * tab under "Added mods". A got mod shows On; hovering that offers Remove, which puts it back to
 * sleep, and clicking the card opens its tab.
 */
final class ShelfPanel {
    private static final int TEXT = 0xE9EFEE, TEXT_2 = 0x9AABA9, TEAL = 0x46C2B8, GOLD = 0xE4C084;
    private static final Identifier GLOW = SpaceTextures.texture("glow");
    /** How long the Get burst lasts, in seconds. */
    private static final float BURST = 0.9f;
    private static final int SPARKS = 14;

    private final SettingsScreen page;
    private final List<Card> cards = new ArrayList<>();
    private final Random random = new Random();
    private float x, y, w, h, s, g;
    private float scroll, scrollGoal, contentHeight;
    private long lastFrame = System.nanoTime();
    // The cards' shared measurements, from place().
    private float pad, gap, iconSize, cardH, buttonW, buttonH, tick, spacing;
    private FontDescription nameFont, aboutFont, buttonFont;

    ShelfPanel(SettingsScreen page) {
        this.page = page;
        for (ShelfMod mod : Shelf.mods()) {
            this.cards.add(new Card(mod));
        }
    }

    // ------------------------------------------------------------------ layout

    /** The panel's area in screen pixels, and the page's scale. */
    void place(float x, float y, float w, float h, float s, float g) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.s = s;
        this.g = g;
        this.pad = Math.round(Math.max(12f, 16f * s));
        this.gap = Math.round(Math.max(10f, 14f * s));
        this.iconSize = Math.round(Math.max(36f, 48f * s));
        this.nameFont = Fonts.label(Math.max(12f, 16f * s));
        this.aboutFont = Fonts.label(Math.max(10f, 12f * s));
        this.buttonFont = Fonts.strong(Math.max(10f, 12f * s));
        this.spacing = Math.max(1.2f, 1.8f * s);
        this.buttonH = Math.round(Math.max(26f, 32f * s));
        this.tick = Math.round(this.buttonH * 0.42f);
        // As wide as its widest label, so it doesn't change size between Get, On and Remove.
        float widest = Math.max(Paint.measure("REMOVE", this.buttonFont, this.spacing), Paint.measure("ON", this.buttonFont, this.spacing) + this.tick + 6f * s);
        this.buttonW = Math.round(Math.max(this.buttonH * 2.4f, widest + this.buttonH * 0.9f));
        this.cardH = Math.round(2f * this.pad + Math.max(this.iconSize, Fonts.size(this.nameFont) + this.nameGap() + 2f * this.lineHeight()));
        float minWidth = Math.max(250f, 330f * s);
        int columns = Math.max(1, Math.min(3, (int) ((w + this.gap) / (minWidth + this.gap))));
        float cardW = (w - this.gap * (columns - 1)) / columns;
        for (int i = 0; i < this.cards.size(); i++) {
            Card card = this.cards.get(i);
            int column = i % columns, row = i / columns;
            float left = x + column * (cardW + this.gap);
            card.x = Math.round(left);
            card.w = Math.round(left + cardW) - card.x;
            card.y = Math.round(y + row * (this.cardH + this.gap));
            card.h = this.cardH;
            card.lines = null;
        }
        int rows = (this.cards.size() + columns - 1) / columns;
        this.contentHeight = rows * this.cardH + Math.max(0, rows - 1) * this.gap;
    }

    private float lineHeight() {
        return Fonts.size(this.aboutFont) + Math.max(3f, 4f * this.s);
    }

    private float nameGap() {
        return Math.max(5f, 7f * this.s);
    }

    boolean contains(double mx, double my) {
        return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
    }

    // ------------------------------------------------------------------ drawing

    void extract(GuiGraphicsExtractor graphics, Paint paint, float mx, float my, float k) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - this.lastFrame) / 1e9f);
        this.lastFrame = now;
        float maxScroll = Math.max(0f, this.contentHeight - this.h);
        this.scrollGoal = Math.max(0f, Math.min(maxScroll, this.scrollGoal));
        this.scroll += (this.scrollGoal - this.scroll) * Math.min(1f, dt * 14f);

        if (this.cards.isEmpty()) {
            FontDescription font = Fonts.label(Math.max(12f, 16f * this.s));
            String text = "Nothing to get right now.";
            paint.text(text, font, Math.round(this.x + (this.w - Paint.measure(text, font, 0f)) / 2f), Math.round(this.y + this.h * 0.38f), Rgb.alpha(TEXT, k), 0f);
            return;
        }
        boolean inside = this.contains(mx, my);
        graphics.enableScissor((int) Math.floor(this.x / this.g) - 1, (int) Math.floor(this.y / this.g), (int) Math.ceil((this.x + this.w) / this.g) + 1, (int) Math.ceil((this.y + this.h) / this.g));
        for (Card card : this.cards) {
            float top = card.y - this.scroll;
            if (top + card.h >= this.y && top <= this.y + this.h) {
                card.extract(graphics, paint, top, inside ? mx : -1f, inside ? my : -1f, k);
            }
        }
        // Bursts last, so their sparks fly over the cards next door.
        for (Card card : this.cards) {
            card.extractBurst(paint, card.y - this.scroll, k);
        }
        graphics.disableScissor();
        if (maxScroll > 0f) {
            float thumb = Math.max(24f, this.h * this.h / this.contentHeight);
            float ty = this.y + (this.h - thumb) * (this.scroll / maxScroll);
            Paint.roundedRect(paint.solid(), this.x + this.w + Math.max(4f, 6f * this.s), ty, 3f, thumb, 1.5f, 0f, Rgb.alpha(GOLD, 0.35f * k), 0);
        }
    }

    // ------------------------------------------------------------------ input

    /** A click at a point in screen pixels, inside the panel. */
    boolean mouseClicked(double mx, double my) {
        for (Card card : this.cards) {
            float top = card.y - this.scroll;
            if (mx >= card.x && mx < card.x + card.w && my >= top && my < top + card.h) {
                if (card.buttonHit(mx, my, top)) {
                    if (Shelf.isOn(card.mod)) {
                        this.remove(card);
                    } else {
                        this.get(card);
                    }
                } else if (Shelf.isOn(card.mod) && this.page.hasTab(card.mod.id)) {
                    this.page.openTab(card.mod.id);
                }
                return true;
            }
        }
        return true;
    }

    boolean mouseScrolled(double amount) {
        if (this.contentHeight <= this.h) {
            return false;
        }
        this.scrollGoal -= (float) amount * Math.max(40f, 60f * this.s);
        return true;
    }

    /** Gets a mod, as if its Get button was pressed. */
    void get(String id) {
        for (Card card : this.cards) {
            if (card.mod.id.equals(id) && !Shelf.isOn(card.mod)) {
                this.get(card);
            }
        }
    }

    private void get(Card card) {
        if (!Shelf.get(card.mod)) {
            card.failed = System.nanoTime();
            return;
        }
        card.got = System.nanoTime();
        // The cursor is still on the button: it shows On, not Remove, until it moves off.
        card.justGot = true;
        card.burst();
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.25f, 1f));
        this.page.shelfChanged(card.mod, true);
    }

    private void remove(Card card) {
        Shelf.remove(card.mod);
        card.got = 0L;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
        this.page.shelfChanged(card.mod, false);
    }

    // ------------------------------------------------------------------ one mod

    private final class Card {
        final ShelfMod mod;
        /** The mod's icon, or null (the TNT timer shows a TNT block). */
        final Identifier icon;
        final Motion hover = new Motion(), buttonHover = new Motion(), on;
        float x, y, w, h;
        /** When Get was pressed (nanoTime), or 0. */
        long got;
        /** When Get failed (nanoTime), or 0. */
        long failed;
        boolean justGot;
        /** Each spark's angle, speed, size and a random phase. */
        final float[] sparks = new float[SPARKS * 4];
        /** What the mod does, wrapped to the card, and the width it was wrapped to. */
        List<String> lines;
        float linesWidth;
        /** The button's rectangle, relative to the card. */
        float bx, by;

        Card(ShelfMod mod) {
            this.mod = mod;
            this.icon = mod.builtIn ? null : ModEntry.iconOf(mod.id);
            this.on = new Motion(Shelf.isOn(mod) ? 1f : 0f);
        }

        void burst() {
            for (int i = 0; i < SPARKS; i++) {
                this.sparks[i * 4] = (float) (Math.PI * 2 * i / SPARKS + ShelfPanel.this.random.nextFloat() * 0.5f);
                this.sparks[i * 4 + 1] = 0.6f + ShelfPanel.this.random.nextFloat() * 0.8f;
                this.sparks[i * 4 + 2] = 0.6f + ShelfPanel.this.random.nextFloat() * 0.7f;
                this.sparks[i * 4 + 3] = ShelfPanel.this.random.nextFloat();
            }
        }

        float sinceGot() {
            return this.got == 0L ? 99f : (System.nanoTime() - this.got) / 1e9f;
        }

        boolean buttonHit(double mx, double my, float top) {
            ShelfPanel p = ShelfPanel.this;
            return mx >= this.x + this.bx && mx < this.x + this.bx + p.buttonW && my >= top + this.by && my < top + this.by + p.buttonH;
        }

        void extract(GuiGraphicsExtractor graphics, Paint paint, float top, float mx, float my, float k) {
            ShelfPanel p = ShelfPanel.this;
            float s = p.s;
            boolean isOn = Shelf.isOn(this.mod);
            float onK = this.on.towards(isOn ? 1f : 0f, 0.3f);
            boolean over = mx >= this.x && mx < this.x + this.w && my >= top && my < top + this.h;
            float hoverK = this.hover.towards(over ? 1f : 0f, 0.2f);
            float since = this.sinceGot();
            float flash = since < BURST ? 1f - since / BURST : 0f;
            boolean opens = isOn && p.page.hasTab(this.mod.id);
            this.bx = this.w - p.pad - p.buttonW;
            this.by = Math.round((this.h - p.buttonH) / 2f);
            float bx = this.x + this.bx, by = top + this.by;

            // The card: glass, with a hairline that warms to gold on hover, while on, and on Get.
            int border = Rgb.lerp(Rgb.alpha(0xFFFFFF, (0.08f + 0.06f * hoverK) * k), Rgb.alpha(GOLD, 0.8f * k), Math.max(flash, 0.3f * onK));
            Paint.roundedRect(paint.solid(), this.x, top, this.w, this.h, 16f * s, 1f, Rgb.alpha(0xFFFFFF, (0.035f + 0.025f * hoverK) * k), border);
            if (flash > 0f) {
                paint.texture(SpaceTextures.HAZE, true).rect(this.x - 30f * s, top - 30f * s, this.x + this.w + 30f * s, top + this.h + 30f * s, Rgb.alpha(GOLD, 0.16f * flash * k));
            }

            // The icon, with a small pop on Get.
            float pop = since < 0.4f ? 1f + 0.16f * (float) Math.sin(Math.PI * since / 0.4f) : 1f;
            float size = p.iconSize, ix = this.x + p.pad, iy = Math.round(top + (this.h - size) / 2f);
            float drawn = size * pop, dx = ix - (drawn - size) / 2f, dy = iy - (drawn - size) / 2f;
            if (this.mod.builtIn) {
                tnt(graphics, dx, dy, drawn, p.g);
            } else if (this.icon != null) {
                paint.texture(this.icon, false).rect(dx, dy, dx + drawn, dy + drawn, Rgb.alpha(0xFFFFFF, k));
            } else {
                QuadBatch tile = paint.solid();
                Paint.roundedRect(tile, dx, dy, drawn, drawn, drawn * 0.26f, 0f, Rgb.alpha(TEAL, 0.13f * k), 0);
                float mark = Math.round(drawn * 0.5f);
                Paint.icon(tile, LineIcon.MODS, Math.round(dx + (drawn - mark) / 2f), Math.round(dy + (drawn - mark) / 2f), mark, Rgb.alpha(TEAL, k));
            }

            // Its name and what it does, between the icon and the button.
            float textX = ix + size + Math.round(Math.max(10f, 14f * s));
            float textW = bx - Math.round(Math.max(10f, 14f * s)) - textX;
            if (this.lines == null || this.linesWidth != textW) {
                this.lines = wrap(this.mod.about, p.aboutFont, textW, 2);
                this.linesWidth = textW;
            }
            float nameSize = Fonts.size(p.nameFont), aboutSize = Fonts.size(p.aboutFont), lineH = p.lineHeight();
            float block = nameSize + p.nameGap() + (this.lines.size() - 1) * lineH + aboutSize;
            float textTop = Math.round(top + (this.h - block) / 2f);
            float chevron = Math.round(nameSize * 0.8f);
            String title = SettingsScreen.fit(this.mod.title, p.nameFont, textW - (opens ? chevron + 4f * s : 0f));
            float nameBase = Math.round(textTop + nameSize * 0.8f);
            paint.text(title, p.nameFont, textX, nameBase, Rgb.lerp(Rgb.alpha(TEXT, k), Rgb.alpha(GOLD, k), opens ? hoverK : 0f), 0f);
            if (opens && hoverK > 0.02f) {
                // Clicking the card opens its settings.
                Paint.icon(paint.solid(), LineIcon.RIGHT, Math.round(textX + Paint.measure(title, p.nameFont, 0f) + 4f * s), Math.round(nameBase - nameSize * 0.72f), chevron,
                        Rgb.alpha(GOLD, hoverK * k));
            }
            for (int i = 0; i < this.lines.size(); i++) {
                float base = Math.round(textTop + nameSize + p.nameGap() + i * lineH + aboutSize * 0.8f);
                paint.text(this.lines.get(i), p.aboutFont, textX, base, Rgb.alpha(TEXT_2, k), 0f);
            }

            // The button: a gold Get, or a glass On with a tick that offers Remove on hover.
            boolean overButton = this.buttonHit(mx, my, top);
            if (!overButton) {
                this.justGot = false;
            }
            float buttonK = this.buttonHover.towards(overButton && !this.justGot ? 1f : 0f, 0.15f);
            QuadBatch button = paint.solid();
            float textBase = Math.round(by + p.buttonH / 2f + Fonts.size(p.buttonFont) * 0.36f);
            if (!isOn) {
                // Brighter on hover; it dims for a moment if the mod couldn't wake.
                float failK = this.failed == 0L ? 0f : Math.max(0f, 1f - (System.nanoTime() - this.failed) / 1.2e9f);
                int fill = Rgb.lerp(Rgb.lerp(Rgb.alpha(0xF7E6C2, k), Rgb.alpha(0xC29756, k), 0.45f - 0.25f * buttonK), Rgb.alpha(0x8A6A3A, k), failK);
                Paint.capsule(button, bx, by, p.buttonW, p.buttonH, 0f, (px, py) -> fill, 0, false);
                float textW2 = Paint.measure("GET", p.buttonFont, p.spacing);
                paint.text("GET", p.buttonFont, Math.round(bx + (p.buttonW - textW2) / 2f), textBase, Rgb.alpha(0x15100A, k), p.spacing);
            } else {
                boolean remove = buttonK > 0.5f;
                int fill = Rgb.alpha(0xFFFFFF, (0.05f + 0.05f * buttonK) * k);
                int edge = Rgb.lerp(Rgb.alpha(GOLD, 0.55f * k), Rgb.alpha(0xFF8A7A, 0.7f * k), buttonK);
                Paint.capsule(button, bx, by, p.buttonW, p.buttonH, 1f, (px, py) -> fill, edge, false);
                int ink = Rgb.lerp(Rgb.alpha(GOLD, k), Rgb.alpha(0xFFB4A8, k), buttonK);
                String text = remove ? "REMOVE" : "ON";
                float tick = remove ? 0f : p.tick;
                float total = Paint.measure(text, p.buttonFont, p.spacing) + (tick > 0f ? tick + 6f * s : 0f);
                float tx = Math.round(bx + (p.buttonW - total) / 2f);
                if (tick > 0f) {
                    Paint.icon(button, LineIcon.CHECK, tx, Math.round(by + (p.buttonH - tick) / 2f), tick, ink);
                    tx += tick + 6f * s;
                }
                paint.text(text, p.buttonFont, Math.round(tx), textBase, ink, p.spacing);
            }
        }

        /** After Get: gold sparks fly out of the button and fade, and a ring widens around it. */
        void extractBurst(Paint paint, float top, float k) {
            float since = this.sinceGot();
            if (since >= BURST) {
                return;
            }
            ShelfPanel p = ShelfPanel.this;
            float s = p.s, t = since / BURST;
            float bx = this.x + this.bx, by = top + this.by;
            float cx = bx + p.buttonW / 2f, cy = by + p.buttonH / 2f;
            QuadBatch glow = paint.texture(GLOW, true);
            for (int i = 0; i < SPARKS; i++) {
                float angle = this.sparks[i * 4], speed = this.sparks[i * 4 + 1], sizeK = this.sparks[i * 4 + 2];
                float travel = (1f - (1f - t) * (1f - t)) * speed * 70f * s;
                float sx = cx + (float) Math.cos(angle) * travel * 1.4f, sy = cy + (float) Math.sin(angle) * travel;
                float r = (5f + 7f * sizeK) * s * (1f - 0.6f * t);
                glow.rect(sx - r, sy - r, sx + r, sy + r, Rgb.alpha(i % 3 == 0 ? 0xFFF4DA : GOLD, (1f - t) * k));
            }
            float ring = t * 46f * s;
            Paint.capsule(paint.solid(), bx - ring, by - ring * 0.6f, p.buttonW + 2f * ring, p.buttonH + 1.2f * ring, 1f, (px, py) -> 0, Rgb.alpha(GOLD, 0.5f * (1f - t) * k), false);
        }
    }

    /** A TNT block where a mod's icon would be: the TNT timer is part of this client. */
    private static void tnt(GuiGraphicsExtractor graphics, float x, float y, float size, float g) {
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x / g, y / g);
        pose.scale(size / g / 16f, size / g / 16f);
        graphics.fakeItem(new ItemStack(Items.TNT), 0, 0);
        pose.popMatrix();
    }

    /** Words onto at most {@code lines} lines, the last ending in "…" when they don't fit. */
    private static List<String> wrap(String text, FontDescription font, float width, int lines) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        String[] words = text.split(" ");
        for (int i = 0; i < words.length; i++) {
            String next = line.isEmpty() ? words[i] : line + " " + words[i];
            if (Paint.measure(next, font, 0f) <= width || line.isEmpty()) {
                line.setLength(0);
                line.append(next);
                continue;
            }
            if (out.size() == lines - 1) {
                // No more room: the rest goes on this last line, cut short.
                StringBuilder rest = new StringBuilder(line);
                for (int j = i; j < words.length; j++) {
                    rest.append(' ').append(words[j]);
                }
                out.add(SettingsScreen.fit(rest.toString(), font, width));
                return out;
            }
            out.add(line.toString());
            line.setLength(0);
            line.append(words[i]);
        }
        if (!line.isEmpty()) {
            out.add(SettingsScreen.fit(line.toString(), font, width));
        }
        return out;
    }
}
