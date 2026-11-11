package dev.cubeutilityclient.screen;

import dev.cubeutilityclient.CubeUtilityClient;
import dev.cubeutilityclient.compat.ModMenuCompat;
import dev.cubeutilityclient.render.Fonts;
import dev.cubeutilityclient.render.LineIcon;
import dev.cubeutilityclient.render.Paint;
import dev.cubeutilityclient.render.Rgb;
import dev.cubeutilityclient.space.SpaceScene;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/**
 * The client's title screen: CUBE UTILITY CLIENT over a turning galaxy, one gold button that
 * joins CubeCraft, Singleplayer and Multiplayer, and round buttons for Mods, Settings and Quit.
 *
 * It stays a {@link TitleScreen} so the game's own checks for "on the title screen" still hold,
 * but draws nothing of vanilla's: no panorama, logo, splash text or Realms notifications.
 */
public final class CubeTitleScreen extends TitleScreen {
    private static final String SERVER = "play.cubecraft.net";
    private static final Identifier SHADE = CubeUtilityClient.id("textures/gui/shade.png");

    private final long opened = System.nanoTime();
    private PillButton play, singleplayer, multiplayer;
    private IconButton mods, settings, quit;
    private TextLink credits;

    public CubeTitleScreen() {
        super();
    }

    @Override
    protected void init() {
        this.play = this.addRenderableWidget(new PillButton(PillButton.Kind.GOLD, Component.literal("Play CubeCraft"), "PLAY CUBECRAFT", true, this::playCubeCraft));
        this.singleplayer = this.addRenderableWidget(new PillButton(PillButton.Kind.GLASS, Component.translatable("menu.singleplayer"), null, false,
                () -> this.minecraft.gui.setScreen(new SelectWorldScreen(this))));
        this.multiplayer = this.addRenderableWidget(new PillButton(PillButton.Kind.GLASS, Component.translatable("menu.multiplayer"), null, false, this::multiplayer));
        this.mods = FabricLoader.getInstance().isModLoaded("modmenu")
                ? this.addRenderableWidget(new IconButton(LineIcon.MODS, Component.literal("Mods"), () -> this.minecraft.gui.setScreen(ModMenuCompat.modsScreen(this))))
                : null;
        this.settings = this.addRenderableWidget(new IconButton(LineIcon.SETTINGS, Component.translatable("menu.options"),
                () -> this.minecraft.gui.setScreen(new OptionsScreen(this, this.minecraft.options))));
        this.quit = this.addRenderableWidget(new IconButton(LineIcon.QUIT, Component.translatable("menu.quit"), this.minecraft::stop));
        this.credits = this.addRenderableWidget(new TextLink(Component.translatable("title.credits"),
                () -> this.minecraft.gui.setScreen(new CreditsAndAttributionScreen(this))));
        this.layout();
        this.openPreview();
    }

    private static boolean previewOpened;

    /**
     * For screenshots while developing: {@code ./gradlew runClient -Ppreview=options} opens that
     * menu once the title screen is up (options, video, multiplayer or worlds). Never set for players.
     */
    private void openPreview() {
        String preview = System.getProperty("cubeutilityclient.preview", "");
        if (preview.isBlank() || previewOpened) {
            return;
        }
        previewOpened = true;
        this.minecraft.execute(() -> this.minecraft.gui.setScreen(switch (preview) {
            case "video" -> new VideoSettingsScreen(this, this.minecraft, this.minecraft.options);
            case "multiplayer" -> new JoinMultiplayerScreen(this);
            case "worlds" -> new SelectWorldScreen(this);
            default -> new OptionsScreen(this, this.minecraft.options);
        }));
    }

    /** Positions in a 1280 x 720 design, scaled to fit and centred vertically. */
    private void layout() {
        float g = this.minecraft.getWindow().getGuiScale();
        float w = this.width * g, h = this.height * g;
        float s = Math.min(w / 1280f, h / 720f);
        float oy = (h - 720f * s) / 2f;

        // In small windows text and icons stop shrinking at sizes that stay legible: Manrope below
        // 10 px and icons below 16 px lose their shape.
        float rowTop = Math.round(oy + 498f * s), rowHeight = Math.round(54f * s);
        FontDescription strong = Fonts.strong(Math.max(10f, 13f * s)), label = Fonts.label(Math.max(10f, 12f * s));
        this.play.place(Math.round(96f * s), rowTop, rowHeight, s, g, strong, 2.6f * s);
        this.singleplayer.place(Math.round(this.play.right() + 14f * s), rowTop, rowHeight, s, g, label, 2.4f * s);
        this.multiplayer.place(Math.round(this.singleplayer.right() + 14f * s), rowTop, rowHeight, s, g, label, 2.4f * s);

        float size = Math.round(Math.max(34f, 46f * s)), gap = Math.round(10f * s), icon = Math.round(Math.max(16f, 19f * s));
        float x = Math.round(w - 28f * s - size), top = Math.round(24f * s);
        for (IconButton button : new IconButton[] {this.quit, this.settings, this.mods}) {
            if (button != null) {
                button.place(x, top, size, icon, g);
                x -= size + gap;
            }
        }
        this.credits.place(w - 28f * s, h - 21f * s, Fonts.label(Math.max(9f, 11f * s)), g);
    }

    private void playCubeCraft() {
        ConnectScreen.startConnecting(this, this.minecraft, ServerAddress.parseString(SERVER), new ServerData("CubeCraft", SERVER, ServerData.Type.OTHER), false, null);
    }

    private void multiplayer() {
        this.minecraft.gui.setScreen(this.minecraft.options.skipMultiplayerWarning ? new JoinMultiplayerScreen(this) : new SafetyScreen(this));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        Paint paint = new Paint(graphics);
        float mx = this.width > 0 ? mouseX / (float) this.width * 2f - 1f : 0f;
        float my = this.height > 0 ? mouseY / (float) this.height * 2f - 1f : 0f;
        SpaceScene.get().draw(paint, mx, my);
        // Darker on the left, so the title reads well whatever the galaxy is doing.
        paint.texture(SHADE, false).rect(0, 0, paint.width, paint.height, 0xFFFFFFFF);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        Paint paint = new Paint(graphics);
        float s = Math.min(paint.width / 1280f, paint.height / 720f);
        float oy = (paint.height - 720f * s) / 2f;
        float time = (System.nanoTime() - this.opened) / 1e9f;
        float first = appear(time, 0f), second = appear(time, 0.12f), rest = appear(time, 0.26f);

        FontDescription title = Fonts.title(80f * s);
        float spacing = 0.03f * 80f * s, left = Math.round(96f * s);
        paint.text("CUBE UTILITY", title, left, Math.round(oy + 343f * s + (1f - first) * 16f * s), Rgb.alpha(0xEEF2F2, first), spacing);
        paint.text("CLIENT", title, left, Math.round(oy + 431f * s + (1f - second) * 16f * s), Rgb.alpha(0xE4C084, second), spacing);

        this.play.appear(rest);
        this.singleplayer.appear(rest);
        this.multiplayer.appear(rest);
        for (IconButton button : new IconButton[] {this.mods, this.settings, this.quit}) {
            if (button != null) {
                button.appear(first);
            }
        }
        for (GuiEventListener child : this.children()) {
            if (child instanceof Renderable renderable) {
                renderable.extractRenderState(graphics, mouseX, mouseY, a);
            }
        }
    }

    /** How far an element is through its entrance: a 1 s rise-and-fade that starts after {@code delay}. */
    private static float appear(float time, float delay) {
        float k = Math.max(0f, Math.min(1f, (time - delay) / 1f));
        return 1f - (float) Math.pow(1f - k, 4);
    }
}
