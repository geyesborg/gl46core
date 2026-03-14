package com.github.gl46core.mixin;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/**
 * Replaces display-list-based model rendering with immediate redraw.
 *
 * In vanilla, ModelRenderer.compileDisplayList() records geometry into a GL
 * display list, then render()/renderWithRotation()/postRender() replay it
 * via glCallList. Display lists are removed in core profile, so we replace
 * this with direct Tessellator draws each frame.
 *
 * The geometry goes through Tessellator → WorldVertexBufferUploader → CoreDrawHandler
 * which is already core-profile safe.
 */
@Mixin(ModelRenderer.class)
public abstract class MixinModelRenderer {

    @Shadow public boolean isHidden;
    @Shadow public boolean showModel;
    @Shadow public float offsetX;
    @Shadow public float offsetY;
    @Shadow public float offsetZ;
    @Shadow public float rotateAngleX;
    @Shadow public float rotateAngleY;
    @Shadow public float rotateAngleZ;
    @Shadow public float rotationPointX;
    @Shadow public float rotationPointY;
    @Shadow public float rotationPointZ;
    @Shadow @Final public List<ModelBox> cubeList;
    @Shadow @Final public List<ModelRenderer> childModels;

    /**
     * Draw all model boxes from a cached VBO — compiled once, reused every frame.
     * Replaces the vanilla per-face Tessellator.draw() path (6 draws per cube)
     * with a single draw call for all cubes in this ModelRenderer.
     */
    private void drawBoxes(float scale) {
        com.github.gl46core.gl.ModelGeometryCache.INSTANCE.drawCached(
                (ModelRenderer)(Object) this, this.cubeList, scale);
    }

    /**
     * @author GL46Core
     * @reason Display lists removed in core profile — draw geometry directly each frame
     */
    @Overwrite
    public void render(float scale) {
        if (this.isHidden) return;
        if (!this.showModel) return;

        net.minecraft.client.renderer.GlStateManager.translate(this.offsetX, this.offsetY, this.offsetZ);

        if (this.rotateAngleX != 0.0F || this.rotateAngleY != 0.0F || this.rotateAngleZ != 0.0F) {
            net.minecraft.client.renderer.GlStateManager.pushMatrix();
            net.minecraft.client.renderer.GlStateManager.translate(
                    this.rotationPointX * scale, this.rotationPointY * scale, this.rotationPointZ * scale);

            if (this.rotateAngleZ != 0.0F) {
                net.minecraft.client.renderer.GlStateManager.rotate(
                        this.rotateAngleZ * (180F / (float) Math.PI), 0.0F, 0.0F, 1.0F);
            }
            if (this.rotateAngleY != 0.0F) {
                net.minecraft.client.renderer.GlStateManager.rotate(
                        this.rotateAngleY * (180F / (float) Math.PI), 0.0F, 1.0F, 0.0F);
            }
            if (this.rotateAngleX != 0.0F) {
                net.minecraft.client.renderer.GlStateManager.rotate(
                        this.rotateAngleX * (180F / (float) Math.PI), 1.0F, 0.0F, 0.0F);
            }

            drawBoxes(scale);

            if (this.childModels != null) {
                for (int i = 0; i < this.childModels.size(); i++) {
                    this.childModels.get(i).render(scale);
                }
            }

            net.minecraft.client.renderer.GlStateManager.popMatrix();
        } else if (this.rotationPointX != 0.0F || this.rotationPointY != 0.0F || this.rotationPointZ != 0.0F) {
            net.minecraft.client.renderer.GlStateManager.translate(
                    this.rotationPointX * scale, this.rotationPointY * scale, this.rotationPointZ * scale);

            drawBoxes(scale);

            if (this.childModels != null) {
                for (int i = 0; i < this.childModels.size(); i++) {
                    this.childModels.get(i).render(scale);
                }
            }

            net.minecraft.client.renderer.GlStateManager.translate(
                    -this.rotationPointX * scale, -this.rotationPointY * scale, -this.rotationPointZ * scale);
        } else {
            drawBoxes(scale);

            if (this.childModels != null) {
                for (int i = 0; i < this.childModels.size(); i++) {
                    this.childModels.get(i).render(scale);
                }
            }
        }

        net.minecraft.client.renderer.GlStateManager.translate(-this.offsetX, -this.offsetY, -this.offsetZ);
    }

    /**
     * @author GL46Core
     * @reason Display lists removed in core profile — draw geometry directly each frame
     */
    @Overwrite
    public void renderWithRotation(float scale) {
        if (this.isHidden) return;
        if (!this.showModel) return;

        net.minecraft.client.renderer.GlStateManager.pushMatrix();
        net.minecraft.client.renderer.GlStateManager.translate(
                this.rotationPointX * scale, this.rotationPointY * scale, this.rotationPointZ * scale);

        if (this.rotateAngleY != 0.0F) {
            net.minecraft.client.renderer.GlStateManager.rotate(
                    this.rotateAngleY * (180F / (float) Math.PI), 0.0F, 1.0F, 0.0F);
        }
        if (this.rotateAngleX != 0.0F) {
            net.minecraft.client.renderer.GlStateManager.rotate(
                    this.rotateAngleX * (180F / (float) Math.PI), 1.0F, 0.0F, 0.0F);
        }
        if (this.rotateAngleZ != 0.0F) {
            net.minecraft.client.renderer.GlStateManager.rotate(
                    this.rotateAngleZ * (180F / (float) Math.PI), 0.0F, 0.0F, 1.0F);
        }

        drawBoxes(scale);
        net.minecraft.client.renderer.GlStateManager.popMatrix();
    }

    /**
     * @author GL46Core
     * @reason Prevent display list compilation — we draw directly
     */
    @Overwrite
    private void compileDisplayList(float scale) {
        // No-op: we don't use display lists. render() draws directly.
    }
}
