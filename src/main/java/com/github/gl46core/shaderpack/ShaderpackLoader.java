package com.github.gl46core.shaderpack;

import com.github.gl46core.GL46Core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads and parses an OptiFine/Iris shaderpack from a .zip file or folder.
 *
 * Discovers shader source files by program name, reads shaders.properties
 * for configuration, and resolves fallback chains. Produces a
 * {@link ShaderpackSource} containing all discovered shader source code
 * and parsed properties.
 *
 * Usage:
 * <pre>
 *   ShaderpackSource source = ShaderpackLoader.load(Path.of("shaderpacks/BSL.zip"));
 *   // source.getVertexSource("gbuffers_terrain") → GLSL string or null
 *   // source.getProperties() → parsed shaders.properties
 * </pre>
 */
public final class ShaderpackLoader {

    private ShaderpackLoader() {}

    /**
     * Load a shaderpack from a .zip file or folder path.
     *
     * @param path path to the .zip file or shaderpack folder
     * @return parsed shaderpack source, or null if loading failed
     */
    public static ShaderpackSource load(Path path) {
        try {
            if (Files.isDirectory(path)) {
                return loadFromFolder(path);
            } else if (path.toString().endsWith(".zip")) {
                return loadFromZip(path);
            } else {
                GL46Core.LOGGER.error("Unsupported shaderpack format: {}", path);
                return null;
            }
        } catch (IOException e) {
            GL46Core.LOGGER.error("Failed to load shaderpack: {}", path, e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Zip loading
    // ═══════════════════════════════════════════════════════════════════

    private static ShaderpackSource loadFromZip(Path zipPath) throws IOException {
        String name = zipPath.getFileName().toString().replace(".zip", "");
        Map<String, String> sources = new LinkedHashMap<>();
        Properties properties = new Properties();

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();

                // Normalize: some zips have a top-level folder
                String normalized = normalizeEntryPath(entryName);
                if (normalized == null) continue;

                if (normalized.equals(ShaderpackFormat.PROPERTIES_FILE)) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        properties.load(is);
                    }
                } else if (isShaderFile(normalized)) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        String source = readString(is);
                        sources.put(normalized, source);
                    }
                }
            }
        }

        GL46Core.LOGGER.info("Loaded shaderpack '{}': {} shader files, {} properties",
                name, sources.size(), properties.size());
        return new ShaderpackSource(name, sources, properties);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Folder loading
    // ═══════════════════════════════════════════════════════════════════

    private static ShaderpackSource loadFromFolder(Path folderPath) throws IOException {
        String name = folderPath.getFileName().toString();
        Map<String, String> sources = new LinkedHashMap<>();
        Properties properties = new Properties();

        Path shadersDir = folderPath.resolve("shaders");
        if (!Files.isDirectory(shadersDir)) {
            GL46Core.LOGGER.warn("No 'shaders/' directory found in {}", folderPath);
            return new ShaderpackSource(name, sources, properties);
        }

        // Read properties
        Path propsFile = shadersDir.resolve("shaders.properties");
        if (Files.exists(propsFile)) {
            try (InputStream is = Files.newInputStream(propsFile)) {
                properties.load(is);
            }
        }

        // Discover shader files
        Files.walk(shadersDir, 2).forEach(file -> {
            if (!Files.isRegularFile(file)) return;
            String relative = "shaders/" + shadersDir.relativize(file).toString().replace('\\', '/');
            if (isShaderFile(relative)) {
                try {
                    sources.put(relative, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    GL46Core.LOGGER.warn("Failed to read shader file: {}", file, e);
                }
            }
        });

        GL46Core.LOGGER.info("Loaded shaderpack '{}' (folder): {} shader files, {} properties",
                name, sources.size(), properties.size());
        return new ShaderpackSource(name, sources, properties);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Utilities
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Normalize a zip entry path — strip any top-level directory prefix
     * so paths start with "shaders/".
     */
    private static String normalizeEntryPath(String path) {
        // Already starts with shaders/
        if (path.startsWith(ShaderpackFormat.SHADERS_DIR)) {
            return path;
        }

        // Some packs nest: "PackName/shaders/..." — strip the prefix
        int idx = path.indexOf("/" + ShaderpackFormat.SHADERS_DIR);
        if (idx >= 0) {
            return path.substring(idx + 1);
        }

        // Also try without leading slash
        idx = path.indexOf(ShaderpackFormat.SHADERS_DIR);
        if (idx > 0) {
            return path.substring(idx);
        }

        return null; // Not a shader file
    }

    private static boolean isShaderFile(String path) {
        return path.endsWith(ShaderpackFormat.VERTEX_EXT)
                || path.endsWith(ShaderpackFormat.FRAGMENT_EXT)
                || path.endsWith(ShaderpackFormat.GEOMETRY_EXT)
                || path.endsWith(ShaderpackFormat.COMPUTE_EXT)
                || path.endsWith(".glsl"); // common include files
    }

    private static String readString(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toString(StandardCharsets.UTF_8.name());
    }
}
