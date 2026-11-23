package dev.cubeutilityclient.settings.model;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cubeutilityclient.CubeUtilityClient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Settings screens built the way Minecraft builds its own Options screens: a list of switches,
 * choices and sliders under headings (Spectator Inside's and CubeCraft Level + Rank's, among
 * others). The screen is set up out of sight and its list read as it is, so every name, value
 * and tooltip is the mod's own, and changing one runs the mod's own code. A button that opens
 * more ("Edit HUD layout...") becomes a row that opens it. The mod saves when its screen
 * closes, so the page closes it, quietly, when it's done with it.
 */
final class ListScreenSettings implements SettingsModel {
    private final Screen screen;
    private final List<Page> pages;
    private final boolean complete;
    private final Consumer<Runnable> quietly;

    private ListScreenSettings(Screen screen, List<Page> pages, boolean complete, Consumer<Runnable> quietly) {
        this.screen = screen;
        this.pages = pages;
        this.complete = complete;
        this.quietly = quietly;
    }

    /**
     * @param quietly runs something without it changing the screen (closing the mod's screen
     *     would otherwise go back to its parent, the page itself)
     */
    static SettingsModel from(Screen screen, Consumer<Runnable> quietly) {
        if (!(screen instanceof OptionsSubScreen) && listField(screen.getClass()) == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        screen.init(minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
        OptionsList list = list(screen);
        if (list == null) {
            return null;
        }
        boolean complete = true;
        Set<Object> listed = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Section> sections = new ArrayList<>();
        String heading = "";
        List<Setting> rows = new ArrayList<>();
        // The list's rows are Minecraft's own (protected) kinds: a heading, or one or two options.
        for (Object entry : list.children()) {
            String kind = entry.getClass().getSimpleName();
            if (kind.equals("HeaderEntry")) {
                add(sections, heading, rows);
                rows = new ArrayList<>();
                heading = Ref.text(((AbstractWidget) Ref.field(entry, "widget")).getMessage());
            } else if (kind.equals("Entry")) {
                for (Object child : (List<?>) Ref.field(entry, "children")) {
                    AbstractWidget shown = (AbstractWidget) Ref.call(child, "widget");
                    Object option = Ref.call(child, "optionInstance");
                    listed.add(shown);
                    Setting setting = option instanceof OptionInstance<?> instance ? VanillaOption.of(instance) : widget(shown);
                    if (setting == null) {
                        complete = false;
                    } else {
                        rows.add(setting);
                    }
                }
            } else {
                complete = false;
            }
        }
        add(sections, heading, rows);
        // Buttons around the list: Done goes, buttons to more of the mod's screens stay.
        List<Setting> more = new ArrayList<>();
        for (GuiEventListener child : screen.children()) {
            if (child == list || listed.contains(child) || !(child instanceof AbstractButton || child instanceof AbstractSliderButton || child instanceof EditBox)) {
                continue;
            }
            if (child instanceof AbstractButton button && closes(button)) {
                continue;
            }
            Setting setting = child instanceof AbstractWidget widget ? widget(widget) : null;
            if (setting == null) {
                complete = false;
            } else {
                more.add(setting);
            }
        }
        add(sections, "More", more);
        if (sections.isEmpty()) {
            return null;
        }
        return new ListScreenSettings(screen, List.of(new Page("", List.copyOf(sections), complete)), complete, quietly);
    }

    private static void add(List<Section> sections, String heading, List<Setting> rows) {
        if (!rows.isEmpty()) {
            sections.add(new Section(heading, List.copyOf(rows)));
        }
    }

    /** Done, Cancel and Back only close the mod's screen, which the page does by itself. */
    private static boolean closes(AbstractButton button) {
        String text = button.getMessage().getString();
        return text.equals(CommonComponents.GUI_DONE.getString()) || text.equals(CommonComponents.GUI_CANCEL.getString()) || text.equals(CommonComponents.GUI_BACK.getString());
    }

    /** One of the screen's own widgets as the page's kind of setting, or null for one it can't show. */
    private static Setting widget(AbstractWidget widget) {
        if (widget instanceof CycleButton<?> cycle) {
            return cycle(cycle);
        }
        if (widget instanceof AbstractSliderButton slider) {
            return slider(slider);
        }
        if (widget instanceof Button button) {
            // "Edit HUD layout..." and the like open another screen of the mod's: a row that opens it.
            String text = Ref.text(button.getMessage()).trim();
            if (text.endsWith("...") || text.endsWith("…")) {
                String name = text.replaceAll("(\\.\\.\\.|…)$", "").trim();
                return new Setting.Action(name, tooltip(button), () -> button.active, () -> button.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0)));
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Setting cycle(CycleButton cycle) {
        String name = Ref.text(Ref.field(cycle, "name"));
        List<Object> values = ((CycleButton.ValueListSupplier<Object>) Ref.field(cycle, "values")).getSelectedList();
        Function<Object, Component> label = (Function<Object, Component>) Ref.field(cycle, "valueStringifier");
        CycleButton.OnValueChange<Object> listener = (CycleButton.OnValueChange<Object>) Ref.field(cycle, "onValueChange");
        // What a click does: the button shows the new value, then tells the mod.
        Consumer<Object> set = value -> {
            cycle.setValue(value);
            listener.onValueChange(cycle, value);
        };
        String description = tooltip(cycle);
        if (values.size() == 2 && values.contains(Boolean.TRUE) && values.contains(Boolean.FALSE)) {
            return new Setting.Toggle(name, description, false, () -> cycle.active, () -> Boolean.TRUE.equals(cycle.getValue()), set::accept);
        }
        return new Setting.Choice(name, description, false, () -> cycle.active, () -> Math.max(0, values.indexOf(cycle.getValue())),
                index -> set.accept(values.get(index)), values::size, () -> Ref.text(label.apply(cycle.getValue())));
    }

    /** A slider of the mod's own; its label reads "Name: value". */
    private static Setting slider(AbstractSliderButton slider) {
        Method set = method(AbstractSliderButton.class, "setValue", double.class);
        Field value;
        try {
            value = AbstractSliderButton.class.getDeclaredField("value");
            value.setAccessible(true);
        } catch (NoSuchFieldException error) {
            return null;
        }
        String text = Ref.text(slider.getMessage());
        int colon = text.indexOf(": ");
        String name = colon > 0 ? text.substring(0, colon) : text;
        return new Setting.Slider(name, tooltip(slider), false, () -> slider.active, 0, 1, 0, () -> {
            try {
                return value.getDouble(slider);
            } catch (IllegalAccessException error) {
                throw new IllegalStateException(error);
            }
        }, fraction -> invoke(set, slider, fraction), () -> {
            String shown = Ref.text(slider.getMessage());
            int at = shown.indexOf(": ");
            return at > 0 ? shown.substring(at + 2) : shown;
        });
    }

    /** What a widget's tooltip says, which is what it does. */
    private static String tooltip(AbstractWidget widget) {
        try {
            Tooltip tooltip = ((WidgetTooltipHolder) Ref.field(widget, "tooltip")).get();
            return tooltip == null ? "" : Ref.text(Ref.field(tooltip, "message"));
        } catch (RuntimeException unreadable) {
            return "";
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try {
            Method method = type.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void invoke(Method method, Object target, Object... args) {
        try {
            method.invoke(target, args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(method.getName(), error);
        }
    }

    private static Field listField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Screen.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (OptionsList.class.isAssignableFrom(field.getType())) {
                    return field;
                }
            }
        }
        return null;
    }

    private static OptionsList list(Screen screen) {
        Field field = listField(screen.getClass());
        if (field == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            return (OptionsList) field.get(screen);
        } catch (IllegalAccessException error) {
            return null;
        }
    }

    @Override
    public List<Page> pages() {
        return this.pages;
    }

    /** Each change goes to the mod as it's made; the mod writes its file when its screen closes. */
    @Override
    public void save() {
    }

    @Override
    public boolean complete() {
        return this.complete;
    }

    /** Closes the mod's screen the way Done would, so it saves, without leaving the page. */
    @Override
    public void close() {
        try {
            this.quietly.accept(this.screen::onClose);
            this.screen.removed();
        } catch (Throwable error) {
            CubeUtilityClient.LOGGER.warn("Closing {} failed", this.screen.getClass().getName(), error);
        }
    }
}
