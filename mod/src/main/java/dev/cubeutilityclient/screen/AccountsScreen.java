package dev.cubeutilityclient.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.util.UndashedUuid;
import dev.cubeutilityclient.account.AccountLink;
import dev.cubeutilityclient.account.Accounts;
import dev.cubeutilityclient.account.Faces;
import dev.cubeutilityclient.account.SessionSwitch;
import dev.cubeutilityclient.render.Fonts;
import dev.cubeutilityclient.render.LineIcon;
import dev.cubeutilityclient.render.Paint;
import dev.cubeutilityclient.render.QuadBatch;
import dev.cubeutilityclient.render.Rgb;
import dev.cubeutilityclient.render.SpaceTextures;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/**
 * Accounts, from the title screen's account button: the Microsoft accounts signed in on this PC
 * (the launcher keeps them), the one that plays first. Clicking another switches Minecraft to it
 * straight away, without closing the game, and goes back to the title screen. Add account signs
 * one more in through the browser and plays as it. The × on a row signs that account out of
 * this PC, after a second click.
 */
public final class AccountsScreen extends Screen {
    private static final int TEXT = 0xE9EFEE, TEXT_2 = 0x9AABA9, TEXT_3 = 0x7E8F8D, GOLD = 0xE4C084, ROSE = 0xF2A3B4;
    /** How long a sign-out waits for its second click. */
    private static final long CONFIRM_NANOS = 4_000_000_000L;

    /** A row: one of the launcher's accounts, or the one playing if the launcher doesn't know it. */
    private record Entry(String uuid, String name, byte[] skin, boolean playing, boolean known) {
        UUID id() {
            return UndashedUuid.fromStringLenient(this.uuid);
        }
    }

    private final Screen parent;
    private final AccountLink link = AccountLink.find();
    private final long opened = System.nanoTime();
    private long lastFrame = System.nanoTime();
    private List<Entry> entries = List.of();
    private boolean asked;
    /** The account being switched to while the launcher signs it in. */
    private String switching;
    /** A new account is signing in, in the browser. */
    private boolean signingIn;
    /** What went wrong last, for the player. */
    private String message;
    private String confirming;
    private long confirmingSince;
    /** The row the arrow keys are on, or -1. */
    private int keyboard = -1;
    private float scroll, scrollGoal;
    private IconButton close;
    private PillButton add, cancel;

    // The layout, in screen pixels.
    private float g, s, px, py, pw, ph, pad, rowH, rowGap, listTop, listBottom, brandBase, titleBase, subtitleBase, messageBase;

    public AccountsScreen(Screen parent) {
        super(Component.literal("Accounts"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ layout

    private void layout() {
        this.g = this.minecraft.getWindow().getGuiScale();
        float w = this.width * this.g, h = this.height * this.g;
        this.s = Math.min(w / 1280f, h / 720f);
        float margin = Math.round(Math.max(12f, 28f * this.s));
        this.pad = Math.round(Math.max(16f, 28f * this.s));
        this.pw = Math.round(Math.min(w - 2f * margin, Math.max(380f, 580f * this.s)));
        this.rowH = Math.round(Math.max(42f, 60f * this.s));
        this.rowGap = Math.round(Math.max(3f, 6f * this.s));

        float brand = Math.round(Math.max(9f, 11f * this.s));
        float title = Math.round(Math.max(36f, 50f * this.s));
        float subtitle = Math.round(Math.max(20f, 26f * this.s));
        float header = this.pad + brand + title + subtitle + Math.round(Math.max(16f, 24f * this.s));
        float messageGap = Math.round(Math.max(14f, 20f * this.s)), messageSize = Math.max(10f, 12f * this.s);
        float footer = messageGap + messageSize + Math.round(Math.max(12f, 18f * this.s)) + this.buttonHeight() + this.pad;
        float wanted = this.entries.size() * (this.rowH + this.rowGap) - this.rowGap;
        float room = Math.max(this.rowH, h - 2f * margin - header - footer);
        float listHeight = Math.min(wanted, room);

        this.ph = header + listHeight + footer;
        this.px = Math.round((w - this.pw) / 2f);
        this.py = Math.round((h - this.ph) / 2f);
        this.brandBase = this.py + this.pad + brand;
        this.titleBase = this.brandBase + title;
        this.subtitleBase = this.titleBase + subtitle;
        this.listTop = this.py + header;
        this.listBottom = this.listTop + listHeight;
        this.messageBase = Math.round(this.listBottom + messageGap + messageSize);
    }

    private float buttonHeight() {
        return Math.round(Math.max(30f, 42f * this.s));
    }

    @Override
    protected void init() {
        this.entries = this.entries(Accounts.last());
        this.layout();
        float size = Math.round(Math.max(30f, 38f * this.s));
        this.close = this.addRenderableWidget(new IconButton(LineIcon.CLOSE, Component.literal("Close"), this::onClose));
        this.close.place(Math.round(this.px + this.pw - this.pad - size), Math.round(this.py + this.pad), size, Math.round(size * 0.42f), this.g);

        FontDescription label = Fonts.label(Math.max(10f, 12f * this.s));
        float spacing = Math.max(1.5f, 2.2f * this.s);
        float top = Math.round(this.messageBase + Math.max(12f, 18f * this.s));
        this.add = this.addRenderableWidget(new PillButton(PillButton.Kind.GLASS, Component.literal("Add account"), "ADD ACCOUNT", false, this::addAccount));
        this.add.place(Math.round(this.px + this.pad), top, this.buttonHeight(), this.s, this.g, label, spacing);
        this.cancel = this.addRenderableWidget(new PillButton(PillButton.Kind.GLASS, Component.literal("Cancel sign-in"), "CANCEL", false, this::cancelSignIn));
        this.cancel.place(Math.round(this.px + this.pad), top, this.buttonHeight(), this.s, this.g, label, spacing);
        this.updateButtons();

        if (!this.asked) {
            this.asked = true;
            this.reload();
        }
    }

    private void updateButtons() {
        this.add.visible = this.link != null && !this.signingIn;
        this.add.active = this.switching == null;
        this.cancel.visible = this.signingIn;
    }

    /** The account that plays first, then the launcher's others in the order they were added. */
    private List<Entry> entries(AccountLink.Listing listing) {
        User user = Minecraft.getInstance().getUser();
        String playing = UndashedUuid.toString(user.getProfileId());
        AccountLink.Account mine = listing.accounts().stream().filter(a -> Accounts.same(a.uuid(), playing)).findFirst().orElse(null);
        List<Entry> out = new ArrayList<>();
        out.add(new Entry(playing, user.getName(), mine == null ? null : mine.skin(), true, mine != null));
        for (AccountLink.Account account : listing.accounts()) {
            if (!Accounts.same(account.uuid(), playing)) {
                out.add(new Entry(account.uuid(), account.name(), account.skin(), false, true));
            }
        }
        return out;
    }

    private void reload() {
        if (this.link == null) {
            return;
        }
        this.link.accounts().whenComplete((listing, error) -> {
            if (error != null) {
                this.message = AccountLink.failure(error).getMessage();
                return;
            }
            Accounts.remember(listing);
            this.show(listing);
        });
    }

    private void show(AccountLink.Listing listing) {
        this.entries = this.entries(listing);
        this.keyboard = Math.min(this.keyboard, this.entries.size() - 1);
        if (this.minecraft != null && this.minecraft.gui.screen() == this) {
            this.rebuildWidgets();
        }
    }

    // ------------------------------------------------------------------ actions

    private boolean busy() {
        return this.switching != null || this.signingIn;
    }

    private void switchTo(Entry entry) {
        if (entry.playing || this.busy() || this.link == null) {
            return;
        }
        if (!SessionSwitch.possible(this.minecraft)) {
            this.message = "Leave the world first, then switch accounts.";
            return;
        }
        this.switching = entry.uuid;
        this.message = null;
        this.confirming = null;
        this.updateButtons();
        this.link.login(entry.uuid).thenCompose(SessionSwitch::apply).whenComplete((done, error) -> {
            this.switching = null;
            if (error != null) {
                this.message = AccountLink.failure(error).getMessage();
                this.updateButtons();
                this.reload();
                return;
            }
            this.switched();
        });
    }

    private void addAccount() {
        if (this.busy() || this.link == null) {
            return;
        }
        if (!SessionSwitch.possible(this.minecraft)) {
            this.message = "Leave the world first, then add an account.";
            return;
        }
        this.signingIn = true;
        this.message = null;
        this.confirming = null;
        this.updateButtons();
        this.link.signIn().thenCompose(SessionSwitch::apply).whenComplete((done, error) -> {
            this.signingIn = false;
            if (error != null) {
                AccountLink.Failure failure = AccountLink.failure(error);
                this.message = failure.cancelled() ? null : failure.getMessage();
                this.updateButtons();
                this.reload();
                return;
            }
            this.switched();
        });
    }

    private void cancelSignIn() {
        if (this.signingIn && this.link != null) {
            this.link.cancelSignIn();
        }
    }

    /** Signs the account out of this PC, on the second click within a few seconds. */
    private void signOut(Entry entry) {
        if (entry.playing || !entry.known || this.busy() || this.link == null) {
            return;
        }
        long now = System.nanoTime();
        if (!entry.uuid.equals(this.confirming) || now - this.confirmingSince > CONFIRM_NANOS) {
            this.confirming = entry.uuid;
            this.confirmingSince = now;
            return;
        }
        this.confirming = null;
        this.link.signOut(entry.uuid).whenComplete((listing, error) -> {
            if (error != null) {
                this.message = AccountLink.failure(error).getMessage();
                return;
            }
            this.message = null;
            Accounts.remember(listing);
            this.show(listing);
        });
    }

    /** The switch worked: back to the title screen, which shows the account that plays now. */
    private void switched() {
        this.message = null;
        if (this.minecraft.gui.screen() == this) {
            this.onClose();
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public void removed() {
        // Leaving while a new account signs in stops that sign-in, so nothing switches later on its own.
        this.cancelSignIn();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        Paint paint = new Paint(graphics);
        float k = this.appear();
        Paint.roundedRect(paint.solid(), this.px, this.py, this.pw, this.ph, 22f * this.s, 1f, Rgb.alpha(0x060B0C, 0.86f * k), Rgb.alpha(0xFFFFFF, 0.08f * k));
        // A faint gold glow along the top edge, as on the settings page.
        paint.texture(SpaceTextures.HAZE, true).rect(this.px + this.pw * 0.18f, this.py - 120f * this.s, this.px + this.pw * 0.82f, this.py + 90f * this.s, Rgb.alpha(GOLD, 0.06f * k));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - this.lastFrame) / 1e9f);
        this.lastFrame = now;
        this.scroll += (this.scrollGoal - this.scroll) * Math.min(1f, dt * 14f);
        if (this.confirming != null && now - this.confirmingSince > CONFIRM_NANOS) {
            this.confirming = null;
        }

        Paint paint = new Paint(graphics);
        float k = this.appear();
        float mx = mouseX * this.g, my = mouseY * this.g;
        float x = this.px + this.pad, maxWidth = this.pw - 2f * this.pad;
        paint.text("CUBE UTILITY CLIENT", Fonts.strong(Math.max(9f, 11f * this.s)), x, this.brandBase, Rgb.alpha(GOLD, k), Math.max(1.5f, 2.2f * this.s));
        paint.text("Accounts", Fonts.title(Math.max(32f, 38f * this.s)), x, this.titleBase, Rgb.alpha(TEXT, k), 0f);
        String subtitle = this.link == null
                ? "Start Minecraft from the Cube Utility Client launcher to switch accounts here."
                : "Play as any of your Microsoft accounts. Minecraft stays open.";
        FontDescription body = Fonts.label(Math.max(10f, 13f * this.s));
        paint.text(fit(subtitle, body, maxWidth), body, x, this.subtitleBase, Rgb.alpha(TEXT_2, k), 0f);

        this.extractList(graphics, paint, mx, my, k);

        String line;
        int color;
        if (this.message != null) {
            line = this.message;
            color = ROSE;
        } else if (this.signingIn) {
            line = "Finish signing in in your browser. Microsoft's page opened there.";
            color = TEXT_2;
        } else if (this.link != null) {
            line = "Add account signs in with Microsoft in your browser.";
            color = TEXT_3;
        } else {
            line = null;
            color = TEXT_3;
        }
        if (line != null) {
            FontDescription small = Fonts.label(Math.max(10f, 12f * this.s));
            paint.text(fit(line, small, maxWidth), small, x, this.messageBase, Rgb.alpha(color, k), 0f);
        }
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    private void extractList(GuiGraphicsExtractor graphics, Paint paint, float mx, float my, float k) {
        float contentHeight = this.entries.size() * (this.rowH + this.rowGap) - this.rowGap;
        float maxScroll = Math.max(0f, contentHeight - (this.listBottom - this.listTop));
        this.scrollGoal = Math.max(0f, Math.min(maxScroll, this.scrollGoal));
        this.scroll = Math.max(0f, Math.min(maxScroll, this.scroll));

        graphics.enableScissor((int) Math.floor(this.px / this.g), (int) Math.floor(this.listTop / this.g), (int) Math.ceil((this.px + this.pw) / this.g),
                (int) Math.ceil(this.listBottom / this.g));
        QuadBatch rows = paint.solid();
        float left = this.left(), right = this.right();
        FontDescription nameFont = Fonts.label(Math.max(11f, 15f * this.s));
        FontDescription captionFont = Fonts.label(Math.max(9f, 11f * this.s));
        float t = (System.nanoTime() - this.opened) / 1e9f;
        for (int i = 0; i < this.entries.size(); i++) {
            float y = this.rowTop(i);
            if (y + this.rowH <= this.listTop || y >= this.listBottom) {
                continue;
            }
            Entry entry = this.entries.get(i);
            boolean hover = !this.busy() && this.rowAt(mx, my) == i;
            boolean focus = this.keyboard == i;
            boolean switchingHere = entry.uuid.equals(this.switching);
            if (entry.playing) {
                Paint.roundedRect(rows, left, y, right - left, this.rowH, 12f * this.s, 1f, Rgb.alpha(0xFFFFFF, 0.06f * k), Rgb.alpha(GOLD, 0.3f * k));
            } else if (hover || focus || switchingHere) {
                Paint.roundedRect(rows, left, y, right - left, this.rowH, 12f * this.s, focus ? 1f : 0f, Rgb.alpha(0xFFFFFF, 0.035f * k), Rgb.alpha(GOLD, 0.22f * k));
            }

            float face = Math.round(this.rowH * 0.56f);
            float fx = Math.round(left + Math.max(10f, 14f * this.s)), fy = Math.round(y + (this.rowH - face) / 2f);
            Identifier texture = Faces.get(entry.id(), entry.skin);
            if (texture != null) {
                paint.texture(texture, false).rect(fx, fy, fx + face, fy + face, Rgb.alpha(0xFFFFFF, (this.busy() && !switchingHere && !entry.playing ? 0.55f : 1f) * k));
            }

            boolean confirmingHere = entry.uuid.equals(this.confirming);
            String caption = entry.playing ? "Playing now"
                    : switchingHere ? "Signing in…"
                    : confirmingHere ? "Signs it out of this PC."
                    : hover || focus ? "Play as this account"
                    : "Microsoft account";
            float textX = fx + face + Math.round(Math.max(10f, 14f * this.s));
            float side = Math.round(this.rowH * 0.5f);
            float textW = right - textX - side - Math.max(8f, 12f * this.s) - (confirmingHere ? this.signOutWidth() : 0f);
            float nameSize = Fonts.size(nameFont), captionSize = Fonts.size(captionFont), gap = Math.max(4f, 5f * this.s);
            float top = y + (this.rowH - (nameSize + gap + captionSize)) / 2f;
            paint.text(fit(entry.name, nameFont, textW), nameFont, textX, Math.round(top + nameSize * 0.8f), Rgb.alpha(entry.playing ? GOLD : TEXT, k), 0f);
            paint.text(fit(caption, captionFont, textW), captionFont, textX, Math.round(top + nameSize + gap + captionSize * 0.8f),
                    Rgb.alpha(entry.playing ? 0xCDA76A : confirmingHere ? ROSE : TEXT_3, k), 0f);

            float cy = y + this.rowH / 2f;
            if (entry.playing) {
                float icon = Math.round(Math.max(14f, 18f * this.s));
                Paint.icon(rows, LineIcon.CHECK, Math.round(right - side / 2f - icon / 2f - 4f * this.s), Math.round(cy - icon / 2f), icon, Rgb.alpha(GOLD, k));
            } else if (switchingHere) {
                // Three dots that pulse while the launcher signs the account in.
                float dot = Math.max(4f, 5f * this.s), spacing = dot * 2.2f;
                float dx = right - side / 2f - spacing - dot / 2f - 4f * this.s;
                for (int d = 0; d < 3; d++) {
                    float pulse = 0.3f + 0.7f * Math.max(0f, (float) Math.sin(t * 6f - d * 0.9f));
                    Paint.capsule(rows, dx + d * spacing, cy - dot / 2f, dot, dot, 0f, (ppx, ppy) -> Rgb.alpha(GOLD, pulse * k), 0, false);
                }
            } else if (entry.known && confirmingHere) {
                this.extractSignOut(paint, right - Math.max(8f, 12f * this.s), cy, k);
            } else if (entry.known && (hover || focus) && !this.busy()) {
                float icon = Math.round(Math.max(12f, 14f * this.s));
                boolean onX = this.onSignOut(i, mx, my);
                Paint.icon(rows, LineIcon.CLOSE, Math.round(right - side / 2f - icon / 2f - 4f * this.s), Math.round(cy - icon / 2f), icon, Rgb.alpha(onX ? ROSE : TEXT_3, k));
            }
        }
        graphics.disableScissor();
        if (maxScroll > 0f) {
            float track = this.listBottom - this.listTop, thumb = Math.max(24f, track * track / contentHeight);
            float ty = this.listTop + (track - thumb) * (this.scroll / maxScroll);
            Paint.roundedRect(paint.solid(), this.px + this.pw - 8f, ty, 3f, thumb, 1.5f, 0f, Rgb.alpha(GOLD, 0.35f * k), 0);
        }
    }

    private float signOutWidth() {
        FontDescription font = Fonts.strong(Math.max(9f, 10f * this.s));
        return Math.round(Paint.measure("SIGN OUT", font, Math.max(1f, 1.4f * this.s)) + Math.max(16f, 22f * this.s));
    }

    /** The rose "Sign out" pill that confirms, its right edge at {@code right}. */
    private void extractSignOut(Paint paint, float right, float cy, float k) {
        FontDescription font = Fonts.strong(Math.max(9f, 10f * this.s));
        float spacing = Math.max(1f, 1.4f * this.s);
        float w = this.signOutWidth(), h = Math.round(Math.max(22f, 28f * this.s));
        float x = Math.round(right - w), y = Math.round(cy - h / 2f);
        Paint.capsule(paint.solid(), x, y, w, h, 1f, (ppx, ppy) -> Rgb.alpha(0xE0395E, 0.16f * k), Rgb.alpha(ROSE, 0.7f * k), false);
        paint.text("SIGN OUT", font, Math.round(x + (w - Paint.measure("SIGN OUT", font, spacing)) / 2f), Math.round(cy + Fonts.size(font) * 0.36f), Rgb.alpha(ROSE, k), spacing);
    }

    /** How far the screen is through its quick fade-in. */
    private float appear() {
        float t = (System.nanoTime() - this.opened) / 1e9f;
        float k = Math.max(0f, Math.min(1f, t / 0.25f));
        return 1f - (1f - k) * (1f - k);
    }

    private static String fit(String text, FontDescription font, float maxWidth) {
        if (maxWidth <= 0f || Paint.measure(text, font, 0f) <= maxWidth) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && Paint.measure(cut + "…", font, 0f) > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut.stripTrailing() + "…";
    }

    // ------------------------------------------------------------------ input

    private float left() {
        return this.px + this.pad * 0.55f;
    }

    private float right() {
        return this.px + this.pw - this.pad * 0.55f;
    }

    private float rowTop(int i) {
        return this.listTop + i * (this.rowH + this.rowGap) - this.scroll;
    }

    /** The row under a point in screen pixels, or -1. */
    private int rowAt(float mx, float my) {
        if (mx < this.left() || mx >= this.right() || my < this.listTop || my >= this.listBottom) {
            return -1;
        }
        for (int i = 0; i < this.entries.size(); i++) {
            float y = this.rowTop(i);
            if (my >= y && my < y + this.rowH) {
                return i;
            }
        }
        return -1;
    }

    /** Whether a point is on the row's sign-out control: the × or, while confirming, the pill. */
    private boolean onSignOut(int i, float mx, float my) {
        Entry entry = this.entries.get(i);
        if (entry.playing || !entry.known) {
            return false;
        }
        float right = this.right();
        float width = entry.uuid.equals(this.confirming) ? this.signOutWidth() + Math.max(8f, 12f * this.s) : Math.round(this.rowH * 0.5f) + 8f * this.s;
        float y = this.rowTop(i);
        return mx >= right - width && mx < right && my >= y && my < y + this.rowH;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        float mx = (float) (event.x() * this.g), my = (float) (event.y() * this.g);
        int i = this.rowAt(mx, my);
        if (i < 0 || this.busy() || event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        this.setFocused(null);
        this.keyboard = -1;
        Entry entry = this.entries.get(i);
        if (this.onSignOut(i, mx, my)) {
            this.signOut(entry);
        } else {
            this.confirming = null;
            this.switchTo(entry);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        float my = (float) (mouseY * this.g);
        if (my >= this.listTop && my < this.listBottom) {
            this.scrollGoal -= (float) scrollY * this.rowH;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            this.onClose();
            return true;
        }
        if (this.getFocused() == null || this.keyboard >= 0) {
            if (event.isUp() || event.isDown()) {
                this.setFocused(null);
                int next = this.keyboard + (event.isUp() ? -1 : 1);
                this.keyboard = Math.max(0, Math.min(this.entries.size() - 1, this.keyboard < 0 ? 0 : next));
                this.reveal(this.keyboard);
                return true;
            }
            if (this.keyboard >= 0 && event.isConfirmation()) {
                this.switchTo(this.entries.get(this.keyboard));
                return true;
            }
            if (this.keyboard >= 0 && event.key() == InputConstants.KEY_DELETE) {
                this.signOut(this.entries.get(this.keyboard));
                return true;
            }
        }
        return super.keyPressed(event);
    }

    /** Scrolls a row into view. */
    private void reveal(int i) {
        float top = this.listTop + i * (this.rowH + this.rowGap);
        float view = this.listBottom - this.listTop;
        if (top - this.scrollGoal < this.listTop) {
            this.scrollGoal = top - this.listTop;
        } else if (top + this.rowH - this.scrollGoal > this.listTop + view) {
            this.scrollGoal = top + this.rowH - this.listTop - view;
        }
    }
}
