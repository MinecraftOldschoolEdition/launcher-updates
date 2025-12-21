// =============================================================================
// IMPORTS
// =============================================================================

// Swing GUI components
import javax.swing.*;
import javax.swing.border.EmptyBorder;

// AWT graphics and windowing
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

// I/O and networking
import java.io.*;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

// Collections and utilities
import java.util.List;
import java.util.*;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Archive handling
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipFile;

/**
 * Minecraft Oldschool Edition Launcher - A nostalgic GUI updater styled after the classic 2011 Minecraft launcher.
 * 
 * =============================================================================
 * OVERVIEW
 * =============================================================================
 * 
 * This is the main launcher application that provides a graphical interface for:
 * - Checking for and installing game updates from GitHub releases
 * - Displaying patch notes and news
 * - Managing launcher self-updates
 * - Providing a faithful recreation of the classic Minecraft launcher aesthetic
 * 
 * The launcher runs as a Pre-Launch command in Prism Launcher or similar Minecraft
 * launchers, appearing before the game starts to handle any necessary updates.
 * 
 * =============================================================================
 * VISUAL DESIGN
 * =============================================================================
 * 
 * The UI is designed to match the classic 2011 Minecraft launcher:
 * - Dirt block tiled background
 * - Pixelated fonts and text rendering (no antialiasing)
 * - Classic button styling with texture-based rendering
 * - "Updating Minecraft" progress screen with green progress bar
 * - "New update available" prompt with Yes/Not now buttons
 * 
 * =============================================================================
 * INSTALLATION MODES
 * =============================================================================
 * 
 * The launcher supports three installation modes configured via updater.properties:
 * 
 * 1. "mods" mode (default):
 *    - Downloads mod JAR files to the mods/ directory
 *    - Used for modern Minecraft installations with mod loaders
 * 
 * 2. "clientJar" mode:
 *    - Replaces the client minecraft.jar directly
 *    - Used for legacy Minecraft installations (pre-1.6)
 * 
 * 3. "jarmods" mode:
 *    - Installs to the Prism Launcher jarmods directory
 *    - Used for jar-mod based installations
 * 
 * =============================================================================
 * SELF-UPDATE MECHANISM
 * =============================================================================
 * 
 * The launcher can update itself, but this requires special handling:
 * - On Windows, a running JAR file cannot be replaced (file is locked)
 * - Solution: Download update to .pending file, then use LauncherUpdatePromoter
 *   as a Post-Exit command to apply the update after the launcher closes
 * 
 * =============================================================================
 * BUILD INSTRUCTIONS
 * =============================================================================
 * 
 * Requires Java 8 or later:
 *   javac -encoding UTF-8 -d out src/ModUpdaterGUI.java
 *   jar cfe mod-updater-gui.jar ModUpdaterGUI -C out .
 * 
 * Or use the provided build script:
 *   Windows: build.bat
 *   Linux/Mac: ./build.sh
 * 
 * =============================================================================
 * COMMAND LINE OPTIONS
 * =============================================================================
 * 
 * --config <path>      Path to updater.properties configuration file
 * --instanceDir <path> Path to Prism/MultiMC instance directory
 * --minecraftDir <path> Path to .minecraft directory
 * --repo <owner/repo>  GitHub repository for game updates
 * --betaRepo <owner/repo> GitHub repository for beta updates (optional)
 * --jarRegex <regex>   Regex to match the mod JAR asset
 * --assetsRegex <regex> Regex to match the assets ZIP (optional)
 * --mode <mode>        Installation mode: mods, clientJar, or jarmods
 * --newsUrl <url>      URL for embedded news/patch notes page
 * 
 * =============================================================================
 * CONFIGURATION FILE (updater.properties)
 * =============================================================================
 * 
 * The launcher reads configuration from tools/mod-updater/updater.properties:
 * 
 *   repo=YourOrg/YourRepo
 *   jarRegex=YourMod-.*\\.jar
 *   assetsRegex=assets-.*\\.zip
 *   mode=mods
 *   newsUrl=https://example.com/patchnotes
 *   launcherRepo=YourOrg/launcher-updates
 * 
 * @author Minecraft Oldschool Edition Team
 * @see ModUpdater CLI version of this updater
 * @see LauncherUpdatePromoter Helper for self-update on Windows
 */
public final class ModUpdaterGUI {

    // =========================================================================
    // STATIC INITIALIZATION
    // =========================================================================
    
    /**
     * Static initializer block - runs before any AWT/Swing classes are loaded.
     * 
     * This sets up system properties needed for compatibility with various
     * environments, particularly the Steam Deck running in game mode (gamescope).
     * 
     * These properties MUST be set before Swing/AWT initializes, which is why
     * they're in a static block rather than in main().
     */
    static {
        try {
            // =====================================================================
            // Steam Deck / Linux Gamescope Compatibility Fixes
            // =====================================================================
            // 
            // On Steam Deck in game mode, the display runs through "gamescope",
            // a Wayland-based compositor. Java's 2D acceleration can cause issues:
            // - Blank white screens
            // - Rendering glitches
            // - Window management problems
            //
            // Solution: Disable all hardware acceleration and force software rendering
            
            System.setProperty("sun.java2d.opengl", "false");      // Disable OpenGL acceleration
            System.setProperty("sun.java2d.xrender", "false");     // Disable XRender (Linux)
            System.setProperty("sun.java2d.pmoffscreen", "false"); // Disable offscreen pixmaps
            System.setProperty("sun.java2d.d3d", "false");         // Disable Direct3D (Windows)
            System.setProperty("sun.java2d.noddraw", "true");      // Disable DirectDraw (Windows)
            
            // Fix for Wayland/gamescope window manager interaction issues
            System.setProperty("sun.awt.disablegrab", "true");
        } catch (Throwable ignored) {
            // Security manager might prevent setting properties - continue anyway
        }
    }
    
    // =========================================================================
    // CONSTANTS - Network Configuration
    // =========================================================================
    
    /** HTTP connection and read timeout in milliseconds (15 seconds) */
    private static final int HTTP_TIMEOUT_MS = 15000;
    
    /** GitHub API endpoint template for fetching the latest release from a repository */
    private static final String GITHUB_API_LATEST = "https://api.github.com/repos/%s/releases/latest";
    
    // =========================================================================
    // CONSTANTS - UI Fonts and Textures
    // =========================================================================
    
    /** 
     * Base font for UI elements. Attempts to use classic JRE fonts for authentic look.
     * Falls back to system default if classic fonts unavailable.
     */
    private static final Font UI_BASE_FONT = detectBaseFont();
    
    /** 
     * Instance directory path, set from command-line args.
     * Used for loading icons and other instance-specific resources.
     */
    private static String INSTANCE_DIR;
    
    /** 
     * Pixel-style font for retro text rendering.
     * If the game font can't be loaded, falls back to default LAF font.
     */
    private static final Font PIXEL_FONT = loadGameFont();
    
    /** 
     * Texture atlas for classic Minecraft-style buttons.
     * Contains normal, hover, and pressed button states.
     */
    private static final BufferedImage BUTTON_TEXTURE = loadButtonTexture();

    // =========================================================================
    // CONSTANTS - Bouncy Castle Cryptography Library
    // =========================================================================
    // 
    // Bouncy Castle is used for cryptographic operations in the friends system.
    // It's downloaded automatically if not present in the instance's libraries.
    
    private static final String BC_GROUP_ID = "org.bouncycastle";
    private static final String BC_ARTIFACT_ID = "bcprov-jdk18on";
    private static final String BC_VERSION = "1.78.1";
    private static final String BC_JAR_NAME = "bcprov-jdk18on-1.78.1.jar";
    private static final String BC_MAVEN_URL = "https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/1.78.1/bcprov-jdk18on-1.78.1.jar";

    // =========================================================================
    // MAIN ENTRY POINT
    // =========================================================================
    
    /**
     * Main entry point for the launcher GUI.
     * 
     * EXECUTION FLOW:
     * 1. Configure Swing look-and-feel for classic 2011 appearance
     * 2. Parse command-line arguments and load configuration
     * 3. Resolve paths (minecraft dir, instance dir, launcher jar)
     * 4. Check for any pending launcher updates and apply them
     * 5. Fetch current update state from GitHub
     * 6. Display the launcher GUI and wait for user interaction
     * 
     * ERROR HANDLING:
     * If any error occurs, it's displayed to the user but the launcher exits
     * with code 0 to allow the game to continue starting. This prevents
     * update failures from blocking gameplay.
     * 
     * @param args Command-line arguments (see class javadoc for options)
     */
    public static void main(String[] args) {
        try {
            // =================================================================
            // STEP 1: Configure Swing Look-and-Feel
            // =================================================================
            // Disable font antialiasing for authentic blocky 2011-era text
            try {
                System.setProperty("awt.useSystemAAFontSettings", "off");
                System.setProperty("swing.aatext", "false");
            } catch (Throwable ignored) {}
            
            // Use cross-platform (Metal) look-and-feel for consistent appearance
            try { 
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); 
            } catch (Exception ignored) {}
            
            // Apply classic JRE fonts (Lucida Sans family) for authentic 2011 look
            try {
                Font baseUi = new Font("Lucida Sans", Font.PLAIN, 12);
                applyUIFont(baseUi);
            } catch (Throwable ignored) {}
            
            // =================================================================
            // STEP 3: Parse Arguments and Load Configuration
            // =================================================================
            Map<String, String> cli = parseArgs(args);
            Properties cfg = loadConfig(cli.get("--config"));

            // Required: GitHub repository for game updates (e.g., "YourOrg/YourMod")
            String repo = value(cli, cfg, "repo", null);
            if (repo == null) {
                throw new IllegalArgumentException("Missing 'repo' (owner/repo). Provide in --config or as --repo.");
            }

            // Optional: Secondary repository for beta/development releases
            String betaRepo = value(cli, cfg, "betaRepo", null);

            // Regex patterns for identifying release assets
            String jarRegex = value(cli, cfg, "jarRegex", ".*\\.jar");      // Pattern for mod JAR
            String assetsRegex = value(cli, cfg, "assetsRegex", null);       // Pattern for assets ZIP (optional)
            
            // Installation mode: mods (default), clientJar (legacy), or jarmods
            String mode = value(cli, cfg, "mode", "mods");
            String jarmodName = value(cli, cfg, "jarmodName", "mod.jar");
            
            // Optional URL for embedded news/patch notes page
            String newsUrl = value(cli, cfg, "newsUrl", null);
            
            // Auto-migrate old newsUrl to new domain if needed
            newsUrl = migrateNewsUrl(newsUrl, cfg, cli.get("--config"));
            
            // Launcher self-update configuration
            String launcherRepo = value(cli, cfg, "launcherRepo", "MinecraftOldschoolEdition/launcher-updates");
            String launcherJarRegex = value(cli, cfg, "launcherJarRegex", "mod-updater-gui\\.jar");
            
            // Resource pack repository (assets synced before each launch)
            String resourcePackRepo = value(cli, cfg, "resourcePackRepo", "MinecraftOldschoolEdition/resourcepack");

            // =================================================================
            // STEP 4: Resolve Directory Paths
            // =================================================================
            // Resolve the .minecraft directory from various sources
            Path minecraftDir = resolveMinecraftDir(
                firstNonNull(cli.get("--minecraftDir"), cfg.getProperty("minecraftDir")),
                firstNonNull(cli.get("--instanceDir"), cfg.getProperty("instanceDir")),
                getenv("MC_DIR")
            );
            if (minecraftDir == null) {
                throw new IllegalArgumentException("Unable to resolve Minecraft directory. Set minecraftDir in config or pass --minecraftDir / --instanceDir.");
            }

            // Resolve the instance root directory (parent of .minecraft in Prism)
            Path instanceRoot = resolveInstanceRoot(minecraftDir, firstNonNull(cli.get("--instanceDir"), cfg.getProperty("instanceDir")));
            INSTANCE_DIR = firstNonNull(cli.get("--instanceDir"), cfg.getProperty("instanceDir"));

            // =================================================================
            // STEP 5: Handle Pending Launcher Updates
            // =================================================================
            // Find where this JAR is running from
            Path launcherJarPath = locateSelfJar();
            
            // Apply any staged launcher update (from previous session)
            applyStagedLauncherUpdate(launcherJarPath, instanceRoot);
            
            // For jarmods mode, try to derive the jarmod name from existing files
            if (instanceRoot != null) {
                String derivedJarmod = derivePatchJarmodName(instanceRoot, jarmodName);
                if (derivedJarmod != null && derivedJarmod.length() > 0) {
                    jarmodName = derivedJarmod;
                }
            }

            // =================================================================
            // STEP 6: Fetch Update State from GitHub
            // =================================================================
            // Check if beta updates are enabled in config
            boolean useBetaUpdates = "true".equalsIgnoreCase(cfg.getProperty("useBetaUpdates"));
            
            // Fetch the current update state (checks installed version vs latest release)
            BranchContext branch = fetchBranchState(useBetaUpdates, repo, betaRepo, jarRegex, assetsRegex, minecraftDir, instanceRoot, mode, jarmodName);
            
            // Find the dirt background image for the classic look
            Path bgPath = findBgPath(minecraftDir);

            // Determine config file path for saving settings changes
            String cliConfig = cli.get("--config");
            Path configPath = (cliConfig != null) ? Paths.get(cliConfig) : Paths.get("tools", "mod-updater", "updater.properties");

            // =================================================================
            // STEP 7: Build Launcher State and Show GUI
            // =================================================================
            // Package all state into a single object for the GUI
            LauncherState state = new LauncherState();
            state.hasUpdate = !branch.upToDate;           // Is a game update available?
            state.releaseRepo = repo;                      // Main release repository
            state.betaRepo = betaRepo;                     // Beta release repository
            state.useBetaUpdates = useBetaUpdates;         // Beta updates enabled?
            state.configPath = configPath;                 // Path to config file
            state.instanceRoot = instanceRoot;             // Instance root directory
            state.launcherUpdate = checkLauncherUpdate(launcherRepo, launcherJarRegex, launcherJarPath, instanceRoot);
            state.branch = branch;                         // Current branch context
            state.resourcePackRepo = resourcePackRepo;     // Resource pack repository
            state.minecraftDir = minecraftDir;             // Minecraft directory for assets
            
            // Display the launcher GUI (blocks until user closes it)
            showLauncher(bgPath, minecraftDir, instanceRoot, mode, jarRegex, assetsRegex, jarmodName, state, newsUrl);
        } catch (Throwable t) {
            // If the updater fails for any reason, log/show the error but do NOT
            // fail the outer launcher; exit with 0 so the game can still start.
            try {
                showError(t);
            } catch (Throwable ignored) {
                // In case we're in a context where dialogs are not allowed, ignore.
            }
            System.exit(0);
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--config".equals(a) || "--repo".equals(a) || "--betaRepo".equals(a) || "--jarRegex".equals(a) || "--assetsRegex".equals(a)
                || "--minecraftDir".equals(a) || "--instanceDir".equals(a) || "--mode".equals(a)
                || "--jarmodName".equals(a) || "--newsUrl".equals(a)) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + a);
                map.put(a, args[++i]);
            } else {
                throw new IllegalArgumentException("Unknown argument: " + a);
            }
        }
        return map;
    }

    private static Properties loadConfig(String cfgPath) throws IOException {
        Properties p = new Properties();
        Path candidate;
        if (cfgPath != null) {
            candidate = Paths.get(cfgPath);
        } else {
            // default location relative to working dir
            candidate = Paths.get("tools", "mod-updater", "updater.properties");
            if (!Files.isRegularFile(candidate)) {
                writeDefaultConfig(candidate);
            }
        }
        if (Files.isRegularFile(candidate)) {
            InputStream in = Files.newInputStream(candidate);
            try { p.load(new InputStreamReader(in, StandardCharsets.UTF_8)); }
            finally { try { in.close(); } catch (IOException ignored) {} }
        }
        return p;
    }

    private static void writeDefaultConfig(Path configPath) throws IOException {
        if (configPath == null) return;
        Path parent = configPath.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent);
        }
        String newline = System.getProperty("line.separator", "\n");
        StringBuilder sb = new StringBuilder();
        sb.append("# Auto-generated updater configuration").append(newline);
        sb.append("repo=MinecraftOldschoolEdition/release-patches").append(newline);
        sb.append("betaRepo=MinecraftOldschoolEdition/beta-patches").append(newline);
        sb.append("jarRegex=patch\\.jar").append(newline);
        sb.append("assetsRegex=(assets|resources).*(?i)\\.zip").append(newline);
        sb.append("mode=jarmods").append(newline);
        sb.append("jarmodName=mod.jar").append(newline);
        sb.append("minecraftDir=../../minecraft/game").append(newline);
        sb.append("newsUrl=https://minecraftoldschool.com/updates.html").append(newline);
        sb.append("resourcePackRepo=MinecraftOldschoolEdition/resourcepack").append(newline);
        Files.write(configPath, sb.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String value(Map<String, String> cli, Properties cfg, String key, String def) {
        String v = cli.get("--" + key);
        if (v != null) return v;
        v = cfg.getProperty(key);
        return v != null ? v : def;
    }
    
    // Canonical news URL - all old URLs should redirect here
    private static final String CANONICAL_NEWS_URL = "https://minecraftoldschool.com/updates.html";
    
    // Old URLs that should be migrated
    private static final String[] OLD_NEWS_URLS = {
        "https://minecraftoldschooledition.github.io/Minecraft-Oldschool-Edition-Updates-Site/",
        "https://minecraftoldschooledition.github.io",
        "minecraftoldschooledition.github.io",
        "https://minecraftoldschool.com/updates" // Without .html
    };
    
    /**
     * Auto-migrate old newsUrl values to the canonical URL.
     * Updates the config file if migration is needed.
     */
    private static String migrateNewsUrl(String currentUrl, Properties cfg, String configPathStr) {
        System.out.println("[mod-updater] Checking newsUrl migration. Current: " + currentUrl);
        
        if (currentUrl == null || currentUrl.trim().isEmpty()) {
            System.out.println("[mod-updater] newsUrl is empty, using canonical: " + CANONICAL_NEWS_URL);
            return CANONICAL_NEWS_URL; // Default to canonical
        }
        
        // Already canonical?
        if (currentUrl.equals(CANONICAL_NEWS_URL)) {
            System.out.println("[mod-updater] newsUrl already canonical, no migration needed");
            return currentUrl;
        }
        
        // Check if it's the URL without .html extension
        if (currentUrl.equals("https://minecraftoldschool.com/updates") ||
            currentUrl.equals("https://minecraftoldschool.com/updates/")) {
            System.out.println("[mod-updater] Found URL without .html extension, migrating");
            return migrateAndSave(cfg, configPathStr);
        }
        
        // Check if current URL needs migration (old github.io URLs)
        boolean needsMigration = false;
        for (String oldUrl : OLD_NEWS_URLS) {
            if (currentUrl.toLowerCase().contains(oldUrl.toLowerCase())) {
                needsMigration = true;
                System.out.println("[mod-updater] Found old URL pattern: " + oldUrl);
                break;
            }
        }
        
        if (!needsMigration) {
            System.out.println("[mod-updater] newsUrl doesn't match old patterns, keeping: " + currentUrl);
            return currentUrl; // Custom URL, don't change
        }
        
        return migrateAndSave(cfg, configPathStr);
    }
    
    private static String migrateAndSave(Properties cfg, String configPathStr) {
        System.out.println("[mod-updater] Migrating newsUrl to " + CANONICAL_NEWS_URL);
        
        // Update the properties object
        cfg.setProperty("newsUrl", CANONICAL_NEWS_URL);
        
        // Save the updated config
        if (configPathStr == null || configPathStr.trim().isEmpty()) {
            System.err.println("[mod-updater] No config path provided, cannot save migration");
            return CANONICAL_NEWS_URL;
        }
        
        try {
            Path configPath = Paths.get(configPathStr).toAbsolutePath();
            System.out.println("[mod-updater] Config path: " + configPath);
            
            if (Files.exists(configPath)) {
                // Read existing file, replace newsUrl line
                String content = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
                // Replace the old newsUrl line with new one (handle both with and without trailing newline)
                String newContent = content.replaceAll(
                    "(?m)^newsUrl=.*$",
                    "newsUrl=" + CANONICAL_NEWS_URL
                );
                
                if (!newContent.equals(content)) {
                    Files.write(configPath, newContent.getBytes(StandardCharsets.UTF_8));
                    System.out.println("[mod-updater] Successfully updated config file with new newsUrl");
                } else {
                    System.out.println("[mod-updater] Config content unchanged (newsUrl line not found?)");
                }
            } else {
                System.err.println("[mod-updater] Config file does not exist: " + configPath);
            }
        } catch (Exception e) {
            System.err.println("[mod-updater] Failed to save migrated newsUrl: " + e.getMessage());
            e.printStackTrace();
        }
        
        return CANONICAL_NEWS_URL;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    /**
     * Small bit of mutable launcher state shared between the main window and
     * the options dialog (for "Force update").
     */
    private static final class LauncherState {
        boolean hasUpdate;
        volatile boolean forceUpdate;
        String releaseRepo;
        String betaRepo;
        volatile boolean useBetaUpdates;
        Path configPath;
        BranchContext branch;
        LauncherUpdateState launcherUpdate;
        Path instanceRoot;
        String resourcePackRepo;
        Path minecraftDir;
    }

    private static final class BranchContext {
        String repo;
        boolean beta;
        LatestRelease latest;
        ReleaseAsset jarAsset;
        ReleaseAsset assetsZip;
        boolean upToDate;
    }

    private static final class LauncherUpdateState {
        boolean updateAvailable;
        LatestRelease latest;
        ReleaseAsset asset;
        Path launcherJar;
        String currentVersion;
    }

    private static void saveBetaSetting(Path configPath, boolean useBeta) {
        if (configPath == null) return;
        try {
            if (!Files.exists(configPath)) return;
            List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
            boolean found = false;
            String key = "useBetaUpdates";
            String val = useBeta ? "true" : "false";
            List<String> newLines = new ArrayList<String>();
            for (String line : lines) {
                String trim = line.trim();
                if (trim.startsWith(key + "=") || trim.startsWith(key + " =")) {
                    newLines.add(key + "=" + val);
                    found = true;
                } else {
                    newLines.add(line);
                }
            }
            if (!found) {
                newLines.add("");
                newLines.add("# Automatically toggled by launcher");
                newLines.add(key + "=" + val);
            }
            Files.write(configPath, newLines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }

    private static BranchContext fetchBranchState(
            boolean useBeta,
            String releaseRepo,
            String betaRepo,
            String jarRegex,
            String assetsRegex,
            Path minecraftDir,
            Path instanceRoot,
            String mode,
            String jarmodName) throws IOException {

        String repo = (useBeta && betaRepo != null && !betaRepo.isEmpty()) ? betaRepo : releaseRepo;
        if (repo == null || repo.isEmpty()) {
            throw new IllegalArgumentException("Missing release repository (set 'repo' in updater config).");
        }

        LatestRelease latest = fetchLatestRelease(repo);
        ReleaseAsset jarAsset = selectAsset(latest.assets, jarRegex);
        if (jarAsset == null) {
            throw new IllegalStateException("No release asset matches jarRegex '" + jarRegex + "' in repo " + repo);
        }
        ReleaseAsset assetsZip = assetsRegex != null ? selectAsset(latest.assets, assetsRegex) : null;
        boolean upToDate = isUpToDate(minecraftDir, instanceRoot, mode, jarRegex, jarAsset.name, latest.tag, jarmodName);

        BranchContext ctx = new BranchContext();
        ctx.repo = repo;
        ctx.beta = useBeta && betaRepo != null && !betaRepo.isEmpty();
        ctx.latest = latest;
        ctx.jarAsset = jarAsset;
        ctx.assetsZip = assetsZip;
        ctx.upToDate = upToDate;
        return ctx;
    }

    private static Path locateSelfJar() {
        try {
            java.net.URL loc = ModUpdaterGUI.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc == null) return null;
            Path p = Paths.get(loc.toURI());
            if (Files.isDirectory(p)) return null;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private static void applyStagedLauncherUpdate(Path jarPath, Path instanceRoot) {
        if (jarPath == null) return;
        Path pending = pendingLauncherPath(jarPath);
        if (!Files.isRegularFile(pending)) return;

        String stagedVersion = readPendingVersionTag(jarPath);

        try {
            Files.move(pending, jarPath, StandardCopyOption.REPLACE_EXISTING);
            if (stagedVersion != null) {
                writeLauncherVersionMarker(jarPath, stagedVersion);
                writeLauncherVersionJson(instanceRoot, stagedVersion);
            }
        } catch (IOException ex) {
            System.err.println("[mod-updater] Failed to apply staged launcher update: " + ex.getMessage());
        } finally {
            try { Files.deleteIfExists(pending); } catch (IOException ignored) {}
            Path pendingVersion = pendingVersionMarkerPath(jarPath);
            if (pendingVersion != null) {
                try { Files.deleteIfExists(pendingVersion); } catch (IOException ignored) {}
            }
        }
    }

    private static LauncherUpdateState checkLauncherUpdate(String repo, String assetRegex, Path launcherJar, Path instanceRoot) {
        LauncherUpdateState state = new LauncherUpdateState();
        state.launcherJar = launcherJar;
        if (repo == null || repo.isEmpty() || launcherJar == null) return state;
        try {
            LatestRelease latest = fetchLatestRelease(repo);
            ReleaseAsset asset = selectAsset(latest.assets, assetRegex != null ? assetRegex : ".*\\.jar");
            if (asset == null) {
                System.err.println("[mod-updater] No launcher asset matched regex '" + assetRegex + "'.");
                return state;
            }
            String currentVersion = detectLauncherVersion(launcherJar, instanceRoot);
            if (currentVersion == null && latest.tag != null && Files.isRegularFile(launcherJar)) {
                try {
                    long localSize = Files.size(launcherJar);
                    Long remoteSize = fetchRemoteContentLength(asset.url);
                    if (remoteSize != null && remoteSize.longValue() == localSize) {
                        currentVersion = latest.tag;
                        writeLauncherVersionMarker(launcherJar, currentVersion);
                        writeLauncherVersionJson(instanceRoot, currentVersion);
                    }
                } catch (IOException ignored) {}
            }
            boolean needsUpdate = currentVersion == null || latest.tag == null || !latest.tag.equals(currentVersion);
            state.latest = latest;
            state.asset = asset;
            state.currentVersion = currentVersion;
            state.updateAvailable = needsUpdate;
            if (!needsUpdate && latest.tag != null) {
                try {
                    writeLauncherVersionMarker(launcherJar, latest.tag);
                    writeLauncherVersionJson(instanceRoot, latest.tag);
                } catch (IOException ignored) {}
            }
        } catch (Throwable t) {
            System.err.println("[mod-updater] Launcher self-update check failed: " + t.getMessage());
        }
        return state;
    }

    private static Long fetchRemoteContentLength(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(HTTP_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "ModUpdaterGUI/1.0");
        try {
            conn.setRequestMethod("HEAD");
        } catch (ProtocolException ignored) {}
        int code = conn.getResponseCode();
        if (code >= 200 && code < 400) {
            long len = conn.getContentLengthLong();
            return len >= 0 ? Long.valueOf(len) : null;
        }
        return null;
    }

    private static String detectLauncherVersion(Path jarPath, Path instanceRoot) {
        String jsonVersion = readLauncherVersionJson(instanceRoot);
        if (jsonVersion != null && !jsonVersion.isEmpty()) return jsonVersion.trim();
        String marker = readLauncherVersionMarker(jarPath);
        if (marker != null && !marker.isEmpty()) return marker.trim();
        Package pkg = ModUpdaterGUI.class.getPackage();
        if (pkg != null) {
            String impl = pkg.getImplementationVersion();
            if (impl != null && !impl.trim().isEmpty()) return impl.trim();
        }
        return null;
    }

    private static Path launcherVersionJsonPath(Path instanceRoot) {
        if (instanceRoot == null) return null;
        return instanceRoot.resolve("tools").resolve("mod-updater").resolve("version.json");
    }

    private static String readLauncherVersionJson(Path instanceRoot) {
        Path jsonPath = launcherVersionJsonPath(instanceRoot);
        if (jsonPath == null || !Files.isRegularFile(jsonPath)) return null;
        try {
            String json = new String(Files.readAllBytes(jsonPath), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"launcher\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            if (m.find()) {
                return m.group(1);
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static void writeLauncherVersionJson(Path instanceRoot, String version) throws IOException {
        if (instanceRoot == null || version == null) return;
        Path jsonPath = launcherVersionJsonPath(instanceRoot);
        if (jsonPath == null) return;
        Path parent = jsonPath.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent);
        }
        String newline = System.getProperty("line.separator", "\n");
        StringBuilder sb = new StringBuilder();
        sb.append("{").append(newline);
        sb.append("  \"launcher\": \"").append(version).append("\"").append(newline);
        sb.append("}").append(newline);
        Files.write(jsonPath, sb.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String readLauncherVersionMarker(Path jarPath) {
        if (jarPath == null) return null;
        Path marker = launcherVersionMarkerPath(jarPath);
        if (marker == null || !Files.isRegularFile(marker)) return null;
        try {
            return new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeLauncherVersionMarker(Path jarPath, String version) throws IOException {
        if (jarPath == null || version == null) return;
        Path marker = launcherVersionMarkerPath(jarPath);
        if (marker == null) return;
        ensureDir(marker.getParent());
        Files.write(marker, version.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static Path launcherVersionMarkerPath(Path jarPath) {
        if (jarPath == null) return null;
        String name = jarPath.getFileName().toString();
        String base = stripExtension(name);
        Path parent = jarPath.getParent();
        if (parent == null) return null;
        return parent.resolve(base + ".version");
    }

    private static Path pendingLauncherPath(Path jarPath) {
        if (jarPath == null) return null;
        return jarPath.resolveSibling(jarPath.getFileName().toString() + ".pending");
    }

    private static Path pendingVersionMarkerPath(Path jarPath) {
        if (jarPath == null) return null;
        String base = stripExtension(jarPath.getFileName().toString());
        Path parent = jarPath.getParent();
        if (parent == null) return null;
        return parent.resolve(base + ".version.pending");
    }

    private static boolean installLauncherUpdate(Path jarPath, Path payload, String versionTag, Path instanceRoot) throws IOException {
        if (jarPath == null) throw new IOException("Launcher jar path is unknown.");
        Path parent = jarPath.getParent();
        if (parent != null) {
            ensureDir(parent);
        }
        try {
            Files.move(payload, jarPath, StandardCopyOption.REPLACE_EXISTING);
            if (versionTag != null) {
                writeLauncherVersionMarker(jarPath, versionTag);
                writeLauncherVersionJson(instanceRoot, versionTag);
            }
            Files.deleteIfExists(pendingLauncherPath(jarPath));
            Path pendingVersion = pendingVersionMarkerPath(jarPath);
            if (pendingVersion != null) {
                Files.deleteIfExists(pendingVersion);
            }
            return true;
        } catch (IOException direct) {
            Path pending = pendingLauncherPath(jarPath);
            Files.move(payload, pending, StandardCopyOption.REPLACE_EXISTING);
            if (versionTag != null) {
                writePendingVersionMarker(jarPath, versionTag);
            }
            return false;
        }
    }

    private static void writePendingVersionMarker(Path jarPath, String versionTag) {
        if (versionTag == null) return;
        Path pending = pendingVersionMarkerPath(jarPath);
        if (pending == null) return;
        try {
            if (pending.getParent() != null) {
                ensureDir(pending.getParent());
            }
            Files.write(pending, versionTag.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {}
    }

    private static String readPendingVersionTag(Path jarPath) {
        Path pending = pendingVersionMarkerPath(jarPath);
        if (pending == null || !Files.isRegularFile(pending)) return null;
        try {
            return new String(Files.readAllBytes(pending), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String stripExtension(String name) {
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    private static LatestRelease currentLatest(LauncherState state) {
        return state != null && state.branch != null ? state.branch.latest : null;
    }

    /**
     * Launcher-style window that embeds a patch-notes "web page" and a dirt
     * bottom bar with Play / Options buttons. When an update is available,
     * pressing Play will flip to the classic updater screen as a second page
     * and run the update before exiting.
     */
    private static void showLauncher(
            final Path bgPath,
            final Path minecraftDir,
            final Path instanceRoot,
            final String mode,
            final String jarRegex,
            final String assetsRegex,
            final String jarmodName,
            final LauncherState launcherState,
            final String newsUrl) {

        // Use a latch to keep the main thread alive until the GUI window closes.
        // Without this, the main thread exits immediately after invokeLater returns,
        // and the JVM may terminate before the Swing EDT can display the window.
        final CountDownLatch windowClosedLatch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                final JFrame frame = new JFrame("Minecraft Oldschool Edition Launcher");
                frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
                frame.setMinimumSize(new Dimension(854, 480));
                // Start slightly larger than the classic 854x480 to better match the original launcher feel.
                frame.setSize(900, 520);
                frame.setLocationRelativeTo(null);

                List<Image> icons = loadAppIcons();
                if (!icons.isEmpty()) {
                    frame.setIconImages(icons);
                    Image best = pickLargestIcon(icons);
                    if (best != null) frame.setIconImage(best);
                }

                JPanel root = new JPanel(new BorderLayout());
                // Match the embedded news page background so there is no visible
                // grey border around the web content area in the client.
                Color newsBg = new Color(16, 16, 16);
                root.setBackground(newsBg);
                frame.setContentPane(root);

                final CardLayout cards = new CardLayout();
                final JPanel cardPanel = new JPanel(cards);
                cardPanel.setOpaque(false);
                root.add(cardPanel, BorderLayout.CENTER);

                // --- Page 1: patch notes / news (embedded web-style area) ---
                JPanel newsPage = new JPanel(new BorderLayout());
                newsPage.setOpaque(true);
                newsPage.setBackground(newsBg);

                final JEditorPane newsPane = new JEditorPane();
                newsPane.setEditable(false);
                newsPane.setContentType("text/html");
                newsPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
                // Use a slightly smaller base font so embedded pages (like mcupdate.tumblr.com
                // or your cloned patch-notes page) render closer to their original size inside
                // the launcher window and leave enough room for the sidebar.
                Font baseNewsFont = UI_BASE_FONT != null ? UI_BASE_FONT : newsPane.getFont();
                if (baseNewsFont != null) {
                    newsPane.setFont(baseNewsFont.deriveFont(Font.PLAIN, 10f));
                }
                newsPane.setBackground(newsBg);
                // Remove any default Swing border so the HTML page touches the
                // scrollpane edge without a dark outline.
                newsPane.setBorder(null);
                newsPane.setForeground(new Color(220, 220, 220));
                newsPane.setMargin(new Insets(8, 8, 8, 8));

                JScrollPane scroll = new JScrollPane(newsPane);
                // Remove the default scrollpane/viewport borders and ensure their background
                // matches the page so there is no dark frame around the content.
                scroll.setBorder(null);
                scroll.setBackground(newsBg);
                scroll.getViewport().setBackground(newsBg);
                scroll.getViewport().setBorder(null);
                newsPage.add(scroll, BorderLayout.CENTER);

                cardPanel.add(newsPage, "news");

                // --- Page 2: "New update available" prompt on a dirt page (classic launcher style) ---
                final JPanel promptPage = new BackgroundPanel(bgPath);
                promptPage.setLayout(new BorderLayout());
                promptPage.setBorder(new EmptyBorder(24, 24, 50, 24));

                PixelLabel promptTitle = new PixelLabel("New update available", 18f, true);
                promptTitle.setForeground(new Color(202, 202, 202)); // #CACACA
                PixelLabel promptSubtitle = new PixelLabel("Would you like to update?", 12f, false);
                promptSubtitle.setForeground(new Color(202, 202, 202)); // #CACACA

                JPanel promptButtonsRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 0));
                JButton yesButton = new PixelButton("Yes");
                JButton noButton = new PixelButton("Not now");
                promptButtonsRow.add(yesButton);
                promptButtonsRow.add(noButton);
                promptButtonsRow.setOpaque(false);

                JPanel promptCenter = new JPanel();
                promptCenter.setLayout(new BoxLayout(promptCenter, BoxLayout.Y_AXIS));
                promptCenter.setOpaque(false);
                promptTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
                promptSubtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
                Component spacer1 = Box.createVerticalStrut(16);
                Component spacer2 = Box.createVerticalStrut(2);
                promptCenter.add(promptTitle);
                promptCenter.add(spacer1);
                promptCenter.add(promptSubtitle);
                promptCenter.add(spacer2);
                promptCenter.add(promptButtonsRow);

                promptCenter.setMaximumSize(new Dimension(Integer.MAX_VALUE, promptCenter.getPreferredSize().height));
                promptButtonsRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, promptButtonsRow.getPreferredSize().height));
                Box vboxPrompt = Box.createVerticalBox();
                vboxPrompt.add(promptCenter);
                vboxPrompt.add(Box.createVerticalGlue());
                promptPage.add(vboxPrompt, BorderLayout.CENTER);
                cardPanel.add(promptPage, "prompt");

                // Dynamic scaling for the prompt page like the original showPrompt() window
                java.awt.event.ComponentAdapter promptResizer = new java.awt.event.ComponentAdapter() {
                    private void apply() {
                        int w = frame.getWidth();
                        int h = frame.getHeight();
                        double layout = Math.min(w / 854.0, h / 480.0);
                        int k = (int) Math.max(1, Math.ceil(layout - 1e-6));
                        promptTitle.setPixelScale(k);
                        promptSubtitle.setPixelScale(k);
                        int baseW, baseH;
                        if (k >= 3) {
                            baseW = 96; baseH = 24;
                        } else {
                            baseW = 50; baseH = 18;
                        }
                        int buttonK = (k < 2 ? 2 : k);
                        yesButton.putClientProperty("pixelScale", Integer.valueOf(buttonK));
                        noButton.putClientProperty("pixelScale", Integer.valueOf(buttonK));
                        yesButton.putClientProperty("baseW", Integer.valueOf(baseW));
                        yesButton.putClientProperty("baseH", Integer.valueOf(baseH));
                        noButton.putClientProperty("baseW", Integer.valueOf(baseW));
                        noButton.putClientProperty("baseH", Integer.valueOf(baseH));
                        yesButton.setFont((UI_BASE_FONT != null ? UI_BASE_FONT : yesButton.getFont()).deriveFont(Font.PLAIN, 11.15f));
                        noButton.setFont((UI_BASE_FONT != null ? UI_BASE_FONT : noButton.getFont()).deriveFont(Font.PLAIN, 11.15f));
                        yesButton.revalidate();
                        noButton.revalidate();
                        int sidePad = 48 * k;
                        // Position content in upper area like the original launcher
                        int topPad = (int) Math.round(frame.getHeight() * 0.15);
                        promptPage.setBorder(new EmptyBorder(topPad, sidePad, sidePad, sidePad));
                        Dimension sp1 = new Dimension(1, 12 * k);
                        spacer1.setPreferredSize(sp1); spacer1.setMinimumSize(sp1); spacer1.setMaximumSize(new Dimension(Integer.MAX_VALUE, sp1.height));
                        Dimension sp2 = new Dimension(1, 8 * k);
                        spacer2.setPreferredSize(sp2); spacer2.setMinimumSize(sp2); spacer2.setMaximumSize(new Dimension(Integer.MAX_VALUE, sp2.height));
                        if (promptButtonsRow.getLayout() instanceof FlowLayout) {
                            ((FlowLayout) promptButtonsRow.getLayout()).setHgap(30 * k);
                            ((FlowLayout) promptButtonsRow.getLayout()).setVgap(2 * k);
                        }
                        promptButtonsRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, promptButtonsRow.getPreferredSize().height));
                        promptButtonsRow.revalidate();
                        promptPage.revalidate();
                        promptPage.repaint();
                    }
                    public void componentShown(java.awt.event.ComponentEvent e) { apply(); }
                    public void componentResized(java.awt.event.ComponentEvent e) { apply(); }
                };
                frame.addComponentListener(promptResizer);

                // --- Page 3: classic updater progress screen, embedded ---
                final ProgressCanvas progressCanvas = new ProgressCanvas(bgPath);
                progressCanvas.setOpaque(false);
                JPanel updatePage = new JPanel(new BorderLayout());
                updatePage.setOpaque(false);
                updatePage.add(progressCanvas, BorderLayout.CENTER);
                cardPanel.add(updatePage, "update");

                // Dirt bottom bar with Play / Options replacing the old login fields
                BackgroundPanel bottomBg = new BackgroundPanel(bgPath);
                // No extra top padding so the black separator line sits exactly on the dirt edge.
                bottomBg.setBorder(new EmptyBorder(0, 0, 0, 0));
                bottomBg.setLayout(new BorderLayout());
                // Taller dirt bar so the logo and buttons sit more like the original launcher.
                bottomBg.setPreferredSize(new Dimension(10, 96));
                root.add(bottomBg, BorderLayout.SOUTH);

                // Thin black line that sits directly above the dirt bar.
                JComponent topBorder = new JComponent() {
                    protected void paintComponent(Graphics g0) {
                        g0.setColor(Color.BLACK);
                        g0.fillRect(0, 0, getWidth(), 1);
                    }
                    public Dimension getPreferredSize() {
                        return new Dimension(10, 1);
                    }
                };
                bottomBg.add(topBorder, BorderLayout.NORTH);

                JPanel bottomInner = new JPanel(new BorderLayout());
                bottomInner.setOpaque(false);
                bottomBg.add(bottomInner, BorderLayout.CENTER);

                // Bottom left: Minecraft logo instead of text.
                JLabel logoLabel = new JLabel();
                logoLabel.setHorizontalAlignment(SwingConstants.LEFT);
                logoLabel.setVerticalAlignment(SwingConstants.CENTER);
                logoLabel.setBorder(new EmptyBorder(8, 12, 8, 0)); // inset a bit from the left edge
                try {
                    Image logoImg = loadLauncherLogoImage(minecraftDir);
                    if (logoImg != null) {
                        // Scale to better match the original launcher logo height.
                        int targetH = 48;
                        int w = logoImg.getWidth(null);
                        int h = logoImg.getHeight(null);
                        if (w > 0 && h > 0) {
                            int targetW = (int) Math.round((targetH / (double) h) * w);
                            Image scaled = logoImg.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
                            logoLabel.setIcon(new ImageIcon(scaled));
                        } else {
                            logoLabel.setIcon(new ImageIcon(logoImg));
                        }
                    }
                } catch (Throwable ignored) {
                }
                bottomInner.add(logoLabel, BorderLayout.WEST);

                // Right side: Options / Play buttons side by side, larger, with subtle rounded edges.
                JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 8));
                buttonRow.setOpaque(false);
                // Nudge buttons inward and further downward so they sit nicely centered vertically.
                buttonRow.setBorder(new EmptyBorder(20, 4, 4, 20));
                final LegacyButton updateLauncherButton = new LegacyButton("Update Launcher");
                final LegacyButton optionsButton = new LegacyButton("Options");
                optionsButton.putClientProperty("primary", Boolean.TRUE);
                final LegacyButton playButton = new LegacyButton("Play");
                Dimension btnSize = new Dimension(140, 34); // slightly taller buttons for better vertical centering
                updateLauncherButton.setPreferredSize(btnSize);
                updateLauncherButton.setMinimumSize(btnSize);
                optionsButton.setPreferredSize(btnSize);
                optionsButton.setMinimumSize(btnSize);
                playButton.setPreferredSize(btnSize);
                playButton.setMinimumSize(btnSize);
                buttonRow.add(updateLauncherButton);
                buttonRow.add(optionsButton);
                buttonRow.add(playButton);
                bottomInner.add(buttonRow, BorderLayout.EAST);

                boolean hasLauncherUpdate = launcherState != null
                        && launcherState.launcherUpdate != null
                        && launcherState.launcherUpdate.updateAvailable
                        && launcherState.launcherUpdate.asset != null;
                updateLauncherButton.setVisible(hasLauncherUpdate);

                // Load the nested patch notes "web page"
                try {
                    if (newsUrl != null && newsUrl.trim().length() > 0) {
                        newsPane.setPage(newsUrl);
                    } else {
                        newsPane.setText(buildReleaseHtml(currentLatest(launcherState), null));
                    }
                } catch (Exception e) {
                    newsPane.setText(buildReleaseHtml(currentLatest(launcherState), e));
                }
                newsPane.setCaretPosition(0);
                newsPane.addHyperlinkListener(new javax.swing.event.HyperlinkListener() {
                    public void hyperlinkUpdate(javax.swing.event.HyperlinkEvent e) {
                        if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                            if (Desktop.isDesktopSupported() && e.getURL() != null) {
                                try {
                                    Desktop.getDesktop().browse(e.getURL().toURI());
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                });

                // Debug helper: Ctrl+R reloads the news page so CSS/HTML changes can be tested
                // without restarting the launcher.
                javax.swing.KeyStroke reloadStroke =
                        javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK);
                newsPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                        .put(reloadStroke, "reloadNewsPage");
                newsPane.getActionMap().put("reloadNewsPage", new AbstractAction() {
                    public void actionPerformed(ActionEvent e) {
                        System.out.println("[mod-updater] Reloading news page (Ctrl+R)...");
                        try {
                            if (newsUrl != null && newsUrl.trim().length() > 0) {
                                newsPane.setPage(newsUrl);
                            } else {
                                newsPane.setText(buildReleaseHtml(currentLatest(launcherState), null));
                            }
                        } catch (Exception ex) {
                            newsPane.setText(buildReleaseHtml(currentLatest(launcherState), ex));
                        }
                        newsPane.setCaretPosition(0);
                    }
                });

                updateLauncherButton.addActionListener(new AbstractAction() {
                    public void actionPerformed(ActionEvent e) {
                        runLauncherSelfUpdate(frame, launcherState, updateLauncherButton);
                    }
                });

                // Play button: only prompt if force-update is enabled or the active branch has an update.
                playButton.addActionListener(new AbstractAction() {
                    public void actionPerformed(ActionEvent e) {
                        boolean needsUpdate = launcherState != null && (launcherState.forceUpdate || launcherState.hasUpdate);
                        if (needsUpdate) {
                            showPrompt();
                        } else {
                            // Show update screen and sync resource pack before launching
                            launchWithResourcePackSync();
                        }
                    }

                    private void showPrompt() {
                        cards.show(cardPanel, "prompt");
                        bottomBg.setVisible(false);
                        bottomBg.revalidate();
                        root.revalidate();
                        root.repaint();
                    }
                    
                    private void launchWithResourcePackSync() {
                        cards.show(cardPanel, "update");
                        bottomBg.setVisible(false);
                        root.revalidate();
                        root.repaint();
                        final ProgressUI ui = new EmbeddedProgressUI(progressCanvas);
                        Thread t = new Thread(new Runnable() {
                            public void run() {
                                try {
                                    if (launcherState != null && launcherState.resourcePackRepo != null) {
                                        ui.setPhaseText("Syncing resource pack...");
                                        ui.progress(10);
                                        syncResourcePack(launcherState.resourcePackRepo, launcherState.minecraftDir);
                                    }
                                    ui.setPhaseText("Done loading");
                                    ui.progress(100);
                                    try { Thread.sleep(250L); } catch (InterruptedException ignored) {}
                                    System.exit(0);
                                } catch (Throwable ex) {
                                    showError(ex);
                                    System.exit(1);
                                }
                            }
                        }, "ModUpdater-ResourcePackSync");
                        t.setDaemon(false); // Don't let JVM exit before thread completes
                        t.start();
                    }
                });

                // Options dialog styled after the classic launcher "Launcher options" window.
                optionsButton.addActionListener(new AbstractAction() {
                    public void actionPerformed(ActionEvent e) {
                        boolean previousBeta = launcherState != null && launcherState.useBetaUpdates;
                        showLauncherOptions(frame, minecraftDir, launcherState);
                        boolean currentBeta = launcherState != null && launcherState.useBetaUpdates;
                        if (launcherState != null && previousBeta != currentBeta) {
                            refreshBranchAsync(
                                    launcherState,
                                    launcherState.useBetaUpdates,
                                    launcherState.releaseRepo,
                                    launcherState.betaRepo,
                                    jarRegex,
                                    assetsRegex,
                                    minecraftDir,
                                    instanceRoot,
                                    mode,
                                    jarmodName,
                                    playButton,
                                    newsPane,
                                    newsUrl);
                        }
                    }
                });

                // Hook up prompt buttons now that all dependencies are defined
                yesButton.addActionListener(new AbstractAction() {
                    public void actionPerformed(ActionEvent e) {
                        cards.show(cardPanel, "update");
                        final ProgressUI ui = new EmbeddedProgressUI(progressCanvas);
                        Thread t = new Thread(new Runnable() {
                            public void run() {
                                try {
                                    BranchContext ctx = fetchBranchState(
                                            launcherState != null && launcherState.useBetaUpdates,
                                            launcherState != null ? launcherState.releaseRepo : null,
                                            launcherState != null ? launcherState.betaRepo : null,
                                            jarRegex,
                                            assetsRegex,
                                            minecraftDir,
                                            instanceRoot,
                                            mode,
                                            jarmodName);

                                    runUpdate(ui, minecraftDir, instanceRoot, mode, jarRegex, ctx.jarAsset, ctx.assetsZip, ctx.latest, jarmodName);
                                    
                                    // Sync resource pack before launching
                                    ui.setPhaseText("Syncing resource pack...");
                                    if (launcherState != null) {
                                        syncResourcePack(launcherState.resourcePackRepo, launcherState.minecraftDir);
                                    }
                                    
                                    ui.setPhaseText("Done loading");
                                    ui.progress(100);
                                    try { Thread.sleep(250L); } catch (InterruptedException ignored) {}
                                    if (launcherState != null) {
                                        launcherState.branch = ctx;
                                        launcherState.hasUpdate = false;
                                    }
                                    System.exit(0);
                                } catch (Throwable ex) {
                                    showError(ex);
                                    System.exit(1);
                                }
                            }
                        }, "ModUpdater-Update");
                        t.setDaemon(false); // Don't let JVM exit before update/sync completes
                        t.start();
                    }
                });
                noButton.addActionListener(new AbstractAction() {
                    public void actionPerformed(ActionEvent e) {
                        // Show update screen and sync resource pack before launching
                        cards.show(cardPanel, "update");
                        root.revalidate();
                        root.repaint();
                        final ProgressUI ui = new EmbeddedProgressUI(progressCanvas);
                        Thread t = new Thread(new Runnable() {
                            public void run() {
                                try {
                                    if (launcherState != null && launcherState.resourcePackRepo != null) {
                                        ui.setPhaseText("Syncing resource pack...");
                                        ui.progress(10);
                                        syncResourcePack(launcherState.resourcePackRepo, launcherState.minecraftDir);
                                    }
                                    ui.setPhaseText("Done loading");
                                    ui.progress(100);
                                    try { Thread.sleep(250L); } catch (InterruptedException ignored) {}
                                    System.exit(0);
                                } catch (Throwable ex) {
                                    showError(ex);
                                    System.exit(1);
                                }
                            }
                        }, "ModUpdater-ResourcePackSync");
                        t.setDaemon(false);
                        t.start();
                    }
                });

                // Release the main-thread latch when the window is closed so the JVM can exit.
                frame.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosed(java.awt.event.WindowEvent e) {
                        windowClosedLatch.countDown();
                    }
                });

                frame.setVisible(true);
            }
        });

        // Block the main thread until the window is closed. This ensures the JVM stays
        // alive even if the EDT hasn't fully started processing before main() would return.
        try {
            windowClosedLatch.await();
        } catch (InterruptedException ignored) {
            // If interrupted, just let the program exit normally.
        }
    }

    private static void runLauncherSelfUpdate(
            final JFrame frame,
            final LauncherState launcherState,
            final JButton updateButton) {

        if (launcherState == null || launcherState.launcherUpdate == null) return;
        final LauncherUpdateState update = launcherState.launcherUpdate;
        if (!update.updateAvailable || update.asset == null) return;
        if (update.launcherJar == null) {
            JOptionPane.showMessageDialog(frame,
                    "Cannot update the launcher because its jar path could not be determined.",
                    "Launcher update", JOptionPane.WARNING_MESSAGE);
            return;
        }
        updateButton.setEnabled(false);
        updateButton.setText("Updating...");
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    ButtonProgressUI progress = new ButtonProgressUI(updateButton);
                    Path download = downloadToTemp(progress, update.asset.url, update.asset.name, 0.0, 1.0);
                    boolean appliedNow = installLauncherUpdate(update.launcherJar, download, update.latest != null ? update.latest.tag : null, launcherState.instanceRoot);
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            updateButton.setVisible(false);
                            updateButton.setEnabled(false);
                            launcherState.launcherUpdate.updateAvailable = false;
                            launcherState.launcherUpdate.currentVersion = update.latest != null ? update.latest.tag : update.currentVersion;
                            String msg = appliedNow
                                    ? "Launcher updated successfully. Restart Prism/PrismMC to relaunch with the new build."
                                    : "Launcher update staged. Restart Prism/PrismMC to finish installing.";
                            JOptionPane.showMessageDialog(frame, msg, "Launcher update", JOptionPane.INFORMATION_MESSAGE);
                        }
                    });
                } catch (final Throwable ex) {
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            updateButton.setEnabled(true);
                            updateButton.setText("Update Launcher");
                            showError(ex);
                        }
                    });
                }
            }
        }, "LauncherSelfUpdate");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Shows a small options dialog similar to the classic launcher "Launcher options"
     * window. This is purely informational for now (no persistent settings).
     */
    private static void showLauncherOptions(Window parent, Path minecraftDir, LauncherState launcherState) {
        final JDialog dialog = new JDialog(parent, "Launcher options", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setResizable(false);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        // Ensure the options window shows the same icon as the main launcher.
        try {
            if (parent instanceof Frame) {
                Frame f = (Frame) parent;
                java.util.List<Image> icons = f.getIconImages();
                if (icons != null && !icons.isEmpty()) {
                    dialog.setIconImages(icons);
                }
            } else {
                java.util.List<Image> icons = loadAppIcons();
                if (icons != null && !icons.isEmpty()) {
                    dialog.setIconImages(icons);
                }
            }
        } catch (Throwable ignored) {
        }

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(new EmptyBorder(10, 16, 12, 16));
        dialog.setContentPane(root);

        // Header with "Launcher options" title (icon only in the OS title bar)
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel title = new JLabel("Launcher options");
        Font base = UI_BASE_FONT != null ? UI_BASE_FONT : title.getFont();
        title.setFont(base.deriveFont(Font.BOLD, 14f));
        title.setHorizontalAlignment(SwingConstants.CENTER);
        header.add(title, BorderLayout.CENTER);

        root.add(header, BorderLayout.NORTH);

        // Center content: two rows similar to the original
        JPanel center = new JPanel(new GridBagLayout());
        center.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(6, 0, 4, 8);

        JLabel forceLabel = new JLabel("Force game update:");
        center.add(forceLabel, c);

        c.gridx = 1;
        JButton forceButton = new JButton(launcherState != null && launcherState.forceUpdate ? "Will force!" : "Force update!");
        center.add(forceButton, c);

        c.gridx = 0;
        c.gridy = 1;
        c.insets = new Insets(6, 0, 4, 8);
        JLabel pathLabel = new JLabel("Game location on disk:");
        center.add(pathLabel, c);

        c.gridx = 1;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        String pathText = minecraftDir != null ? minecraftDir.toAbsolutePath().toString() : "<unknown>";
        final String finalPathText = pathText;
        JLabel pathLink = new JLabel("<html><a href=\"file://" + htmlEscape(pathText) + "\">" + htmlEscape(pathText) + "</a></html>");
        pathLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        pathLink.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                try {
                    java.awt.Desktop desk = java.awt.Desktop.isDesktopSupported() ? java.awt.Desktop.getDesktop() : null;
                    if (desk != null) {
                        java.io.File f = new java.io.File(finalPathText);
                        if (f.isDirectory()) {
                            desk.open(f);
                        } else {
                            desk.open(f.getParentFile());
                        }
                    }
                } catch (Exception ignored) {}
            }
        });
        center.add(pathLink, c);

        root.add(center, BorderLayout.CENTER);

        // Bottom row: beta updates checkbox on the left, "Done" button on the right
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        JCheckBox betaCheck = new JCheckBox("Get beta updates");
        betaCheck.setOpaque(false);
        betaCheck.setSelected(launcherState != null && launcherState.useBetaUpdates);
        // Only enable the checkbox if a beta repo is configured
        betaCheck.setEnabled(launcherState != null && launcherState.betaRepo != null && !launcherState.betaRepo.isEmpty());
        bottom.add(betaCheck, BorderLayout.WEST);
        JPanel bottomButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton done = new JButton("Done");
        bottomButtons.add(done);
        bottom.add(bottomButtons, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);

        // Wire up actions
        done.addActionListener(new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                dialog.dispose();
            }
        });
        forceButton.addActionListener(new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (launcherState != null) {
                    // Toggle force state on each press and update button text
                    launcherState.forceUpdate = !launcherState.forceUpdate;
                    boolean on = launcherState.forceUpdate;
                    forceButton.setText(on ? "Will force!" : "Force update!");
                }
            }
        });
        betaCheck.addActionListener(new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (launcherState != null) {
                    launcherState.useBetaUpdates = betaCheck.isSelected();
                    saveBetaSetting(launcherState.configPath, launcherState.useBetaUpdates);
                }
            }
        });

        dialog.pack();
        dialog.setSize(new Dimension(520, dialog.getPreferredSize().height + 10));
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static void refreshBranchAsync(
            final LauncherState launcherState,
            final boolean desiredBeta,
            final String releaseRepo,
            final String betaRepo,
            final String jarRegex,
            final String assetsRegex,
            final Path minecraftDir,
            final Path instanceRoot,
            final String mode,
            final String jarmodName,
            final JButton playButton,
            final JEditorPane newsPane,
            final String newsUrl) {

        if (launcherState == null) {
            return;
        }

        playButton.setEnabled(false);
        playButton.setText("Checking...");

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    BranchContext ctx = fetchBranchState(desiredBeta, releaseRepo, betaRepo, jarRegex, assetsRegex, minecraftDir, instanceRoot, mode, jarmodName);
                    launcherState.branch = ctx;
                    launcherState.hasUpdate = !ctx.upToDate;
                    launcherState.useBetaUpdates = desiredBeta;

                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            if (newsUrl == null || newsUrl.trim().isEmpty()) {
                                newsPane.setText(buildReleaseHtml(ctx.latest, null));
                                newsPane.setCaretPosition(0);
                            }
                            playButton.setText("Play");
                            playButton.setEnabled(true);
                        }
                    });
                } catch (final Throwable ex) {
                    ex.printStackTrace();
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            playButton.setText("Play");
                            playButton.setEnabled(true);
                            showError(ex);
                        }
                    });
                }
            }
        }, "ModUpdater-BranchRefresh");
        t.setDaemon(true);
        t.start();
    }

    private static boolean showPrompt(Path bgPath) {
        final JFrame frame = new JFrame("Minecraft: Oldschool Edition");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setResizable(true);
        frame.setMinimumSize(new Dimension(520, 300));
        List<Image> icons = loadAppIcons();
        if (!icons.isEmpty()) {
            frame.setIconImages(icons);
            Image best = pickLargestIcon(icons);
            if (best != null) frame.setIconImage(best);
        }

        JPanel panel = new BackgroundPanel(bgPath);
        panel.setLayout(new BorderLayout());
        panel.setBorder(new EmptyBorder(24, 24, 50, 24));

        PixelLabel title = new PixelLabel("New update available", 18f, true);
        title.setForeground(new Color(202, 202, 202)); // #CACACA
        PixelLabel subtitle = new PixelLabel("Would you like to update?", 12f, false);
        subtitle.setForeground(new Color(202, 202, 202)); // #CACACA

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 0));
        JButton yes = new PixelButton("Yes");
        JButton no = new PixelButton("Not now");
        buttons.add(yes);
        buttons.add(no);
        buttons.setOpaque(false);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setOpaque(false);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        Component spacer1 = Box.createVerticalStrut(16);
        // Minimal initial gap; dynamic resizer will set scale-aware value
        Component spacer2 = Box.createVerticalStrut(2);
        center.add(title);
        center.add(spacer1);
        center.add(subtitle);
        center.add(spacer2);
        center.add(buttons);

        // Keep the content block toward the top: only bottom glue
        center.setMaximumSize(new Dimension(Integer.MAX_VALUE, center.getPreferredSize().height));
        // Allow the button row to grow horizontally so both buttons always fit
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, buttons.getPreferredSize().height));
        Box v = Box.createVerticalBox();
        v.add(center);
        v.add(Box.createVerticalGlue());
        panel.add(v, BorderLayout.CENTER);
        frame.setContentPane(panel);
        frame.pack();
        // Start at classic 854x480; buttons will enforce 2x height at k==1
        frame.setSize(854, 480);
        frame.setLocationRelativeTo(null);

        // Dynamic scaling for fonts and buttons (integer scaling like OG)
        java.awt.event.ComponentAdapter resizer = new java.awt.event.ComponentAdapter() {
            private void apply() {
                // Default 1x at 854x480; scale in integer steps like the OG launcher
                int w = frame.getWidth();
                int h = frame.getHeight();
                double layout = Math.min(w / 854.0, h / 480.0);
                // Use ceiling so scaling bumps as soon as either axis exceeds base
                int k = (int) Math.max(1, Math.ceil(layout - 1e-6));
                title.setPixelScale(k);
                subtitle.setPixelScale(k);
                // Provide OG-style button sizing; default compact at 50x20, larger baseline on high-res
                int baseW, baseH;
                if (k >= 3) { // high-res (>=1080p equivalent): wider and a bit taller baseline
                    baseW = 96; baseH = 24;
                } else {      // default look at base resolutions
                    baseW = 50; baseH = 18;
                }
                int buttonK = (k < 2 ? 2 : k); // ensure 40px tall at 854x480
                yes.putClientProperty("pixelScale", Integer.valueOf(buttonK));
                no.putClientProperty("pixelScale", Integer.valueOf(buttonK));
                yes.putClientProperty("baseW", Integer.valueOf(baseW));
                yes.putClientProperty("baseH", Integer.valueOf(baseH));
                no.putClientProperty("baseW", Integer.valueOf(baseW));
                no.putClientProperty("baseH", Integer.valueOf(baseH));
                // Regular, consistent font sizing
                yes.setFont((UI_BASE_FONT != null ? UI_BASE_FONT : yes.getFont()).deriveFont(Font.PLAIN, 11.15f));
                no.setFont((UI_BASE_FONT != null ? UI_BASE_FONT : no.getFont()).deriveFont(Font.PLAIN, 11.15f));
                yes.revalidate();
                no.revalidate();
                int sidePad = 48 * k;
                // Position content in upper area like the original launcher
                int topPad = (int) Math.round(frame.getHeight() * 0.15);
                panel.setBorder(new EmptyBorder(topPad, sidePad, sidePad, sidePad));
                // Space between title and subtitle (faithful to original)
                Dimension sp1 = new Dimension(1, 12 * k);
                spacer1.setPreferredSize(sp1); spacer1.setMinimumSize(sp1); spacer1.setMaximumSize(new Dimension(Integer.MAX_VALUE, sp1.height));
                // Space between subtitle and buttons
                Dimension sp2 = new Dimension(1, 8 * k);
                spacer2.setPreferredSize(sp2); spacer2.setMinimumSize(sp2); spacer2.setMaximumSize(new Dimension(Integer.MAX_VALUE, sp2.height));
                // tighten gap between buttons and keep row close to subtitle
                if (buttons.getLayout() instanceof FlowLayout) {
                    // Slightly widen gap between buttons
                    ((FlowLayout) buttons.getLayout()).setHgap(30 * k);
                    ((FlowLayout) buttons.getLayout()).setVgap(2 * k);
                }
                // Refresh max size after resizing so both buttons remain visible
                buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, buttons.getPreferredSize().height));
                buttons.revalidate();
                panel.revalidate();
                panel.repaint();
            }
            public void componentShown(java.awt.event.ComponentEvent e) { apply(); }
            public void componentResized(java.awt.event.ComponentEvent e) { apply(); }
        };
        frame.addComponentListener(resizer);

        final boolean[] result = new boolean[] { false };
        final CountDownLatch latch = new CountDownLatch(1);
        yes.addActionListener(new AbstractAction() {
            public void actionPerformed(ActionEvent e) { result[0] = true; frame.dispose(); latch.countDown(); }
        });
        no.addActionListener(new AbstractAction() {
            public void actionPerformed(ActionEvent e) { result[0] = false; frame.dispose(); latch.countDown(); }
        });
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosed(java.awt.event.WindowEvent e) { latch.countDown(); }
        });

        frame.setVisible(true);
        try { latch.await(); } catch (InterruptedException ignored) {}
        return result[0];
    }

    private static void runUpdate(ProgressUI ui, Path minecraftDir, Path instanceRoot, String mode, String jarRegex,
                                  ReleaseAsset jarAsset, ReleaseAsset assetsZip, LatestRelease latest, String jarmodName) throws Exception {
        if ("mods".equalsIgnoreCase(mode)) {
            Path modsDir = minecraftDir.resolve("mods");
            ensureDir(modsDir);

            Path existing = findExistingMatching(modsDir, jarRegex);
            if (existing != null) {
                Path backup = withUniqueSuffix(existing, ".bak");
                ui.setPhaseText("Backing up existing mod...");
                Files.move(existing, backup, StandardCopyOption.REPLACE_EXISTING);
            }

            ui.setPhaseText("Downloading mod...");
            Path downloaded = downloadToTemp(ui, jarAsset.url, jarAsset.name, 0.0, 0.7);
            ui.setPhaseText("Extracting assets...");
            extractAssetsFromJarToResources(ui, downloaded, minecraftDir, 0.7, 0.9);

            Path dest = modsDir.resolve(jarAsset.name);
            ui.setPhaseText("Installing mod jar...");
            moveOrCopy(downloaded, dest);
            writeMarker(dest, latest, jarAsset);
        } else if ("jarmods".equalsIgnoreCase(mode)) {
            if (instanceRoot == null) throw new IllegalArgumentException("jarmods mode requires instance root; pass --instanceDir or configure instanceDir.");
            Path jarmodsDir = instanceRoot.resolve("jarmods");
            ensureDir(jarmodsDir);

            // Download, then extract assets and install as fixed name (jarmodName)
            ui.setPhaseText("Downloading jarmod...");
            Path downloaded = downloadToTemp(ui, jarAsset.url, jarAsset.name, 0.0, 0.7);
            ui.setPhaseText("Extracting assets...");
            extractAssetsFromJarToResources(ui, downloaded, minecraftDir, 0.7, 0.9);
            Path dest = pickJarmodTarget(jarmodsDir, jarmodName);
            if (Files.isRegularFile(dest)) {
                Path backup = withUniqueSuffix(dest, ".bak");
                ui.setPhaseText("Backing up existing jarmod...");
                Files.move(dest, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            ui.setPhaseText("Installing update...");
            moveOrCopy(downloaded, dest);
            writeMarker(dest, latest, jarAsset);
        } else if ("clientJar".equalsIgnoreCase(mode)) {
            Path clientJar = resolveClientJarPath(minecraftDir, null);
            if (clientJar == null) throw new IllegalArgumentException("Cannot resolve client jar at 'bin/minecraft.jar'.");
            ui.setPhaseText("Downloading client jar...");
            Path downloaded = downloadToTemp(ui, jarAsset.url, jarAsset.name, 0.0, 0.7);
            ui.setPhaseText("Extracting assets...");
            extractAssetsFromJarToResources(ui, downloaded, minecraftDir, 0.7, 0.9);
            Path backup = withUniqueSuffix(clientJar, ".bak");
            ui.setPhaseText("Backing up old jar...");
            Files.copy(clientJar, backup, StandardCopyOption.REPLACE_EXISTING);
            ui.setPhaseText("Replacing client jar...");
            moveOrCopy(downloaded, clientJar);
            writeMarker(clientJar, latest, jarAsset);
        } else {
            throw new IllegalArgumentException("Unsupported mode: " + mode);
        }
        // Assets now extracted from the mod jar itself.
        
        // Note: Bouncy Castle dependency for friends system crypto is optional.
        // The friends system works without it (just without cryptographic verification).
        // Users who want crypto can manually add bcprov-jdk18on-1.78.1.jar as a jarmod.
    }
    
    /**
     * Syncs the resource pack from a GitHub repository before launching the game.
     * Downloads the repository's main branch as a zipball and extracts the assets/
     * folder to the Minecraft resources directory.
     *
     * @param repo        GitHub repository in "owner/repo" format
     * @param minecraftDir Path to the .minecraft directory
     */
    private static void syncResourcePack(String repo, Path minecraftDir) {
        if (repo == null || repo.isEmpty() || minecraftDir == null) {
            return;
        }
        
        System.out.println("[mod-updater] Syncing resource pack from: " + repo);
        
        try {
            // Download the repository as a zipball from the main branch
            String zipUrl = "https://github.com/" + repo + "/archive/refs/heads/main.zip";
            String tmpDir = System.getProperty("java.io.tmpdir");
            Path zipPath = Paths.get(tmpDir, "resourcepack-" + System.currentTimeMillis() + ".zip");
            
            URL url = new URL(zipUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "ModUpdaterGUI/1.0");
            conn.setInstanceFollowRedirects(true);
            
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                System.err.println("[mod-updater] Failed to download resource pack: HTTP " + code);
                return;
            }
            
            // Download the zip file
            InputStream in = new BufferedInputStream(conn.getInputStream());
            FileOutputStream out = new FileOutputStream(zipPath.toFile());
            byte[] buf = new byte[64 * 1024];
            int n;
            try {
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } finally {
                try { in.close(); } catch (IOException ignored) {}
                try { out.close(); } catch (IOException ignored) {}
            }
            
            // Extract assets/ folder to resources/
            Path resourcesDir = minecraftDir.resolve("resources");
            ensureDir(resourcesDir);
            
            ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath.toFile()));
            ZipEntry entry;
            int extractedCount = 0;
            try {
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName().replace('\\', '/');
                    
                    // Skip the root folder (e.g., "resourcepack-main/")
                    int slashIdx = name.indexOf('/');
                    if (slashIdx < 0) continue;
                    String relativePath = name.substring(slashIdx + 1);
                    
                    // Only extract files under assets/
                    if (!relativePath.startsWith("assets/")) continue;
                    if (entry.isDirectory()) continue;
                    
                    // Target path: resources/assets/...
                    Path dest = resourcesDir.resolve(relativePath);
                    ensureDir(dest.getParent());
                    
                    FileOutputStream fos = new FileOutputStream(dest.toFile());
                    try {
                        while ((n = zis.read(buf)) != -1) {
                            fos.write(buf, 0, n);
                        }
                        extractedCount++;
                    } finally {
                        try { fos.close(); } catch (IOException ignored) {}
                    }
                    
                    zis.closeEntry();
                }
            } finally {
                try { zis.close(); } catch (IOException ignored) {}
            }
            
            // Clean up the downloaded zip
            Files.deleteIfExists(zipPath);
            
            System.out.println("[mod-updater] Resource pack synced: " + extractedCount + " files extracted to " + resourcesDir);
            
        } catch (Exception e) {
            // Don't fail the launch if resource pack sync fails
            System.err.println("[mod-updater] Warning: Failed to sync resource pack: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Ensures Bouncy Castle library is installed for cryptographic operations.
     * Downloads from Maven Central if not present. Uses libraries folder for classpath inclusion.
     */
    private static void ensureBouncyCastleDependency(ProgressUI ui, Path instanceRoot) {
        try {
            // Use libraries folder instead of jarmods - this adds to classpath without merging
            Path librariesDir = instanceRoot.resolve("libraries");
            ensureDir(librariesDir);
            
            // Check if BC jar already exists in libraries
            Path bcJar = librariesDir.resolve(BC_JAR_NAME);
            if (Files.isRegularFile(bcJar)) {
                ui.log("Bouncy Castle already installed: " + bcJar.getFileName());
                return;
            }
            
            // Also check jarmods folder (user might have added manually)
            Path jarmodsDir = instanceRoot.resolve("jarmods");
            if (Files.isDirectory(jarmodsDir)) {
                File[] files = jarmodsDir.toFile().listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && f.getName().contains("bcprov") && f.getName().endsWith(".jar")) {
                            ui.log("Bouncy Castle found in jarmods: " + f.getName());
                            return;
                        }
                    }
                }
            }
            
            ui.setPhaseText("Installing crypto library...");
            ui.log("Downloading Bouncy Castle from Maven Central...");
            
            // Download from Maven
            Path downloaded = downloadFromUrl(ui, BC_MAVEN_URL, BC_JAR_NAME);
            if (downloaded == null || !Files.isRegularFile(downloaded)) {
                ui.log("Warning: Failed to download Bouncy Castle. Some features may be unavailable.");
                return;
            }
            
            // Move to libraries folder
            Files.move(downloaded, bcJar, StandardCopyOption.REPLACE_EXISTING);
            ui.log("Installed: " + bcJar.getFileName());
            ui.log("Note: You may need to add this library to your instance manually via Prism Launcher.");
            
        } catch (Exception e) {
            // Don't fail the whole update if BC install fails
            System.err.println("Warning: Could not install Bouncy Castle dependency: " + e.getMessage());
            if (ui != null) {
                ui.log("Warning: Crypto library install failed. Some features may be unavailable.");
            }
        }
    }
    
    /**
     * Download a file from a URL to a temp location.
     */
    private static Path downloadFromUrl(ProgressUI ui, String urlStr, String filename) {
        try {
            String tmpDir = System.getProperty("java.io.tmpdir");
            Path tmp = Paths.get(tmpDir, filename);
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "ModUpdater/1.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                System.err.println("Download failed: HTTP " + code + " for " + urlStr);
                return null;
            }
            
            long total = conn.getContentLengthLong();
            InputStream in = new BufferedInputStream(conn.getInputStream());
            FileOutputStream out = new FileOutputStream(tmp.toFile());
            byte[] buf = new byte[64 * 1024];
            int n;
            long downloaded = 0;
            try {
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    downloaded += n;
                }
            } finally {
                try { in.close(); } catch (IOException ignored) {}
                try { out.close(); } catch (IOException ignored) {}
            }
            return tmp;
        } catch (Exception e) {
            System.err.println("Download error: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Creates a jarmod patch file for Prism/MultiMC to recognize the jarmod.
     * This creates a file in patches/ that tells the launcher about the jarmod.
     */
    private static void createJarmodPatch(Path instanceRoot, String jarFileName, String displayName) {
        try {
            // Generate a UUID for the jarmod
            String uuid = java.util.UUID.randomUUID().toString();
            
            // First, rename the jar to use the UUID (this is how Prism expects jarmods)
            Path jarmodsDir = instanceRoot.resolve("jarmods");
            Path originalJar = jarmodsDir.resolve(jarFileName);
            Path uuidJar = jarmodsDir.resolve(uuid + ".jar");
            
            if (Files.isRegularFile(originalJar)) {
                Files.move(originalJar, uuidJar, StandardCopyOption.REPLACE_EXISTING);
            }
            
            // Create the patches directory if it doesn't exist
            Path patchesDir = instanceRoot.resolve("patches");
            if (!Files.isDirectory(patchesDir)) {
                Files.createDirectories(patchesDir);
            }
            
            // Create the patch file
            String patchUid = "custom.jarmod." + uuid;
            Path patchFile = patchesDir.resolve(patchUid + ".json");
            
            // Check if patch already exists
            if (Files.isRegularFile(patchFile)) {
                System.out.println("Patch file already exists: " + patchFile.getFileName());
                return;
            }
            
            // Build the patch JSON
            String patchJson = "{\n" +
                "    \"formatVersion\": 1,\n" +
                "    \"name\": \"" + displayName + " (jar mod)\",\n" +
                "    \"uid\": \"" + patchUid + "\",\n" +
                "    \"version\": \"1\",\n" +
                "    \"jarMods\": [\n" +
                "        {\n" +
                "            \"name\": \"" + jarFileName + "\",\n" +
                "            \"originalFileName\": \"" + jarFileName + "\"\n" +
                "        }\n" +
                "    ]\n" +
                "}";
            
            Files.write(patchFile, patchJson.getBytes(StandardCharsets.UTF_8));
            System.out.println("Created jarmod patch: " + patchFile.getFileName());
            
        } catch (Exception e) {
            System.err.println("Warning: Could not create jarmod patch: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Silent version of BC check - runs without UI progress, for use when skipping updates.
     */
    private static void ensureBouncyCastleDependencySilent(final Path instanceRoot) {
        if (instanceRoot == null) return;
        
        // Run in background thread to not block UI
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Path librariesDir = instanceRoot.resolve("libraries");
                    if (!Files.isDirectory(librariesDir)) {
                        Files.createDirectories(librariesDir);
                    }
                    
                    // Check if BC already exists in libraries
                    Path bcJar = librariesDir.resolve(BC_JAR_NAME);
                    if (Files.isRegularFile(bcJar)) {
                        return; // Already installed
                    }
                    
                    // Check jarmods folder too
                    Path jarmodsDir = instanceRoot.resolve("jarmods");
                    if (Files.isDirectory(jarmodsDir)) {
                        File[] files = jarmodsDir.toFile().listFiles();
                        if (files != null) {
                            for (File f : files) {
                                if (f.isFile() && f.getName().contains("bcprov") && f.getName().endsWith(".jar")) {
                                    return; // Already have a version
                                }
                            }
                        }
                    }
                    
                    System.out.println("[ModUpdater] Installing Bouncy Castle crypto library...");
                    
                    // Download from Maven
                    Path downloaded = downloadFromUrl(null, BC_MAVEN_URL, BC_JAR_NAME);
                    if (downloaded == null || !Files.isRegularFile(downloaded)) {
                        System.err.println("[ModUpdater] Failed to download Bouncy Castle.");
                        return;
                    }
                    
                    // Move to libraries folder
                    Files.move(downloaded, bcJar, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[ModUpdater] Installed: " + bcJar.getFileName());
                    System.out.println("[ModUpdater] Note: You may need to add this library to your instance manually.");
                    
                } catch (Exception e) {
                    System.err.println("[ModUpdater] Warning: Could not install Bouncy Castle: " + e.getMessage());
                }
            }
        }, "BC-Installer");
        t.setDaemon(true);
        t.start();
        
        // Wait briefly for it to complete (but don't block too long)
        try {
            t.join(5000); // Wait up to 5 seconds
        } catch (InterruptedException ignored) {}
    }

    private static void extractAssetsSubtree(ProgressUI ui, Path zipPath, Path minecraftDir) throws IOException {
        Path assetsTarget1 = minecraftDir.resolve("resources").resolve("assets");
        Path assetsTarget2 = minecraftDir.resolve("assets");
        ensureDir(assetsTarget1);
        ensureDir(assetsTarget2);

        ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipPath));
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');

                // Try to normalize to extract only resources/assets/* subtree
                int idx = name.indexOf("resources/assets/");
                Path dest;
                if (idx >= 0) {
                    String rel = name.substring(idx + "resources/assets/".length());
                    dest = assetsTarget1.resolve(rel);
                } else if (name.startsWith("assets/") || name.contains("/assets/")) {
                    String rel = name.substring(name.indexOf("assets/" ) + "assets/".length());
                    // If the instance uses separate resources/assets, place under resources/assets
                    dest = assetsTarget1.resolve(rel);
                } else if (name.startsWith("resources/") || name.contains("/resources/")) {
                    // Some releases may package directly under resources/*
                    String rel = name.substring(name.indexOf("resources/") + "resources/".length());
                    dest = minecraftDir.resolve("resources").resolve(rel);
                } else {
                    continue; // skip unrelated files
                }

                ensureDir(dest.getParent());
                Files.copy(zin, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try { zin.close(); } catch (IOException ignored) {}
        }
        ui.log("Assets extracted.");
    }

    private static void extractAssetsFromJarToResources(ProgressUI ui, Path jarPath, Path minecraftDir, double start, double end) throws IOException {
        Path resourcesDir = minecraftDir.resolve("resources");
        Path assetsDir = resourcesDir.resolve("assets");
        ensureDir(assetsDir);

        ZipFile zipFile = new ZipFile(jarPath.toFile());
        try {
            int total = zipFile.size();
            int processed = 0;
            Enumeration<? extends ZipEntry> it = zipFile.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                processed++;
                String name = e.getName().replace('\\', '/');
                if (e.isDirectory()) continue;
                Path dest = null;
                if (name.startsWith("assets/")) {
                    String rel = name.substring("assets/".length());
                    dest = assetsDir.resolve(rel);
                } else if (name.startsWith("resources/assets/")) {
                    String rel = name.substring("resources/assets/".length());
                    dest = assetsDir.resolve(rel);
                } else if (name.startsWith("resources/")) {
                    String rel = name.substring("resources/".length());
                    dest = resourcesDir.resolve(rel);
                }
                if (dest != null) {
                    ensureDir(dest.getParent());
                    try (InputStream in = zipFile.getInputStream(e)) {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                double frac = start + (end - start) * (processed / (double) Math.max(1, total));
                ui.progress((int) Math.round(frac * 100));
            }
        } finally {
            try { zipFile.close(); } catch (IOException ignored) {}
        }
    }

    private static boolean isUpToDate(Path minecraftDir, Path instanceRoot, String mode, String jarRegex, String latestAssetName, String latestTag, String jarmodName) throws IOException {
        if ("mods".equalsIgnoreCase(mode)) {
            Path modsDir = minecraftDir.resolve("mods");
            Path existing = findExistingMatching(modsDir, jarRegex);
            if (existing == null) return false;
            InstalledMarker m = readMarker(existing);
            return m != null && equalsSafe(m.tag, latestTag);
        } else if ("clientJar".equalsIgnoreCase(mode)) {
            Path client = resolveClientJarPath(minecraftDir, null);
            if (client == null) return false;
            InstalledMarker m = readMarker(client);
            return m != null && equalsSafe(m.tag, latestTag);
        } else if ("jarmods".equalsIgnoreCase(mode)) {
            if (instanceRoot == null) return false;
            Path jarmodsDir = instanceRoot.resolve("jarmods");
            if (!Files.isDirectory(jarmodsDir)) return false;
            Path target = pickJarmodTarget(jarmodsDir, jarmodName);
            if (target == null || !Files.isRegularFile(target)) return false;
            InstalledMarker m = readMarker(target);
            return m != null && equalsSafe(m.tag, latestTag);
        }
        return false;
    }

    private static LatestRelease fetchLatestRelease(String repo) throws IOException {
        // Try /releases/latest first
        String url = String.format(GITHUB_API_LATEST, repo);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(HTTP_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "ModUpdaterGUI/1.0");
        String token = getenv("GITHUB_TOKEN");
        if (token != null && !token.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "token " + token.trim());
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(in);
        
        // If /releases/latest returns 404, fall back to /releases and pick the first one
        if (code == 404) {
            String fallbackUrl = "https://api.github.com/repos/" + repo + "/releases";
            HttpURLConnection fallbackConn = (HttpURLConnection) new URL(fallbackUrl).openConnection();
            fallbackConn.setConnectTimeout(HTTP_TIMEOUT_MS);
            fallbackConn.setReadTimeout(HTTP_TIMEOUT_MS);
            fallbackConn.setRequestMethod("GET");
            fallbackConn.setRequestProperty("Accept", "application/vnd.github+json");
            fallbackConn.setRequestProperty("User-Agent", "ModUpdaterGUI/1.0");
            if (token != null && !token.trim().isEmpty()) {
                fallbackConn.setRequestProperty("Authorization", "token " + token.trim());
            }
            int fallbackCode = fallbackConn.getResponseCode();
            InputStream fallbackIn = fallbackCode >= 200 && fallbackCode < 300 ? fallbackConn.getInputStream() : fallbackConn.getErrorStream();
            String fallbackBody = readAll(fallbackIn);
            if (fallbackCode < 200 || fallbackCode >= 300) {
                throw new IOException("GitHub API error: HTTP " + fallbackCode + "\n" + fallbackBody);
            }
            // Parse first release from array
            return parseFirstReleaseFromArray(fallbackBody);
        }
        
        if (code < 200 || code >= 300) {
            throw new IOException("GitHub API error: HTTP " + code + "\n" + body);
        }
        return parseLatestRelease(body);
    }
    
    private static LatestRelease parseFirstReleaseFromArray(String json) {
        // The /releases endpoint returns an array; extract the first object
        // Find first { after the opening [
        int start = json.indexOf('[');
        if (start < 0) return parseLatestRelease(json); // Not an array, try as single object
        int braceStart = json.indexOf('{', start);
        if (braceStart < 0) return new LatestRelease(); // Empty array
        // Find matching closing brace
        int depth = 0;
        int braceEnd = -1;
        for (int i = braceStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    braceEnd = i;
                    break;
                }
            }
        }
        if (braceEnd < 0) return new LatestRelease();
        String firstRelease = json.substring(braceStart, braceEnd + 1);
        return parseLatestRelease(firstRelease);
    }

    private static LatestRelease parseLatestRelease(String json) {
        LatestRelease r = new LatestRelease();
        r.tag = extractString(json, "\"tag_name\"\\s*:\\s*\"(.*?)\"");
        r.name = extractString(json, "\"name\"\\s*:\\s*\"(.*?)\"");
        r.body = extractString(json, "\"body\"\\s*:\\s*\"(.*?)\"");
        r.htmlUrl = extractString(json, "\"html_url\"\\s*:\\s*\"(.*?)\"");
        r.zipballUrl = extractString(json, "\"zipball_url\"\\s*:\\s*\"(.*?)\"");
        r.assets = extractAssets(json);
        return r;
    }

    private static String extractString(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        if (m.find()) return unescapeJson(m.group(1));
        return null;
    }

    private static List<ReleaseAsset> extractAssets(String json) {
        // Robust scan based solely on browser_download_url; derive the name from the URL tail
        List<ReleaseAsset> list = new ArrayList<ReleaseAsset>();
        Pattern p = Pattern.compile("\\\"browser_download_url\\\"\\s*:\\s*\\\"(.*?)\\\"", Pattern.DOTALL);
        Matcher m = p.matcher(json);
        while (m.find()) {
            String url = unescapeJson(m.group(1));
            String name = url;
            int slash = url.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < url.length()) name = url.substring(slash + 1);
            ReleaseAsset a = new ReleaseAsset();
            a.name = name;
            a.url = url;
            list.add(a);
        }
        return list;
    }

    // Simple HTML builder for the embedded patch-notes view, using the GitHub
    // latest-release metadata as a fallback when no explicit newsUrl is given.
    private static String buildReleaseHtml(LatestRelease latest, Exception loadError) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<html><head><style>");
        sb.append("body { background-color:#101010; color:#e0e0e0; font-family:sans-serif; font-size:12px; }");
        sb.append("h1 { font-size:16px; margin:0 0 8px 0; }");
        sb.append("a { color:#68a0ff; }");
        sb.append("pre { white-space:pre-wrap; }");
        sb.append("</style></head><body>");

        String title = null;
        if (latest != null) {
            if (latest.name != null && !latest.name.isEmpty()) title = latest.name;
            else if (latest.tag != null && !latest.tag.isEmpty()) title = "Release " + latest.tag;
        }
        if (title == null) title = "Latest release";
        sb.append("<h1>").append(htmlEscape(title)).append("</h1>");

        if (latest != null && latest.tag != null) {
            sb.append("<div style='color:#a0a0a0;margin-bottom:8px;'>Tag: ")
              .append(htmlEscape(latest.tag));
            if (latest.htmlUrl != null && !latest.htmlUrl.isEmpty()) {
                sb.append(" &middot; <a href=\"")
                  .append(htmlEscape(latest.htmlUrl))
                  .append("\">View on GitHub</a>");
            }
            sb.append("</div>");
        }

        String body = latest != null ? latest.body : null;
        if (body != null && body.trim().length() > 0) {
            String norm = body.replace("\r\n", "\n").replace("\r", "\n");
            String[] lines = norm.split("\n");
            sb.append("<pre>");
            for (int i = 0; i < lines.length; i++) {
                sb.append(htmlEscape(lines[i]));
                if (i + 1 < lines.length) sb.append("\n");
            }
            sb.append("</pre>");
        } else {
            sb.append("<p>No detailed patch notes were provided for this release.</p>");
        }

        if (loadError != null) {
            String msg = loadError.getMessage() != null ? loadError.getMessage() : loadError.toString();
            sb.append("<hr><p style='color:#ff8080;'>Failed to load external news page: ")
              .append(htmlEscape(msg))
              .append("</p>");
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    private static String htmlEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private static int findMatchingBracket(String s, int openIdx) {
        return findMatchingDelimiter(s, openIdx, '[', ']');
    }

    private static int findMatchingBrace(String s, int openIdx) {
        return findMatchingDelimiter(s, openIdx, '{', '}');
    }

    private static int findMatchingDelimiter(String s, int openIdx, char open, char close) {
        int depth = 0;
        boolean inString = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && !isEscaped(s, i)) {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static boolean isEscaped(String s, int idx) {
        int backslashes = 0;
        for (int i = idx - 1; i >= 0 && s.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return (backslashes % 2) == 1;
    }

    private static String unescapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '"' || n == '\\' || n == '/') { out.append(n); i++; }
                else if (n == 'b') { out.append('\b'); i++; }
                else if (n == 'f') { out.append('\f'); i++; }
                else if (n == 'n') { out.append('\n'); i++; }
                else if (n == 'r') { out.append('\r'); i++; }
                else if (n == 't') { out.append('\t'); i++; }
                else if (n == 'u' && i + 5 < s.length()) {
                    String hex = s.substring(i + 2, i + 6);
                    try { out.append((char) Integer.parseInt(hex, 16)); } catch (NumberFormatException ignored) { out.append('?'); }
                    i += 5;
                } else { out.append(n); i++; }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static ReleaseAsset selectAsset(List<ReleaseAsset> assets, String assetRegex) {
        Pattern p = Pattern.compile(assetRegex);
        for (ReleaseAsset a : assets) {
            if (p.matcher(a.name).find()) return a;
        }
        return null;
    }

    private static Path downloadToTemp(ProgressUI ui, String url, String suggestedName, double start, double end) throws IOException {
        String tmpDir = System.getProperty("java.io.tmpdir");
        Path tmp = Paths.get(tmpDir, suggestedName);
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(HTTP_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "ModUpdaterGUI/1.0");
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            InputStream err = conn.getErrorStream();
            String body = err != null ? readAll(err) : "";
            throw new IOException("Download failed: HTTP " + code + "\n" + body);
        }
        InputStream in = new BufferedInputStream(conn.getInputStream());
        FileOutputStream out = new FileOutputStream(tmp.toFile());
        byte[] buf = new byte[64 * 1024];
        int n;
        long total = 0;
        long len = -1L;
        try { len = conn.getContentLengthLong(); } catch (Throwable ignored) {}
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (len > 0L) {
                    double frac = start + (end - start) * (total / (double) len);
                    ui.progress((int) Math.round(frac * 100));
                }
            }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            try { out.close(); } catch (IOException ignored) {}
        }
        return tmp;
    }

    private static String readAll(InputStream in) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = br.read(buf)) != -1) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static Path findExistingMatching(Path dir, String assetRegex) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return null;
        final Pattern p = Pattern.compile(assetRegex);
        Path newest = null;
        long newestTs = Long.MIN_VALUE;
        try {
            File[] files = dir.toFile().listFiles();
            if (files == null) return null;
            for (File f : files) {
                if (f.isFile()) {
                    Matcher m = p.matcher(f.getName());
                    if (m.find()) {
                        long ts = f.lastModified();
                        if (ts > newestTs) {
                            newestTs = ts;
                            newest = f.toPath();
                        }
                    }
                }
            }
            return newest;
        } catch (SecurityException se) {
            return null;
        }
    }

    private static Path withUniqueSuffix(Path path, String suffix) {
        String name = path.getFileName().toString();
        String stamp = String.valueOf(System.currentTimeMillis());
        Path parent = path.getParent();
        return parent.resolve(name + suffix + "." + stamp);
    }

    private static void ensureDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) Files.createDirectories(dir);
    }

    private static void moveOrCopy(Path src, Path dest) throws IOException {
        ensureDir(dest.getParent());
        try {
            Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            // Cross-volume or atomic move not supported: fall back to copy+delete
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            try { Files.deleteIfExists(src); } catch (IOException ignored) {}
        }
    }

    private static Path pickJarmodTarget(Path jarmodsDir, String preferredName) throws IOException {
        Path preferred = jarmodsDir.resolve(preferredName);
        if (Files.isRegularFile(preferred)) return preferred;

        // Known dependency jars that should be excluded from mod updates
        // (e.g., jna_5.13.0.jar for controller support)
        Set<String> dependencyJars = new HashSet<String>();
        dependencyJars.add("jna_5.13.0.jar");
        dependencyJars.add("jna-5.13.0.jar");
        dependencyJars.add("jna.jar");

        File[] files = jarmodsDir.toFile().listFiles();
        if (files != null && files.length > 0) {
            // 1) Prefer UUID-named jars (what Prism creates when you add a Jar Mod)
            //    but exclude known dependency jars
            Pattern uuidJar = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.jar");
            Path bestUuid = null;
            long bestUuidTs = Long.MIN_VALUE;
            Path bestAny = null;
            long bestAnyTs = Long.MIN_VALUE;
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                // Skip known dependency jars
                if (dependencyJars.contains(name.toLowerCase(Locale.ROOT))) continue;
                long ts = f.lastModified();
                if (uuidJar.matcher(name).matches()) {
                    if (ts > bestUuidTs) { bestUuidTs = ts; bestUuid = f.toPath(); }
                }
                if (ts > bestAnyTs) { bestAnyTs = ts; bestAny = f.toPath(); }
            }
            if (bestUuid != null) return bestUuid;
            if (bestAny != null) return bestAny;
        }
        // Nothing present yet: fall back to mod.jar (first run; user must add once in Versions)
        return preferred;
    }

    private static String derivePatchJarmodName(Path instanceRoot, String fallback) {
        try {
            Path mmcPack = instanceRoot.resolve("mmc-pack.json");
            if (!Files.isRegularFile(mmcPack)) {
                return fallback;
            }
            String json = new String(Files.readAllBytes(mmcPack), StandardCharsets.UTF_8);
            Matcher marker = Pattern.compile("\"cachedName\"\\s*:\\s*\"patch \\(jar mod\\)\"", Pattern.CASE_INSENSITIVE).matcher(json);
            while (marker.find()) {
                int objStart = json.lastIndexOf('{', marker.start());
                if (objStart < 0) continue;
                int objEnd = findMatchingBrace(json, objStart);
                if (objEnd < 0) continue;
                String block = json.substring(objStart, objEnd + 1);
                Matcher uidMatcher = Pattern.compile("\"uid\"\\s*:\\s*\"([^\"]+)\"").matcher(block);
                if (uidMatcher.find()) {
                    String uid = uidMatcher.group(1).trim();
                    String prefix = "custom.jarmod.";
                    if (uid.startsWith(prefix)) {
                        return uid.substring(prefix.length()) + ".jar";
                    }
                }
            }
        } catch (IOException ex) {
            System.err.println("[mod-updater] Failed to read mmc-pack.json: " + ex.getMessage());
        }
        return fallback;
    }

    private static Path resolveMinecraftDir(String minecraftDirArg, String instanceDirArg, String mcDirEnv) {
        try {
            if (minecraftDirArg != null) {
                Path p = Paths.get(minecraftDirArg);
                if (Files.isDirectory(p)) return p.toAbsolutePath();
            }
            if (mcDirEnv != null && !mcDirEnv.trim().isEmpty()) {
                Path p = Paths.get(mcDirEnv);
                if (Files.isDirectory(p)) return p.toAbsolutePath();
            }
            if (instanceDirArg != null) {
                Path base = Paths.get(instanceDirArg);
                Path candidate1 = base.resolve(".minecraft");
                Path candidate2 = base.resolve("minecraft");
                if (Files.isDirectory(candidate1)) return candidate1.toAbsolutePath();
                if (Files.isDirectory(candidate2)) return candidate2.toAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Path resolveClientJarPath(Path minecraftDir, String clientJarArg) {
        try {
            if (clientJarArg != null) {
                Path p = Paths.get(clientJarArg);
                if (Files.isRegularFile(p)) return p.toAbsolutePath();
            }
            if (minecraftDir != null) {
                Path p = minecraftDir.resolve("bin").resolve("minecraft.jar");
                if (Files.isRegularFile(p)) return p.toAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Path resolveInstanceRoot(Path minecraftDir, String instanceDirArg) {
        try {
            if (instanceDirArg != null) {
                Path p = Paths.get(instanceDirArg);
                if (Files.isDirectory(p)) return p.toAbsolutePath();
            }
            if (minecraftDir != null) {
                Path parent = minecraftDir.getParent();
                if (parent != null && Files.isDirectory(parent)) return parent.toAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String getenv(String key) {
        try { return System.getenv(key); } catch (SecurityException ignored) { return null; }
    }

    private static void showError(Throwable t) {
        String msg = t.getMessage() != null ? t.getMessage() : t.toString();
        JTextArea area = new JTextArea(msg);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(new EmptyBorder(8, 8, 8, 8));
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(560, 220));
        JOptionPane.showMessageDialog(null, sp, "Updater Error", JOptionPane.ERROR_MESSAGE);
    }

    // UI Components
    private static final class BackgroundPanel extends JPanel {
        private final BufferedImage bg;
        BackgroundPanel(Path bgPath) {
            setOpaque(true);
            setBackground(new Color(60, 43, 29));
            BufferedImage tmp = null;
            if (bgPath != null && Files.isRegularFile(bgPath)) {
                try { tmp = ImageIO.read(bgPath.toFile()); } catch (Exception ignored) {}
            }
            this.bg = tmp;
        }
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            drawBackground(g, getWidth(), getHeight(), bg);
        }
    }

    // Abstraction for the progress UI so it can be shown either in its own
    // window or embedded as a second page inside the launcher.
    private interface ProgressUI {
        void progress(int pct);
        void setPhaseText(String text);
        void log(String s);
    }

    private static final class ButtonProgressUI implements ProgressUI {
        private final JButton button;
        ButtonProgressUI(JButton button) {
            this.button = button;
        }
        public void progress(final int pct) {
            if (button == null) return;
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    button.setText("Updating... " + pct + "%");
                }
            });
        }
        public void setPhaseText(String text) {
            // no-op
        }
        public void log(String s) {
            // no-op
        }
    }

    private static final class ProgressFrame extends JFrame implements ProgressUI {
        private final ProgressCanvas canvas;
        ProgressFrame(Path bgPath) {
            super("Minecraft: Oldschool Edition");
            setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            // Start progress window at same size as prompt (854x480)
            setSize(854, 480);
            setLocationRelativeTo(null);
            this.canvas = new ProgressCanvas(bgPath);
            List<Image> icons = loadAppIcons();
            if (!icons.isEmpty()) {
                setIconImages(icons);
                Image best = pickLargestIcon(icons);
                if (best != null) setIconImage(best);
            }
            setContentPane(canvas);
        }
        public void progress(int pct) { canvas.setProgress(Math.max(0.0, Math.min(1.0, pct / 100.0))); }
        public void setPhaseText(String text) { canvas.setPhase(text); }
        public void log(String s) { canvas.setPhase(s); }
        void done() { canvas.setProgress(1.0); }
    }

    // Lightweight ProgressUI wrapper around a ProgressCanvas so we can embed the
    // existing update screen inside another window (the launcher) as a "second page".
    private static final class EmbeddedProgressUI implements ProgressUI {
        private final ProgressCanvas canvas;
        EmbeddedProgressUI(ProgressCanvas canvas) {
            this.canvas = canvas;
        }
        public void progress(int pct) {
            canvas.setProgress(Math.max(0.0, Math.min(1.0, pct / 100.0)));
        }
        public void setPhaseText(String text) {
            canvas.setPhase(text);
        }
        public void log(String s) {
            canvas.setPhase(s);
        }
    }

    private static final class ProgressCanvas extends JPanel {
        private BufferedImage bg;
        private double progress = 0.0;
        private String phase = "";
        private final Color fg = new Color(202, 202, 202); // #CACACA
        private final Color sub = new Color(202, 202, 202); // #CACACA
        private final Color barBg = new Color(16, 16, 16);
        private final Color barFill = new Color(44, 156, 6);
        ProgressCanvas(Path bgPath) {
            setLayout(new BorderLayout());
            setOpaque(true);
            setBackground(new Color(60, 43, 29));
            if (bgPath != null && Files.isRegularFile(bgPath)) {
                try { bg = ImageIO.read(bgPath.toFile()); } catch (Exception ignored) {}
            }
        }
        void setProgress(double f) { this.progress = f; repaint(); }
        void setPhase(String s) { this.phase = s != null ? s : ""; repaint(); }
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            int w = getWidth();
            int h = getHeight();
            drawBackground(g, w, h, bg);

            // Title - positioned to match original exactly
            String title = "Updating Minecraft";
            // Integer scaling using ceiling so text scales sooner when window grows
            double layout = Math.min(w / 854.0, h / 480.0);
            int kk = (int) Math.max(1, Math.ceil(layout - 1e-6));
            Font base = UI_BASE_FONT != null ? UI_BASE_FONT : getFont();
            BufferedImage titleImg = renderTextRaster(title, base.deriveFont(Font.BOLD, 30f), fg);
            int tsw = titleImg.getWidth() * kk;
            int tsh = titleImg.getHeight() * kk;
            int tx = (w - tsw) / 2;
            // Position title at ~30% from top like original
            int ty = (int) Math.round(h * 0.30) - tsh / 2;
            Object oldI = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(titleImg, tx, ty, tsw, tsh, null);
            if (oldI != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldI);

            // Phase text - centered vertically like original
            String p = phase != null ? phase : "";
            BufferedImage phaseImg = renderTextRaster(p, base.deriveFont(Font.PLAIN, 18f), sub);
            int psw = phaseImg.getWidth() * kk;
            int psh = phaseImg.getHeight() * kk;
            int px = (w - psw) / 2;
            // Position phase text at ~50% from top (center) like original
            int py = (int) Math.round(h * 0.50) - psh / 2;
            oldI = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(phaseImg, px, py, psw, psh, null);
            if (oldI != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldI);

            // Progress bar: positioned at ~83% from top like original
            int barW = Math.max(200 * kk, w - 160 * kk);
            // Thin bar matching original
            int barH = Math.max(4, 6 * kk);
            int barX = (w - barW) / 2;
            // Position at 83% from top like original
            int barY = (int) Math.round(h * 0.83);
            g.setColor(barBg);
            g.fillRect(barX, barY, barW, barH);
            int fill = (int) Math.round(barW * progress);
            g.setColor(barFill);
            g.fillRect(barX, barY, fill, barH);
            g.setColor(Color.black);
            g.drawRect(barX, barY, barW, barH);
        }
    }

    // Data classes
    private static final class LatestRelease {
        String tag;
        String name;
        String body;
        String htmlUrl;
        String zipballUrl;
        List<ReleaseAsset> assets;
    }
    private static final class ReleaseAsset {
        String name;
        String url;
    }
    
    private static Path findBgPath(Path minecraftDir) {
        // Next to the updater jar
        Path jarDir = getJarDir();
        if (jarDir != null) {
            Path pJar = jarDir.resolve("bg.png");
            if (Files.isRegularFile(pJar)) return pJar;
        }
        // Dev fallback
        Path p2 = Paths.get("tools", "mod-updater", "bg.png");
        if (Files.isRegularFile(p2)) return p2;
        return null;
    }

    private static Path findButtonPath() {
        Path jarDir = getJarDir();
        if (jarDir != null) {
            Path p = jarDir.resolve("button.png");
            if (Files.isRegularFile(p)) return p;
        }
        Path p2 = Paths.get("tools", "mod-updater", "button.png");
        if (Files.isRegularFile(p2)) return p2;
        return null;
    }

    /**
     * Attempts to load the classic launcher logo image from the Minecraft
     * instance on disk so the updater can show it in the bottom dirt bar.
     * Tries, in order:
     *   - <minecraftDir>/resources/gui/logo.png
     *   - gui/logo.png from the client jar at <minecraftDir>/bin/minecraft.jar
     */
    private static Image loadLauncherLogoImage(Path minecraftDir) {
        // 0) Prefer a logo.png shipped alongside the updater jar (or in tools/mod-updater in dev).
        try {
            Path jarDir = getJarDir();
            if (jarDir != null) {
                Path p = jarDir.resolve("logo.png");
                if (Files.isRegularFile(p)) {
                    return ImageIO.read(p.toFile());
                }
            }
            // Dev fallback for running from the repo without packaging.
            Path dev = Paths.get("tools", "mod-updater", "logo.png");
            if (Files.isRegularFile(dev)) {
                return ImageIO.read(dev.toFile());
            }
        } catch (Throwable ignored) {
        }

        if (minecraftDir == null) return null;
        // 1) Look for loose resources/gui/logo.png alongside the game.
        try {
            Path loose = minecraftDir.resolve("resources").resolve("gui").resolve("logo.png");
            if (Files.isRegularFile(loose)) {
                return ImageIO.read(loose.toFile());
            }
        } catch (Throwable ignored) {
        }
        // 2) Try to read it out of the client jar.
        try {
            Path jarPath = resolveClientJarPath(minecraftDir, null);
            if (jarPath != null && Files.isRegularFile(jarPath)) {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarPath.toFile())) {
                    java.util.zip.ZipEntry e = zip.getEntry("gui/logo.png");
                    if (e != null) {
                        try (InputStream in = zip.getInputStream(e)) {
                            return ImageIO.read(in);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Path getJarDir() {
        try {
            java.net.URL loc = ModUpdaterGUI.class.getProtectionDomain().getCodeSource().getLocation();
            java.nio.file.Path p = java.nio.file.Paths.get(loc.toURI());
            if (java.nio.file.Files.isDirectory(p)) return p;
            return p.getParent();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Font loadGameFont() {
        try {
            Path dir = getJarDir();
            if (dir != null) {
                Path ttf1 = dir.resolve("minecraft.ttf");
                Path ttf2 = dir.resolve("Minecraftia.ttf");
                Path ttf = Files.isRegularFile(ttf1) ? ttf1 : (Files.isRegularFile(ttf2) ? ttf2 : null);
                if (ttf != null) {
                    InputStream in = Files.newInputStream(ttf);
                    try { return Font.createFont(Font.TRUETYPE_FONT, in); } finally { in.close(); }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Font loadSystemSansFont() {
        try {
            // Prefer Swing LAF font if present
            javax.swing.plaf.FontUIResource laf = (javax.swing.plaf.FontUIResource) UIManager.getFont("Label.font");
            if (laf != null) return laf;
        } catch (Throwable ignored) {}
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String fam;
        if (os.contains("mac")) fam = "Lucida Grande"; // classic macOS UI font (2011 era)
        else if (os.contains("win")) fam = "Arial";
        else fam = "DejaVu Sans";
        Font f = new Font(fam, Font.PLAIN, 12);
        if (f != null && !"Dialog".equalsIgnoreCase(f.getFamily())) return f;
        // Fallback to logical SansSerif mapping
        return new Font("SansSerif", Font.PLAIN, 12);
    }

    private static Font detectBaseFont() {
        Font f = loadGameFont();
        if (f != null) return f;
        return loadSystemSansFont();
    }

    private static List<Image> loadAppIcons() {
        List<Image> list = new ArrayList<Image>();
        // Build candidate directories to search for minecraft.png/.ico
        List<Path> dirs = new ArrayList<Path>();
        Path jarDir = getJarDir();
        if (jarDir != null) dirs.add(jarDir);
        // Instance tools/mod-updater directory from command-line arg (Prism launcher)
        try {
            if (INSTANCE_DIR != null && !INSTANCE_DIR.isEmpty()) {
                Path p = java.nio.file.Paths.get(INSTANCE_DIR).resolve("tools").resolve("mod-updater");
                if (Files.isDirectory(p.getParent())) dirs.add(p);
            }
        } catch (Throwable ignored) {}
        // Fallback: check env var (if running outside Prism)
        try {
            String inst = System.getenv("INST_DIR");
            if (inst != null && !inst.isEmpty()) {
                Path p = java.nio.file.Paths.get(inst).resolve("tools").resolve("mod-updater");
                dirs.add(p);
            }
        } catch (Throwable ignored) {}
        // Current working directory
        try { dirs.add(java.nio.file.Paths.get("").toAbsolutePath()); } catch (Throwable ignored) {}

        for (Path dir : dirs) {
            try {
                if (dir == null) continue;
                Path absDir = dir.toAbsolutePath();
                System.out.println("[mod-updater] Icon: checking directory " + absDir);
                // Prefer PNG first
                Path png = absDir.resolve("minecraft.png");
                System.out.println("[mod-updater] Icon: looking for " + png);
                System.out.println("[mod-updater] Icon: exists=" + Files.exists(png) + ", isRegularFile=" + (Files.exists(png) ? Files.isRegularFile(png) : false));
                if (Files.isRegularFile(png)) {
                    try {
                        // Try multiple methods to load the PNG
                        BufferedImage img = null;
                        byte[] bytes = null;
                        // Method 1: Standard ImageIO
                        img = ImageIO.read(png.toFile());
                        if (img == null) {
                            // Method 2: Read bytes and use ImageIO with InputStream
                            bytes = Files.readAllBytes(png);
                            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
                            img = ImageIO.read(bais);
                            bais.close();
                        }
                        if (img == null) {
                            // Method 2b: Try explicit PNG ImageReader
                            bytes = Files.readAllBytes(png);
                            java.io.ByteArrayInputStream bais2 = new java.io.ByteArrayInputStream(bytes);
                            try {
                                java.util.Iterator<javax.imageio.ImageReader> readers = javax.imageio.ImageIO.getImageReadersByFormatName("png");
                                System.out.println("[mod-updater] Icon: Available PNG readers: " + (readers.hasNext() ? "yes" : "none"));
                                javax.imageio.ImageReader reader = null;
                                while (readers.hasNext()) {
                                    reader = readers.next();
                                    System.out.println("[mod-updater] Icon: Trying reader: " + reader.getClass().getName());
                                    break;
                                }
                                if (reader != null) {
                                    javax.imageio.stream.ImageInputStream iis = javax.imageio.ImageIO.createImageInputStream(bais2);
                                    reader.setInput(iis);
                                    img = reader.read(0);
                                    reader.dispose();
                                    iis.close();
                                }
                                bais2.close();
                            } catch (Exception readerEx) {
                                System.out.println("[mod-updater] Icon: ImageReader method failed: " + readerEx.getMessage());
                                readerEx.printStackTrace();
                            }
                        }
                        if (img == null) {
                            // Method 3: Try Toolkit (need bytes first)
                            if (bytes == null) bytes = Files.readAllBytes(png);
                            try {
                                Image toolkitImg = java.awt.Toolkit.getDefaultToolkit().createImage(bytes);
                                if (toolkitImg != null) {
                                    // Wait for image to load using MediaTracker
                                    java.awt.MediaTracker mt = new java.awt.MediaTracker(new java.awt.Component() {});
                                    mt.addImage(toolkitImg, 0);
                                    try {
                                        mt.waitForID(0, 5000); // Wait up to 5 seconds
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                    }
                                    // Check if loaded successfully
                                    int w = toolkitImg.getWidth(null);
                                    int h = toolkitImg.getHeight(null);
                                    if (w > 0 && h > 0 && !mt.isErrorID(0)) {
                                        // Convert to BufferedImage
                                        img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                                        Graphics2D g = img.createGraphics();
                                        g.drawImage(toolkitImg, 0, 0, null);
                                        g.dispose();
                                    } else {
                                        System.out.println("[mod-updater] Icon: Toolkit image not loaded (w=" + w + ", h=" + h + ", error=" + mt.isErrorID(0) + ")");
                                    }
                                }
                            } catch (Exception tkEx) {
                                System.out.println("[mod-updater] Icon: Toolkit method failed: " + tkEx.getMessage());
                                tkEx.printStackTrace();
                            }
                        }
                        if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                            list.addAll(makeIconSizes(img));
                            System.out.println("[mod-updater] Icon: SUCCESS loaded minecraft.png from " + png + " (size: " + img.getWidth() + "x" + img.getHeight() + ")");
                            break;
                        } else {
                            System.out.println("[mod-updater] Icon: ImageIO.read returned null or invalid image for " + png + " (file size: " + Files.size(png) + " bytes)");
                        }
                    } catch (Exception e) {
                        System.out.println("[mod-updater] Icon: failed to read " + png + ": " + e.getClass().getName() + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                Path ico = absDir.resolve("minecraft.ico");
                System.out.println("[mod-updater] Icon: looking for " + ico);
                if (Files.isRegularFile(ico)) {
                    try {
                        List<Image> icoFrames = decodeIco(ico);
                        if (!icoFrames.isEmpty()) {
                            list.addAll(icoFrames);
                            System.out.println("[mod-updater] Icon: SUCCESS loaded minecraft.ico from " + ico);
                            break;
                        }
                    } catch (Exception e) {
                        System.out.println("[mod-updater] Icon: failed to read " + ico + ": " + e.getMessage());
                    }
                }
            } catch (Throwable e) {
                System.out.println("[mod-updater] Icon: error checking directory: " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        if (list.isEmpty()) System.out.println("[mod-updater] Icon: no minecraft.png/.ico found. Searched " + dirs.size() + " directories.");
        return list;
    }

    private static BufferedImage loadButtonTexture() {
        try {
            Path p = findButtonPath();
            if (p != null && Files.isRegularFile(p)) {
                return ImageIO.read(p.toFile());
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void applyUIFont(Font f) {
        try {
            java.util.Enumeration<?> e = UIManager.getDefaults().keys();
            while (e.hasMoreElements()) {
                Object key = e.nextElement();
                Object val = UIManager.get(key);
                if (val instanceof Font) {
                    UIManager.put(key, f);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static List<Image> makeIconSizes(Image base) {
        int[] sizes = new int[] {16, 24, 32, 48, 64, 128, 256};
        List<Image> out = new ArrayList<Image>(sizes.length);
        for (int s : sizes) {
            Image scaled = base.getScaledInstance(s, s, Image.SCALE_SMOOTH);
            BufferedImage b = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = b.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(scaled, 0, 0, null);
            g.dispose();
            out.add(b);
        }
        return out;
    }

    private static Image pickLargestIcon(List<Image> images) {
        if (images == null || images.isEmpty()) return null;
        Image best = null;
        int bestArea = -1;
        for (Image img : images) {
            if (img == null) continue;
            int w = img.getWidth(null);
            int h = img.getHeight(null);
            int a = (w > 0 && h > 0) ? w * h : -1;
            if (a > bestArea) { bestArea = a; best = img; }
        }
        return best;
    }

    // JLabel that paints text with antialiasing disabled
    private static final class NoAATextLabel extends JLabel {
        NoAATextLabel(String text, int align) { super(text, align); }
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            Object oldAA = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
            Object oldA  = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            super.paintComponent(g0);
            if (oldAA != null) g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, oldAA);
            if (oldA  != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldA);
        }
    }

    // Pixelated label: renders at base size and upscales by integer pixelScale (nearest-neighbor)
    private static final class PixelLabel extends JComponent {
        private String text;
        private final float basePt;
        private final boolean bold;
        private int pixelScale = 1;
        PixelLabel(String text, float basePt, boolean bold) {
            this.text = text != null ? text : "";
            this.basePt = basePt;
            this.bold = bold;
            setOpaque(false);
        }
        public void setText(String t) { this.text = t != null ? t : ""; repaint(); }
        public void setPixelScale(int k) { this.pixelScale = Math.max(1, k); revalidate(); repaint(); }
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            Font base = UI_BASE_FONT != null ? UI_BASE_FONT : getFont();
            Font f = base.deriveFont(bold ? Font.BOLD : Font.PLAIN, basePt);
            BufferedImage img = renderTextRaster(text, f, getForeground() != null ? getForeground() : new Color(202,202,202));
            int iw = img.getWidth(), ih = img.getHeight();
            int k = this.pixelScale;
            int sw = iw * k, sh = ih * k;
            int x = (getWidth() - sw) / 2;
            int y = (getHeight() - sh) / 2;
            Object oldI = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            // No text shadow for labels; draw only the glyphs
            g.drawImage(img, x, y, sw, sh, null);
            if (oldI != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldI);
        }
        public Dimension getPreferredSize() {
            Font base = UI_BASE_FONT != null ? UI_BASE_FONT : getFont();
            Font f = base.deriveFont(bold ? Font.BOLD : Font.PLAIN, basePt);
            java.awt.font.FontRenderContext frc = new java.awt.font.FontRenderContext(null, false, false);
            java.awt.geom.Rectangle2D b = f.getStringBounds(text, frc);
            int w = Math.max(1, (int)Math.ceil(b.getWidth())) * pixelScale;
            int h = Math.max(1, (int)Math.ceil(b.getHeight())) * pixelScale;
            return new Dimension(w, h);
        }
    }

    // JButton that paints text with antialiasing disabled
    private static final class PixelButton extends JButton {
        PixelButton(String text) {
            super(text);
            setBorder(javax.swing.BorderFactory.createEmptyBorder());
            setMargin(new java.awt.Insets(0,0,0,0));
            setContentAreaFilled(false);
            setFocusPainted(false);
        }
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            int k = 1;
            try {
                Object v = getClientProperty("pixelScale");
                if (v instanceof Number) k = Math.max(1, ((Number) v).intValue());
            } catch (Exception ignored) {}

            // Draw background using button.png if available
            if (BUTTON_TEXTURE != null) {
                Object oldI = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(BUTTON_TEXTURE, 0, 0, getWidth(), getHeight(), null);
                if (oldI != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldI);
            } else {
                // Fallback flat style
                g.setColor(new Color(228, 236, 244));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(new Color(180, 190, 200));
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            }

            // Render text to 1x raster and scale up by k (nearest-neighbor)
            String txt = getText();
            float basePt = 16f;
            try { if (getFont() != null) basePt = (float) getFont().getSize2D(); } catch (Exception ignored) {}
            Font font = (UI_BASE_FONT != null ? UI_BASE_FONT : getFont()).deriveFont(Font.PLAIN, basePt);
            // Button text: pure black, no shadow
            BufferedImage ras = renderTextRaster(txt, font, Color.BLACK);
            int iw = ras.getWidth(), ih = ras.getHeight();
            int sw = iw * k, sh = ih * k;
            int x = (getWidth() - sw) / 2;
            // Subtle vertical tweak: half a pixel per scale, closer to visual center
            int y = (getHeight() - sh) / 2 - Math.max(0, (int) Math.round(0.5 * k));
            Object oldI = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(ras, x, y, sw, sh, null);
            if (oldI != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldI);
        }
        public Dimension getPreferredSize() {
            int k = 1;
            try {
                Object ps = getClientProperty("pixelScale");
                if (ps instanceof Number) k = Math.max(1, ((Number) ps).intValue());
            } catch (Exception ignored) {}
            int bw = 96, bh = 20;
            try {
                Object bW = getClientProperty("baseW");
                Object bH = getClientProperty("baseH");
                if (bW instanceof Number) bw = ((Number) bW).intValue();
                if (bH instanceof Number) bh = ((Number) bH).intValue();
            } catch (Exception ignored) {}
            return new Dimension(bw * k, bh * k);
        }
        public Dimension getMaximumSize() { return getPreferredSize(); }
    }

    /**
     * Lightweight Swing button styled to look like the classic launcher buttons:
     * flat light grey, simple 1px darker border, no focus or rollover chrome.
     */
    private static final class LegacyButton extends JButton {
        LegacyButton(String text) {
            super(text);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setMargin(new Insets(2, 10, 2, 10));
        }
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            int w = getWidth();
            int h = getHeight();

            int arc = 10; // slightly more curved corners

            // Determine if this is the "primary" button (Options) and whether it's pressed
            boolean primary = Boolean.TRUE.equals(getClientProperty("primary"));
            ButtonModel model = getModel();
            boolean pressed = model.isArmed() && model.isPressed();

            // Base fill: grey by default, blue-tinted when the primary button is pressed
            Color base = new Color(0xE0E0E0);
            if (primary && pressed) {
                base = new Color(0xA8C4FF); // soft blue tint for pressed Options button
            }

            g.setColor(base);
            g.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);

            // 3D-style border similar to the original launcher buttons, but rounded
            // Outer light highlight (top/left)
            g.setColor(Color.WHITE);
            g.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
            // Inner mid-grey border
            g.setColor(new Color(0xC0C0C0));
            g.drawRoundRect(1, 1, w - 3, h - 3, arc - 2, arc - 2);
            // Outer dark edge (bottom/right) simulated with a subtle shadow line
            g.setColor(new Color(0xA0A0A0));
            g.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);

            // Text
            String txt = getText();
            Font f = (UI_BASE_FONT != null ? UI_BASE_FONT : getFont());
            g.setFont(f.deriveFont(Font.PLAIN, 13f));
            g.setColor(Color.BLACK);
            FontMetrics fm = g.getFontMetrics();
            int tx = (w - fm.stringWidth(txt)) / 2;
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(txt, tx, ty);
        }
    }

    // Minimal ICO reader for PNG-based icon frames
    private static List<Image> decodeIco(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 6) return Collections.emptyList();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int reserved = Short.toUnsignedInt(in.readShort());
        int type = Short.toUnsignedInt(in.readShort());
        int count = Short.toUnsignedInt(in.readShort());
        if (reserved != 0 || type != 1 || count <= 0) return Collections.emptyList();
        class Entry { int w,h,offset,size; }
        Entry[] entries = new Entry[count];
        // skip, we'll re-parse with ByteBuffer LE below
        // Re-parse directory in simple manual way because DataInputStream big-endian; ICO uses little-endian.
        // We'll parse using ByteBuffer LE to be safe.
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.position(6);
        entries = new Entry[count];
        for (int i = 0; i < count; i++) {
            Entry e = new Entry();
            int w = Byte.toUnsignedInt(bb.get());
            int h = Byte.toUnsignedInt(bb.get());
            bb.get(); // color count
            bb.get(); // reserved
            bb.getShort(); // planes
            bb.getShort(); // bitcount
            int size = bb.getInt();
            int offset = bb.getInt();
            e.w = (w == 0 ? 256 : w); e.h = (h == 0 ? 256 : h); e.size = size; e.offset = offset;
            entries[i] = e;
        }
        // Choose largest, then load each via ImageIO (works for PNG-encoded frames)
        Arrays.sort(entries, new java.util.Comparator<Entry>() {
            public int compare(Entry a, Entry b) { return (b.w*b.h) - (a.w*a.h); }
        });
        List<Image> out = new ArrayList<Image>();
        for (Entry e : entries) {
            if (e.offset < 0 || e.offset + e.size > bytes.length) continue;
            byte[] imgBytes = Arrays.copyOfRange(bytes, e.offset, e.offset + e.size);
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(imgBytes));
                if (img != null) out.add(img);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static void drawBackground(Graphics2D g, int w, int h, BufferedImage bg) {
        if (bg != null && bg.getWidth() > 0) {
            int iw = bg.getWidth();
            int ih = bg.getHeight();
            if (iw <= 64 && ih <= 64) {
                // Integer scaling based on 854x480 reference
                int kx = Math.max(1, w / 854);
                int ky = Math.max(1, h / 480);
                int k = Math.max(1, Math.min(kx, ky));
                int tw = iw * k;
                int th = ih * k;
                for (int y = 0; y < h; y += th) {
                    for (int x = 0; x < w; x += tw) {
                        g.drawImage(bg, x, y, x + tw, y + th, 0, 0, iw, ih, null);
                    }
                }
            } else {
                double sx = w / (double) iw;
                double sy = h / (double) ih;
                double s = Math.max(sx, sy);
                int dw = (int) (iw * s);
                int dh = (int) (ih * s);
                int dx = (w - dw) / 2;
                int dy = (h - dh) / 2;
                g.drawImage(bg, dx, dy, dw, dh, null);
            }
        } else {
            g.setColor(new Color(60, 43, 29));
            g.fillRect(0, 0, w, h);
        }
    }

    private static BufferedImage renderTextRaster(String text, Font font, Color color) {
        if (text == null) text = "";
        java.awt.font.FontRenderContext frc = new java.awt.font.FontRenderContext(null, false, false);
        java.awt.geom.Rectangle2D bounds = font.getStringBounds(text, frc);
        int iw = Math.max(1, (int) Math.ceil(bounds.getWidth()));
        int ih = Math.max(1, (int) Math.ceil(bounds.getHeight()));
        BufferedImage img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(new Color(0,0,0,0));
        g.fillRect(0,0,iw,ih);
        g.setFont(font);
        g.setColor(color);
        int baseline = (int) Math.round(-bounds.getY());
        g.drawString(text, 0, baseline);
        g.dispose();
        return img;
    }

    private static void writeMarker(Path jarPath, LatestRelease release, ReleaseAsset asset) {
        try {
            String json = "{\n" +
                "  \"tag\": \"" + safe(release.tag) + "\",\n" +
                "  \"asset\": \"" + safe(asset.name) + "\",\n" +
                "  \"url\": \"" + safe(asset.url) + "\",\n" +
                "  \"installedAt\": " + System.currentTimeMillis() + "\n" +
                "}\n";
            Path marker = jarPath.resolveSibling(jarPath.getFileName().toString() + ".mcose.json");
            java.nio.file.Files.write(marker, json.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    private static InstalledMarker readMarker(Path jarPath) {
        try {
            Path marker = jarPath.resolveSibling(jarPath.getFileName().toString() + ".mcose.json");
            if (!java.nio.file.Files.isRegularFile(marker)) return null;
            String text = new String(java.nio.file.Files.readAllBytes(marker), java.nio.charset.StandardCharsets.UTF_8);
            InstalledMarker m = new InstalledMarker();
            m.tag = extractString(text, "\\\"tag\\\"\\s*:\\s*\\\"(.*?)\\\"");
            m.assetName = extractString(text, "\\\"asset\\\"\\s*:\\s*\\\"(.*?)\\\"");
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static String safe(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static boolean equalsSafe(String a, String b) { return a != null && b != null && a.equals(b); }

    private static final class InstalledMarker {
        String tag;
        String assetName;
    }
}


