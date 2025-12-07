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
 * Post-exit helper that promotes staged launcher updates after the main GUI has closed.
 * 
 * BACKGROUND:
 * On Windows, you cannot replace a running JAR file because the file is locked by the JVM.
 * This creates a chicken-and-egg problem: the launcher GUI (mod-updater-gui.jar) cannot
 * update itself while it's running.
 * 
 * SOLUTION:
 * The main GUI downloads updates to a ".pending" file instead of replacing itself directly.
 * This small helper program runs as a Post-Exit command in Prism Launcher, after the main
 * GUI has closed. At that point, the JAR file is no longer locked and can be replaced.
 * 
 * WORKFLOW:
 * 1. Main GUI detects a new version is available
 * 2. Main GUI downloads the update to "mod-updater-gui.jar.pending"
 * 3. Main GUI saves the version tag to "mod-updater-gui.version.pending"
 * 4. User closes the launcher (or it exits after updating the game)
 * 5. Prism Launcher runs this helper as a Post-Exit command
 * 6. This helper moves the .pending file to replace the actual JAR
 * 7. This helper updates version marker files for tracking
 * 
 * BUILD INSTRUCTIONS (requires Java 8+):
 *   javac -encoding UTF-8 -d out src/LauncherUpdatePromoter.java
 *   jar cfe launcher-promoter.jar LauncherUpdatePromoter -C out .
 * 
 * USAGE (typically configured in Prism Launcher):
 *   java -jar launcher-promoter.jar --instanceDir "path/to/instance"
 * 
 * COMMAND LINE OPTIONS:
 *   --instanceDir   Path to the Prism/MultiMC instance directory
 *   --launcherJar   Explicit path to the launcher JAR (optional, auto-detected)
 */
public final class LauncherUpdatePromoter {

    // =========================================================================
    // MAIN ENTRY POINT
    // =========================================================================

    /**
     * Main entry point for the launcher update promoter.
     * 
     * This runs after the main launcher GUI has exited, allowing us to
     * replace the locked JAR file.
     * 
     * @param args Command-line arguments
     */
    public static void main(String[] args) {
        // Parse command-line arguments
        Map<String, String> cli = parseArgs(args);
        
        // Get instance directory if provided
        Path instanceDir = cli.containsKey("--instanceDir") ? Paths.get(cli.get("--instanceDir")) : null;
        
        // Determine the launcher JAR path
        // Priority: explicit --launcherJar > auto-derived from this JAR's location
        Path launcherJar = cli.containsKey("--launcherJar")
                ? Paths.get(cli.get("--launcherJar"))
                : deriveLauncherJar();
                
        if (launcherJar == null) {
            System.err.println("[launcher-promoter] Unable to determine launcher jar path.");
            return;
        }
        
        // Attempt to promote any staged update
        try {
            promote(launcherJar, instanceDir);
        } catch (IOException e) {
            System.err.println("[launcher-promoter] Failed to promote staged update: " + e.getMessage());
        }
    }

    // =========================================================================
    // ARGUMENT PARSING
    // =========================================================================

    /**
     * Parses command-line arguments into a key-value map.
     * 
     * All arguments are expected in "--key value" format.
     * 
     * @param args Raw command-line arguments
     * @return Map of argument names to values
     */
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

    // =========================================================================
    // PATH RESOLUTION
    // =========================================================================

    /**
     * Attempts to derive the launcher JAR path based on this JAR's location.
     * 
     * Assumes the launcher JAR is named "mod-updater-gui.jar" and is in the
     * same directory as this promoter JAR.
     * 
     * @return Path to the launcher JAR, or null if unable to determine
     */
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

    // =========================================================================
    // UPDATE PROMOTION
    // =========================================================================

    /**
     * Promotes a staged launcher update by replacing the current JAR.
     * 
     * This is the core function that:
     * 1. Checks if a .pending file exists
     * 2. Reads the pending version tag
     * 3. Moves the .pending file to replace the current JAR
     * 4. Updates version tracking files
     * 5. Cleans up temporary files
     * 
     * @param launcherJar Path to the current launcher JAR
     * @param instanceDir Path to the instance directory (for version.json)
     * @throws IOException If file operations fail
     */
    private static void promote(Path launcherJar, Path instanceDir) throws IOException {
        // Check for pending update file
        Path pendingJar = launcherJar.resolveSibling(launcherJar.getFileName().toString() + ".pending");
        if (!Files.isRegularFile(pendingJar)) {
            System.out.println("[launcher-promoter] No staged launcher update found.");
            return;
        }
        
        // Read the version tag from the pending version marker
        Path pendingVersion = pendingVersionMarkerPath(launcherJar);
        String versionTag = readPendingVersionTag(pendingVersion);

        // Ensure the parent directory exists
        ensureDir(launcherJar.getParent());

        // Replace the current JAR with the pending update
        // This is the critical operation that was previously impossible while the GUI was running
        Files.move(pendingJar, launcherJar, StandardCopyOption.REPLACE_EXISTING);
        
        // Update version tracking files if we have a version tag
        if (versionTag != null && !versionTag.isEmpty()) {
            // Write version marker next to the JAR (e.g., mod-updater-gui.version)
            writeLauncherVersionMarker(launcherJar, versionTag);
            // Write version.json in the tools/mod-updater directory
            writeLauncherVersionJson(instanceDir, versionTag);
        }
        
        // Clean up temporary files
        Files.deleteIfExists(pendingJar); // Should already be gone after move, but ensure cleanup
        if (pendingVersion != null) {
            Files.deleteIfExists(pendingVersion);
        }
        
        System.out.println("[launcher-promoter] Promoted staged launcher update to " + launcherJar);
    }

    // =========================================================================
    // FILE UTILITIES
    // =========================================================================

    /**
     * Ensures a directory exists, creating it if necessary.
     * 
     * @param dir Directory path to ensure exists
     * @throws IOException If directory creation fails
     */
    private static void ensureDir(Path dir) throws IOException {
        if (dir != null && !Files.isDirectory(dir)) {
            Files.createDirectories(dir);
        }
    }

    // =========================================================================
    // VERSION TRACKING
    // =========================================================================

    /**
     * Constructs the path for the pending version marker file.
     * 
     * The pending version marker stores the version tag of the staged update.
     * Example: "mod-updater-gui.jar" -> "mod-updater-gui.version.pending"
     * 
     * @param jarPath Path to the launcher JAR
     * @return Path to the pending version marker
     */
    private static Path pendingVersionMarkerPath(Path jarPath) {
        if (jarPath == null) return null;
        String base = stripExtension(jarPath.getFileName().toString());
        Path parent = jarPath.getParent();
        if (parent == null) return null;
        return parent.resolve(base + ".version.pending");
    }

    /**
     * Reads the version tag from a pending version marker file.
     * 
     * @param pendingVersion Path to the pending version marker
     * @return Version tag string, or null if file doesn't exist or can't be read
     */
    private static String readPendingVersionTag(Path pendingVersion) {
        if (pendingVersion == null || !Files.isRegularFile(pendingVersion)) return null;
        try {
            return new String(Files.readAllBytes(pendingVersion), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * Writes the version marker file next to the launcher JAR.
     * 
     * This file stores the current installed version of the launcher.
     * Example: "mod-updater-gui.jar" -> "mod-updater-gui.version" containing "v1.2.3"
     * 
     * @param jarPath Path to the launcher JAR
     * @param version Version tag to write
     * @throws IOException If file write fails
     */
    private static void writeLauncherVersionMarker(Path jarPath, String version) throws IOException {
        if (jarPath == null || version == null) return;
        Path marker = launcherVersionMarkerPath(jarPath);
        if (marker == null) return;
        ensureDir(marker.getParent());
        Files.write(marker, version.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Constructs the path for the version marker file.
     * 
     * Example: "mod-updater-gui.jar" -> "mod-updater-gui.version"
     * 
     * @param jarPath Path to the launcher JAR
     * @return Path to the version marker
     */
    private static Path launcherVersionMarkerPath(Path jarPath) {
        if (jarPath == null) return null;
        String base = stripExtension(jarPath.getFileName().toString());
        Path parent = jarPath.getParent();
        if (parent == null) return null;
        return parent.resolve(base + ".version");
    }

    /**
     * Writes the version.json file in the tools/mod-updater directory.
     * 
     * This JSON file provides a machine-readable way to check the installed
     * launcher version. Format:
     * {
     *   "launcher": "v1.2.3"
     * }
     * 
     * @param instanceRoot Path to the instance root directory
     * @param version Version tag to write
     * @throws IOException If file write fails
     */
    private static void writeLauncherVersionJson(Path instanceRoot, String version) throws IOException {
        if (instanceRoot == null || version == null) return;
        
        // Construct path: instance/tools/mod-updater/version.json
        Path jsonPath = instanceRoot.resolve("tools").resolve("mod-updater").resolve("version.json");
        ensureDir(jsonPath.getParent());
        
        // Build simple JSON manually (no external dependencies)
        String newline = System.getProperty("line.separator", "\n");
        StringBuilder sb = new StringBuilder();
        sb.append("{").append(newline);
        sb.append("  \"launcher\": \"").append(version).append("\"").append(newline);
        sb.append("}").append(newline);
        
        Files.write(jsonPath, sb.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // =========================================================================
    // STRING UTILITIES
    // =========================================================================

    /**
     * Removes the file extension from a filename.
     * 
     * Example: "mod-updater-gui.jar" -> "mod-updater-gui"
     * 
     * @param name Filename with extension
     * @return Filename without extension
     */
    private static String stripExtension(String name) {
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    // =========================================================================
    // JAR LOCATION
    // =========================================================================

    /**
     * Gets the directory containing this JAR file.
     * 
     * Uses the class protection domain to find where the JAR is located.
     * This allows the promoter to find the launcher JAR in the same directory.
     * 
     * @return Path to the directory containing this JAR
     * @throws Exception If unable to determine location
     */
    private static Path getJarDir() throws Exception {
        // Get the URL of the code source (the JAR file or class directory)
        java.net.URL loc = LauncherUpdatePromoter.class.getProtectionDomain().getCodeSource().getLocation();
        Path p = Paths.get(loc.toURI());
        
        // If running from a directory (dev mode), return the directory
        // If running from a JAR, return the parent directory
        if (Files.isDirectory(p)) return p;
        return p.getParent();
    }
}
