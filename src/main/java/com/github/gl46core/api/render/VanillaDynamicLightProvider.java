package com.github.gl46core.api.render;

import com.github.gl46core.api.hook.DynamicLightProvider;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.AxisAlignedBB;

import java.util.List;

/**
 * Extracts dynamic lights from vanilla MC entities and items.
 *
 * Uses vanilla-accurate light levels (0-15) and neutral white color.
 * Shader packs can override colors via the Light SSBO data.
 *
 * Light sources:
 *   - Held items: any block item with lightValue > 0, plus lava bucket
 *   - Burning entities (fire = light level 15)
 *   - Dropped light-emitting items on ground
 */
public final class VanillaDynamicLightProvider implements DynamicLightProvider {

    public static final VanillaDynamicLightProvider INSTANCE = new VanillaDynamicLightProvider();

    private VanillaDynamicLightProvider() {}

    @Override public String getId() { return "gl46core:vanilla_lights"; }
    @Override public int getPriority() { return 0; }

    @Override
    public void collectLights(FrameContext frame, LightCollector collector) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) return;

        // Player held item light
        collectHeldItemLight(mc.player, collector);

        // Nearby entity lights (within 64 blocks of camera)
        double cx = mc.player.posX;
        double cy = mc.player.posY;
        double cz = mc.player.posZ;
        double range = 64.0;

        AxisAlignedBB searchBox = new AxisAlignedBB(
            cx - range, cy - range, cz - range,
            cx + range, cy + range, cz + range);

        List<Entity> entities = mc.world.getEntitiesWithinAABBExcludingEntity(null, searchBox);

        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);

            // Burning entities emit light (fire = vanilla light level 15)
            if (entity.isBurning() && !(entity instanceof EntityPlayer)) {
                LightData ld = collector.addLight();
                ld.setPosition((float) entity.posX, (float) entity.posY + 0.5f, (float) entity.posZ);
                ld.setRadius(15.0f);
                ld.setColor(1.0f, 1.0f, 1.0f);
                ld.setIntensity(1.0f);
                ld.setEntityId(entity.getEntityId());
                continue;
            }

            // Dropped items that emit light
            if (entity instanceof EntityItem) {
                int lightLevel = getItemLightLevel(((EntityItem) entity).getItem());
                if (lightLevel > 0) {
                    float f = lightLevel / 15.0f;
                    LightData ld = collector.addLight();
                    ld.setPosition((float) entity.posX, (float) entity.posY + 0.25f, (float) entity.posZ);
                    ld.setRadius(lightLevel);
                    ld.setColor(1.0f, 1.0f, 1.0f);
                    ld.setIntensity(f * 0.6f);
                    ld.setEntityId(entity.getEntityId());
                }
            }
        }
    }

    /**
     * Emit light from the player's held item.
     */
    private void collectHeldItemLight(EntityPlayer player, LightCollector collector) {
        int mainLevel = getItemLightLevel(player.getHeldItemMainhand());
        int offLevel = getItemLightLevel(player.getHeldItemOffhand());

        // Use whichever hand has the higher light level
        int level = Math.max(mainLevel, offLevel);
        if (level <= 0) return;

        float f = level / 15.0f;
        LightData ld = collector.addLight();
        ld.setPosition((float) player.posX, (float) player.posY + player.getEyeHeight(), (float) player.posZ);
        ld.setRadius(level);
        ld.setColor(1.0f, 1.0f, 1.0f);
        ld.setIntensity(f);
        ld.setEntityId(player.getEntityId());
    }

    /**
     * Get vanilla light level (0-15) for an item. Returns 0 if not luminous.
     *
     * Uses vanilla block lightValue for block items. Shader packs can
     * override the resulting color/intensity via the Light SSBO.
     */
    private static int getItemLightLevel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;

        Item item = stack.getItem();

        // Lava bucket → lava light level (15)
        if (item == Items.LAVA_BUCKET) return 15;

        // Block items: use vanilla lightValue from default state
        if (item instanceof ItemBlock) {
            Block block = ((ItemBlock) item).getBlock();
            return block.getDefaultState().getLightValue();
        }

        return 0;
    }
}
