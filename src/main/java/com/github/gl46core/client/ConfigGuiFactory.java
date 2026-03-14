package com.github.gl46core.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.IConfigElement;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Generic IModGuiFactory that auto-generates a config GUI from a Forge
 * Configuration (.cfg) file. Injected by {@link ConfigGuiInjector} for
 * mods that have config files but didn't register their own GUI factory.
 */
public class ConfigGuiFactory implements IModGuiFactory {

    private final String modId;
    private final String modName;
    private final File configFile;

    public ConfigGuiFactory(String modId, String modName, File configFile) {
        this.modId = modId;
        this.modName = modName;
        this.configFile = configFile;
    }

    @Override
    public void initialize(Minecraft minecraftInstance) {}

    @Override
    public boolean hasConfigGui() {
        return true;
    }

    @Override
    public GuiScreen createConfigGui(GuiScreen parentScreen) {
        Configuration config = new Configuration(configFile);
        config.load();

        List<IConfigElement> elements = new ArrayList<>();
        for (String categoryName : config.getCategoryNames()) {
            elements.addAll(new ConfigElement(config.getCategory(categoryName)).getChildElements());
        }

        // Subclass GuiConfig to save the Configuration when the screen closes
        return new GuiConfig(
                parentScreen,
                elements,
                modId,
                false,
                false,
                modName + " Configuration"
        ) {
            @Override
            public void onGuiClosed() {
                super.onGuiClosed();
                if (this.configID == null && this.parentScreen instanceof GuiConfig) {
                    // Sub-screen — don't save yet, parent will handle it
                    return;
                }
                // Save any changes back to the .cfg file
                if (config.hasChanged()) {
                    config.save();
                }
            }
        };
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return Collections.emptySet();
    }
}
