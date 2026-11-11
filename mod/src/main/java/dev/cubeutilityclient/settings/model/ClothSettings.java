package dev.cubeutilityclient.settings.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Settings made with Cloth Config, read from the screen the mod builds. */
final class ClothSettings implements SettingsModel {
    private static final String ENTRIES = "me.shedaniel.clothconfig2.gui.entries.";

    private final Screen screen;
    private final List<Page> pages;
    private final boolean complete;
    /** The entries shown, and their values when last saved. */
    private final List<Object> entries;
    private List<Object> saved;

    private ClothSettings(Screen screen, List<Page> pages, boolean complete, List<Object> entries) {
        this.screen = screen;
        this.pages = pages;
        this.complete = complete;
        this.entries = entries;
        this.saved = this.values();
    }

    static SettingsModel from(Screen screen) {
        if (!Ref.isA(screen, "me.shedaniel.clothconfig2.gui.AbstractConfigScreen")) {
            return null;
        }
        boolean[] complete = {true};
        List<Page> pages = new ArrayList<>();
        List<Object> shown = new ArrayList<>();
        for (Map.Entry<?, ?> category : ((Map<?, ?>) Ref.call(screen, "getCategorizedEntries")).entrySet()) {
            List<Section> sections = new ArrayList<>();
            boolean[] whole = {true};
            collect(screen.getTitle().getString(), sections, "", (List<?>) category.getValue(), whole, shown);
            complete[0] &= whole[0];
            if (!sections.isEmpty()) {
                pages.add(new Page(Ref.text(category.getKey()), List.copyOf(sections), whole[0]));
            }
        }
        return pages.isEmpty() ? null : new ClothSettings(screen, List.copyOf(pages), complete[0], shown);
    }

    /** Entries in order; each sub-category becomes its own heading, as Cloth folds them. */
    private static void collect(String mod, List<Section> sections, String title, List<?> entries, boolean[] complete, List<Object> shown) {
        List<Setting> loose = new ArrayList<>();
        for (Object entry : entries) {
            if (Ref.isA(entry, ENTRIES + "SubCategoryListEntry")) {
                if (!loose.isEmpty()) {
                    sections.add(new Section(title, List.copyOf(loose)));
                    loose.clear();
                }
                String name = Ref.text(Ref.call(entry, "getFieldName"));
                collect(mod, sections, title.isEmpty() ? name : title + " › " + name, (List<?>) Ref.field(entry, "entries"), complete, shown);
                continue;
            }
            Setting setting = setting(entry);
            if (setting == null) {
                complete[0] = false;
                Models.skipped(mod, Ref.text(Ref.call(entry, "getFieldName")), entry);
            } else {
                loose.add(setting);
                shown.add(entry);
            }
        }
        if (!loose.isEmpty()) {
            sections.add(new Section(title, List.copyOf(loose)));
        }
    }

    @SuppressWarnings("unchecked")
    private static Setting setting(Object entry) {
        if (Ref.isA(entry, ENTRIES + "TextListEntry")) {
            return new Setting.Note(Ref.text(Ref.field(entry, "text")));
        }
        String name = Ref.text(Ref.call(entry, "getFieldName"));
        String description = tooltip(entry);
        boolean restart = (boolean) Ref.call(entry, "isRequiresRestart");
        BooleanSupplier enabled = () -> !Ref.has(entry, "isEditable", 0) || (boolean) Ref.call(entry, "isEditable");
        if (Ref.isA(entry, ENTRIES + "BooleanListEntry")) {
            AtomicBoolean value = (AtomicBoolean) Ref.field(entry, "bool");
            return new Setting.Toggle(name, description, restart, enabled, value::get, value::set);
        }
        if (Ref.isA(entry, ENTRIES + "IntegerSliderEntry")) {
            int min = (int) Ref.field(entry, "minimum"), max = (int) Ref.field(entry, "maximum");
            AtomicInteger value = (AtomicInteger) Ref.field(entry, "value");
            Function<Integer, Component> text = (Function<Integer, Component>) Ref.field(entry, "textGetter");
            return new Setting.Slider(name, description, restart, enabled, min, max, 1, value::get, v -> Ref.call(entry, "setValue", (int) Math.round(v)),
                    () -> text == null ? String.valueOf(value.get()) : Ref.text(text.apply(value.get())));
        }
        if (Ref.isA(entry, ENTRIES + "LongSliderEntry")) {
            long min = (long) Ref.field(entry, "minimum"), max = (long) Ref.field(entry, "maximum");
            java.util.concurrent.atomic.AtomicLong value = (java.util.concurrent.atomic.AtomicLong) Ref.field(entry, "value");
            Function<Long, Component> text = (Function<Long, Component>) Ref.field(entry, "textGetter");
            return new Setting.Slider(name, description, restart, enabled, min, max, 1, value::get, v -> Ref.call(entry, "setValue", Math.round(v)),
                    () -> text == null ? String.valueOf(value.get()) : Ref.text(text.apply(value.get())));
        }
        if (Ref.isA(entry, ENTRIES + "SelectionListEntry")) {
            List<Object> values = (List<Object>) Ref.field(entry, "values");
            AtomicInteger index = (AtomicInteger) Ref.field(entry, "index");
            Function<Object, Component> names = (Function<Object, Component>) Ref.field(entry, "nameProvider");
            if (values.isEmpty()) {
                return null;
            }
            return Setting.Choice.ofNames(name, description, restart, enabled, index::get, index::set,
                    i -> names == null ? String.valueOf(values.get(i)) : Ref.text(names.apply(values.get(i))), values.size());
        }
        if (Ref.isA(entry, ENTRIES + "TextFieldListEntry")) {
            EditBox box = (EditBox) Ref.field(entry, "textFieldWidget");
            return new Setting.Text(name, description, restart, enabled, box::getValue, text -> {
                Ref.call(entry, "setValue", text);
                return ((Optional<?>) Ref.call(entry, "getError")).isEmpty();
            });
        }
        return null;
    }

    /** The entry's tooltip lines as one description. */
    private static String tooltip(Object entry) {
        if (!Ref.isA(entry, ENTRIES + "TooltipListEntry")) {
            return "";
        }
        Object supplier = Ref.field(entry, "tooltipSupplier");
        if (!(supplier instanceof Supplier<?> lines) || !(lines.get() instanceof Optional<?> found) || found.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Object line : (Object[]) found.get()) {
            String part = Ref.text(line).trim();
            if (!part.isEmpty()) {
                text.append(text.isEmpty() ? "" : " ").append(part);
            }
        }
        return text.toString();
    }

    @Override
    public List<Page> pages() {
        return this.pages;
    }

    /**
     * Saves every entry and runs the mod's own saving, as Cloth's Save button does, when a value
     * changed since the last save. (Cloth's own "edited" check compares with the values the
     * screen opened with, so changing a setting back wouldn't be saved.)
     */
    @Override
    public void save() {
        List<Object> now = this.values();
        if (!now.equals(this.saved)) {
            Ref.call(this.screen, "saveAll", false);
            this.saved = now;
        }
    }

    private List<Object> values() {
        List<Object> values = new ArrayList<>();
        for (Object entry : this.entries) {
            Object value;
            try {
                value = Ref.isA(entry, ENTRIES + "TextFieldListEntry") ? ((EditBox) Ref.field(entry, "textFieldWidget")).getValue() : Ref.call(entry, "getValue");
            } catch (Throwable unreadable) {
                value = null;
            }
            values.add(value);
        }
        return values;
    }

    @Override
    public boolean complete() {
        return this.complete;
    }
}
