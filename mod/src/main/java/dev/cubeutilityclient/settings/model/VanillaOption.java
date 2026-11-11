package dev.cubeutilityclient.settings.model;

import java.util.List;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * Minecraft's own kind of option ({@link OptionInstance}, what its Options screens are made of),
 * which some mods use for their settings too (Noxesium). Setting one runs the option's own
 * listener, which is where those mods save.
 */
final class VanillaOption {
    private VanillaOption() {
    }

    /** The option as a switch, choice or slider; null for a kind this doesn't know. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Setting of(OptionInstance option) {
        String name = Ref.text(Ref.field(option, "caption"));
        String description = description(option);
        OptionInstance.ValueSet values = option.values();
        boolean cycles = values instanceof OptionInstance.CycleableValueSet
                && !(values instanceof OptionInstance.SliderableOrCyclableValueSet both && !both.createCycleButton());
        if (cycles) {
            List list = ((OptionInstance.CycleableValueSet) values).valueListSupplier().getSelectedList();
            if (list.size() == 2 && list.contains(Boolean.TRUE) && list.contains(Boolean.FALSE)) {
                return new Setting.Toggle(name, description, false, null, () -> Boolean.TRUE.equals(option.get()), on -> option.set(on));
            }
            return new Setting.Choice(name, description, false, null, () -> list.indexOf(option.get()), index -> option.set(list.get(index)), list::size,
                    () -> shown(option, name));
        }
        if (values instanceof OptionInstance.SliderableValueSet slider) {
            // A slider from 0 to 1, as Minecraft's own sliders work; the option rounds to its steps.
            return new Setting.Slider(name, description, false, null, 0, 1, 0, () -> slider.toSliderValue(option.get()),
                    fraction -> option.set(slider.fromSliderValue(fraction)), () -> shown(option, name));
        }
        return null;
    }

    /** Puts the option back to the value it starts with. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void reset(OptionInstance option) {
        option.set(Ref.field(option, "initialValue"));
    }

    /** The option's tooltip, which is what it does. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String description(OptionInstance option) {
        try {
            Object supplier = Ref.field(option, "tooltip");
            if (supplier instanceof OptionInstance.TooltipSupplier tooltips && tooltips.apply(option.get()) instanceof Tooltip tooltip) {
                return Ref.text(Ref.field(tooltip, "message"));
            }
        } catch (RuntimeException unreadable) {
            // No description, then.
        }
        return "";
    }

    /** The value as the option's button writes it, without the "Name: " in front. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String shown(OptionInstance option, String name) {
        Object label = Ref.field(option, "toString");
        String text = label instanceof java.util.function.Function format ? Ref.text((Component) format.apply(option.get())) : String.valueOf(option.get());
        String prefix = name + ": ";
        return text.startsWith(prefix) ? text.substring(prefix.length()) : text;
    }
}
