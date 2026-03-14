package com.github.gl46core.gl;

/**
 * Duck interface for accessing mega-buffer fields injected into VertexBuffer
 * by MixinVertexBuffer. Cast any VertexBuffer to this interface to read
 * mega-buffer allocation state.
 */
public interface IMegaBufferAccess {

    /** Byte offset in MegaTerrainBuffer, or -1 if not allocated. */
    long gl46core$getMegaOffset();

    /** Size in bytes of the allocated region. */
    int gl46core$getMegaSize();

    /** Vertex count corresponding to the mega-buffer data. */
    int gl46core$getMegaVertexCount();

    /** Whether the mega-buffer region is valid and current. */
    boolean gl46core$hasMegaRegion();
}
