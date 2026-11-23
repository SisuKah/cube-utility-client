package dev.cubeutilityclient.settings;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cubeutilityclient.render.Fonts;
import dev.cubeutilityclient.render.LineIcon;
import dev.cubeutilityclient.render.Motion;
import dev.cubeutilityclient.render.Paint;
import dev.cubeutilityclient.render.QuadBatch;
import dev.cubeutilityclient.render.Rgb;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

/**
 * Browse Modrinth: search Modrinth for mods that run on the pack's Minecraft and add one, with
 * the libraries it needs, through the launcher ({@link ModrinthLink}). A running game can't load
 * a new mod, so what you add is ready the next time Minecraft starts, and a mod you remove goes
 * when it closes. Mods added here show in the launcher's Mods screen too, and the other way round.
 */
final class ModrinthPanel {
    private static final int TEXT = 0xE9EFEE, TEXT_2 = 0x9AABA9, TEXT_3 = 0x7E8F8D, TEAL = 0x46C2B8, GOLD = 0xE4C084, GOLD_DIM = 0xCDA76A, RED = 0xFF8A7A;
    /** The search goes out once typing pauses this long, in nanoseconds. */
    private static final long SETTLE = 350_000_000L;
    /** What was searched last, so the panel opens on it again. */
    private static String lastQuery = "";
    /** Mods added while this game runs: they're ready the next time it starts. */
    private static final Set<String> ADDED_THIS_GAME = new HashSet<>();

    private enum State {
        ADD, ADDING, ADDED, ON, REMOVING, LIBRARY, IN_PACK
    }

    private final ModrinthLink link = ModrinthLink.find();
    /** The page's lines: section headings and mods, in order. */
    private final List<Item> items = new ArrayList<>();
    private final List<ModrinthLink.Hit> results = new ArrayList<>();
    private final Map<String, ModrinthLink.Installed> installed = new HashMap<>();
    private final Set<String> adding = new HashSet<>();
    private final Map<String, Card> cards = new HashMap<>();
    private String query = lastQuery;
    private boolean typing;
    private long caretFrom = System.nanoTime();
    /** When to send the search (nanoTime), or 0. */
    private long searchAt;
    /** Answers to older searches are ignored. */
    private int generation;
    private boolean loading, loadingMore;
    private String error;
    private int total, nextOffset;
    private String minecraft = "";

    // The layout, in screen pixels.
    private float x, y, w, h, s, g;
    private float fieldH, listTop, scroll, scrollGoal, contentHeight;
    private long lastFrame = System.nanoTime();
    private float pad, gap, iconSize, cardH, headerH, buttonW, buttonH, tick, spacing;
    private int columns = 1;
    private FontDescription nameFont, aboutFont, metaFont, buttonFont, fieldFont, headerFont;

    /** A heading, or a mod. */
    private record Item(String heading, Card card) {
    }

    ModrinthPanel() {
        if (this.link != null) {
            this.refreshInstalled();
            this.search(0);
        }
    }

    // ------------------------------------------------------------------ talking to the launcher

    private void search(int offset) {
        int asked = offset == 0 ? ++this.generation : this.generation;
        String words = this.query.trim();
        if (offset == 0) {
            this.loading = true;
            this.error = null;
        } else {
            this.loadingMore = true;
        }
        this.link.search(words, offset).whenComplete((page, failure) -> {
            if (asked != this.generation) {
                return;
            }
            this.loading = false;
            this.loadingMore = false;
            if (failure != null) {
                this.error = ModrinthLink.reason(failure);
                return;
            }
            if (offset == 0) {
                this.results.clear();
                this.scroll = this.scrollGoal = 0f;
            }
            for (ModrinthLink.Hit hit : page.hits()) {
                if (this.results.stream().noneMatch(old -> old.projectId().equals(hit.projectId()))) {
                    this.results.add(hit);
                }
            }
            this.total = page.total();
            this.nextOffset = page.nextOffset();
            this.minecraft = page.minecraft();
            this.rebuild();
        });
    }

    private void refreshInstalled() {
        this.link.installed().whenComplete((list, failure) -> {
            if (failure == null) {
                this.setInstalled(list);
            }
        });
    }

    private void setInstalled(List<ModrinthLink.Installed> list) {
        this.installed.clear();
        for (ModrinthLink.Installed mod : list) {
            this.installed.put(mod.projectId(), mod);
        }
        this.rebuild();
    }

    private State state(Card card) {
        if (this.adding.contains(card.id)) {
            return State.ADDING;
        }
        ModrinthLink.Installed mine = this.installed.get(card.id);
        if (mine != null) {
            if (!mine.picked()) {
                return State.LIBRARY;
            }
            if (mine.removing()) {
                return State.REMOVING;
            }
            return ADDED_THIS_GAME.contains(card.id) ? State.ADDED : State.ON;
        }
        return "inPack".equals(card.status) ? State.IN_PACK : State.ADD;
    }

    private void press(Card card) {
        State state = this.state(card);
        String id = card.id;
        switch (state) {
            case ADD -> {
                this.adding.add(id);
                this.link.install(id).whenComplete((libraries, failure) -> {
                    this.adding.remove(id);
                    if (failure != null) {
                        card.fail(ModrinthLink.reason(failure));
                        return;
                    }
                    ADDED_THIS_GAME.add(id);
                    card.added = System.nanoTime();
                    card.note = libraries > 0 ? "Added with " + libraries + (libraries == 1 ? " library it needs." : " libraries it needs.") : null;
                    Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.25f, 1f));
                    this.refreshInstalled();
                });
            }
            case ADDED, ON -> this.link.remove(id).whenComplete((list, failure) -> {
                if (failure != null) {
                    card.fail(ModrinthLink.reason(failure));
                    return;
                }
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
                this.setInstalled(list);
            });
            case REMOVING -> this.link.keep(id).whenComplete((list, failure) -> {
                if (failure != null) {
                    card.fail(ModrinthLink.reason(failure));
                    return;
                }
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
                this.setInstalled(list);
            });
            default -> {
                // Adding, a library, or in the pack: nothing to press.
            }
        }
    }

    /** The lines to show: the player's own mods first (when not searching), then the results. */
    private void rebuild() {
        this.items.clear();
        boolean browsing = this.query.trim().isEmpty();
        if (browsing) {
            List<ModrinthLink.Installed> mine = this.installed.values().stream().filter(ModrinthLink.Installed::picked)
                    .sorted((a, b) -> a.title().compareToIgnoreCase(b.title())).toList();
            if (!mine.isEmpty()) {
                this.items.add(new Item("YOUR MODS FROM MODRINTH", null));
                for (ModrinthLink.Installed mod : mine) {
                    Card card = this.card(mod.projectId(), mod.title(), mod.iconUrl(), "installed");
                    card.meta = "Version " + mod.version();
                    card.about = "";
                    this.items.add(new Item(null, card));
                }
            }
            if (!this.results.isEmpty()) {
                this.items.add(new Item(this.minecraft.isEmpty() ? "POPULAR" : "POPULAR FOR MINECRAFT " + this.minecraft, null));
            }
        }
        for (ModrinthLink.Hit hit : this.results) {
            if (browsing && this.installed.containsKey(hit.projectId()) && this.installed.get(hit.projectId()).picked()) {
                continue;
            }
            Card card = this.card(hit.projectId(), hit.title(), hit.iconUrl(), hit.status());
            card.meta = (hit.author().isEmpty() ? "" : "by " + hit.author() + " · ") + downloads(hit.downloads()) + " downloads";
            card.about = hit.description();
            this.items.add(new Item(null, card));
        }
        this.layoutItems();
    }

    /** The card for a mod, kept across searches so its state and animations carry on. */
    private Card card(String id, String title, String iconUrl, String status) {
        Card card = this.cards.computeIfAbsent(id, Card::new);
        card.title = title;
        card.iconUrl = iconUrl;
        card.status = status;
        card.lines = null;
        return card;
    }

    static String downloads(long n) {
        if (n >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fM", n / 1e6).replace(".0M", "M");
        }
        if (n >= 1_000L) {
            return String.format(Locale.ROOT, "%.1fK", n / 1e3).replace(".0K", "K");
        }
        return Long.toString(n);
    }

    // ------------------------------------------------------------------ layout

    void place(float x, float y, float w, float h, float s, float g) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.s = s;
        this.g = g;
        this.pad = Math.round(Math.max(12f, 16f * s));
        this.gap = Math.round(Math.max(8f, 12f * s));
        this.iconSize = Math.round(Math.max(32f, 44f * s));
        this.nameFont = Fonts.label(Math.max(12f, 15f * s));
        this.aboutFont = Fonts.label(Math.max(10f, 12f * s));
        this.metaFont = Fonts.label(Math.max(9f, 11f * s));
        this.buttonFont = Fonts.strong(Math.max(10f, 12f * s));
        this.fieldFont = Fonts.label(Math.max(10f, 13f * s));
        this.headerFont = Fonts.strong(Math.max(9f, 10f * s));
        this.spacing = Math.max(1.2f, 1.8f * s);
        this.buttonH = Math.round(Math.max(26f, 32f * s));
        this.tick = Math.round(this.buttonH * 0.42f);
        float widest = 0f;
        for (String label : new String[] {"ADDING…", "REMOVED", "LIBRARY", "IN PACK", "REMOVE"}) {
            widest = Math.max(widest, Paint.measure(label, this.buttonFont, this.spacing));
        }
        widest = Math.max(widest, Paint.measure("ADDED", this.buttonFont, this.spacing) + this.tick + 6f * s);
        this.buttonW = Math.round(Math.max(this.buttonH * 2.4f, widest + this.buttonH * 0.9f));
        this.fieldH = Math.round(Math.max(28f, 38f * s));
        this.headerH = Math.round(Math.max(22f, 30f * s));
        float line = Fonts.size(this.aboutFont) + Math.max(3f, 4f * s);
        this.cardH = Math.round(2f * this.pad + Math.max(this.iconSize, Fonts.size(this.nameFont) + this.small() + Fonts.size(this.metaFont) + this.small() + 2f * line));
        this.listTop = Math.round(this.y + this.fieldH + Math.max(30f, 40f * s));
        this.columns = Math.max(1, Math.min(2, (int) ((w + this.gap) / (Math.max(300f, 380f * s) + this.gap))));
        this.layoutItems();
    }

    private float small() {
        return Math.max(4f, 5f * this.s);
    }

    private void layoutItems() {
        float cardW = (this.w - this.gap * (this.columns - 1)) / this.columns;
        float top = 0f;
        int column = 0;
        for (Item item : this.items) {
            if (item.heading != null) {
                if (column > 0) {
                    top += this.cardH + this.gap;
                    column = 0;
                }
                top += this.headerH;
                continue;
            }
            Card card = item.card;
            float left = this.x + column * (cardW + this.gap);
            card.x = Math.round(left);
            card.w = Math.round(left + cardW) - card.x;
            card.y = Math.round(top);
            card.lines = null;
            column++;
            if (column == this.columns) {
                column = 0;
                top += this.cardH + this.gap;
            }
        }
        if (column > 0) {
            top += this.cardH + this.gap;
        }
        this.contentHeight = Math.max(0f, top - this.gap);
    }

    boolean contains(double mx, double my) {
        return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
    }

    private boolean onField(double mx, double my) {
        return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.fieldH;
    }

    private float listHeight() {
        return this.y + this.h - this.listTop;
    }

    // ------------------------------------------------------------------ drawing

    void extract(GuiGraphicsExtractor graphics, Paint paint, float mx, float my, float k) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - this.lastFrame) / 1e9f);
        this.lastFrame = now;
        if (this.searchAt != 0L && now >= this.searchAt) {
            this.searchAt = 0L;
            lastQuery = this.query;
            this.search(0);
        }
        this.extractField(paint, k);
        if (this.link == null) {
            this.extractMessage(paint, "Browse Modrinth works when Minecraft is started from the Cube Utility Client launcher.",
                    "Add mods in the launcher's Mods screen, or start the game from the launcher.", k);
            return;
        }
        this.extractStatus(paint, k);

        float view = this.listHeight();
        float maxScroll = Math.max(0f, this.contentHeight - view);
        this.scrollGoal = Math.max(0f, Math.min(maxScroll, this.scrollGoal));
        this.scroll += (this.scrollGoal - this.scroll) * Math.min(1f, dt * 14f);
        // Close to the end: the next page.
        if (!this.loading && !this.loadingMore && this.error == null && this.nextOffset < this.total && this.contentHeight - (this.scroll + view) < this.cardH * 2f) {
            this.search(this.nextOffset);
        }
        if (this.items.isEmpty()) {
            if (!this.loading && this.error == null) {
                String first = this.query.isBlank() ? "Modrinth has nothing to show right now." : "No mods found for “" + this.query.trim() + "”.";
                this.extractMessage(paint, first, "Try other words, or check the spelling.", k);
            }
            return;
        }
        boolean inside = this.contains(mx, my) && my >= this.listTop;
        graphics.enableScissor((int) Math.floor(this.x / this.g) - 1, (int) Math.floor(this.listTop / this.g), (int) Math.ceil((this.x + this.w) / this.g) + 1,
                (int) Math.ceil((this.y + this.h) / this.g));
        float top = 0f;
        int column = 0;
        for (Item item : this.items) {
            if (item.heading != null) {
                if (column > 0) {
                    top += this.cardH + this.gap;
                    column = 0;
                }
                float base = this.listTop + top - this.scroll + this.headerH - Math.max(8f, 10f * this.s);
                if (base > this.listTop - this.headerH && base < this.y + this.h + this.headerH) {
                    paint.text(item.heading, this.headerFont, this.x, Math.round(base), Rgb.alpha(GOLD_DIM, 0.85f * k), Math.max(1.2f, 1.8f * this.s));
                }
                top += this.headerH;
                continue;
            }
            Card card = item.card;
            float cardTop = this.listTop + card.y - this.scroll;
            if (cardTop + this.cardH >= this.listTop && cardTop <= this.y + this.h) {
                card.extract(paint, cardTop, inside ? mx : -1f, inside ? my : -1f, k);
            }
            column = (column + 1) % this.columns;
            if (column == 0) {
                top += this.cardH + this.gap;
            }
        }
        graphics.disableScissor();
        if (maxScroll > 0f) {
            float thumb = Math.max(24f, view * view / this.contentHeight);
            float ty = this.listTop + (view - thumb) * (this.scroll / maxScroll);
            Paint.roundedRect(paint.solid(), this.x + this.w + Math.max(4f, 6f * this.s), ty, 3f, thumb, 1.5f, 0f, Rgb.alpha(GOLD, 0.35f * k), 0);
        }
    }

    private void extractField(Paint paint, float k) {
        QuadBatch pill = paint.solid();
        boolean live = this.typing && this.link != null;
        int border = live ? Rgb.alpha(GOLD, 0.6f * k) : Rgb.alpha(0xFFFFFF, 0.11f * k);
        Paint.roundedRect(pill, this.x, this.y, this.w, this.fieldH, this.fieldH / 2f, 1f, Rgb.alpha(0xFFFFFF, 0.045f * k), border);
        float lens = Math.round(this.fieldH * 0.42f);
        Paint.icon(pill, LineIcon.SEARCH, Math.round(this.x + this.fieldH * 0.38f), Math.round(this.y + (this.fieldH - lens) / 2f), lens, Rgb.alpha(live ? GOLD : TEXT_3, k));
        float textX = Math.round(this.x + this.fieldH * 0.95f), baseline = Math.round(this.y + this.fieldH / 2f + Fonts.size(this.fieldFont) * 0.36f);
        float maxW = this.x + this.w - this.fieldH * 0.45f - textX;
        if (this.query.isEmpty()) {
            paint.text("Search Modrinth", this.fieldFont, textX, baseline, Rgb.alpha(TEXT_3, k), 0f);
        } else {
            String shown = this.query;
            while (shown.length() > 1 && Paint.measure(shown, this.fieldFont, 0f) > maxW) {
                shown = shown.substring(1);
            }
            paint.text(shown, this.fieldFont, textX, baseline, Rgb.alpha(TEXT, k), 0f);
            textX += Paint.measure(shown, this.fieldFont, 0f);
        }
        if (live && ((System.nanoTime() - this.caretFrom) / 530_000_000L) % 2 == 0) {
            float size = Fonts.size(this.fieldFont);
            pill.rect(Math.round(textX + 1f), Math.round(baseline - size * 0.82f), Math.round(textX + 1f) + Math.max(1f, Math.round(this.s)), Math.round(baseline + size * 0.18f), Rgb.alpha(GOLD, k));
        }
    }

    /** One line under the search: what's showing, what went wrong, or when added mods load. */
    private void extractStatus(Paint paint, float k) {
        float base = Math.round(this.y + this.fieldH + Math.max(20f, 26f * this.s));
        String text;
        int color;
        if (this.error != null) {
            text = this.error;
            color = RED;
        } else if (this.loading) {
            text = "Searching Modrinth…";
            color = TEXT_3;
        } else if (this.installed.values().stream().anyMatch(ModrinthLink.Installed::removing)) {
            text = "Mods you remove go when you close Minecraft. Hover one to keep it.";
            color = GOLD;
        } else if (!ADDED_THIS_GAME.isEmpty()) {
            text = "Mods you add are ready the next time you start Minecraft.";
            color = GOLD;
        } else if (this.query.isBlank()) {
            text = "Mods you add here are ready the next time you start Minecraft.";
            color = TEXT_3;
        } else {
            text = this.total == 1 ? "1 mod found." : downloads(this.total) + " mods found.";
            color = TEXT_3;
        }
        paint.text(SettingsScreen.fit(text, this.metaFont, this.w), this.metaFont, this.x + Math.round(this.fieldH * 0.38f), base, Rgb.alpha(color, k), 0f);
    }

    private void extractMessage(Paint paint, String first, String second, float k) {
        FontDescription big = Fonts.label(Math.max(12f, 15f * this.s));
        FontDescription small = Fonts.label(Math.max(10f, 12f * this.s));
        float cx = this.x + this.w / 2f, cy = this.y + this.h * 0.4f;
        String one = SettingsScreen.fit(first, big, this.w), two = SettingsScreen.fit(second, small, this.w);
        paint.text(one, big, Math.round(cx - Paint.measure(one, big, 0f) / 2f), Math.round(cy), Rgb.alpha(TEXT, k), 0f);
        paint.text(two, small, Math.round(cx - Paint.measure(two, small, 0f) / 2f), Math.round(cy + Fonts.size(small) + Math.max(10f, 14f * this.s)), Rgb.alpha(TEXT_2, k), 0f);
    }

    // ------------------------------------------------------------------ input

    boolean isTyping() {
        return this.typing && this.link != null;
    }

    void blur() {
        this.typing = false;
    }

    /** Ready to type a search. */
    void focus() {
        this.typing = true;
        this.caretFrom = System.nanoTime();
    }

    boolean mouseClicked(double mx, double my) {
        if (this.onField(mx, my)) {
            this.focus();
            return true;
        }
        this.typing = false;
        if (my < this.listTop) {
            return true;
        }
        for (Item item : this.items) {
            Card card = item.card;
            if (card != null && card.buttonHit(mx, my, this.listTop + card.y - this.scroll)) {
                this.press(card);
                return true;
            }
        }
        return true;
    }

    boolean mouseScrolled(double amount) {
        if (this.contentHeight <= this.listHeight()) {
            return false;
        }
        this.scrollGoal -= (float) amount * Math.max(40f, 60f * this.s);
        return true;
    }

    /** A key while typing in the search. */
    void keyPressed(KeyEvent event) {
        int key = event.key();
        if (event.isEscape()) {
            if (!this.query.isEmpty()) {
                this.setQuery("");
            } else {
                this.typing = false;
            }
        } else if (key == InputConstants.KEY_BACKSPACE && !this.query.isEmpty()) {
            this.setQuery(event.hasControlDown() ? this.query.replaceAll("\\S*\\s*$", "") : this.query.substring(0, this.query.offsetByCodePoints(this.query.length(), -1)));
        } else if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            this.searchAt = System.nanoTime();
        }
    }

    boolean charTyped(CharacterEvent event) {
        if (!this.isTyping()) {
            return false;
        }
        if (event.isAllowedChatCharacter() && this.query.length() < 60) {
            this.setQuery(this.query + event.codepointAsString());
        }
        return true;
    }

    private void setQuery(String query) {
        this.query = query;
        this.caretFrom = System.nanoTime();
        this.searchAt = System.nanoTime() + SETTLE;
    }

    // ------------------------------------------------------------------ one mod

    private final class Card {
        final String id;
        String title = "", iconUrl, status = "available", meta = "", about = "";
        /** A line after adding, like "Added with 1 library it needs.", or null. */
        String note;
        final Motion hover = new Motion(), buttonHover = new Motion();
        float x, y, w;
        /** When it was added (nanoTime), or 0. */
        long added;
        /** Why the last press failed, and when (nanoTime). */
        String failure;
        long failedAt;
        /** The description wrapped to the card, and the width it was wrapped to. */
        List<String> lines;
        float linesWidth;

        Card(String id) {
            this.id = id;
        }

        void fail(String why) {
            this.failure = why;
            this.failedAt = System.nanoTime();
        }

        private float buttonX() {
            return this.x + this.w - ModrinthPanel.this.pad - ModrinthPanel.this.buttonW;
        }

        boolean buttonHit(double mx, double my, float top) {
            ModrinthPanel p = ModrinthPanel.this;
            float by = Math.round(top + (p.cardH - p.buttonH) / 2f);
            return mx >= this.buttonX() && mx < this.buttonX() + p.buttonW && my >= by && my < by + p.buttonH;
        }

        void extract(Paint paint, float top, float mx, float my, float k) {
            ModrinthPanel p = ModrinthPanel.this;
            float s = p.s, h = p.cardH;
            State state = p.state(this);
            boolean over = mx >= this.x && mx < this.x + this.w && my >= top && my < top + h;
            float hoverK = this.hover.towards(over ? 1f : 0f, 0.2f);
            float since = this.added == 0L ? 99f : (System.nanoTime() - this.added) / 1e9f;
            float flash = since < 0.9f ? 1f - since / 0.9f : 0f;
            boolean on = state == State.ON || state == State.ADDED;

            int border = Rgb.lerp(Rgb.alpha(0xFFFFFF, (0.08f + 0.06f * hoverK) * k), Rgb.alpha(GOLD, 0.8f * k), Math.max(flash, on ? 0.3f : 0f));
            Paint.roundedRect(paint.solid(), this.x, top, this.w, h, 16f * s, 1f, Rgb.alpha(0xFFFFFF, (0.035f + 0.025f * hoverK) * k), border);

            // The icon, or two letters on a tile until (or unless) it arrives.
            float size = p.iconSize, ix = this.x + p.pad, iy = Math.round(top + (h - size) / 2f);
            Identifier icon = ModrinthLink.icon(this.id, this.iconUrl);
            if (icon != null) {
                paint.texture(icon, false).rect(ix, iy, ix + size, iy + size, Rgb.alpha(0xFFFFFF, k));
            } else {
                Paint.roundedRect(paint.solid(), ix, iy, size, size, size * 0.26f, 0f, Rgb.alpha(TEAL, 0.13f * k), 0);
                FontDescription font = Fonts.strong(Math.max(8f, size * 0.36f));
                String letters = SettingsScreen.monogram(this.title);
                paint.text(letters, font, Math.round(ix + (size - Paint.measure(letters, font, 0f)) / 2f), Math.round(iy + size / 2f + Fonts.size(font) * 0.36f), Rgb.alpha(TEAL, k), 0f);
            }

            // Name, who made it (or what went wrong), and what it does.
            float bx = this.buttonX();
            float textX = ix + size + Math.round(Math.max(10f, 14f * s));
            float textW = bx - Math.round(Math.max(10f, 14f * s)) - textX;
            if (this.lines == null || this.linesWidth != textW) {
                this.lines = this.about.isEmpty() ? List.of() : ShelfPanel.wrap(this.about, p.aboutFont, textW, 2);
                this.linesWidth = textW;
            }
            float nameSize = Fonts.size(p.nameFont), metaSize = Fonts.size(p.metaFont), aboutSize = Fonts.size(p.aboutFont);
            float lineH = aboutSize + Math.max(3f, 4f * s);
            float block = nameSize + p.small() + metaSize + (this.lines.isEmpty() ? 0f : p.small() + (this.lines.size() - 1) * lineH + aboutSize);
            float textTop = Math.round(top + (h - block) / 2f);
            float nameBase = Math.round(textTop + nameSize * 0.8f);
            paint.text(SettingsScreen.fit(this.title, p.nameFont, textW), p.nameFont, textX, nameBase, Rgb.alpha(TEXT, k), 0f);
            float failK = this.failure == null ? 0f : Math.max(0f, 1f - (System.nanoTime() - this.failedAt) / 6e9f);
            String meta;
            int metaColor;
            if (failK > 0f) {
                meta = this.failure;
                metaColor = Rgb.alpha(RED, Math.min(1f, failK * 3f) * k);
            } else if (state == State.REMOVING) {
                meta = "Goes when you close Minecraft.";
                metaColor = Rgb.alpha(RED, k);
            } else if (state == State.ADDED) {
                meta = this.note != null ? this.note : "Ready the next time you start Minecraft.";
                metaColor = Rgb.alpha(GOLD, k);
            } else if (state == State.LIBRARY) {
                meta = "Came with another mod you added.";
                metaColor = Rgb.alpha(TEXT_3, k);
            } else if (state == State.IN_PACK) {
                meta = "Already part of the client.";
                metaColor = Rgb.alpha(TEXT_3, k);
            } else {
                meta = this.meta;
                metaColor = Rgb.alpha(TEXT_3, k);
            }
            float metaBase = Math.round(textTop + nameSize + p.small() + metaSize * 0.8f);
            paint.text(SettingsScreen.fit(meta, p.metaFont, textW), p.metaFont, textX, metaBase, metaColor, 0f);
            for (int i = 0; i < this.lines.size(); i++) {
                float base = Math.round(textTop + nameSize + p.small() + metaSize + p.small() + i * lineH + aboutSize * 0.8f);
                paint.text(this.lines.get(i), p.aboutFont, textX, base, Rgb.alpha(TEXT_2, k), 0f);
            }

            // The button.
            float by = Math.round(top + (h - p.buttonH) / 2f);
            boolean overButton = this.buttonHit(mx, my, top);
            boolean pressable = state != State.ADDING && state != State.LIBRARY && state != State.IN_PACK;
            float buttonK = this.buttonHover.towards(overButton && pressable ? 1f : 0f, 0.15f);
            QuadBatch button = paint.solid();
            float textBase = Math.round(by + p.buttonH / 2f + Fonts.size(p.buttonFont) * 0.36f);
            String label;
            int ink;
            float tick = 0f;
            if (state == State.ADD) {
                int fill = Rgb.lerp(Rgb.alpha(0xF7E6C2, k), Rgb.alpha(0xC29756, k), 0.45f - 0.25f * buttonK);
                Paint.capsule(button, bx, by, p.buttonW, p.buttonH, 0f, (px, py) -> fill, 0, false);
                label = "ADD";
                ink = Rgb.alpha(0x15100A, k);
            } else {
                boolean warn = (on || state == State.REMOVING) && buttonK > 0.5f;
                int fill = Rgb.alpha(0xFFFFFF, (0.05f + 0.05f * buttonK) * k);
                int edge;
                if (state == State.REMOVING) {
                    edge = Rgb.lerp(Rgb.alpha(RED, 0.6f * k), Rgb.alpha(GOLD, 0.6f * k), buttonK);
                    label = buttonK > 0.5f ? "KEEP" : "REMOVED";
                    ink = Rgb.lerp(Rgb.alpha(0xFFB4A8, k), Rgb.alpha(GOLD, k), buttonK);
                } else if (on) {
                    edge = Rgb.lerp(Rgb.alpha(GOLD, 0.55f * k), Rgb.alpha(RED, 0.7f * k), buttonK);
                    label = warn ? "REMOVE" : state == State.ADDED ? "ADDED" : "ON";
                    ink = Rgb.lerp(Rgb.alpha(GOLD, k), Rgb.alpha(0xFFB4A8, k), buttonK);
                    tick = warn ? 0f : p.tick;
                } else {
                    // Adding, a library, or in the pack: a quiet, unpressable label.
                    edge = Rgb.alpha(0xFFFFFF, 0.12f * k);
                    label = state == State.ADDING ? "ADDING…" : state == State.LIBRARY ? "LIBRARY" : "IN PACK";
                    float pulse = state == State.ADDING ? 0.65f + 0.35f * (float) Math.sin((System.nanoTime() % 2_000_000_000L) / 1e9 * Math.PI * 2) : 0.7f;
                    ink = Rgb.alpha(TEXT_2, pulse * k);
                }
                Paint.capsule(button, bx, by, p.buttonW, p.buttonH, 1f, (px, py) -> fill, edge, false);
            }
            float total = Paint.measure(label, p.buttonFont, p.spacing) + (tick > 0f ? tick + 6f * s : 0f);
            float tx = Math.round(bx + (p.buttonW - total) / 2f);
            if (tick > 0f) {
                Paint.icon(button, LineIcon.CHECK, tx, Math.round(by + (p.buttonH - tick) / 2f), tick, ink);
                tx += tick + 6f * s;
            }
            paint.text(label, p.buttonFont, Math.round(tx), textBase, ink, p.spacing);
        }
    }
}
