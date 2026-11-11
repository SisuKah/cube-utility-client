package dev.cubeutilityclient.settings.model;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.locale.Language;

/**
 * EntityCulling's switches, read from its config. Its lists of mobs and blocks to leave alone are
 * long searchable lists, so those stay on its own screen.
 */
final class EntityCullingSettings implements SettingsModel {
    /** The switches on its own General tab, in that order. */
    private static final List<String> SWITCHES = List.of("renderNametagsThroughWalls", "tickCulling", "disableF3", "skipEntityCulling", "skipBlockEntityCulling",
            "forceDisplayCulling", "blockEntityFrustumCulling", "solidLeaves");
    private static final String KEYS = "text.entityculling.";

    private final Object mod;
    private List<Page> pages = List.of();
    private boolean dirty;

    private EntityCullingSettings(Object mod) {
        this.mod = mod;
    }

    static SettingsModel load() {
        Object mod = Ref.staticField("dev.tr7zw.entityculling.EntityCullingModBase", "instance");
        if (mod == null) {
            return null;
        }
        EntityCullingSettings model = new EntityCullingSettings(mod);
        Class<?> type = Ref.field(mod, "config").getClass();
        List<Setting> settings = new ArrayList<>();
        for (String key : SWITCHES) {
            try {
                Field field = type.getField(key);
                if (field.getType() == boolean.class) {
                    settings.add(model.toggle(key, field));
                }
            } catch (NoSuchFieldException missing) {
                // Not in this version.
            }
        }
        if (settings.isEmpty()) {
            return null;
        }
        // The lists of mobs and blocks to leave alone are only on its own screen.
        model.pages = List.of(new Page("General", List.of(new Section("", List.copyOf(settings))), false));
        return model;
    }

    private Setting toggle(String key, Field field) {
        String name = Language.getInstance().getOrDefault(KEYS + key, key);
        String description = Language.getInstance().getOrDefault(KEYS + key + ".tooltip", "");
        return new Setting.Toggle(name, description, false, null, () -> {
            try {
                // The config is read each time: the mod replaces it when it resets.
                return field.getBoolean(Ref.field(this.mod, "config"));
            } catch (IllegalAccessException error) {
                throw new IllegalStateException(error);
            }
        }, on -> {
            try {
                field.setBoolean(Ref.field(this.mod, "config"), on);
                this.dirty = true;
            } catch (IllegalAccessException error) {
                throw new IllegalStateException(error);
            }
        });
    }

    @Override
    public List<Page> pages() {
        return this.pages;
    }

    /** Writes the config, as its own screen's Save does. */
    @Override
    public void save() {
        if (this.dirty) {
            this.dirty = false;
            Ref.call(this.mod, "writeConfig");
        }
    }

    @Override
    public boolean complete() {
        return false;
    }
}
