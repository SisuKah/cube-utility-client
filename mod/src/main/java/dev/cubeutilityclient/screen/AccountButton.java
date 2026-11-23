package dev.cubeutilityclient.screen;

import dev.cubeutilityclient.account.Accounts;
import dev.cubeutilityclient.account.Faces;
import dev.cubeutilityclient.render.Motion;
import dev.cubeutilityclient.render.Paint;
import dev.cubeutilityclient.render.QuadBatch;
import dev.cubeutilityclient.render.Rgb;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/**
 * The account that plays, at the title screen's top left: its face and name on a glass pill that
 * turns gold on hover, like the round buttons opposite. It opens {@link AccountsScreen}.
 */
public final class AccountButton extends AbstractButton {
    private final Runnable action;
    private final Motion motion = new Motion();
    private float x, y, w, h, s, guiScale;
    private FontDescription font;
    /** The name the button was sized for; a switch while it shows sizes it again. */
    private String placedName;
    private float appear = 1f;

    public AccountButton(Runnable action) {
        super(0, 0, 1, 1, Component.literal("Switch account"));
        this.action = action;
    }

    private static User user() {
        return Minecraft.getInstance().getUser();
    }

    /** Places the button in screen pixels, sized to the name. */
    public void place(float x, float y, float height, float s, float guiScale, FontDescription font) {
        this.x = x;
        this.y = y;
        this.h = height;
        this.s = s;
        this.guiScale = guiScale;
        this.font = font;
        String name = user().getName();
        this.placedName = name;
        this.w = Math.round(this.face() + (this.h - this.face()) / 2f + 12f * s + Paint.measure(name, font, 0f) + 20f * s);
        Widgets.bounds(this, x, y, this.w, height, guiScale);
        this.setMessage(Component.literal("Playing as " + name + ". Switch account"));
    }

    private float face() {
        return Math.round(this.h * 0.54f);
    }

    public void appear(float k) {
        this.appear = k;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        this.action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (this.font == null || this.appear <= 0f) {
            return;
        }
        if (!user().getName().equals(this.placedName)) {
            this.place(this.x, this.y, this.h, this.s, this.guiScale, this.font);
        }
        float hover = this.motion.towards(this.isHoveredOrFocused() && this.active ? 1f : 0f, 0.3f);
        float k = this.appear;
        Paint paint = new Paint(graphics);
        QuadBatch body = paint.solid();
        int fill = Rgb.alpha(0xFFFFFF, (0.04f + 0.05f * hover) * k);
        int border = Rgb.lerp(Rgb.alpha(0xFFFFFF, 0.16f * k), Rgb.alpha(0xE4C084, 0.75f * k), hover);
        Paint.capsule(body, this.x, this.y, this.w, this.h, 1f, (px, py) -> fill, border, false);

        // The face sits in the pill's round left end.
        User user = user();
        UUID uuid = user.getProfileId();
        float face = this.face();
        float fx = Math.round(this.x + (this.h - face) / 2f), fy = Math.round(this.y + (this.h - face) / 2f);
        Identifier texture = Faces.get(uuid, Accounts.skin(uuid));
        if (texture != null) {
            paint.texture(texture, false).rect(fx, fy, fx + face, fy + face, Rgb.alpha(0xFFFFFF, k));
        }
        int text = Rgb.lerp(Rgb.alpha(0xE9EFEE, k), Rgb.alpha(0xF6E3BC, k), hover);
        float baseline = Math.round(this.y + this.h / 2f + Widgets.size(this.font) * 0.36f);
        paint.text(user.getName(), this.font, Math.round(fx + face + 12f * this.s), baseline, text, 0f);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
