package com.github.gl46core.api.render.gpu;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

/**
 * A named render target backed by a GL texture.
 *
 * Represents a single attachment for a framebuffer — either a color
 * buffer (colortex0-15, shadowcolor0-1) or a depth buffer (depthtex0-2,
 * shadowtex0-1). Manages texture creation, resizing, and cleanup.
 *
 * Naming follows the OptiFine/Iris shaderpack convention so that the
 * shaderpack module can map targets by name without translation.
 *
 * Supported internal formats:
 *   Color:  GL_RGBA8, GL_RGBA16F, GL_RGBA32F, GL_R11F_G11F_B10F, GL_RGB8
 *   Depth:  GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT32F, GL_DEPTH24_STENCIL8
 */
public final class RenderTarget {

    /** Common internal formats for shaderpack compatibility. */
    public static final int RGBA8          = GL11.GL_RGBA8;
    public static final int RGBA16F        = GL30.GL_RGBA16F;
    public static final int RGBA32F        = GL30.GL_RGBA32F;
    public static final int R11F_G11F_B10F = GL30.GL_R11F_G11F_B10F;
    public static final int RGB8           = GL11.GL_RGB8;
    public static final int R8             = GL30.GL_R8;
    public static final int RG8            = GL30.GL_RG8;
    public static final int RG16F          = GL30.GL_RG16F;
    public static final int DEPTH24        = GL30.GL_DEPTH_COMPONENT24;
    public static final int DEPTH32F       = GL30.GL_DEPTH_COMPONENT32F;
    public static final int DEPTH24_STENCIL8 = GL30.GL_DEPTH24_STENCIL8;

    private final String name;
    private final int internalFormat;
    private final boolean isDepth;

    private int textureId;
    private int width;
    private int height;

    // Clear state
    private float clearR, clearG, clearB, clearA;
    private float clearDepth = 1.0f;
    private boolean clearOnBind = true;

    /**
     * Create a render target with the given name and format.
     *
     * @param name           shaderpack-compatible name (e.g. "colortex0", "depthtex0")
     * @param internalFormat GL internal format (e.g. GL_RGBA8, GL_DEPTH_COMPONENT24)
     */
    public RenderTarget(String name, int internalFormat) {
        this.name = name;
        this.internalFormat = internalFormat;
        this.isDepth = isDepthFormat(internalFormat);
    }

    /**
     * Allocate the backing texture at the given dimensions.
     * If already allocated, destroys the old texture first.
     */
    public void allocate(int width, int height) {
        if (textureId != 0) {
            GL11.glDeleteTextures(textureId);
        }

        this.width = width;
        this.height = height;
        this.textureId = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);

        GL45.glTextureStorage2D(textureId, 1, internalFormat, width, height);

        // Default filtering — nearest for G-buffer, linear for post
        GL45.glTextureParameteri(textureId, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL45.glTextureParameteri(textureId, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL45.glTextureParameteri(textureId, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL45.glTextureParameteri(textureId, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    /**
     * Resize the render target. Only reallocates if dimensions changed.
     */
    public void resize(int width, int height) {
        if (this.width == width && this.height == height && textureId != 0) return;
        allocate(width, height);
    }

    /**
     * Set the clear color for this target.
     */
    public void setClearColor(float r, float g, float b, float a) {
        this.clearR = r;
        this.clearG = g;
        this.clearB = b;
        this.clearA = a;
    }

    /**
     * Set the clear depth value for depth targets.
     */
    public void setClearDepth(float depth) {
        this.clearDepth = depth;
    }

    /**
     * Set whether this target should be cleared when bound to an FBO.
     */
    public void setClearOnBind(boolean clear) {
        this.clearOnBind = clear;
    }

    /**
     * Bind this texture to a texture unit for sampling in shaders.
     */
    public void bindToUnit(int unit) {
        GL45.glBindTextureUnit(unit, textureId);
    }

    /**
     * Destroy the backing texture and release GPU resources.
     */
    public void destroy() {
        if (textureId != 0) {
            GL11.glDeleteTextures(textureId);
            textureId = 0;
        }
    }

    /**
     * Estimate VRAM usage in bytes.
     */
    public long estimateVram() {
        if (textureId == 0) return 0;
        return (long) width * height * bytesPerPixel(internalFormat);
    }

    // ── Accessors ──

    public String  getName()           { return name; }
    public int     getTextureId()      { return textureId; }
    public int     getInternalFormat() { return internalFormat; }
    public int     getWidth()          { return width; }
    public int     getHeight()         { return height; }
    public boolean isDepth()           { return isDepth; }
    public boolean isAllocated()       { return textureId != 0; }
    public boolean isClearOnBind()     { return clearOnBind; }
    public float   getClearR()         { return clearR; }
    public float   getClearG()         { return clearG; }
    public float   getClearB()         { return clearB; }
    public float   getClearA()         { return clearA; }
    public float   getClearDepth()     { return clearDepth; }

    // ── Utilities ──

    private static boolean isDepthFormat(int format) {
        return format == DEPTH24 || format == DEPTH32F || format == DEPTH24_STENCIL8;
    }

    private static int bytesPerPixel(int format) {
        switch (format) {
            case GL11.GL_RGBA8:
            case GL11.GL_RGB8:
            case GL30.GL_DEPTH_COMPONENT24:
            case GL30.GL_DEPTH24_STENCIL8:
                return 4;
            case GL30.GL_RGBA16F:
            case GL30.GL_RG16F:
                return 8;
            case GL30.GL_RGBA32F:
                return 16;
            case GL30.GL_R11F_G11F_B10F:
                return 4;
            case GL30.GL_R8:
                return 1;
            case GL30.GL_RG8:
                return 2;
            case GL30.GL_DEPTH_COMPONENT32F:
                return 4;
            default:
                return 4;
        }
    }

    @Override
    public String toString() {
        return String.format("RenderTarget[%s %dx%d fmt=0x%X tex=%d]",
                name, width, height, internalFormat, textureId);
    }
}
