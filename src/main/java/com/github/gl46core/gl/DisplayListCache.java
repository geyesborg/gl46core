package com.github.gl46core.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emulates OpenGL display lists by recording geometry and matrix ops during glNewList/glEndList
 * and replaying as VAO/VBO draws at glCallList time.
 *
 * Display lists are deprecated in core profile. This captures glBegin/glVertex/glEnd and
 * matrix operations (pushMatrix, translate, etc.), stores them, and replays.
 */
public final class DisplayListCache {

    private static final int VERTEX_SIZE = 36; // Same as ImmediateModeEmulator
    private static final int MAX_VERTICES_PER_CHUNK = 8192;

    /** GL_COMPILE_AND_EXECUTE = 0x1301 — execute immediately after glEndList */
    private static final int GL_COMPILE_AND_EXECUTE = 0x1301;

    /** Commands recorded in a display list */
    sealed interface Command permits CmdPushMatrix, CmdPopMatrix, CmdMatrixMode, CmdTranslate, CmdTranslateD,
            CmdRotate, CmdScale, CmdScaleD, CmdLoadIdentity, CmdMultMatrix, CmdLoadMatrix, CmdOrtho, CmdDrawChunk {}
    record CmdPushMatrix() implements Command {}
    record CmdPopMatrix() implements Command {}
    record CmdMatrixMode(int mode) implements Command {}
    record CmdTranslate(float x, float y, float z) implements Command {}
    record CmdTranslateD(double x, double y, double z) implements Command {}
    record CmdRotate(float angle, float x, float y, float z) implements Command {}
    record CmdScale(float x, float y, float z) implements Command {}
    record CmdScaleD(double x, double y, double z) implements Command {}
    record CmdLoadIdentity() implements Command {}
    record CmdMultMatrix(float[] matrix) implements Command {}
    record CmdLoadMatrix(float[] matrix) implements Command {}
    record CmdOrtho(double left, double right, double bottom, double top, double zNear, double zFar) implements Command {}
    record CmdDrawChunk(int mode, ByteBuffer vertexData, int vertexCount) implements Command {}

    private final Map<Integer, List<Command>> lists = new HashMap<>();
    private int currentListId = -1;
    private int currentMode = GL11.GL_COMPILE;
    private boolean recording = false;
    private int listBase = 0;

    // Recording buffer — reused for each glBegin/glEnd during recording
    private final ByteBuffer recordBuffer = ByteBuffer.allocateDirect(MAX_VERTICES_PER_CHUNK * VERTEX_SIZE)
            .order(ByteOrder.nativeOrder());
    private int recordVertexCount = 0;
    private int recordDrawMode = -1;

    // Current vertex state during recording (set by glColor, glTexCoord, glNormal before glVertex)
    private float recordR = 1, recordG = 1, recordB = 1, recordA = 1;
    private float recordU = 0, recordV = 0;
    private float recordNx = 0, recordNy = 0, recordNz = 1;

    // Reusable VBO/VAO for replay (we upload and draw one chunk at a time)
    private int replayVao = 0;
    private int replayVbo = 0;

    // Next ID for glGenLists (OpenGL returns contiguous block)
    private int nextListId = 1;

    public static final DisplayListCache INSTANCE = new DisplayListCache();

    private DisplayListCache() {}

    /**
     * glGenLists — return first ID of a contiguous block. Caller uses listId, listId+1, ... listId+range-1.
     */
    public int genLists(int range) {
        int first = nextListId;
        nextListId += range;
        return first;
    }

    public boolean isRecording() {
        return recording;
    }

    /**
     * Start recording a display list. Called from glNewList.
     */
    public void startRecording(int listId, int mode) {
        if (recording) {
            // Nested glNewList — end current and start new (OpenGL allows this)
            endRecording();
        }
        currentListId = listId;
        currentMode = mode;
        recording = true;
        syncColorFromState();
        lists.put(listId, new ArrayList<>());
    }

    /**
     * End recording. Called from glEndList.
     * For GL_COMPILE_AND_EXECUTE, immediately replays the list.
     */
    public void endRecording() {
        if (!recording) return;
        // Flush any in-progress glBegin/glEnd
        if (recordDrawMode != -1 && recordVertexCount > 0) {
            finishRecordChunk();
        }
        int listId = currentListId;
        boolean compileAndExecute = (currentMode == GL_COMPILE_AND_EXECUTE);
        recording = false;
        currentListId = -1;
        if (compileAndExecute && listId >= 0) {
            callList(listId);
        }
    }

    /**
     * Record glBegin — start buffering vertices.
     */
    public void recordBegin(int mode) {
        if (!recording) return;
        if (recordDrawMode != -1) {
            finishRecordChunk();
        }
        recordDrawMode = mode;
        recordVertexCount = 0;
        recordBuffer.clear();
    }

    /** Sync color from CoreStateTracker (call before recordBegin or when color may have changed). */
    public void syncColorFromState() {
        CoreStateTracker state = CoreStateTracker.INSTANCE;
        recordR = state.getColorR();
        recordG = state.getColorG();
        recordB = state.getColorB();
        recordA = state.getColorA();
    }

    /** Set texcoord for next vertex. */
    public void recordTexCoord(float u, float v) {
        recordU = u;
        recordV = v;
    }

    /** Set normal for next vertex. */
    public void recordNormal(float x, float y, float z) {
        recordNx = x;
        recordNy = y;
        recordNz = z;
    }

    /**
     * Record glVertex — add vertex to buffer. Uses current color/texcoord/normal state.
     */
    public void recordVertex(float x, float y, float z) {
        if (!recording || recordDrawMode == -1 || recordVertexCount >= MAX_VERTICES_PER_CHUNK) return;

        recordBuffer.putFloat(x);
        recordBuffer.putFloat(y);
        recordBuffer.putFloat(z);
        recordBuffer.put((byte) Math.min(Math.max((int) (recordR * 255.0f + 0.5f), 0), 255));
        recordBuffer.put((byte) Math.min(Math.max((int) (recordG * 255.0f + 0.5f), 0), 255));
        recordBuffer.put((byte) Math.min(Math.max((int) (recordB * 255.0f + 0.5f), 0), 255));
        recordBuffer.put((byte) Math.min(Math.max((int) (recordA * 255.0f + 0.5f), 0), 255));
        recordBuffer.putFloat(recordU);
        recordBuffer.putFloat(recordV);
        recordBuffer.putFloat(recordNx);
        recordBuffer.putFloat(recordNy);
        recordBuffer.putFloat(recordNz);
        recordVertexCount++;
    }

    /**
     * Record glEnd — finish current chunk and add to list.
     */
    public void recordEnd() {
        if (!recording || recordDrawMode == -1) return;
        finishRecordChunk();
    }

    private void finishRecordChunk() {
        if (recordVertexCount == 0) {
            recordDrawMode = -1;
            return;
        }
        ByteBuffer copy = ByteBuffer.allocateDirect(recordVertexCount * VERTEX_SIZE)
                .order(ByteOrder.nativeOrder());
        recordBuffer.flip();
        copy.put(recordBuffer);
        copy.flip();
        lists.get(currentListId).add(new CmdDrawChunk(recordDrawMode, copy, recordVertexCount));
        recordDrawMode = -1;
        recordVertexCount = 0;
        recordBuffer.clear();
    }

    // ── Matrix op recording (called when isRecording, instead of executing) ──
    public void recordPushMatrix() {
        if (!recording) return;
        lists.get(currentListId).add(new CmdPushMatrix());
    }
    public void recordPopMatrix() {
        if (!recording) return;
        lists.get(currentListId).add(new CmdPopMatrix());
    }
    public void recordMatrixMode(int mode) {
        if (!recording) return;
        lists.get(currentListId).add(new CmdMatrixMode(mode));
    }
    public void recordTranslate(float x, float y, float z) {
        if (!recording) return;
        lists.get(currentListId).add(new CmdTranslate(x, y, z));
    }
    public void recordTranslate(double x, double y, double z) {
        if (!recording) return;
        lists.get(currentListId).add(new CmdTranslateD(x, y, z));
    }
    public void recordRotate(float angle, float x, float y, float z) {
        if (!recording) return;
        lists.get(currentListId).add(new CmdRotate(angle, x, y, z));
    }
    public void recordScale(float x, float y, float z) {
        if (!recording) return;
        lists.get(currentListId).add(new CmdScale(x, y, z));
    }
    public void recordScale(double x, double y, double z) {
        if (!recording) return;
        lists.get(currentListId).add(new CmdScaleD(x, y, z));
    }
    public void recordLoadIdentity() {
        if (!recording) return;
        lists.get(currentListId).add(new CmdLoadIdentity());
    }
    public void recordMultMatrix(FloatBuffer matrix) {
        if (!recording) return;
        float[] copy = new float[16];
        int pos = matrix.position();
        matrix.get(copy);
        matrix.position(pos);
        lists.get(currentListId).add(new CmdMultMatrix(copy));
    }
    public void recordLoadMatrix(FloatBuffer matrix) {
        if (!recording) return;
        float[] copy = new float[16];
        int pos = matrix.position();
        matrix.get(copy);
        matrix.position(pos);
        lists.get(currentListId).add(new CmdLoadMatrix(copy));
    }
    public void recordOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
        if (!recording) return;
        lists.get(currentListId).add(new CmdOrtho(left, right, bottom, top, zNear, zFar));
    }

    /**
     * Replay a display list — execute matrix ops then draw each chunk.
     */
    public void callList(int listId) {
        List<Command> commands = lists.get(listId);
        if (commands == null || commands.isEmpty()) return;

        CoreShaderProgram.INSTANCE.ensureInitialized();
        ensureReplayBuffers();

        // Save matrix state before replay, restore after
        CoreMatrixStack.INSTANCE.pushMatrix();
        try {
            for (Command cmd : commands) {
                executeCommand(cmd);
            }
        } finally {
            CoreMatrixStack.INSTANCE.popMatrix();
        }
    }

    private void executeCommand(Command cmd) {
        switch (cmd) {
            case CmdPushMatrix c -> CoreMatrixStack.INSTANCE.pushMatrix();
            case CmdPopMatrix c -> CoreMatrixStack.INSTANCE.popMatrix();
            case CmdMatrixMode c -> CoreMatrixStack.INSTANCE.matrixMode(c.mode());
            case CmdTranslate c -> CoreMatrixStack.INSTANCE.translate(c.x(), c.y(), c.z());
            case CmdTranslateD c -> CoreMatrixStack.INSTANCE.translate(c.x(), c.y(), c.z());
            case CmdRotate c -> CoreMatrixStack.INSTANCE.rotate(c.angle(), c.x(), c.y(), c.z());
            case CmdScale c -> CoreMatrixStack.INSTANCE.scale(c.x(), c.y(), c.z());
            case CmdScaleD c -> CoreMatrixStack.INSTANCE.scale(c.x(), c.y(), c.z());
            case CmdLoadIdentity c -> CoreMatrixStack.INSTANCE.loadIdentity();
            case CmdMultMatrix c -> CoreMatrixStack.INSTANCE.multMatrix(FloatBuffer.wrap(c.matrix()));
            case CmdLoadMatrix c -> CoreMatrixStack.INSTANCE.loadMatrix(FloatBuffer.wrap(c.matrix()));
            case CmdOrtho c -> CoreMatrixStack.INSTANCE.ortho(c.left(), c.right(), c.bottom(), c.top(), c.zNear(), c.zFar());
            case CmdDrawChunk c -> drawChunk(c);
            default -> {}
        }
    }

    private void ensureReplayBuffers() {
        if (replayVao == 0) {
            int[] vaos = new int[1];
            GL45.glCreateVertexArrays(vaos);
            replayVao = vaos[0];
        }
        if (replayVbo == 0) {
            int[] bufs = new int[1];
            GL45.glCreateBuffers(bufs);
            replayVbo = bufs[0];
        }
    }

    private void drawChunk(CmdDrawChunk chunk) {
        int mode = chunk.mode();
        int count = chunk.vertexCount();
        ByteBuffer data = chunk.vertexData();

        if (mode == GL11.GL_QUADS) {
            mode = GL11.GL_TRIANGLES;
            count = (count / 4) * 6;
            data = expandQuadsToTriangles(chunk.vertexData(), chunk.vertexCount());
        }

        GL30.glBindVertexArray(replayVao);
        CoreVboDrawHandler.setTerrainVaoUnbound();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, replayVbo);

        data.rewind();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STREAM_DRAW);

        GL20.glEnableVertexAttribArray(CoreShaderProgram.ATTR_POSITION);
        GL20.glVertexAttribPointer(CoreShaderProgram.ATTR_POSITION, 3, GL11.GL_FLOAT, false, VERTEX_SIZE, 0);
        GL20.glEnableVertexAttribArray(CoreShaderProgram.ATTR_COLOR);
        GL20.glVertexAttribPointer(CoreShaderProgram.ATTR_COLOR, 4, GL11.GL_UNSIGNED_BYTE, true, VERTEX_SIZE, 12);
        GL20.glEnableVertexAttribArray(CoreShaderProgram.ATTR_TEXCOORD);
        GL20.glVertexAttribPointer(CoreShaderProgram.ATTR_TEXCOORD, 2, GL11.GL_FLOAT, false, VERTEX_SIZE, 16);
        GL20.glEnableVertexAttribArray(CoreShaderProgram.ATTR_NORMAL);
        GL20.glVertexAttribPointer(CoreShaderProgram.ATTR_NORMAL, 3, GL11.GL_FLOAT, false, VERTEX_SIZE, 24);
        GL20.glDisableVertexAttribArray(CoreShaderProgram.ATTR_LIGHTMAP);

        CoreShaderProgram.INSTANCE.bind(true, true, true, false);
        GL11.glDrawArrays(mode, 0, count);
    }

    private ByteBuffer expandQuadsToTriangles(ByteBuffer src, int vertexCount) {
        int quadCount = vertexCount / 4;
        ByteBuffer dst = ByteBuffer.allocateDirect(quadCount * 6 * VERTEX_SIZE).order(ByteOrder.nativeOrder());
        int savedPos = src.position();
        try {
            for (int q = 0; q < quadCount; q++) {
                int base = q * 4 * VERTEX_SIZE;
                for (int t = 0; t < 2; t++) {
                    int[] indices = t == 0 ? new int[]{0, 1, 2} : new int[]{0, 2, 3};
                    for (int i : indices) {
                        src.position(base + i * VERTEX_SIZE);
                        byte[] vert = new byte[VERTEX_SIZE];
                        src.get(vert);
                        dst.put(vert);
                    }
                }
            }
            dst.flip();
            return dst;
        } finally {
            src.position(savedPos);
        }
    }

    /**
     * Check if a list exists (for glCallList no-op when list wasn't recorded).
     */
    public boolean hasList(int listId) {
        return lists.containsKey(listId);
    }

    /**
     * glDeleteLists — remove from cache.
     */
    public void deleteLists(int list, int range) {
        for (int i = 0; i < range; i++) {
            lists.remove(list + i);
        }
    }

    public void setListBase(int base) { listBase = base; }
    public int getListBase() { return listBase; }
}
