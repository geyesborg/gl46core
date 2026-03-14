package com.github.gl46core.api.render.gpu;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps a GL framebuffer object with typed color and depth attachments.
 *
 * Manages FBO creation, attachment binding, completeness validation,
 * and draw buffer configuration. Used by {@link RenderTargetManager}
 * to build G-buffer, shadow, and post-processing FBOs.
 *
 * Supports up to 16 color attachments (colortex0-15) and one depth
 * attachment, matching the OptiFine/Iris shaderpack FBO model.
 */
public final class FramebufferObject {

    private static final int MAX_COLOR_ATTACHMENTS = 16;

    private final String name;
    private int fboId;
    private int width;
    private int height;

    private final RenderTarget[] colorAttachments = new RenderTarget[MAX_COLOR_ATTACHMENTS];
    private RenderTarget depthAttachment;
    private int colorAttachmentCount;

    /**
     * Create a named FBO. Call {@link #create(int, int)} to allocate.
     *
     * @param name descriptive name (e.g. "gbuffer", "shadow", "composite")
     */
    public FramebufferObject(String name) {
        this.name = name;
    }

    /**
     * Create the GL FBO and allocate all attached render targets.
     */
    public void create(int width, int height) {
        if (fboId != 0) destroy();

        this.width = width;
        this.height = height;
        this.fboId = GL45.glCreateFramebuffers();

        // Allocate and attach all color targets
        for (int i = 0; i < MAX_COLOR_ATTACHMENTS; i++) {
            if (colorAttachments[i] != null) {
                colorAttachments[i].resize(width, height);
                GL45.glNamedFramebufferTexture(fboId,
                        GL30.GL_COLOR_ATTACHMENT0 + i,
                        colorAttachments[i].getTextureId(), 0);
            }
        }

        // Allocate and attach depth target
        if (depthAttachment != null) {
            depthAttachment.resize(width, height);
            int attachPoint = depthAttachment.getInternalFormat() == RenderTarget.DEPTH24_STENCIL8
                    ? GL30.GL_DEPTH_STENCIL_ATTACHMENT
                    : GL30.GL_DEPTH_ATTACHMENT;
            GL45.glNamedFramebufferTexture(fboId, attachPoint,
                    depthAttachment.getTextureId(), 0);
        }

        // Configure draw buffers
        updateDrawBuffers();
    }

    /**
     * Resize the FBO and all its attachments. Only reallocates if dimensions changed.
     */
    public void resize(int width, int height) {
        if (this.width == width && this.height == height && fboId != 0) return;
        create(width, height);
    }

    /**
     * Add a color attachment at the specified index.
     * Must be called before {@link #create(int, int)}.
     */
    public void setColorAttachment(int index, RenderTarget target) {
        if (index < 0 || index >= MAX_COLOR_ATTACHMENTS) return;
        colorAttachments[index] = target;
        if (index >= colorAttachmentCount) colorAttachmentCount = index + 1;

        // If FBO already exists, attach immediately
        if (fboId != 0 && target.isAllocated()) {
            GL45.glNamedFramebufferTexture(fboId,
                    GL30.GL_COLOR_ATTACHMENT0 + index,
                    target.getTextureId(), 0);
            updateDrawBuffers();
        }
    }

    /**
     * Set the depth attachment.
     * Must be called before {@link #create(int, int)}.
     */
    public void setDepthAttachment(RenderTarget target) {
        this.depthAttachment = target;

        if (fboId != 0 && target.isAllocated()) {
            int attachPoint = target.getInternalFormat() == RenderTarget.DEPTH24_STENCIL8
                    ? GL30.GL_DEPTH_STENCIL_ATTACHMENT
                    : GL30.GL_DEPTH_ATTACHMENT;
            GL45.glNamedFramebufferTexture(fboId, attachPoint,
                    target.getTextureId(), 0);
        }
    }

    /**
     * Bind this FBO as the current draw framebuffer and set viewport.
     */
    public void bind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL11.glViewport(0, 0, width, height);
    }

    /**
     * Clear all attached targets that have clearOnBind enabled.
     */
    public void clear() {
        for (int i = 0; i < colorAttachmentCount; i++) {
            RenderTarget rt = colorAttachments[i];
            if (rt != null && rt.isClearOnBind()) {
                GL45.glClearNamedFramebufferfv(fboId, GL11.GL_COLOR, i,
                        new float[]{rt.getClearR(), rt.getClearG(), rt.getClearB(), rt.getClearA()});
            }
        }
        if (depthAttachment != null && depthAttachment.isClearOnBind()) {
            GL45.glClearNamedFramebufferfv(fboId, GL11.GL_DEPTH, 0,
                    new float[]{depthAttachment.getClearDepth()});
        }
    }

    /**
     * Bind and clear in one call.
     */
    public void bindAndClear() {
        bind();
        clear();
    }

    /**
     * Unbind — restore the default framebuffer.
     */
    public static void unbind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * Check FBO completeness. Returns null if complete, error string otherwise.
     */
    public String checkStatus() {
        int status = GL45.glCheckNamedFramebufferStatus(fboId, GL30.GL_FRAMEBUFFER);
        switch (status) {
            case GL30.GL_FRAMEBUFFER_COMPLETE:
                return null;
            case GL30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT:
                return "INCOMPLETE_ATTACHMENT";
            case GL30.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT:
                return "MISSING_ATTACHMENT";
            case GL30.GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER:
                return "INCOMPLETE_DRAW_BUFFER";
            case GL30.GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER:
                return "INCOMPLETE_READ_BUFFER";
            case GL30.GL_FRAMEBUFFER_UNSUPPORTED:
                return "UNSUPPORTED";
            case GL30.GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE:
                return "INCOMPLETE_MULTISAMPLE";
            default:
                return "UNKNOWN_0x" + Integer.toHexString(status);
        }
    }

    /**
     * Destroy the FBO. Does NOT destroy attached render targets
     * (those are owned by RenderTargetManager).
     */
    public void destroy() {
        if (fboId != 0) {
            GL30.glDeleteFramebuffers(fboId);
            fboId = 0;
        }
    }

    /**
     * Estimate total VRAM usage of all attached targets.
     */
    public long estimateVram() {
        long total = 0;
        for (int i = 0; i < colorAttachmentCount; i++) {
            if (colorAttachments[i] != null) total += colorAttachments[i].estimateVram();
        }
        if (depthAttachment != null) total += depthAttachment.estimateVram();
        return total;
    }

    /**
     * Get all active color attachments.
     */
    public List<RenderTarget> getColorAttachments() {
        List<RenderTarget> result = new ArrayList<>();
        for (int i = 0; i < colorAttachmentCount; i++) {
            if (colorAttachments[i] != null) result.add(colorAttachments[i]);
        }
        return result;
    }

    // ── Accessors ──

    public String       getName()             { return name; }
    public int          getFboId()            { return fboId; }
    public int          getWidth()            { return width; }
    public int          getHeight()           { return height; }
    public RenderTarget getColorAttachment(int i) { return (i >= 0 && i < MAX_COLOR_ATTACHMENTS) ? colorAttachments[i] : null; }
    public RenderTarget getDepthAttachment()  { return depthAttachment; }
    public int          getColorCount()       { return colorAttachmentCount; }
    public boolean      isCreated()           { return fboId != 0; }

    // ── Internal ──

    private void updateDrawBuffers() {
        if (fboId == 0) return;

        // Build list of active draw buffers
        int[] buffers = new int[colorAttachmentCount];
        for (int i = 0; i < colorAttachmentCount; i++) {
            buffers[i] = colorAttachments[i] != null
                    ? GL30.GL_COLOR_ATTACHMENT0 + i
                    : GL11.GL_NONE;
        }
        if (colorAttachmentCount > 0) {
            GL45.glNamedFramebufferDrawBuffers(fboId, buffers);
        } else {
            GL45.glNamedFramebufferDrawBuffer(fboId, GL11.GL_NONE);
        }
    }

    @Override
    public String toString() {
        String status = checkStatus();
        return String.format("FBO[%s id=%d %dx%d colors=%d depth=%s status=%s]",
                name, fboId, width, height, colorAttachmentCount,
                depthAttachment != null ? "yes" : "no",
                status != null ? status : "COMPLETE");
    }
}
