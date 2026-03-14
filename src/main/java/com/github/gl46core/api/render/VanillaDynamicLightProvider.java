package com.github.gl46core.api.render;

import com.github.gl46core.api.hook.DynamicLightProvider;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.EntityBlaze;
import net.minecraft.entity.monster.EntityMagmaCube;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.AxisAlignedBB;

import java.util.List;

/**
 * Extracts dynamic lights from vanilla MC entities and items.
 *
 * Light sources:
 *   - Held items: torch, glowstone, lava bucket, etc.
 *   - Burning entities
 *   - Blazes, magma cubes, fireballs (inherently luminous)
 *   - Dropped light-emitting items on ground
 *   - Glowing effect entities
 */
public final class VanillaDynamicLightProvider implements DynamicLightProvider {

    public static final VanillaDynamicLightProvider INSTANCE = new VanillaDynamicLightProvider();

    private static final float TORCH_RADIUS = 14.0f;
    private static final float GLOWSTONE_RADIUS = 15.0f;
    private static final float FIRE_RADIUS = 12.0f;
    private static final float LAVA_RADIUS = 15.0f;
    private static final float BLAZE_RADIUS = 10.0f;
    private static final float DROPPED_ITEM_RADIUS = 8.0f;

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

            // Burning entities emit fire light
            if (entity.isBurning() && !(entity instanceof EntityPlayer)) {
                LightData ld = collector.addLight();
                ld.setPosition((float) entity.posX, (float) entity.posY + 0.5f, (float) entity.posZ);
                ld.setRadius(FIRE_RADIUS);
                ld.setColor(1.0f, 0.6f, 0.2f);
                ld.setIntensity(0.8f);
                ld.setEntityId(entity.getEntityId());
                continue;
            }

            // Blazes
            if (entity instanceof EntityBlaze) {
                LightData ld = collector.addLight();
                ld.setPosition((float) entity.posX, (float) entity.posY + 0.8f, (float) entity.posZ);
                ld.setRadius(BLAZE_RADIUS);
                ld.setColor(1.0f, 0.7f, 0.3f);
                ld.setIntensity(0.9f);
                ld.setEntityId(entity.getEntityId());
                continue;
            }

            // Magma cubes
            if (entity instanceof EntityMagmaCube) {
                LightData ld = collector.addLight();
                ld.setPosition((float) entity.posX, (float) entity.posY + 0.5f, (float) entity.posZ);
                ld.setRadius(BLAZE_RADIUS * ((EntityMagmaCube) entity).getSlimeSize() / 4.0f);
                ld.setColor(1.0f, 0.5f, 0.1f);
                ld.setIntensity(0.7f);
                ld.setEntityId(entity.getEntityId());
                continue;
            }

            // Fireballs
            if (entity instanceof EntityFireball) {
                LightData ld = collector.addLight();
                ld.setPosition((float) entity.posX, (float) entity.posY, (float) entity.posZ);
                ld.setRadius(FIRE_RADIUS);
                ld.setColor(1.0f, 0.6f, 0.2f);
                ld.setIntensity(1.0f);
                ld.setEntityId(entity.getEntityId());
                continue;
            }

            // Dropped items that emit light
            if (entity instanceof EntityItem) {
                ItemStack stack = ((EntityItem) entity).getItem();
                float[] lightProps = getItemLightProperties(stack);
                if (lightProps != null) {
                    LightData ld = collector.addLight();
                    ld.setPosition((float) entity.posX, (float) entity.posY + 0.25f, (float) entity.posZ);
                    ld.setRadius(DROPPED_ITEM_RADIUS);
                    ld.setColor(lightProps[0], lightProps[1], lightProps[2]);
                    ld.setIntensity(lightProps[3] * 0.6f); // dimmer when dropped
                    ld.setEntityId(entity.getEntityId());
                }
                continue;
            }

            // Glowing effect
            if (entity.isGlowing()) {
                LightData ld = collector.addLight();
                ld.setPosition((float) entity.posX, (float) entity.posY + entity.height * 0.5f, (float) entity.posZ);
                ld.setRadius(6.0f);
                ld.setColor(0.8f, 0.9f, 1.0f);
                ld.setIntensity(0.4f);
                ld.setEntityId(entity.getEntityId());
            }
        }
    }

    /**
     * Emit light from the player's held item.
     */
    private void collectHeldItemLight(EntityPlayer player, LightCollector collector) {
        ItemStack mainHand = player.getHeldItemMainhand();
        ItemStack offHand = player.getHeldItemOffhand();

        float[] mainProps = getItemLightProperties(mainHand);
        float[] offProps = getItemLightProperties(offHand);

        // Use whichever hand has the brighter light
        float[] props = null;
        if (mainProps != null && offProps != null) {
            props = mainProps[3] >= offProps[3] ? mainProps : offProps;
        } else if (mainProps != null) {
            props = mainProps;
        } else {
            props = offProps;
        }

        if (props != null) {
            LightData ld = collector.addLight();
            ld.setPosition((float) player.posX, (float) player.posY + player.getEyeHeight(), (float) player.posZ);
            ld.setRadius(props[4]);
            ld.setColor(props[0], props[1], props[2]);
            ld.setIntensity(props[3]);
            ld.setEntityId(player.getEntityId());
        }
    }

    /**
     * Get light properties for an item: [r, g, b, intensity, radius] or null if not luminous.
     */
    private static float[] getItemLightProperties(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        Item item = stack.getItem();

        // Torch
        if (item == Item.getItemFromBlock(Blocks.TORCH)) {
            return new float[]{1.0f, 0.8f, 0.4f, 1.0f, TORCH_RADIUS};
        }
        // Redstone torch (dimmer, red)
        if (item == Item.getItemFromBlock(Blocks.REDSTONE_TORCH)) {
            return new float[]{1.0f, 0.2f, 0.1f, 0.5f, 8.0f};
        }
        // Glowstone
        if (item == Item.getItemFromBlock(Blocks.GLOWSTONE) || item == Items.GLOWSTONE_DUST) {
            return new float[]{1.0f, 0.9f, 0.5f, 1.0f, GLOWSTONE_RADIUS};
        }
        // Sea lantern
        if (item == Item.getItemFromBlock(Blocks.SEA_LANTERN)) {
            return new float[]{0.7f, 0.9f, 1.0f, 1.0f, GLOWSTONE_RADIUS};
        }
        // Lava bucket
        if (item == Items.LAVA_BUCKET) {
            return new float[]{1.0f, 0.5f, 0.1f, 1.0f, LAVA_RADIUS};
        }
        // Jack-o-lantern
        if (item == Item.getItemFromBlock(Blocks.LIT_PUMPKIN)) {
            return new float[]{1.0f, 0.8f, 0.4f, 1.0f, TORCH_RADIUS};
        }
        // End rod
        if (item == Item.getItemFromBlock(Blocks.END_ROD)) {
            return new float[]{0.95f, 0.9f, 1.0f, 1.0f, TORCH_RADIUS};
        }
        // Nether star
        if (item == Items.NETHER_STAR) {
            return new float[]{1.0f, 1.0f, 0.9f, 0.8f, 10.0f};
        }
        // Blaze rod (warm orange)
        if (item == Items.BLAZE_ROD) {
            return new float[]{1.0f, 0.7f, 0.3f, 0.8f, 10.0f};
        }
        // Blaze powder (dimmer)
        if (item == Items.BLAZE_POWDER) {
            return new float[]{1.0f, 0.7f, 0.2f, 0.5f, 6.0f};
        }
        // Fire charge
        if (item == Items.FIRE_CHARGE) {
            return new float[]{1.0f, 0.6f, 0.2f, 0.7f, 8.0f};
        }
        // Magma cream
        if (item == Items.MAGMA_CREAM) {
            return new float[]{1.0f, 0.5f, 0.1f, 0.4f, 6.0f};
        }
        // Ender eye / ender pearl (faint green)
        if (item == Items.ENDER_EYE) {
            return new float[]{0.3f, 1.0f, 0.5f, 0.5f, 8.0f};
        }
        if (item == Items.ENDER_PEARL) {
            return new float[]{0.3f, 0.8f, 0.5f, 0.3f, 4.0f};
        }
        // Prismarine crystals (aqua)
        if (item == Items.PRISMARINE_CRYSTALS) {
            return new float[]{0.5f, 0.9f, 1.0f, 0.5f, 8.0f};
        }
        // Beacon
        if (item == Item.getItemFromBlock(Blocks.BEACON)) {
            return new float[]{0.9f, 0.95f, 1.0f, 1.0f, GLOWSTONE_RADIUS};
        }
        // Redstone lamp (treat as lit when held)
        if (item == Item.getItemFromBlock(Blocks.REDSTONE_LAMP)) {
            return new float[]{1.0f, 0.8f, 0.5f, 0.9f, GLOWSTONE_RADIUS};
        }
        // Redstone ore (emits red glow when active — light level 9)
        if (item == Item.getItemFromBlock(Blocks.REDSTONE_ORE) || item == Item.getItemFromBlock(Blocks.LIT_REDSTONE_ORE)) {
            return new float[]{1.0f, 0.15f, 0.1f, 0.6f, 9.0f};
        }
        // Furnace (treat as lit when held — warm interior glow)
        if (item == Item.getItemFromBlock(Blocks.FURNACE)) {
            return new float[]{1.0f, 0.6f, 0.3f, 0.6f, 10.0f};
        }
        // Magma block
        if (item == Item.getItemFromBlock(Blocks.MAGMA)) {
            return new float[]{1.0f, 0.4f, 0.1f, 0.5f, 6.0f};
        }
        // Lava block (if obtainable)
        if (item == Item.getItemFromBlock(Blocks.LAVA)) {
            return new float[]{1.0f, 0.5f, 0.1f, 1.0f, LAVA_RADIUS};
        }

        // Generic: any block item with light emission
        if (item instanceof ItemBlock) {
            Block block = ((ItemBlock) item).getBlock();
            int lightValue = block.getDefaultState().getLightValue();
            if (lightValue > 0) {
                float f = lightValue / 15.0f;
                return new float[]{1.0f, 0.9f * f + 0.1f, 0.7f * f, f, f * 15.0f};
            }
        }

        return null;
    }
}
