import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiny helper that promotes staged launcher updates (.pending files) after the
 * main updater has exited. This allows Windows to replace mod-updater-gui.jar
 * while the GUI is not running. Intended for use as a Post-Exit command.
 */
public final class LauncherUpdatePromoter {

    public static void main(String[] args) {
        Map<String, String> cli = parseArgs(args);
        Path instanceDir = cli.containsKey("--instanceDir") ? Paths.get(cli.get("--instanceDir")) : null;
        Path launcherJar = cli.containsKey("--launcherJar")
                ? Paths.get(cli.get("--launcherJar"))
                : deriveLauncherJar();
        if (launcherJar == null) {
            System.err.println("[launcher-promoter] Unable to determine launcher jar path.");
            return;
        }
        try {
            promote(launcherJar, instanceDir);
        } catch (IOException e) {
            System.err.println("[launcher-promoter] Failed to promote staged update: " + e.getMessage());
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + a);
                out.put(a, args[++i]);
            }
        }
        return out;
    }

    private static Path deriveLauncherJar() {
        try {
            Path jarDir = getJarDir();
            if (jarDir != null) {
                return jarDir.resolve("mod-updater-gui.jar");
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void promote(Path launcherJar, Path instanceDir) throws IOException {
        Path pendingJar = launcherJar.resolveSibling(launcherJar.getFileName().toString() + ".pending");
        if (!Files.isRegularFile(pendingJar)) {
            System.out.println("[launcher-promoter] No staged launcher update found.");
            return;
        }
        Path pendingVersion = pendingVersionMarkerPath(launcherJar);
        String versionTag = readPendingVersionTag(pendingVersion);

        ensureDir(launcherJar.getParent());

        Files.move(pendingJar, launcherJar, StandardCopyOption.REPLACE_EXISTING);
        if (versionTag != null && !versionTag.isEmpty()) {
            writeLauncherVersionMarker(launcherJar, versionTag);
            writeLauncherVersionJson(instanceDir, versionTag);
        }
        Files.deleteIfExists(pendingJar);
        if (pendingVersion != null) {
            Files.deleteIfExists(pendingVersion);
        }
        System.out.println("[launcher-promoter] Promoted staged launcher update to " + launcherJar);
    }

    private static void ensureDir(Path dir) throws IOException {
        if (dir != null && !Files.isDirectory(dir)) {
            Files.createDirectories(dir);
        }
    }

    private static Path pendingVersionMarkerPath(Path jarPath) {
        if (jarPath == null) return null;
        String base = stripExtension(jarPath.getFileName().toString());
        Path parent = jarPath.getParent();
        if (parent == null) return null;
        return parent.resolve(base + ".version.pending");
    }

    private static String readPendingVersionTag(Path pendingVersion) {
        if (pendingVersion == null || !Files.isRegularFile(pendingVersion)) return null;
        try {
            return new String(Files.readAllBytes(pendingVersion), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            return null;
        }
    }

    private static void writeLauncherVersionMarker(Path jarPath, String version) throws IOException {
        if (jarPath == null || version == null) return;
        Path marker = launcherVersionMarkerPath(jarPath);
        if (marker == null) return;
        ensureDir(marker.getParent());
        Files.write(marker, version.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static Path launcherVersionMarkerPath(Path jarPath) {
        if (jarPath == null) return null;
        String base = stripExtension(jarPath.getFileName().toString());
        Path parent = jarPath.getParent();
        if (parent == null) return null;
        return parent.resolve(base + ".version");
    }

    private static void writeLauncherVersionJson(Path instanceRoot, String version) throws IOException {
        if (instanceRoot == null || version == null) return;
        Path jsonPath = instanceRoot.resolve("tools").resolve("mod-updater").resolve("version.json");
        ensureDir(jsonPath.getParent());
        String newline = System.getProperty("line.separator", "\n");
        StringBuilder sb = new StringBuilder();
        sb.append("{").append(newline);
        sb.append("  \"launcher\": \"").append(version).append("\"").append(newline);
        sb.append("}").append(newline);
        Files.write(jsonPath, sb.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String stripExtension(String name) {
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    private static Path getJarDir() throws Exception {
        java.net.URL loc = LauncherUpdatePromoter.class.getProtectionDomain().getCodeSource().getLocation();
        Path p = Paths.get(loc.toURI());
        if (Files.isDirectory(p)) return p;
        return p.getParent();
    }
}

