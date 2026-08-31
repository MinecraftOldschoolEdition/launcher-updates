// =============================================================================
// IMPORTS
// =============================================================================

// Swing GUI components
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.text.EditorKit;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

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
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

// Collections and utilities
import java.util.List;
import java.util.*;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
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
 * --resourcePackBranch <branch> Stable resource sync branch (default: main)
 * --resourcePackBetaBranch <branch> Beta resource sync branch (default: beta)
 * --resourcePackCheckIntervalMinutes <minutes> Delay between unchanged-commit local scans (default: 60)
 * --lwjgl3Repo <owner/repo> Repository containing the Prism org.lwjgl.json release asset
 * --lwjgl3AssetRegex <regex> Full-match regex for the LWJGL component release asset
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
 *   resourcePackBranch=main
 *   resourcePackBetaBranch=beta
 *   resourcePackCheckIntervalMinutes=60
 *   lwjgl3Repo=MinecraftOldschoolEdition/lwjgl3-patch-fetcher
 *   lwjgl3AssetRegex=^org[.]lwjgl[.]json$
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
            // macOS AWT Toolkit Fix (MUST be first, before any AWT classes load)
            // =====================================================================
            //
            // On macOS, Java sometimes defaults to X11 toolkit (especially when
            // launched from certain environments like Prism Launcher). This causes:
            //   java.awt.AWTError: Toolkit not found: sun.awt.X11.XToolkit
            //
            // Solution: Explicitly set the native macOS toolkit before AWT initializes.
            
            String osName = System.getProperty("os.name", "").toLowerCase();
            if (osName.contains("mac")) {
                System.setProperty("awt.toolkit", "sun.lwawt.macosx.LWCToolkit");
            }
            
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

    /** Non-zero status tells Prism that the pre-launch command was cancelled. */
    private static final int PRE_LAUNCH_CANCELLED_EXIT_CODE = 2;
    
    /** Resource archive download timeout in milliseconds (45 seconds). */
    private static final int RESOURCE_ARCHIVE_TIMEOUT_MS = 45000;
    
    /** Number of attempts per resource archive candidate URL. */
    private static final int RESOURCE_ARCHIVE_RETRIES = 3;
    
    /** Base delay for resource archive retry backoff. */
    private static final long RESOURCE_ARCHIVE_RETRY_BASE_DELAY_MS = 750L;
    
    /** Maximum per-run detailed resource file log lines for each category. */
    private static final int RESOURCE_SYNC_DETAIL_LOG_LIMIT = 120;

    /** Maximum number of resource-pack files fetched at the same time. */
    private static final int RESOURCE_DOWNLOAD_THREADS = 4;

    /** Number of attempts for a transient metadata or per-file endpoint failure. */
    private static final int RESOURCE_ENDPOINT_RETRIES = 2;

    /** Base delay for transient metadata and per-file retry backoff. */
    private static final long RESOURCE_ENDPOINT_RETRY_BASE_DELAY_MS = 500L;

    /** Default delay between local integrity scans when the remote commit is unchanged. */
    private static final long DEFAULT_RESOURCE_CHECK_INTERVAL_MINUTES = 60L;

    /** Persisted remote resource tree used to calculate incremental changes. */
    private static final String RESOURCE_SYNC_MANIFEST_NAME = ".mcose-resource-sync.properties";
    
    /** GitHub API endpoint template for fetching the latest release from a repository */
    private static final String GITHUB_API_LATEST = "https://api.github.com/repos/%s/releases/latest";

    /** Optional companion server jar shipped next to patch.jar for Open To Multiplayer. */
    private static final String DEFAULT_SERVER_JAR_REGEX = "server\\.jar";
    private static final String LAN_SERVER_DIR_NAME = "lan-server";
    private static final String LAN_SERVER_JAR_NAME = "server.jar";
    
    /**
     * OS trust bundle paths used to supplement outdated Java truststores.
     * These are best-effort fallbacks and are ignored when missing.
     */
    private static final String[] OS_CA_BUNDLE_PATHS = {
        "/etc/ssl/certs/ca-certificates.crt",
        "/etc/pki/tls/certs/ca-bundle.crt",
        "/etc/ssl/cert.pem"
    };
    
    /** Lazy-initialized TLS socket factory that merges Java and OS trust roots. */
    private static volatile SSLSocketFactory TLS_SOCKET_FACTORY;
    private static volatile boolean TLS_SOCKET_FACTORY_INIT_FAILED;
    private static final Object TLS_SOCKET_FACTORY_LOCK = new Object();
    
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
            String jarRegex = value(cli, cfg, "jarRegex", "patch\\.jar");   // Pattern for mod JAR
            String serverJarRegex = normalizeOptionalRegex(value(cli, cfg, "serverJarRegex", DEFAULT_SERVER_JAR_REGEX)); // Optional LAN server JAR
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
            String launcherPromoterJarRegex = value(cli, cfg, "launcherPromoterJarRegex", "launcher-promoter\\.jar");
            
            // Resource pack repository (assets synced before each launch)
            String resourcePackRepo = value(cli, cfg, "resourcePackRepo", "MinecraftOldschoolEdition/resourcepack");
            String resourcePackBranch = value(cli, cfg, "resourcePackBranch", "main");
            String resourcePackBetaBranch = value(cli, cfg, "resourcePackBetaBranch", "beta");
            String resourcePackArchiveMirrorUrl = normalizeOptionalText(
                    value(cli, cfg, "resourcePackArchiveMirrorUrl", null));
            long resourcePackCheckIntervalMs = parseResourcePackCheckIntervalMillis(
                    value(cli, cfg, "resourcePackCheckIntervalMinutes", Long.toString(DEFAULT_RESOURCE_CHECK_INTERVAL_MINUTES)));

            // Separately-versioned Prism component patch used to replace the legacy
            // org.lwjgl metadata with the LWJGL 3 runtime required by this client.
            String lwjgl3Repo = value(cli, cfg, "lwjgl3Repo", "MinecraftOldschoolEdition/lwjgl3-patch-fetcher");
            String lwjgl3AssetRegex = normalizeOptionalRegex(
                    value(cli, cfg, "lwjgl3AssetRegex", "^org[.]lwjgl[.]json$"));

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
            BranchContext branch = fetchBranchState(useBetaUpdates, repo, betaRepo, jarRegex, serverJarRegex, assetsRegex, minecraftDir, instanceRoot, mode, jarmodName);
            
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
            state.lwjgl3PatchUpdate = checkLwjgl3PatchUpdate(
                    lwjgl3Repo,
                    lwjgl3AssetRegex,
                    instanceRoot);
            state.hasUpdate = !branch.upToDate
                    || (state.lwjgl3PatchUpdate != null && state.lwjgl3PatchUpdate.updateAvailable);
            state.releaseRepo = repo;                      // Main release repository
            state.betaRepo = betaRepo;                     // Beta release repository
            state.useBetaUpdates = useBetaUpdates;         // Beta updates enabled?
            state.configPath = configPath;                 // Path to config file
            state.instanceRoot = instanceRoot;             // Instance root directory
            state.launcherUpdate = checkLauncherUpdate(
                    launcherRepo,
                    launcherJarRegex,
                    launcherPromoterJarRegex,
                    launcherJarPath,
                    instanceRoot);
            if (wasRestartedAfterLauncherUpdate() && state.launcherUpdate != null) {
                state.launcherUpdate.updateAvailable = false;
            }
            state.branch = branch;                         // Current branch context
            state.resourcePackRepo = resourcePackRepo;     // Resource pack repository
            state.resourcePackBranch = resourcePackBranch; // Stable resource pack branch
            state.resourcePackBetaBranch = resourcePackBetaBranch; // Beta resource pack branch
            state.resourcePackArchiveMirrorUrl = resourcePackArchiveMirrorUrl; // Optional independent full-pack mirror
            state.resourcePackCheckIntervalMs = resourcePackCheckIntervalMs; // Delay between unchanged-commit local scans
            state.minecraftDir = minecraftDir;             // Minecraft directory for assets
            state.launchArgs = args != null ? (String[]) args.clone() : new String[0];
            
            // Display the launcher GUI (blocks until user closes it)
            showLauncher(bgPath, minecraftDir, instanceRoot, mode, jarRegex, serverJarRegex, assetsRegex, jarmodName, state, newsUrl);
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
            if ("--config".equals(a) || "--repo".equals(a) || "--betaRepo".equals(a) || "--jarRegex".equals(a) || "--serverJarRegex".equals(a) || "--assetsRegex".equals(a)
                || "--minecraftDir".equals(a) || "--instanceDir".equals(a) || "--mode".equals(a)
                || "--jarmodName".equals(a) || "--newsUrl".equals(a)
                || "--resourcePackRepo".equals(a) || "--resourcePackBranch".equals(a) || "--resourcePackBetaBranch".equals(a)
                || "--resourcePackCheckIntervalMinutes".equals(a)
                || "--lwjgl3Repo".equals(a) || "--lwjgl3AssetRegex".equals(a)) {
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
        sb.append("serverJarRegex=").append(DEFAULT_SERVER_JAR_REGEX).append(newline);
        sb.append("assetsRegex=(assets|resources).*(?i)\\.zip").append(newline);
        sb.append("mode=jarmods").append(newline);
        sb.append("jarmodName=mod.jar").append(newline);
        sb.append("minecraftDir=../../minecraft/game").append(newline);
        sb.append("newsUrl=https://minecraftoldschool.com/updates.html").append(newline);
        sb.append("resourcePackRepo=MinecraftOldschoolEdition/resourcepack").append(newline);
        sb.append("resourcePackBranch=main").append(newline);
        sb.append("resourcePackBetaBranch=beta").append(newline);
        sb.append("resourcePackArchiveMirrorUrl=").append(newline);
        sb.append("resourcePackCheckIntervalMinutes=").append(DEFAULT_RESOURCE_CHECK_INTERVAL_MINUTES).append(newline);
        sb.append("lwjgl3Repo=MinecraftOldschoolEdition/lwjgl3-patch-fetcher").append(newline);
        sb.append("lwjgl3AssetRegex=^org[.]lwjgl[.]json$").append(newline);
        Files.write(configPath, sb.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String value(Map<String, String> cli, Properties cfg, String key, String def) {
        String v = cli.get("--" + key);
        if (v != null) return v;
        v = cfg.getProperty(key);
        return v != null ? v : def;
    }

    private static String normalizeOptionalRegex(String regex) {
        if (regex == null) return null;
        String trimmed = regex.trim();
        return trimmed.length() == 0 ? null : trimmed;
    }

    private static String normalizeOptionalText(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() == 0 ? null : trimmed;
    }

    private static long parseResourcePackCheckIntervalMillis(String value) {
        long minutes = DEFAULT_RESOURCE_CHECK_INTERVAL_MINUTES;
        if (value != null) {
            try {
                minutes = Long.parseLong(value.trim());
            } catch (NumberFormatException badValue) {
                System.err.println("[mod-updater] Invalid resourcePackCheckIntervalMinutes='" + value
                        + "'; using " + DEFAULT_RESOURCE_CHECK_INTERVAL_MINUTES + " minutes.");
            }
        }
        if (minutes < 0L) {
            System.err.println("[mod-updater] resourcePackCheckIntervalMinutes cannot be negative; using "
                    + DEFAULT_RESOURCE_CHECK_INTERVAL_MINUTES + " minutes.");
            minutes = DEFAULT_RESOURCE_CHECK_INTERVAL_MINUTES;
        }
        if (minutes > Long.MAX_VALUE / 60000L) {
            return Long.MAX_VALUE;
        }
        return minutes * 60000L;
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

    private static String currentResourcePackBranch(LauncherState state) {
        if (state == null) return "main";
        if (state.useBetaUpdates) {
            String betaBranch = state.resourcePackBetaBranch;
            return betaBranch != null && betaBranch.trim().length() > 0 ? betaBranch : "beta";
        }
        return state.resourcePackBranch;
    }

    private static boolean wasRestartedAfterLauncherUpdate() {
        String value = getenv("MCOSE_LAUNCHER_RESTARTED_AFTER_UPDATE");
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static String launcherVersionText(LauncherState state) {
        if (state == null || state.launcherUpdate == null) {
            return "unknown";
        }
        LauncherUpdateState update = state.launcherUpdate;
        String current = update.currentVersion;
        if (current == null || current.trim().isEmpty()) {
            current = "unknown";
        }
        if (update.updateAvailable && update.latest != null && update.latest.tag != null && update.latest.tag.trim().length() > 0) {
            return current + " (latest: " + update.latest.tag.trim() + ")";
        }
        return current;
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
        String resourcePackBranch;
        String resourcePackBetaBranch;
        String resourcePackArchiveMirrorUrl;
        long resourcePackCheckIntervalMs;
        Path minecraftDir;
        String[] launchArgs;
        Lwjgl3PatchUpdateState lwjgl3PatchUpdate;
    }

    private static final class BranchContext {
        String repo;
        boolean beta;
        LatestRelease latest;
        ReleaseAsset jarAsset;
        ReleaseAsset serverJarAsset;
        ReleaseAsset assetsZip;
        boolean upToDate;
    }

    private static final class LauncherUpdateState {
        boolean updateAvailable;
        boolean promoterUpdateAvailable;
        LatestRelease latest;
        ReleaseAsset asset;
        ReleaseAsset promoterAsset;
        Path launcherJar;
        Path promoterJar;
        String currentVersion;
        String promoterCurrentVersion;
    }

    private static final class Lwjgl3PatchUpdateState {
        boolean updateAvailable;
        LatestRelease latest;
        ReleaseAsset asset;
        Path patchFile;
        String currentVersion;
    }

    private static final class PreparedLwjgl3Patch {
        final Lwjgl3PatchUpdateState update;
        final Path target;
        final Path staged;
        final String version;

        PreparedLwjgl3Patch(Lwjgl3PatchUpdateState update, Path target, Path staged, String version) {
            this.update = update;
            this.target = target;
            this.staged = staged;
            this.version = version;
        }
    }

    /** Minimal dependency-free JSON syntax validator for trusted component payloads. */
    private static final class JsonSyntaxValidator {
        private final String text;
        private int index;

        private JsonSyntaxValidator(String text) {
            this.text = text;
        }

        static void validate(String text) throws IOException {
            if (text == null) throw new IOException("JSON payload is null.");
            JsonSyntaxValidator parser = new JsonSyntaxValidator(text);
            parser.skipWhitespace();
            parser.readValue();
            parser.skipWhitespace();
            if (parser.index != text.length()) parser.fail("trailing data");
        }

        private void readValue() throws IOException {
            if (index >= text.length()) fail("expected a value");
            char c = text.charAt(index);
            if (c == '{') readObject();
            else if (c == '[') readArray();
            else if (c == '"') readString();
            else if (c == 't') readLiteral("true");
            else if (c == 'f') readLiteral("false");
            else if (c == 'n') readLiteral("null");
            else if (c == '-' || (c >= '0' && c <= '9')) readNumber();
            else fail("unexpected character '" + c + "'");
        }

        private void readObject() throws IOException {
            index++;
            skipWhitespace();
            if (consume('}')) return;
            while (true) {
                if (index >= text.length() || text.charAt(index) != '"') fail("expected an object key");
                readString();
                skipWhitespace();
                require(':');
                skipWhitespace();
                readValue();
                skipWhitespace();
                if (consume('}')) return;
                require(',');
                skipWhitespace();
            }
        }

        private void readArray() throws IOException {
            index++;
            skipWhitespace();
            if (consume(']')) return;
            while (true) {
                readValue();
                skipWhitespace();
                if (consume(']')) return;
                require(',');
                skipWhitespace();
            }
        }

        private void readString() throws IOException {
            require('"');
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') return;
                if (c < 0x20) fail("unescaped control character in string");
                if (c != '\\') continue;
                if (index >= text.length()) fail("unterminated escape sequence");
                char escaped = text.charAt(index++);
                if (escaped == '"' || escaped == '\\' || escaped == '/'
                        || escaped == 'b' || escaped == 'f' || escaped == 'n'
                        || escaped == 'r' || escaped == 't') {
                    continue;
                }
                if (escaped != 'u') fail("invalid escape sequence");
                for (int i = 0; i < 4; i++) {
                    if (index >= text.length() || Character.digit(text.charAt(index++), 16) < 0) {
                        fail("invalid unicode escape");
                    }
                }
            }
            fail("unterminated string");
        }

        private void readNumber() throws IOException {
            if (consume('-') && index >= text.length()) fail("incomplete number");
            if (consume('0')) {
                if (index < text.length() && Character.isDigit(text.charAt(index))) fail("leading zero in number");
            } else {
                readDigits();
            }
            if (consume('.')) readDigits();
            if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                index++;
                if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) index++;
                readDigits();
            }
        }

        private void readDigits() throws IOException {
            int start = index;
            while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
            if (index == start) fail("expected a digit");
        }

        private void readLiteral(String literal) throws IOException {
            if (!text.regionMatches(index, literal, 0, literal.length())) fail("invalid literal");
            index += literal.length();
        }

        private void skipWhitespace() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return;
                index++;
            }
        }

        private boolean consume(char expected) {
            if (index < text.length() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void require(char expected) throws IOException {
            if (!consume(expected)) fail("expected '" + expected + "'");
        }

        private void fail(String detail) throws IOException {
            throw new IOException("Invalid JSON at character " + index + ": " + detail + ".");
        }
    }
    
    private enum ResourceSyncMode {
        SMART,
        FULL
    }
    
    private static final class ResourceArchiveCandidate {
        final String url;
        final String branch;
        
        ResourceArchiveCandidate(String url, String branch) {
            this.url = url;
            this.branch = branch;
        }
    }
    
    private static final class ResourceArchiveDownload {
        final Path zipPath;
        final String url;
        final String branch;
        
        ResourceArchiveDownload(Path zipPath, String url, String branch) {
            this.zipPath = zipPath;
            this.url = url;
            this.branch = branch;
        }
    }
    
    private static final class ResourceSyncResult {
        boolean success;
        String sourceUrl;
        String sourceBranch;
        String sourceCommit;
        ResourceSyncMode mode;
        boolean checkDeferred;
        int copiedFiles;
        int langFilesRefreshed;
        int missingFilesCopied;
        int skippedExistingFiles;
        int changedFilesDownloaded;
        int addedFilesDownloaded;
        int removedFiles;
        int unchangedFiles;
        final List<String> missingAssetDetails = new ArrayList<String>();
        final List<String> refreshedLanguageDetails = new ArrayList<String>();
        final List<String> changedAssetDetails = new ArrayList<String>();
        final List<String> removedAssetDetails = new ArrayList<String>();
        int suppressedMissingDetails;
        int suppressedLanguageDetails;
        int suppressedChangedDetails;
        int suppressedRemovedDetails;
        final List<String> attempts = new ArrayList<String>();
        final List<String> errors = new ArrayList<String>();
        
        void addMissingAssetDetail(String path) {
            if (path == null) return;
            if (missingAssetDetails.size() < RESOURCE_SYNC_DETAIL_LOG_LIMIT) {
                missingAssetDetails.add(path);
            } else {
                suppressedMissingDetails++;
            }
        }
        
        void addRefreshedLanguageDetail(String path) {
            if (path == null) return;
            if (refreshedLanguageDetails.size() < RESOURCE_SYNC_DETAIL_LOG_LIMIT) {
                refreshedLanguageDetails.add(path);
            } else {
                suppressedLanguageDetails++;
            }
        }

        void addChangedAssetDetail(String path) {
            if (path == null) return;
            if (changedAssetDetails.size() < RESOURCE_SYNC_DETAIL_LOG_LIMIT) {
                changedAssetDetails.add(path);
            } else {
                suppressedChangedDetails++;
            }
        }

        void addRemovedAssetDetail(String path) {
            if (path == null) return;
            if (removedAssetDetails.size() < RESOURCE_SYNC_DETAIL_LOG_LIMIT) {
                removedAssetDetails.add(path);
            } else {
                suppressedRemovedDetails++;
            }
        }
        
        String describeFailure() {
            StringBuilder sb = new StringBuilder();
            if (!attempts.isEmpty()) {
                sb.append("Attempts: ");
                for (int i = 0; i < attempts.size(); i++) {
                    if (i > 0) sb.append(" | ");
                    sb.append(attempts.get(i));
                }
            }
            if (!errors.isEmpty()) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append("Errors: ");
                for (int i = 0; i < errors.size(); i++) {
                    if (i > 0) sb.append(" | ");
                    sb.append(errors.get(i));
                }
            }
            return sb.toString();
        }
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
            String serverJarRegex,
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
        ReleaseAsset jarAsset = selectOptionalAsset(latest.assets, jarRegex);
        if (jarAsset == null) {
            System.out.println("[mod-updater] No patch jar asset matched jarRegex '" + jarRegex + "' in repo " + repo + "; skipping client patch install for this release.");
        }

        ReleaseAsset serverJarAsset = selectOptionalAsset(latest.assets, serverJarRegex);
        if (serverJarRegex != null && serverJarAsset == null) {
            System.out.println("[mod-updater] No server jar asset matched serverJarRegex '" + serverJarRegex + "' in repo " + repo + "; skipping LAN server install for this release.");
        }
        ReleaseAsset assetsZip = assetsRegex != null ? selectAsset(latest.assets, assetsRegex) : null;
        boolean upToDate = isUpToDate(minecraftDir, instanceRoot, mode, jarRegex, jarAsset, serverJarAsset, latest.tag, jarmodName);

        BranchContext ctx = new BranchContext();
        ctx.repo = repo;
        ctx.beta = useBeta && betaRepo != null && !betaRepo.isEmpty();
        ctx.latest = latest;
        ctx.jarAsset = jarAsset;
        ctx.serverJarAsset = serverJarAsset;
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

        // The application class loader keeps this JAR open on Windows. Trying to
        // replace it from here cannot succeed, and deleting the pending files in
        // that failure path loses the already-downloaded update. Leave promotion
        // to LauncherUpdatePromoter, which Prism runs after this JVM exits.
        if (isWindows()) {
            System.out.println("[mod-updater] Staged launcher update is waiting for the Prism post-exit promoter.");
            return;
        }

        String stagedVersion = readPendingVersionTag(jarPath);

        try {
            Files.move(pending, jarPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            System.err.println("[mod-updater] Failed to apply staged launcher update; pending files were preserved: " + ex.getMessage());
            return;
        }

        try {
            if (stagedVersion != null) {
                writeLauncherVersionMarker(jarPath, stagedVersion);
                writeLauncherVersionJson(instanceRoot, stagedVersion);
            }
        } catch (IOException ex) {
            System.err.println("[mod-updater] Launcher jar was updated, but its version marker could not be written: " + ex.getMessage());
        }

        try { Files.deleteIfExists(pending); } catch (IOException ignored) {}
        Path pendingVersion = pendingVersionMarkerPath(jarPath);
        if (pendingVersion != null) {
            try { Files.deleteIfExists(pendingVersion); } catch (IOException ignored) {}
        }
    }

    private static final class ResourceHttpStatusException extends IOException {
        final int statusCode;
        final String retryAfter;
        final String rateLimitRemaining;
        final String rateLimitReset;
        final String requestId;

        ResourceHttpStatusException(
                int statusCode,
                String message,
                String retryAfter,
                String rateLimitRemaining,
                String rateLimitReset,
                String requestId) {
            super(message);
            this.statusCode = statusCode;
            this.retryAfter = retryAfter;
            this.rateLimitRemaining = rateLimitRemaining;
            this.rateLimitReset = rateLimitReset;
            this.requestId = requestId;
        }

        boolean isRateLimitExhausted() {
            return "0".equals(rateLimitRemaining) || (retryAfter != null && retryAfter.length() > 0);
        }
    }

    private static final class ResourceInstallException extends IOException {
        ResourceInstallException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class ResourceArchiveVerificationException extends IOException {
        ResourceArchiveVerificationException(String message) {
            super(message);
        }

        ResourceArchiveVerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class PreparedResourcePackState {
        final ResourcePackManifest previous;
        final ResourcePackRef remoteRef;
        final ResourcePackRemoteTree remote;

        PreparedResourcePackState(
                ResourcePackManifest previous,
                ResourcePackRef remoteRef,
                ResourcePackRemoteTree remote) {
            this.previous = previous;
            this.remoteRef = remoteRef;
            this.remote = remote;
        }
    }

    private static final class ResourceDownloadSession {
        private final Map<String, String> disabledHosts = new HashMap<String, String>();

        synchronized String disabledReason(String url) {
            return disabledHosts.get(resourceRequestHost(url));
        }

        synchronized boolean disable(String url, String reason) {
            String host = resourceRequestHost(url);
            if (disabledHosts.containsKey(host)) return false;
            disabledHosts.put(host, reason);
            return true;
        }
    }

    private static LauncherUpdateState checkLauncherUpdate(
            String repo,
            String assetRegex,
            String promoterAssetRegex,
            Path launcherJar,
            Path instanceRoot) {
        LauncherUpdateState state = new LauncherUpdateState();
        state.launcherJar = launcherJar;
        state.promoterJar = launcherPromoterPath(launcherJar);
        if (repo == null || repo.isEmpty() || launcherJar == null) return state;
        try {
            LatestRelease latest = fetchLatestRelease(repo);
            ReleaseAsset asset = selectOptionalAsset(latest.assets, assetRegex != null ? assetRegex : ".*\\.jar");
            ReleaseAsset promoterAsset = selectOptionalAsset(latest.assets, promoterAssetRegex);

            state.latest = latest;
            state.asset = asset;
            state.promoterAsset = promoterAsset;

            if (asset == null) {
                System.err.println("[mod-updater] No launcher asset matched regex '" + assetRegex + "'.");
            } else {
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
                state.currentVersion = currentVersion;
                state.updateAvailable = needsUpdate;
                if (!needsUpdate && latest.tag != null) {
                    try {
                        writeLauncherVersionMarker(launcherJar, latest.tag);
                        writeLauncherVersionJson(instanceRoot, latest.tag);
                    } catch (IOException ignored) {}
                }
            }

            if (promoterAsset != null && state.promoterJar != null) {
                String promoterVersion = detectLauncherPromoterVersion(state.promoterJar, instanceRoot);
                boolean promoterNeedsUpdate = !Files.isRegularFile(state.promoterJar)
                        || promoterVersion == null
                        || latest.tag == null
                        || !latest.tag.equals(promoterVersion);
                state.promoterCurrentVersion = promoterVersion;
                state.promoterUpdateAvailable = promoterNeedsUpdate;
                if (!promoterNeedsUpdate && latest.tag != null) {
                    try {
                        writeLauncherVersionMarker(state.promoterJar, latest.tag);
                        writeLauncherPromoterVersionJson(instanceRoot, latest.tag);
                    } catch (IOException ignored) {}
                }
            }
        } catch (Throwable t) {
            System.err.println("[mod-updater] Launcher self-update check failed: " + t.getMessage());
        }
        return state;
    }

    private static Lwjgl3PatchUpdateState checkLwjgl3PatchUpdate(
            String repo,
            String assetRegex,
            Path instanceRoot) {
        Lwjgl3PatchUpdateState state = new Lwjgl3PatchUpdateState();
        state.patchFile = lwjgl3PatchPath(instanceRoot);
        state.currentVersion = readLwjgl3PatchVersion(state.patchFile);
        if (repo == null || repo.trim().length() == 0 || assetRegex == null || instanceRoot == null) {
            return state;
        }

        try {
            LatestRelease latest = fetchLatestRelease(repo.trim());
            ReleaseAsset asset = selectExactOptionalAsset(latest.assets, assetRegex);
            state.latest = latest;
            state.asset = asset;
            if (asset == null) {
                System.err.println("[mod-updater] No LWJGL3 patch asset matched regex '" + assetRegex
                        + "' in repo " + repo + ".");
                return state;
            }

            boolean localPatchValid = false;
            if (state.patchFile != null && Files.isRegularFile(state.patchFile)) {
                try {
                    state.currentVersion = validateLwjgl3Patch(state.patchFile);
                    localPatchValid = true;
                } catch (IOException invalidLocalPatch) {
                    System.err.println("[mod-updater] Installed org.lwjgl.json is invalid: "
                            + invalidLocalPatch.getMessage());
                }
            }

            InstalledMarker marker = localPatchValid ? readMarker(state.patchFile) : null;
            boolean releaseMatches = marker != null
                    && equalsSafe(marker.tag, latest.tag)
                    && equalsSafe(marker.assetName, asset.name);
            state.updateAvailable = !localPatchValid || !releaseMatches;
            if (state.updateAvailable) {
                System.out.println("[mod-updater] LWJGL3 component patch update available"
                        + (state.currentVersion != null ? " (installed " + state.currentVersion + ")" : "")
                        + (latest.tag != null ? ": release " + latest.tag : "."));
            }
        } catch (Throwable t) {
            // This auxiliary update source must not prevent the game updater from
            // opening when GitHub is temporarily unavailable.
            System.err.println("[mod-updater] LWJGL3 patch update check failed: " + t.getMessage());
        }
        return state;
    }

    private static Path lwjgl3PatchPath(Path instanceRoot) {
        if (instanceRoot == null) return null;
        return instanceRoot.resolve("patches").resolve("org.lwjgl.json");
    }

    private static String readLwjgl3PatchVersion(Path patchFile) {
        if (patchFile == null || !Files.isRegularFile(patchFile)) return null;
        try {
            String json = new String(Files.readAllBytes(patchFile), StandardCharsets.UTF_8);
            return extractString(json, "\\\"version\\\"\\s*:\\s*\\\"(.*?)\\\"");
        } catch (IOException ex) {
            return null;
        }
    }

    private static Long fetchRemoteContentLength(String url) throws IOException {
        HttpURLConnection conn = openHttpConnection(url, HTTP_TIMEOUT_MS, HTTP_TIMEOUT_MS, "ModUpdaterGUI/1.0");
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

    private static Path launcherPromoterPath(Path launcherJar) {
        if (launcherJar == null || launcherJar.getParent() == null) return null;
        return launcherJar.resolveSibling("launcher-promoter.jar");
    }

    private static String detectLauncherPromoterVersion(Path promoterJar, Path instanceRoot) {
        String jsonVersion = readVersionJsonValue(instanceRoot, "promoter");
        if (jsonVersion != null && !jsonVersion.isEmpty()) return jsonVersion.trim();
        String marker = readLauncherVersionMarker(promoterJar);
        return marker != null && !marker.isEmpty() ? marker.trim() : null;
    }

    private static String readLauncherVersionJson(Path instanceRoot) {
        return readVersionJsonValue(instanceRoot, "launcher");
    }

    private static String readVersionJsonValue(Path instanceRoot, String key) {
        Path jsonPath = launcherVersionJsonPath(instanceRoot);
        if (jsonPath == null || key == null || !Files.isRegularFile(jsonPath)) return null;
        try {
            String json = new String(Files.readAllBytes(jsonPath), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            if (m.find()) {
                return m.group(1);
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static void writeLauncherVersionJson(Path instanceRoot, String version) throws IOException {
        writeVersionJsonValue(instanceRoot, "launcher", version);
    }

    private static void writeLauncherPromoterVersionJson(Path instanceRoot, String version) throws IOException {
        writeVersionJsonValue(instanceRoot, "promoter", version);
    }

    private static void writeVersionJsonValue(Path instanceRoot, String key, String version) throws IOException {
        if (instanceRoot == null || key == null || version == null) return;
        Path jsonPath = launcherVersionJsonPath(instanceRoot);
        if (jsonPath == null) return;
        Path parent = jsonPath.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent);
        }
        String launcherVersion = "launcher".equals(key) ? version : readVersionJsonValue(instanceRoot, "launcher");
        String promoterVersion = "promoter".equals(key) ? version : readVersionJsonValue(instanceRoot, "promoter");
        String newline = System.getProperty("line.separator", "\n");
        StringBuilder sb = new StringBuilder();
        sb.append("{").append(newline);
        if (launcherVersion != null) {
            sb.append("  \"launcher\": \"").append(launcherVersion).append("\"");
            if (promoterVersion != null) sb.append(",");
            sb.append(newline);
        }
        if (promoterVersion != null) {
            sb.append("  \"promoter\": \"").append(promoterVersion).append("\"").append(newline);
        }
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
     * Loads news HTML into the embedded pane using the updater's TLS-compatible
     * HTTP client, then falls back to release notes if loading fails.
     */
    private static void loadNewsPage(final JEditorPane newsPane, final String newsUrl, final LatestRelease fallbackLatest) {
        if (newsPane == null) return;
        
        String url = newsUrl != null ? newsUrl.trim() : "";
        if (url.isEmpty()) {
            setNewsHtml(newsPane, buildReleaseHtml(fallbackLatest, null), null);
            newsPane.setCaretPosition(0);
            return;
        }
        
        setNewsHtml(newsPane, "<html><body style='background:#101010;color:#d0d0d0;font-family:sans-serif;'>Loading news...</body></html>", null);
        newsPane.setCaretPosition(0);
        
        final String targetUrl = url;
        Thread loader = new Thread(new Runnable() {
            public void run() {
                String html;
                URL baseUrl = null;
                Exception loadError = null;
                try {
                    NewsPage page = fetchNewsPage(targetUrl);
                    html = page.html;
                    baseUrl = page.baseUrl;
                    if (html == null || html.trim().isEmpty()) {
                        throw new IOException("News page returned empty content.");
                    }
                } catch (Exception ex) {
                    loadError = ex;
                    html = buildReleaseHtml(fallbackLatest, ex);
                }
                
                final String finalHtml = html;
                final URL finalBaseUrl = baseUrl;
                final Exception finalLoadError = loadError;
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        setNewsHtml(newsPane, finalHtml, finalBaseUrl);
                        newsPane.setCaretPosition(0);
                        if (finalLoadError != null) {
                            System.err.println("[mod-updater] News load failed for " + targetUrl + ": " + finalLoadError.getMessage());
                        }
                    }
                });
            }
        }, "ModUpdater-NewsLoad");
        loader.setDaemon(true);
        loader.start();
    }
    
    private static NewsPage fetchNewsPage(String newsUrl) throws IOException {
        HttpURLConnection conn = openHttpConnection(newsUrl, HTTP_TIMEOUT_MS, HTTP_TIMEOUT_MS, "ModUpdaterGUI/1.0");
        conn.setRequestMethod("GET");
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
        
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            InputStream err = conn.getErrorStream();
            String body = err != null ? readAll(err) : "";
            throw new IOException("News page HTTP " + code + " " + truncateErrorBody(body));
        }
        
        String html = readAll(conn.getInputStream());
        return new NewsPage(html, conn.getURL());
    }
    
    private static void setNewsHtml(JEditorPane newsPane, String html, URL baseUrl) {
        String safeHtml = html != null ? html : "";
        try {
            EditorKit kit = newsPane.getEditorKit();
            if (kit instanceof HTMLEditorKit) {
                HTMLEditorKit htmlKit = (HTMLEditorKit) kit;
                HTMLDocument doc = (HTMLDocument) htmlKit.createDefaultDocument();
                if (baseUrl != null) {
                    doc.setBase(baseUrl);
                }
                newsPane.setDocument(doc);
                htmlKit.read(new StringReader(safeHtml), doc, 0);
            } else {
                newsPane.setText(safeHtml);
            }
        } catch (Exception ignored) {
            newsPane.setText(safeHtml);
        }
    }

    private static final class ResourcePackManifest {
        String repo;
        String branch;
        String commit;
        long checkedAt;
        final Map<String, String> files = new LinkedHashMap<String, String>();

        boolean matches(String expectedRepo, String expectedBranch) {
            return equalsSafe(repo, expectedRepo) && equalsSafe(branch, expectedBranch);
        }
    }

    private static final class ResourcePackRemoteTree {
        String commit;
        final Map<String, String> files = new LinkedHashMap<String, String>();
    }

    private static final class ResourcePackRef {
        final String branch;
        final String commit;

        ResourcePackRef(String branch, String commit) {
            this.branch = branch;
            this.commit = commit;
        }
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
            final String serverJarRegex,
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
                // Prism continues the instance launch when a pre-launch command
                // exits successfully. Handle the close button ourselves so closing
                // this launcher means "cancel this instance launch", not "Play".
                frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
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
                // Vanilla puts the divider on the news pane itself: a two-pixel
                // black MatteBorder immediately above the textured login panel.
                scroll.setBorder(new MatteBorder(0, 0, 2, 0, Color.BLACK));
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
                JPanel updatePage = new JPanel(new BorderLayout());
                updatePage.setOpaque(false);
                updatePage.add(progressCanvas, BorderLayout.CENTER);
                cardPanel.add(updatePage, "update");

                // Build the navigation strip with the same fixed geometry used by
                // the vanilla launcher's TexturedPanel / LogoPanel combination.
                final LegacyButton updateLauncherButton = new LegacyButton("Update Launcher");
                final LegacyButton optionsButton = new LegacyButton("Options");
                optionsButton.putClientProperty("primary", Boolean.TRUE);
                final LegacyButton playButton = new LegacyButton("Play");

                Image launcherLogo = null;
                try {
                    launcherLogo = loadLauncherLogoImage(minecraftDir);
                } catch (Throwable ignored) {
                }

                final LauncherNavPanel bottomBg = new LauncherNavPanel(
                        bgPath,
                        launcherLogo,
                        updateLauncherButton,
                        optionsButton,
                        playButton);
                root.add(bottomBg, BorderLayout.SOUTH);

                boolean hasLauncherUpdate = launcherState != null
                        && launcherState.launcherUpdate != null
                        && hasLauncherSelfUpdate(launcherState.launcherUpdate);
                updateLauncherButton.setVisible(hasLauncherUpdate);

                // Load the nested patch notes "web page"
                loadNewsPage(newsPane, newsUrl, currentLatest(launcherState));
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
                        loadNewsPage(newsPane, newsUrl, currentLatest(launcherState));
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
                        if (startMandatoryLauncherSelfUpdate(frame, launcherState, cardPanel, cards, bottomBg, root, progressCanvas)) {
                            return;
                        }
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
                                        ResourceSyncResult syncResult = syncResourcePack(
                                                launcherState.resourcePackRepo,
                                                currentResourcePackBranch(launcherState),
                                                launcherState.minecraftDir,
                                                ResourceSyncMode.SMART,
                                                false,
                                                launcherState.resourcePackCheckIntervalMs,
                                                launcherState.resourcePackArchiveMirrorUrl);
                                        logResourceSyncResult(syncResult);
                                    }
                                    // Install macOS patches if needed
                                    if (launcherState != null) {
                                        installMacOSPatch(launcherState.instanceRoot);
                                        installNetMinecraftJsonPatch(launcherState.instanceRoot);
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
                                    serverJarRegex,
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
                        if (startMandatoryLauncherSelfUpdate(frame, launcherState, cardPanel, cards, bottomBg, root, progressCanvas)) {
                            return;
                        }
                        cards.show(cardPanel, "update");
                        final ProgressUI ui = new EmbeddedProgressUI(progressCanvas);
                        Thread t = new Thread(new Runnable() {
                            public void run() {
                                PreparedLwjgl3Patch preparedLwjgl3Patch = null;
                                try {
                                    BranchContext ctx = fetchBranchState(
                                            launcherState != null && launcherState.useBetaUpdates,
                                            launcherState != null ? launcherState.releaseRepo : null,
                                            launcherState != null ? launcherState.betaRepo : null,
                                            jarRegex,
                                            serverJarRegex,
                                            assetsRegex,
                                            minecraftDir,
                                            instanceRoot,
                                            mode,
                                            jarmodName);

                                    boolean forceResync = launcherState != null && launcherState.forceUpdate;
                                    preparedLwjgl3Patch = prepareLwjgl3PatchUpdate(
                                            ui,
                                            launcherState != null ? launcherState.lwjgl3PatchUpdate : null,
                                            instanceRoot,
                                            forceResync);
                                    if (forceResync || !ctx.upToDate) {
                                        runUpdate(ui, minecraftDir, instanceRoot, mode, jarRegex, ctx.jarAsset, ctx.serverJarAsset, ctx.assetsZip, ctx.latest, jarmodName);
                                    } else {
                                        ui.log("Game patch and LAN server are already up to date.");
                                    }

                                    boolean lwjgl3Updated = installLwjgl3PatchUpdate(
                                            ui,
                                            preparedLwjgl3Patch);
                                    preparedLwjgl3Patch = null;
                                    if (lwjgl3Updated) {
                                        ui.setPhaseText("Libraries updated - restart Prism Launcher");
                                        ui.progress(100);
                                        System.out.println("[mod-updater] LWJGL component metadata was replaced. "
                                                + "Prism loaded the old metadata before the pre-launch command, so this launch is being cancelled. "
                                                + "Restart Prism Launcher to load the new LWJGL version.");
                                        try {
                                            showLibrariesUpdatedDialog(frame);
                                        } catch (InterruptedException interrupted) {
                                            Thread.currentThread().interrupt();
                                            System.err.println("[mod-updater] Libraries were updated, but the restart notice was interrupted.");
                                        } catch (Exception dialogFailure) {
                                            System.err.println("[mod-updater] Libraries were updated, but the restart notice could not be shown: "
                                                    + dialogFailure.getMessage());
                                        }
                                        System.exit(PRE_LAUNCH_CANCELLED_EXIT_CODE);
                                        return;
                                    }

                                    ResourceSyncMode syncMode = forceResync ? ResourceSyncMode.FULL : ResourceSyncMode.SMART;
                                    boolean strictSync = forceResync;
                                    
                                    // Sync resource pack before launching
                                    ui.setPhaseText(forceResync ? "Force-syncing resource pack..." : "Syncing resource pack...");
                                    if (launcherState != null) {
                                        ResourceSyncResult syncResult = syncResourcePack(
                                                launcherState.resourcePackRepo,
                                                currentResourcePackBranch(launcherState),
                                                launcherState.minecraftDir,
                                                syncMode,
                                                strictSync,
                                                launcherState.resourcePackCheckIntervalMs,
                                                launcherState.resourcePackArchiveMirrorUrl);
                                        logResourceSyncResult(syncResult);
                                        // Install macOS patches if needed
                                        installMacOSPatch(launcherState.instanceRoot);
                                        installNetMinecraftJsonPatch(launcherState.instanceRoot);
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
                                    discardPreparedLwjgl3Patch(preparedLwjgl3Patch);
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
                        if (startMandatoryLauncherSelfUpdate(frame, launcherState, cardPanel, cards, bottomBg, root, progressCanvas)) {
                            return;
                        }
                        if (launcherState != null
                                && launcherState.lwjgl3PatchUpdate != null
                                && launcherState.lwjgl3PatchUpdate.updateAvailable) {
                            JOptionPane.showMessageDialog(
                                    frame,
                                    "This update includes required LWJGL component metadata.\n\n"
                                            + "Install the update, then press Play once more so Prism can reload it.",
                                    "LWJGL update required",
                                    JOptionPane.WARNING_MESSAGE);
                            return;
                        }
                        // The user declined the game update. Do not contact the resource-pack
                        // repository during this launch; refusal applies to the whole update pass.
                        cards.show(cardPanel, "update");
                        root.revalidate();
                        root.repaint();
                        final ProgressUI ui = new EmbeddedProgressUI(progressCanvas);
                        Thread t = new Thread(new Runnable() {
                            public void run() {
                                try {
                                    ui.setPhaseText("Starting Minecraft...");
                                    ui.progress(10);
                                    System.out.println("[mod-updater] Game update declined; skipping resource repository check for this launch.");
                                    // Install macOS patches if needed
                                    if (launcherState != null) {
                                        installMacOSPatch(launcherState.instanceRoot);
                                        installNetMinecraftJsonPatch(launcherState.instanceRoot);
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
                        }, "ModUpdater-DeclinedUpdateLaunch");
                        t.setDaemon(false);
                        t.start();
                    }
                });

                // A user-initiated close cancels Prism's pre-launch pipeline. A
                // programmatic dispose (used by launcher self-update) only releases
                // the latch and lets that update thread control the eventual status.
                frame.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosing(java.awt.event.WindowEvent e) {
                        System.out.println("[mod-updater] Launcher closed without Play; cancelling Prism pre-launch.");
                        System.exit(PRE_LAUNCH_CANCELLED_EXIT_CODE);
                    }

                    @Override
                    public void windowClosed(java.awt.event.WindowEvent e) {
                        windowClosedLatch.countDown();
                    }
                });

                frame.setVisible(true);
                
                // Note: The game now automatically re-launches itself with -XstartOnFirstThread
                // on macOS when needed, so we no longer need to warn about JVM arguments.
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

    private static boolean startMandatoryLauncherSelfUpdate(
            final JFrame frame,
            final LauncherState launcherState,
            final JPanel cardPanel,
            final CardLayout cards,
            final JComponent bottomBg,
            final JComponent root,
            final ProgressCanvas progressCanvas) {

        if (!hasBlockingLauncherSelfUpdate(launcherState)) {
            return false;
        }

        cards.show(cardPanel, "update");
        bottomBg.setVisible(false);
        bottomBg.revalidate();
        root.revalidate();
        root.repaint();

        final ProgressUI ui = new EmbeddedProgressUI(progressCanvas);
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    updateLauncherAndRestart(frame, launcherState, ui);
                } catch (Throwable ex) {
                    showError(ex);
                    System.exit(1);
                }
            }
        }, "LauncherSelfUpdateRestart");
        t.setDaemon(false);
        t.start();
        return true;
    }

    private static boolean hasBlockingLauncherSelfUpdate(LauncherState launcherState) {
        if (launcherState == null || launcherState.launcherUpdate == null) return false;
        return hasLauncherSelfUpdate(launcherState.launcherUpdate);
    }

    private static boolean hasLauncherSelfUpdate(LauncherUpdateState update) {
        if (update == null) return false;
        boolean launcherUpdate = update.updateAvailable && update.asset != null;
        boolean promoterUpdate = update.promoterUpdateAvailable && update.promoterAsset != null;
        return launcherUpdate || promoterUpdate;
    }

    private static void updateLauncherAndRestart(JFrame frame, LauncherState launcherState, ProgressUI ui) throws Exception {
        if (launcherState == null || launcherState.launcherUpdate == null) {
            throw new IOException("Launcher update state is unavailable.");
        }
        LauncherUpdateState update = launcherState.launcherUpdate;
        boolean updateLauncher = update.updateAvailable && update.asset != null;
        boolean updatePromoter = update.promoterUpdateAvailable && update.promoterAsset != null;
        if (!updateLauncher && !updatePromoter) return;
        if (updateLauncher && update.launcherJar == null) {
            throw new IOException("Cannot update the launcher because its jar path could not be determined.");
        }

        String latestTag = update.latest != null ? update.latest.tag : null;

        if (updatePromoter) {
            if (update.promoterJar == null) {
                throw new IOException("Cannot update the launcher promoter because its jar path could not be determined.");
            }
            ui.setPhaseText("Downloading launcher promoter update...");
            double promoterEnd = updateLauncher ? 0.25 : 0.75;
            Path promoterDownload = downloadToTemp(
                    ui,
                    update.promoterAsset.url,
                    update.promoterAsset.name,
                    0.0,
                    promoterEnd);
            ui.setPhaseText("Installing launcher promoter update...");
            ui.progress(updateLauncher ? 28 : 82);
            installLauncherPromoterUpdate(update.promoterJar, promoterDownload, latestTag, launcherState.instanceRoot);
            update.promoterUpdateAvailable = false;
            update.promoterCurrentVersion = latestTag != null ? latestTag : update.promoterCurrentVersion;
        }

        if (updateLauncher) {
            ui.setPhaseText("Downloading launcher update...");
            Path download = downloadToTemp(
                    ui,
                    update.asset.url,
                    update.asset.name,
                    updatePromoter ? 0.30 : 0.0,
                    0.75);

            ui.setPhaseText("Installing launcher update...");
            ui.progress(82);
            boolean appliedNow = installLauncherUpdate(update.launcherJar, download, latestTag, launcherState.instanceRoot);
            if (!appliedNow) {
                String promoterStatus = updatePromoter ? " The launcher promoter was updated first." : "";
                throw new IOException("Launcher update was staged because the running launcher jar could not be replaced."
                        + promoterStatus
                        + " The game launch was stopped so old asset-fetching code cannot run. Prism/PrismMC must run launcher-promoter.jar as its Post-exit command; then launch the instance again. If this repeats, verify the Post-exit command and folder write permissions.");
            }

            update.updateAvailable = false;
            update.currentVersion = latestTag != null ? latestTag : update.currentVersion;
        }

        ui.setPhaseText("Restarting launcher...");
        ui.progress(100);
        try { Thread.sleep(250L); } catch (InterruptedException ignored) {}

        disposeFrame(frame);
        int exitCode = restartLauncherAndWait(update.launcherJar, launcherState.launchArgs);
        System.exit(exitCode);
    }

    private static int restartLauncherAndWait(Path launcherJar, String[] args) throws IOException, InterruptedException {
        if (launcherJar == null) {
            throw new IOException("Launcher jar path is unknown.");
        }
        String javaBin = javaExecutablePath();
        List<String> cmd = new ArrayList<String>();
        cmd.add(javaBin);
        cmd.add("-jar");
        cmd.add(launcherJar.toAbsolutePath().toString());
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                cmd.add(args[i]);
            }
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        pb.environment().put("MCOSE_LAUNCHER_RESTARTED_AFTER_UPDATE", "1");
        Process child = pb.start();
        return child.waitFor();
    }

    private static String javaExecutablePath() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.trim().isEmpty()) {
            return "java";
        }
        String exe = isWindows() ? "java.exe" : "java";
        return javaHome + File.separator + "bin" + File.separator + exe;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    private static void disposeFrame(final JFrame frame) {
        if (frame == null) return;
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    frame.dispose();
                }
            });
        } catch (Exception ignored) {}
    }

    private static void showLibrariesUpdatedDialog(final Component parent) throws Exception {
        Runnable showDialog = new Runnable() {
            public void run() {
                JOptionPane.showMessageDialog(
                        parent,
                        "Libraries have been updated. Please restart Prism Launcher.",
                        "Libraries updated",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            showDialog.run();
        } else {
            SwingUtilities.invokeAndWait(showDialog);
        }
    }

    private static void runLauncherSelfUpdate(
            final JFrame frame,
            final LauncherState launcherState,
            final JButton updateButton) {

        if (launcherState == null || launcherState.launcherUpdate == null) return;
        final LauncherUpdateState update = launcherState.launcherUpdate;
        if (!hasLauncherSelfUpdate(update)) return;
        if (update.updateAvailable && update.asset != null && update.launcherJar == null) {
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
                    updateLauncherAndRestart(frame, launcherState, progress);
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
        t.setDaemon(false);
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
        JLabel versionLabel = new JLabel("Launcher version:");
        center.add(versionLabel, c);

        c.gridx = 1;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        JLabel versionValue = new JLabel(launcherVersionText(launcherState));
        center.add(versionValue, c);

        c.gridx = 0;
        c.gridy = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
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

        // Clear Backups row - deletes old .bak files (renamed jars from previous updates)
        c.gridx = 0;
        c.gridy = 3;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(6, 0, 4, 8);
        JLabel backupsLabel = new JLabel("Old version backups:");
        center.add(backupsLabel, c);

        c.gridx = 1;
        final JButton clearBackupsButton = new JButton("Clear Backups");
        final Path finalMinecraftDir = minecraftDir;
        final Path finalInstanceRoot = launcherState != null ? launcherState.instanceRoot : null;
        clearBackupsButton.addActionListener(new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                int deleted = clearBackupFiles(finalMinecraftDir, finalInstanceRoot);
                if (deleted > 0) {
                    JOptionPane.showMessageDialog(dialog,
                            "Deleted " + deleted + " backup file" + (deleted == 1 ? "" : "s") + ".",
                            "Backups Cleared", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(dialog,
                            "No backup files found to delete.",
                            "Backups Cleared", JOptionPane.INFORMATION_MESSAGE);
                }
            }
        });
        center.add(clearBackupsButton, c);
        
        // Fetch Resources row - downloads and validates a full archive before replacing files.
        c.gridx = 0;
        c.gridy = 4;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(6, 0, 4, 8);
        JLabel fetchResourcesLabel = new JLabel("Resources:");
        center.add(fetchResourcesLabel, c);
        
        c.gridx = 1;
        final JButton fetchResourcesButton = new JButton("Fetch Resources");
        fetchResourcesButton.addActionListener(new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (finalMinecraftDir == null) {
                    JOptionPane.showMessageDialog(dialog,
                            "Minecraft directory is unavailable, so resources cannot be fetched.",
                            "Fetch Resources", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                
                int confirm = JOptionPane.showConfirmDialog(
                        dialog,
                        "This will re-download and replace all managed resource files.\nContinue?",
                        "Fetch Resources",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (confirm != JOptionPane.YES_OPTION) {
                    return;
                }
                
                final String repo = launcherState != null && launcherState.resourcePackRepo != null && launcherState.resourcePackRepo.trim().length() > 0
                        ? launcherState.resourcePackRepo
                        : "MinecraftOldschoolEdition/resourcepack";
                final String branch = currentResourcePackBranch(launcherState);
                
                fetchResourcesButton.setEnabled(false);
                fetchResourcesButton.setText("Fetching...");
                
                Thread worker = new Thread(new Runnable() {
                    public void run() {
                        try {
                            ResourceSyncResult syncResult = syncResourcePack(
                                    repo,
                                    branch,
                                    finalMinecraftDir,
                                    ResourceSyncMode.FULL,
                                    true,
                                    DEFAULT_RESOURCE_CHECK_INTERVAL_MINUTES * 60000L,
                                    launcherState != null ? launcherState.resourcePackArchiveMirrorUrl : null);
                            logResourceSyncResult(syncResult);
                            final String resourceSource = syncResult.sourceUrl != null
                                    ? syncResult.sourceUrl
                                    : "the configured resource repository";
                            final String resourceSourceBranch = syncResult.sourceBranch != null
                                    ? syncResult.sourceBranch
                                    : normalizeResourcePackBranch(branch);
                            
                            SwingUtilities.invokeLater(new Runnable() {
                                public void run() {
                                    fetchResourcesButton.setEnabled(true);
                                    fetchResourcesButton.setText("Fetch Resources");
                                    JOptionPane.showMessageDialog(
                                            dialog,
                                            "Resources were fully re-downloaded from " + resourceSource
                                                    + " (" + resourceSourceBranch + ").",
                                            "Fetch Resources",
                                            JOptionPane.INFORMATION_MESSAGE);
                                }
                            });
                        } catch (final Throwable ex) {
                            SwingUtilities.invokeLater(new Runnable() {
                                public void run() {
                                    fetchResourcesButton.setEnabled(true);
                                    fetchResourcesButton.setText("Fetch Resources");
                                    showError(ex);
                                }
                            });
                        }
                    }
                }, "ModUpdater-FetchResources");
                worker.setDaemon(true);
                worker.start();
            }
        });
        center.add(fetchResourcesButton, c);

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
            final String serverJarRegex,
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
                    BranchContext ctx = fetchBranchState(desiredBeta, releaseRepo, betaRepo, jarRegex, serverJarRegex, assetsRegex, minecraftDir, instanceRoot, mode, jarmodName);
                    launcherState.branch = ctx;
                    launcherState.hasUpdate = !ctx.upToDate
                            || (launcherState.lwjgl3PatchUpdate != null
                                    && launcherState.lwjgl3PatchUpdate.updateAvailable);
                    launcherState.useBetaUpdates = desiredBeta;

                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            loadNewsPage(newsPane, newsUrl, ctx.latest);
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
                                  ReleaseAsset jarAsset, ReleaseAsset serverJarAsset, ReleaseAsset assetsZip, LatestRelease latest, String jarmodName) throws Exception {
        if ("mods".equalsIgnoreCase(mode)) {
            if (jarAsset != null) {
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
            } else {
                ui.log("No patch jar in this release; skipping mod update.");
            }
        } else if ("jarmods".equalsIgnoreCase(mode)) {
            if (jarAsset != null) {
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
            } else {
                ui.log("No patch jar in this release; skipping jarmod update.");
            }
        } else if ("clientJar".equalsIgnoreCase(mode)) {
            if (jarAsset != null) {
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
                ui.log("No patch jar in this release; skipping client jar update.");
            }
        } else {
            throw new IllegalArgumentException("Unsupported mode: " + mode);
        }
        installLanServerJar(ui, minecraftDir, serverJarAsset, latest);
        // Assets now extracted from the mod jar itself.
        
        // Note: Bouncy Castle dependency for friends system crypto is optional.
        // The friends system works without it (just without cryptographic verification).
        // Users who want crypto can manually add bcprov-jdk18on-1.78.1.jar as a jarmod.
    }

    private static void installLanServerJar(ProgressUI ui, Path minecraftDir, ReleaseAsset serverJarAsset, LatestRelease latest) throws Exception {
        if (serverJarAsset == null) {
            ui.log("No server jar in this release; skipping LAN server install.");
            return;
        }
        Path lanServerDir = minecraftDir.resolve(LAN_SERVER_DIR_NAME);
        ensureDir(lanServerDir);
        Path dest = lanServerDir.resolve(LAN_SERVER_JAR_NAME);
        Path downloaded = downloadToTemp(ui, serverJarAsset.url, serverJarAsset.name, 0.9, 0.98);
        if (Files.isRegularFile(dest)) {
            Path backup = withUniqueSuffix(dest, ".bak");
            ui.setPhaseText("Backing up LAN server jar...");
            Files.move(dest, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        ui.setPhaseText("Installing LAN server jar...");
        moveOrCopy(downloaded, dest);
        writeMarker(dest, latest, serverJarAsset);
    }

    /**
     * Installs a separately released Prism component patch at
     * instanceRoot/patches/org.lwjgl.json.
     *
     * @return true when the component file was replaced. Prism loads component
     *         metadata before running its pre-launch command, so callers must
     *         cancel this launch and let the next launch reload the new file.
     */
    private static PreparedLwjgl3Patch prepareLwjgl3PatchUpdate(
            ProgressUI ui,
            Lwjgl3PatchUpdateState update,
            Path instanceRoot,
            boolean force) throws Exception {
        if (update == null || update.asset == null || update.latest == null) return null;
        if (!force && !update.updateAvailable) return null;
        if (instanceRoot == null) {
            throw new IllegalArgumentException("LWJGL3 patch update requires an instance root; pass --instanceDir.");
        }
        if (releaseAssetSha256(update.asset) == null) {
            throw new IOException("LWJGL3 release asset has no valid GitHub SHA-256 digest; refusing an unverified component update.");
        }

        ui.setPhaseText("Verifying LWJGL component update...");
        Path downloaded = downloadToTemp(ui, update.asset.url, update.asset.name, 0.0, 0.05);
        Path staged = null;
        try {
            verifyReleaseAssetFile(downloaded, update.asset);
            String lwjglVersion = validateLwjgl3Patch(downloaded);
            Path target = lwjgl3PatchPath(instanceRoot);
            ensureDir(target.getParent());
            staged = Files.createTempFile(target.getParent(), "org.lwjgl.", ".pending");
            Files.copy(downloaded, staged, StandardCopyOption.REPLACE_EXISTING);
            verifyReleaseAssetFile(staged, update.asset);
            return new PreparedLwjgl3Patch(update, target, staged, lwjglVersion);
        } catch (Exception failure) {
            if (staged != null) {
                try { Files.deleteIfExists(staged); } catch (IOException ignored) {}
            }
            throw failure;
        } finally {
            try { Files.deleteIfExists(downloaded); } catch (IOException ignored) {}
        }
    }

    private static boolean installLwjgl3PatchUpdate(
            ProgressUI ui,
            PreparedLwjgl3Patch prepared) throws Exception {
        if (prepared == null) return false;
        Lwjgl3PatchUpdateState update = prepared.update;
        Path target = prepared.target;
        Path staged = prepared.staged;
        Path backup = target.resolveSibling("org.lwjgl.json.bak");
        boolean hadExisting = Files.isRegularFile(target);
        try {
            if (hadExisting) {
                ui.setPhaseText("Backing up LWJGL component metadata...");
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }

            ui.setPhaseText("Installing LWJGL " + prepared.version + " metadata...");
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException noAtomicMove) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException replaceFailure) {
                if (hadExisting && !Files.isRegularFile(target) && Files.isRegularFile(backup)) {
                    try { Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
                }
                throw replaceFailure;
            }
        } finally {
            try { Files.deleteIfExists(staged); } catch (IOException ignored) {}
        }

        writeMarker(target, update.latest, update.asset);
        update.patchFile = target;
        update.currentVersion = prepared.version;
        update.updateAvailable = false;
        ui.log("Installed LWJGL component metadata " + prepared.version + " at " + target);
        return true;
    }

    private static void discardPreparedLwjgl3Patch(PreparedLwjgl3Patch prepared) {
        if (prepared == null || prepared.staged == null) return;
        try { Files.deleteIfExists(prepared.staged); } catch (IOException ignored) {}
    }

    private static String validateLwjgl3Patch(Path patchFile) throws IOException {
        if (patchFile == null || !Files.isRegularFile(patchFile)) {
            throw new IOException("Downloaded LWJGL3 patch is missing.");
        }
        long size = Files.size(patchFile);
        if (size < 256L || size > 10L * 1024L * 1024L) {
            throw new IOException("Downloaded LWJGL3 patch has an implausible size: " + size + " bytes.");
        }

        String json = new String(Files.readAllBytes(patchFile), StandardCharsets.UTF_8);
        JsonSyntaxValidator.validate(json);
        int objectStart = firstNonWhitespace(json, 0);
        if (objectStart < 0 || json.charAt(objectStart) != '{') {
            throw new IOException("Downloaded LWJGL3 patch is not a JSON object.");
        }
        int objectEnd = findMatchingBrace(json, objectStart);
        if (objectEnd < 0 || firstNonWhitespace(json, objectEnd + 1) >= 0) {
            throw new IOException("Downloaded LWJGL3 patch contains incomplete or trailing JSON data.");
        }

        String uid = extractTopLevelJsonString(json, "uid");
        String version = extractTopLevelJsonString(json, "version");
        if (!"org.lwjgl".equals(uid)) {
            throw new IOException("Downloaded LWJGL3 patch has uid '" + uid + "'; expected 'org.lwjgl'.");
        }
        if (findTopLevelJsonMemberValue(json, "conflicts") >= 0) {
            throw new IOException("Downloaded LWJGL3 patch still declares component conflicts; expected the org.lwjgl override payload.");
        }
        if (version == null || !Pattern.compile("3\\.[0-9]+(?:\\.[0-9]+)?(?:[-+._][A-Za-z0-9.-]+)?").matcher(version).matches()) {
            throw new IOException("Downloaded LWJGL3 patch has an invalid LWJGL 3 version: " + version);
        }
        int formatVersionStart = findTopLevelJsonMemberValue(json, "formatVersion");
        if (formatVersionStart < 0
                || !Pattern.compile("1(?:\\s*[,}])").matcher(json.substring(formatVersionStart)).lookingAt()) {
            throw new IOException("Downloaded LWJGL3 patch does not use Prism formatVersion 1.");
        }

        int librariesStart = findTopLevelJsonMemberValue(json, "libraries");
        if (librariesStart >= 0 && json.charAt(librariesStart) != '[') librariesStart = -1;
        int librariesEnd = librariesStart >= 0 ? findMatchingBracket(json, librariesStart) : -1;
        if (librariesStart < 0 || librariesEnd < 0) {
            throw new IOException("Downloaded LWJGL3 patch does not contain a complete libraries array.");
        }
        String libraries = json.substring(librariesStart, librariesEnd + 1);
        if (!Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"org\\.lwjgl:lwjgl(?:[-A-Za-z0-9.]*)?:")
                .matcher(libraries).find()) {
            throw new IOException("Downloaded LWJGL3 patch does not contain LWJGL library coordinates.");
        }
        return version;
    }

    private static int firstNonWhitespace(String text, int start) {
        if (text == null) return -1;
        for (int i = Math.max(0, start); i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) return i;
        }
        return -1;
    }

    private static int findTopLevelJsonMemberValue(String json, String requestedKey) {
        int objectStart = firstNonWhitespace(json, 0);
        if (objectStart < 0 || json.charAt(objectStart) != '{') return -1;
        int depth = 0;
        for (int i = objectStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                int stringEnd = findJsonStringEnd(json, i);
                if (stringEnd < 0) return -1;
                if (depth == 1) {
                    int colon = firstNonWhitespace(json, stringEnd + 1);
                    if (colon >= 0 && json.charAt(colon) == ':') {
                        String key = unescapeJson(json.substring(i + 1, stringEnd));
                        if (requestedKey.equals(key)) {
                            return firstNonWhitespace(json, colon + 1);
                        }
                    }
                }
                i = stringEnd;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return -1;
    }

    private static String extractTopLevelJsonString(String json, String key) {
        int valueStart = findTopLevelJsonMemberValue(json, key);
        if (valueStart < 0 || json.charAt(valueStart) != '"') return null;
        int valueEnd = findJsonStringEnd(json, valueStart);
        if (valueEnd < 0) return null;
        return unescapeJson(json.substring(valueStart + 1, valueEnd));
    }

    private static int findJsonStringEnd(String json, int quoteStart) {
        for (int i = quoteStart + 1; i < json.length(); i++) {
            if (json.charAt(i) == '"' && !isEscaped(json, i)) return i;
        }
        return -1;
    }

    /**
     * Syncs the resource pack from a repository using either smart or full mode.
     *
     * Smart mode:
     * - Checks the lightweight branch commit ref on every normal launch
     * - Defers only the local integrity scan when that commit is unchanged
     * - Downloads only remotely added or changed files
     * - Removes files deleted from the remote tree when they were tracked previously
     *
     * Full mode:
     * - Replaces every asset file from the archive
     *
     * @param repo GitHub repository in owner/repo format
     * @param branch Preferred branch (defaults to main when missing)
     * @param minecraftDir Path to the .minecraft directory
     * @param mode Smart or full resource sync mode
     * @param strict Whether sync failure should abort launch
     * @return Sync result containing counts and attempted URLs
     * @throws IOException when strict mode is enabled and sync fails
     */
    private static ResourceSyncResult syncResourcePack(String repo, String branch, Path minecraftDir, ResourceSyncMode mode, boolean strict) throws IOException {
        return syncResourcePack(repo, branch, minecraftDir, mode, strict,
                DEFAULT_RESOURCE_CHECK_INTERVAL_MINUTES * 60000L);
    }

    private static ResourceSyncResult syncResourcePack(
            String repo,
            String branch,
            Path minecraftDir,
            ResourceSyncMode mode,
            boolean strict,
            long checkIntervalMs) throws IOException {
        return syncResourcePack(repo, branch, minecraftDir, mode, strict, checkIntervalMs, null);
    }

    private static ResourceSyncResult syncResourcePack(
            String repo,
            String branch,
            Path minecraftDir,
            ResourceSyncMode mode,
            boolean strict,
            long checkIntervalMs,
            String archiveMirrorUrl) throws IOException {
        ResourceSyncResult result = new ResourceSyncResult();
        result.mode = mode != null ? mode : ResourceSyncMode.SMART;
        
        if (repo == null || repo.trim().isEmpty()) {
            if (strict) {
                System.err.println("[mod-updater] Resource sync: missing resourcePackRepo; strict mode requires a full sync.");
                throw new IOException("Resource pack repository is not configured.");
            }
            System.err.println("[mod-updater] Resource download skipped: resourcePackRepo is not configured.");
            result.success = true;
            return result;
        }
        if (minecraftDir == null) {
            if (strict) {
                System.err.println("[mod-updater] Resource sync: missing minecraftDir; strict mode requires a full sync.");
                throw new IOException("Minecraft directory is not available for resource sync.");
            }
            System.err.println("[mod-updater] Resource download skipped: minecraftDir is unavailable.");
            result.success = true;
            return result;
        }
        
        String repoTrimmed = repo.trim();
        String effectiveBranch = normalizeResourcePackBranch(branch);
        IOException incrementalFailure = null;
        boolean archiveRecovery = false;
        System.out.println("[mod-updater] Syncing resource pack from: " + repoTrimmed + " (branch=" + effectiveBranch + ", mode=" + result.mode + ")");
        if (result.mode == ResourceSyncMode.SMART) {
            System.out.println("[mod-updater] Incremental sync policy: download remote additions/changes and remove tracked remote deletions only.");
            try {
                return syncResourcePackIncrementally(
                        repoTrimmed,
                        effectiveBranch,
                        minecraftDir,
                        Math.max(0L, checkIntervalMs),
                        result);
            } catch (IOException e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                result.errors.add(msg);
                if (isResourceSyncInterrupted(e)) {
                    if (strict) {
                        throw new IOException("Incremental resource pack sync was interrupted: " + msg, e);
                    }
                    System.err.println("[mod-updater] Warning: Resource sync was interrupted; archive recovery was not started.");
                    return result;
                }
                incrementalFailure = e;
                archiveRecovery = true;
                System.err.println("[mod-updater] Incremental resource sync failed: " + msg);
                System.err.println("[mod-updater] Trying a full archive contingency before giving up.");
            }
        } else {
            System.out.println("[mod-updater] Full sync policy: re-download and replace all resource-pack assets.");
        }
        
        ResourceArchiveDownload archive = null;
        try {
            archive = downloadResourcePackArchive(
                    repoTrimmed,
                    effectiveBranch,
                    archiveMirrorUrl,
                    minecraftDir,
                    archiveRecovery ? ResourceSyncMode.FULL : result.mode,
                    result);
            if (archive == null || archive.zipPath == null) {
                String detail = result.describeFailure();
                String msg = "Failed to download resource pack archive.";
                if (strict) {
                    throw new IOException(msg + (detail.length() > 0 ? " " + detail : ""), incrementalFailure);
                }
                System.err.println("[mod-updater] Warning: " + msg + (detail.length() > 0 ? " " + detail : ""));
                return result;
            }
            
            result.sourceUrl = archive.url;
            result.sourceBranch = archive.branch;
            result.success = true;
            return result;
        } catch (IOException e) {
            if (incrementalFailure != null && e != incrementalFailure) {
                e.addSuppressed(incrementalFailure);
            }
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            result.errors.add(msg);
            if (strict) {
                String detail = result.describeFailure();
                throw new IOException("Resource pack sync failed in strict mode: " + msg + (detail.length() > 0 ? " | " + detail : ""), e);
            }
            System.err.println("[mod-updater] Warning: Failed to sync resource pack: " + msg);
            return result;
        } finally {
            if (archive != null && archive.zipPath != null) {
                try { Files.deleteIfExists(archive.zipPath); } catch (IOException ignored) {}
            }
        }
    }

    private static ResourceSyncResult syncResourcePackIncrementally(
            String repo,
            String branch,
            Path minecraftDir,
            long checkIntervalMs,
            ResourceSyncResult result) throws IOException {
        Path manifestPath = resourcePackManifestPath(minecraftDir);
        ResourcePackManifest previous = readResourcePackManifest(manifestPath);
        boolean hasTrackedManifest = previous != null && equalsSafe(previous.repo, repo);
        boolean hasMatchingManifest = previous != null && previous.matches(repo, branch);
        long now = System.currentTimeMillis();

        result.sourceBranch = branch;
        ResourcePackRef remoteRef = fetchResourcePackRef(repo, branch, result);
        branch = remoteRef.branch;
        hasMatchingManifest = previous != null && previous.matches(repo, branch);
        hasTrackedManifest = previous != null && equalsSafe(previous.repo, repo);
        result.sourceBranch = branch;
        String commit = remoteRef.commit;
        result.sourceCommit = commit;
        System.out.println("[mod-updater] Resource commit check: tracked="
                + (hasMatchingManifest ? shortSha(previous.commit) : "none")
                + ", remote=" + shortSha(commit) + ", branch=" + branch + ".");

        boolean commitUnchanged = hasMatchingManifest && equalsSafe(previous.commit, commit);
        if (commitUnchanged
                && checkIntervalMs > 0L
                && previous.checkedAt > 0L
                && now >= previous.checkedAt
                && now - previous.checkedAt < checkIntervalMs) {
            result.success = true;
            result.checkDeferred = true;
            result.unchangedFiles = previous.files.size();
            long remainingMs = checkIntervalMs - (now - previous.checkedAt);
            System.out.println("[mod-updater] Resource download skipped: remote commit is unchanged; local integrity scan"
                    + " is not due for another " + Math.max(1L, (remainingMs + 59999L) / 60000L)
                    + " minute(s). Only commit metadata was fetched.");
            return result;
        }

        ResourcePackRemoteTree remote;
        if (commitUnchanged) {
            List<String> missingTrackedFiles = findMissingTrackedResourceFiles(previous, minecraftDir);
            if (missingTrackedFiles.isEmpty()) {
                previous.checkedAt = now;
                writeResourcePackManifest(manifestPath, previous);
                result.success = true;
                result.unchangedFiles = previous.files.size();
                System.out.println("[mod-updater] Resource download skipped: repository commit is unchanged ("
                        + shortSha(commit) + "); no resource files need updating.");
                return result;
            }
            remote = new ResourcePackRemoteTree();
            remote.commit = commit;
            remote.files.putAll(previous.files);
            System.out.println("[mod-updater] Remote commit is unchanged, but " + missingTrackedFiles.size()
                    + " tracked local resource file(s) are missing and will be restored.");
        } else {
            remote = fetchResourcePackTree(repo, branch, commit, result);
        }

        Map<String, Boolean> filesToDownload = new LinkedHashMap<String, Boolean>();
        for (Map.Entry<String, String> entry : remote.files.entrySet()) {
            String relativePath = entry.getKey();
            String remoteSha = entry.getValue();
            Path destination = resolveResourcePackDestination(minecraftDir, relativePath);
            if (destination == null) continue;

            String previousSha = hasTrackedManifest ? previous.files.get(relativePath) : null;
            boolean exists = Files.isRegularFile(destination);
            boolean added = previousSha == null;
            boolean needsDownload;

            if (hasTrackedManifest) {
                needsDownload = !exists || !equalsSafe(previousSha, remoteSha);
            } else if (!exists) {
                needsDownload = true;
            } else {
                needsDownload = !equalsSafe(computeGitBlobSha(destination), remoteSha);
            }

            if (needsDownload) {
                filesToDownload.put(relativePath, Boolean.valueOf(added));
            } else {
                result.unchangedFiles++;
            }
        }

        if (filesToDownload.isEmpty()) {
            System.out.println("[mod-updater] Resource download skipped: repository scan found no added, changed, or missing files"
                    + " (unchanged=" + result.unchangedFiles + ").");
        } else {
            System.out.println("[mod-updater] Resource download required for " + filesToDownload.size() + " file(s):");
            for (Map.Entry<String, Boolean> change : filesToDownload.entrySet()) {
                String relativePath = change.getKey();
                Path destination = resolveResourcePackDestination(minecraftDir, relativePath);
                String reason = Boolean.TRUE.equals(change.getValue())
                        ? "ADDED"
                        : destination != null && Files.isRegularFile(destination) ? "CHANGED" : "RESTORE";
                System.out.println("[mod-updater]   [" + reason + "] " + relativePath
                        + " (blob=" + shortSha(remote.files.get(relativePath)) + ")");
            }
        }

        if (!filesToDownload.isEmpty()) {
            final List<Map.Entry<String, Boolean>> changes =
                    new ArrayList<Map.Entry<String, Boolean>>(filesToDownload.entrySet());
            final ResourceDownloadSession downloadSession = new ResourceDownloadSession();
            final Path resourcesRoot = minecraftDir.resolve("resources").normalize();
            int workerCount = Math.min(RESOURCE_DOWNLOAD_THREADS, changes.size());
            System.out.println("[mod-updater] Fetching resource files with " + workerCount
                    + " parallel download thread(s).");

            List<Callable<Boolean>> downloadTasks = new ArrayList<Callable<Boolean>>(changes.size());
            for (Map.Entry<String, Boolean> change : changes) {
                final String relativePath = change.getKey();
                final String blobSha = remote.files.get(relativePath);
                final Path destination = resolveResourcePackDestination(minecraftDir, relativePath);
                if (destination == null) {
                    throw new IOException("Unsafe resource path returned by GitHub: " + relativePath);
                }
                final boolean existedBefore = Files.isRegularFile(destination);
                downloadTasks.add(new Callable<Boolean>() {
                    public Boolean call() throws IOException {
                        System.out.println("[mod-updater] Downloading resource file: " + relativePath);
                        String sourceHost = downloadResourcePackBlob(
                                repo,
                                commit,
                                relativePath,
                                blobSha,
                                destination,
                                result,
                                downloadSession,
                                resourcesRoot);
                        System.out.println("[mod-updater] Downloaded resource file: " + relativePath
                                + " (blob=" + shortSha(blobSha) + ", source=" + sourceHost + ")");
                        return Boolean.valueOf(existedBefore);
                    }
                });
            }

            ExecutorService downloadExecutor = Executors.newFixedThreadPool(workerCount, new ThreadFactory() {
                private int sequence = 1;

                public synchronized Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "ModUpdater-ResourceFetch-" + sequence++);
                    thread.setDaemon(true);
                    return thread;
                }
            });
            boolean downloadsFinished = false;
            boolean restoreInterrupt = false;
            try {
                List<Future<Boolean>> downloads = downloadExecutor.invokeAll(downloadTasks);
                downloadsFinished = true;
                IOException firstFailure = null;
                for (int i = 0; i < downloads.size(); i++) {
                    Map.Entry<String, Boolean> change = changes.get(i);
                    String relativePath = change.getKey();
                    try {
                        boolean existedBefore = downloads.get(i).get().booleanValue();
                        result.copiedFiles++;

                        if (Boolean.TRUE.equals(change.getValue()) || !existedBefore) {
                            result.addedFilesDownloaded++;
                            result.missingFilesCopied++;
                            result.addMissingAssetDetail(relativePath);
                        } else {
                            result.changedFilesDownloaded++;
                            result.addChangedAssetDetail(relativePath);
                        }
                        if (isLanguageAssetPath(relativePath)) {
                            result.langFilesRefreshed++;
                            result.addRefreshedLanguageDetail(relativePath);
                        }
                    } catch (ExecutionException failedDownload) {
                        Throwable cause = failedDownload.getCause();
                        IOException failure = cause instanceof IOException
                                ? (IOException) cause
                                : new IOException("Failed to download resource '" + relativePath + "'.", cause);
                        if (firstFailure == null) {
                            firstFailure = failure;
                        } else {
                            firstFailure.addSuppressed(failure);
                        }
                    }
                }
                if (firstFailure != null) {
                    throw firstFailure;
                }
            } catch (InterruptedException interrupted) {
                restoreInterrupt = true;
                throw new IOException("Interrupted while downloading resource files.", interrupted);
            } finally {
                finishResourceDownloadExecutor(downloadExecutor, !downloadsFinished, restoreInterrupt);
            }
        }

        if (hasTrackedManifest) {
            for (String previousPath : previous.files.keySet()) {
                if (remote.files.containsKey(previousPath)) continue;
                Path destination = resolveResourcePackDestination(minecraftDir, previousPath);
                if (destination == null) continue;
                if (deleteSafeTrackedResourceFile(minecraftDir.resolve("resources"), destination)) {
                    System.out.println("[mod-updater] Removed resource file deleted upstream: " + previousPath);
                    result.removedFiles++;
                    result.addRemovedAssetDetail(previousPath);
                    pruneEmptyResourceDirectories(destination.getParent(), minecraftDir.resolve("resources"));
                }
            }
        }

        ResourcePackManifest updated = new ResourcePackManifest();
        updated.repo = repo;
        updated.branch = branch;
        updated.commit = commit;
        updated.checkedAt = now;
        updated.files.putAll(remote.files);
        writeResourcePackManifest(manifestPath, updated);

        result.success = true;
        System.out.println("[mod-updater] Incremental resource sync applied commit " + shortSha(commit)
                + ": added=" + result.addedFilesDownloaded
                + ", changed=" + result.changedFilesDownloaded
                + ", removed=" + result.removedFiles
                + ", unchanged=" + result.unchangedFiles + ".");
        return result;
    }

    private static void finishResourceDownloadExecutor(
            ExecutorService executor,
            boolean cancel,
            boolean restoreInterrupt) {
        if (cancel) {
            executor.shutdownNow();
        } else {
            executor.shutdown();
        }

        boolean interruptedWhileWaiting = restoreInterrupt;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(1L, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                interruptedWhileWaiting = true;
                executor.shutdownNow();
            }
        }
        if (interruptedWhileWaiting) {
            Thread.currentThread().interrupt();
        }
    }

    private static PreparedResourcePackState prepareFullResourcePackState(
            String repo,
            String branch,
            Path validationMinecraftDir,
            Path manifestMinecraftDir,
            ResourceSyncResult result) throws IOException {
        Path manifestPath = resourcePackManifestPath(manifestMinecraftDir);
        ResourcePackManifest previous = readResourcePackManifest(manifestPath);
        ResourcePackRef remoteRef = fetchResourcePackRef(repo, branch, result);
        ResourcePackRemoteTree remote = fetchResourcePackTree(repo, remoteRef.branch, remoteRef.commit, result);

        // Ensure the archive and metadata describe the exact same content before
        // promoting staged files. A branch push racing the archive download will
        // be retried later without touching the live resource tree.
        try {
            for (Map.Entry<String, String> entry : remote.files.entrySet()) {
                Path destination = resolveResourcePackDestination(validationMinecraftDir, entry.getKey());
                if (destination == null
                        || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                        || !equalsSafe(computeGitBlobSha(destination), entry.getValue())) {
                    throw new ResourceArchiveVerificationException(
                            "Resource archive did not match current branch commit at " + entry.getKey() + ".");
                }
            }

            Set<String> stagedPaths = listStagedResourcePackPaths(validationMinecraftDir);
            for (String stagedPath : stagedPaths) {
                if (!remote.files.containsKey(stagedPath)) {
                    throw new ResourceArchiveVerificationException(
                            "Resource archive contained an untracked file: " + stagedPath + ".");
                }
            }
        } catch (ResourceArchiveVerificationException mismatch) {
            throw mismatch;
        } catch (IOException validationFailure) {
            throw new ResourceArchiveVerificationException(
                    "Resource archive could not be verified before promotion.",
                    validationFailure);
        }

        return new PreparedResourcePackState(previous, remoteRef, remote);
    }

    private static Set<String> listStagedResourcePackPaths(Path minecraftDir) throws IOException {
        final Path resourcesDir = minecraftDir.resolve("resources").normalize();
        final Set<String> paths = new HashSet<String>();
        Files.walkFileTree(resourcesDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relativePath = resourcesDir.relativize(file).toString().replace('\\', '/');
                if (!isSafeResourcePackPath(relativePath)) {
                    throw new IOException("Unsafe staged resource path: " + relativePath);
                }
                paths.add(relativePath);
                return FileVisitResult.CONTINUE;
            }
        });
        return paths;
    }

    private static void recordPreparedResourcePackState(
            String repo,
            Path minecraftDir,
            PreparedResourcePackState prepared,
            ResourceSyncResult result) throws IOException {
        Path manifestPath = resourcePackManifestPath(minecraftDir);
        if (prepared.previous != null && equalsSafe(prepared.previous.repo, repo)) {
            for (String previousPath : prepared.previous.files.keySet()) {
                if (prepared.remote.files.containsKey(previousPath)) continue;
                Path destination = resolveResourcePackDestination(minecraftDir, previousPath);
                if (destination != null
                        && deleteSafeTrackedResourceFile(minecraftDir.resolve("resources"), destination)) {
                    result.removedFiles++;
                    result.addRemovedAssetDetail(previousPath);
                    pruneEmptyResourceDirectories(destination.getParent(), minecraftDir.resolve("resources"));
                }
            }
        }

        ResourcePackManifest updated = new ResourcePackManifest();
        updated.repo = repo;
        updated.branch = prepared.remoteRef.branch;
        updated.commit = prepared.remoteRef.commit;
        updated.checkedAt = System.currentTimeMillis();
        updated.files.putAll(prepared.remote.files);
        writeResourcePackManifest(manifestPath, updated);
        result.sourceBranch = prepared.remoteRef.branch;
        result.sourceCommit = prepared.remoteRef.commit;
    }

    private static String fetchResourcePackCommit(String repo, String branch, ResourceSyncResult result) throws IOException {
        String url = "https://api.github.com/repos/" + repo + "/git/ref/heads/" + encodeUrlPath(branch);
        String json = fetchGitHubJson(url, result, "commit");
        String commit = extractString(json, "\\\"sha\\\"\\s*:\\s*\\\"([0-9a-fA-F]{40})\\\"");
        if (commit == null) {
            throw new IOException("GitHub commit response did not contain a commit SHA for branch '" + branch + "'.");
        }
        result.sourceUrl = url;
        return commit.toLowerCase(Locale.ROOT);
    }

    private static ResourcePackRef fetchResourcePackRef(String repo, String preferredBranch, ResourceSyncResult result) throws IOException {
        try {
            return new ResourcePackRef(preferredBranch, fetchResourcePackCommit(repo, preferredBranch, result));
        } catch (IOException preferredFailure) {
            if (!"main".equalsIgnoreCase(preferredBranch)
                    || preferredFailure.getMessage() == null
                    || preferredFailure.getMessage().indexOf("HTTP 404") < 0) {
                throw preferredFailure;
            }
            System.err.println("[mod-updater] Resource branch 'main' was not found; trying legacy branch 'master'.");
            try {
                return new ResourcePackRef("master", fetchResourcePackCommit(repo, "master", result));
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(preferredFailure);
                throw fallbackFailure;
            }
        }
    }

    private static ResourcePackRemoteTree fetchResourcePackTree(
            String repo,
            String branch,
            String commit,
            ResourceSyncResult result) throws IOException {
        String url = "https://api.github.com/repos/" + repo + "/git/trees/" + encodeUrlComponent(commit) + "?recursive=1";
        String json = fetchGitHubJson(url, result, "tree");
        if (Pattern.compile("\\\"truncated\\\"\\s*:\\s*true", Pattern.CASE_INSENSITIVE).matcher(json).find()) {
            throw new IOException("GitHub truncated the resource repository tree; refusing to apply an incomplete delta.");
        }

        int treeKey = json.indexOf("\"tree\"");
        int arrayStart = treeKey >= 0 ? json.indexOf('[', treeKey) : -1;
        int arrayEnd = arrayStart >= 0 ? findMatchingBracket(json, arrayStart) : -1;
        if (arrayStart < 0 || arrayEnd < 0) {
            throw new IOException("GitHub tree response did not contain a complete tree array.");
        }

        ResourcePackRemoteTree remote = new ResourcePackRemoteTree();
        remote.commit = commit;
        int cursor = arrayStart + 1;
        while (cursor < arrayEnd) {
            int objectStart = json.indexOf('{', cursor);
            if (objectStart < 0 || objectStart >= arrayEnd) break;
            int objectEnd = findMatchingBrace(json, objectStart);
            if (objectEnd < 0 || objectEnd > arrayEnd) {
                throw new IOException("GitHub tree response contained an incomplete entry.");
            }
            String object = json.substring(objectStart, objectEnd + 1);
            String type = extractString(object, "\\\"type\\\"\\s*:\\s*\\\"(.*?)\\\"");
            String path = extractString(object, "\\\"path\\\"\\s*:\\s*\\\"(.*?)\\\"");
            String sha = extractString(object, "\\\"sha\\\"\\s*:\\s*\\\"([0-9a-fA-F]{40})\\\"");
            if ("blob".equals(type) && sha != null && isSafeResourcePackPath(path)) {
                remote.files.put(path.replace('\\', '/'), sha.toLowerCase(Locale.ROOT));
            }
            cursor = objectEnd + 1;
        }
        result.sourceUrl = url;
        System.out.println("[mod-updater] Resource repository tree at " + shortSha(commit)
                + " contains " + remote.files.size() + " resource file(s).");
        return remote;
    }

    private static String fetchGitHubJson(String url, ResourceSyncResult result, String label) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= RESOURCE_ENDPOINT_RETRIES; attempt++) {
            HttpURLConnection conn = null;
            InputStream in = null;
            try {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Resource metadata request was interrupted before connecting.");
                }
                conn = openHttpConnection(url, HTTP_TIMEOUT_MS, HTTP_TIMEOUT_MS, "ModUpdaterGUI/1.0");
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                String token = getenv("GITHUB_TOKEN");
                if (token != null && !token.trim().isEmpty()) {
                    conn.setRequestProperty("Authorization", "token " + token.trim());
                }
                int code = conn.getResponseCode();
                in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                String body = readAll(in);
                if (code < 200 || code >= 300) {
                    throw resourceHttpStatusFailure(conn, code, body);
                }
                addResourceAttempt(result, url + " [" + label + " metadata, try=" + attempt + "/"
                        + RESOURCE_ENDPOINT_RETRIES + "] -> OK");
                return body;
            } catch (IOException failure) {
                lastFailure = failure;
                String detail = failure.getMessage() != null ? failure.getMessage() : failure.toString();
                addResourceAttempt(result, url + " [" + label + " metadata, try=" + attempt + "/"
                        + RESOURCE_ENDPOINT_RETRIES + "] -> FAIL: " + detail);
                if (isResourceSyncInterrupted(failure)) {
                    throw failure;
                }
                if (attempt >= RESOURCE_ENDPOINT_RETRIES || !isRetryableResourceFailure(failure)) {
                    throw new IOException("GitHub resource " + label + " metadata -> " + detail, failure);
                }
                System.err.println("[mod-updater] Resource " + label + " metadata request failed transiently ("
                        + detail + "). Retrying...");
                pauseBeforeResourceRetry(attempt);
            } finally {
                if (in != null) {
                    try { in.close(); } catch (IOException ignored) {}
                }
                if (conn != null) conn.disconnect();
            }
        }
        throw new IOException("GitHub resource " + label + " metadata failed.", lastFailure);
    }

    private static String downloadResourcePackBlob(
            String repo,
            String commit,
            String relativePath,
            String expectedBlobSha,
            Path destination,
            ResourceSyncResult result,
            ResourceDownloadSession session,
            Path resourcesRoot) throws IOException {
        ensureSafeResourceDestination(resourcesRoot, destination);
        List<String> urls = new ArrayList<String>();
        urls.add("https://cdn.jsdelivr.net/gh/" + repo + "@" + commit + "/" + encodeUrlPath(relativePath));
        urls.add("https://raw.githubusercontent.com/" + repo + "/" + commit + "/" + encodeUrlPath(relativePath));
        urls.add("https://api.github.com/repos/" + repo + "/git/blobs/" + expectedBlobSha);

        IOException lastFailure = null;
        for (String url : urls) {
            String disabledReason = session != null ? session.disabledReason(url) : null;
            if (disabledReason != null) {
                continue;
            }
            for (int attempt = 1; attempt <= RESOURCE_ENDPOINT_RETRIES; attempt++) {
                Path temporary = Files.createTempFile(destination.getParent(), ".mcose-resource-", ".tmp");
                String attemptLabel = url + " [try=" + attempt + "/" + RESOURCE_ENDPOINT_RETRIES + "]";
                try {
                    downloadResourceUrl(url, temporary, url.contains("api.github.com/"));
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("Resource download interrupted for " + relativePath + ".");
                    }
                    String downloadedSha = computeGitBlobSha(temporary);
                    if (!equalsSafe(expectedBlobSha, downloadedSha)) {
                        throw new IOException("Downloaded resource hash mismatch for " + relativePath
                                + " (expected " + expectedBlobSha + ", received " + downloadedSha + ").");
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("Resource download interrupted for " + relativePath + ".");
                    }
                    try {
                        ensureSafeResourceDestination(resourcesRoot, destination);
                        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        try {
                            ensureSafeResourceDestination(resourcesRoot, destination);
                            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException installFailure) {
                            throw new ResourceInstallException(
                                    "Failed to install downloaded resource '" + relativePath + "'.",
                                    installFailure);
                        }
                    } catch (IOException installFailure) {
                        throw new ResourceInstallException(
                                "Failed to install downloaded resource '" + relativePath + "'.",
                                installFailure);
                    }
                    return resourceRequestHost(url);
                } catch (IOException failure) {
                    if (failure instanceof ResourceInstallException) {
                        throw failure;
                    }
                    lastFailure = failure;
                    String detail = failure.getMessage() != null ? failure.getMessage() : failure.toString();
                    addResourceAttempt(result, attemptLabel + " -> FAIL: " + detail);
                    if (isResourceSyncInterrupted(failure)) {
                        throw failure;
                    }
                    boolean retry = attempt < RESOURCE_ENDPOINT_RETRIES && isRetryableResourceFailure(failure);
                    System.err.println("[mod-updater] Resource download source failed for " + relativePath
                            + " (" + attemptLabel + "): " + detail
                            + (retry ? "; retrying this source." : "; trying the next source."));
                    if (retry) {
                        pauseBeforeResourceRetry(attempt);
                    } else {
                        if (session != null
                                && shouldDisableResourceProvider(failure)
                                && session.disable(url, detail)) {
                            System.err.println("[mod-updater] Resource provider " + resourceRequestHost(url)
                                    + " is unavailable for this run; queued files will use the next source. Cause: "
                                    + detail);
                        }
                        break;
                    }
                } finally {
                    try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
                }
            }
        }
        throw new IOException("Failed to download changed resource '" + relativePath + "': "
                + (lastFailure != null ? lastFailure.getMessage() : "no source succeeded"), lastFailure);
    }

    private static void downloadResourceUrl(String url, Path destination, boolean githubApi) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Resource download interrupted before connecting.");
        }
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            conn = openHttpConnection(url, RESOURCE_ARCHIVE_TIMEOUT_MS, RESOURCE_ARCHIVE_TIMEOUT_MS, "ModUpdaterGUI/1.0");
            conn.setRequestMethod("GET");
            if (githubApi) {
                conn.setRequestProperty("Accept", "application/vnd.github.raw+json");
                String token = getenv("GITHUB_TOKEN");
                if (token != null && !token.trim().isEmpty()) {
                    conn.setRequestProperty("Authorization", "token " + token.trim());
                }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                in = conn.getErrorStream();
                String body = in != null ? readAll(in) : "";
                throw resourceHttpStatusFailure(conn, code, body);
            }
            in = new BufferedInputStream(conn.getInputStream());
            out = Files.newOutputStream(destination, StandardOpenOption.TRUNCATE_EXISTING);
            byte[] buffer = new byte[64 * 1024];
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Resource download interrupted while reading " + url + ".");
                }
                int count = in.read(buffer);
                if (count == -1) break;
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Resource download interrupted while reading " + url + ".");
                }
                out.write(buffer, 0, count);
            }
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
            if (out != null) {
                try { out.close(); } catch (IOException ignored) {}
            }
            if (conn != null) conn.disconnect();
        }
    }

    private static ResourceHttpStatusException resourceHttpStatusFailure(
            HttpURLConnection conn,
            int statusCode,
            String body) {
        String retryAfter = conn != null ? conn.getHeaderField("Retry-After") : null;
        String remaining = conn != null ? conn.getHeaderField("X-RateLimit-Remaining") : null;
        String reset = conn != null ? conn.getHeaderField("X-RateLimit-Reset") : null;
        String requestId = conn != null ? conn.getHeaderField("X-GitHub-Request-Id") : null;
        StringBuilder message = new StringBuilder("HTTP ").append(statusCode);
        if (remaining != null) message.append(" rate-limit-remaining=").append(remaining);
        if (reset != null) message.append(" rate-limit-reset=").append(reset);
        if (retryAfter != null) message.append(" retry-after=").append(retryAfter);
        if (requestId != null) message.append(" request-id=").append(requestId);
        String detail = truncateErrorBody(body);
        if (detail.length() > 0) message.append(' ').append(detail);
        return new ResourceHttpStatusException(
                statusCode,
                message.toString(),
                retryAfter,
                remaining,
                reset,
                requestId);
    }

    private static boolean isRetryableResourceFailure(IOException failure) {
        if (isResourceSyncInterrupted(failure)) return false;
        if (failure instanceof ResourceHttpStatusException) {
            ResourceHttpStatusException http = (ResourceHttpStatusException) failure;
            if (http.isRateLimitExhausted()) return false;
            return http.statusCode == 408
                    || http.statusCode == 425
                    || http.statusCode == 429
                    || http.statusCode >= 500;
        }
        return true;
    }

    private static boolean shouldDisableResourceProvider(IOException failure) {
        if (isResourceSyncInterrupted(failure)) return false;
        if (failure instanceof ResourceInstallException) return false;
        if (failure instanceof ResourceHttpStatusException) {
            int status = ((ResourceHttpStatusException) failure).statusCode;
            return status == 401
                    || status == 403
                    || status == 408
                    || status == 425
                    || status == 429
                    || status == 451
                    || status >= 500;
        }
        return true;
    }

    private static String resourceRequestHost(String url) {
        try {
            String host = new URL(url).getHost();
            return host != null && host.length() > 0 ? host : url;
        } catch (Exception invalidUrl) {
            return url;
        }
    }

    private static boolean isResourceSyncInterrupted(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) return true;
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InterruptedException) return true;
            current = current.getCause();
        }
        return false;
    }

    private static void pauseBeforeResourceRetry(int completedAttempt) throws IOException {
        long delay = RESOURCE_ENDPOINT_RETRY_BASE_DELAY_MS * Math.max(1, completedAttempt);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting to retry a resource request.");
        }
    }

    private static void addResourceAttempt(ResourceSyncResult result, String attempt) {
        if (result == null || attempt == null) return;
        synchronized (result.attempts) {
            result.attempts.add(attempt);
        }
    }

    private static Path resourcePackManifestPath(Path minecraftDir) {
        return minecraftDir.resolve("resources").resolve(RESOURCE_SYNC_MANIFEST_NAME);
    }

    private static ResourcePackManifest readResourcePackManifest(Path path) {
        if (path == null || !Files.isRegularFile(path)) return null;
        Properties properties = new Properties();
        InputStream in = null;
        try {
            in = Files.newInputStream(path);
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!"1".equals(properties.getProperty("format"))) return null;
            ResourcePackManifest manifest = new ResourcePackManifest();
            manifest.repo = properties.getProperty("repo");
            manifest.branch = properties.getProperty("branch");
            manifest.commit = properties.getProperty("commit");
            try {
                manifest.checkedAt = Long.parseLong(properties.getProperty("checkedAt", "0"));
            } catch (NumberFormatException ignored) {
                manifest.checkedAt = 0L;
            }
            for (String key : properties.stringPropertyNames()) {
                if (!key.startsWith("file.")) continue;
                String encodedPath = key.substring("file.".length());
                try {
                    String relativePath = new String(Base64.getUrlDecoder().decode(encodedPath), StandardCharsets.UTF_8);
                    String sha = properties.getProperty(key);
                    if (isSafeResourcePackPath(relativePath) && sha != null && sha.matches("[0-9a-fA-F]{40}")) {
                        manifest.files.put(relativePath.replace('\\', '/'), sha.toLowerCase(Locale.ROOT));
                    }
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed entries and rebuild the manifest from GitHub.
                }
            }
            return manifest;
        } catch (IOException failure) {
            System.err.println("[mod-updater] Could not read resource sync manifest; a fresh comparison will be used: " + failure.getMessage());
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    private static void writeResourcePackManifest(Path path, ResourcePackManifest manifest) throws IOException {
        ensureDir(path.getParent());
        Properties properties = new Properties();
        properties.setProperty("format", "1");
        properties.setProperty("repo", manifest.repo != null ? manifest.repo : "");
        properties.setProperty("branch", manifest.branch != null ? manifest.branch : "");
        properties.setProperty("commit", manifest.commit != null ? manifest.commit : "");
        properties.setProperty("checkedAt", Long.toString(manifest.checkedAt));
        for (Map.Entry<String, String> entry : manifest.files.entrySet()) {
            String encodedPath = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8));
            properties.setProperty("file." + encodedPath, entry.getValue());
        }

        Path temporary = Files.createTempFile(path.getParent(), ".mcose-resource-sync-", ".tmp");
        OutputStream out = null;
        try {
            out = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING);
            properties.store(new OutputStreamWriter(out, StandardCharsets.UTF_8),
                    "Minecraft Oldschool Edition incremental resource sync state");
            out.close();
            out = null;
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) {}
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }

    private static List<String> findMissingTrackedResourceFiles(ResourcePackManifest manifest, Path minecraftDir) {
        List<String> missing = new ArrayList<String>();
        for (String relativePath : manifest.files.keySet()) {
            Path destination = resolveResourcePackDestination(minecraftDir, relativePath);
            if (destination != null && !Files.isRegularFile(destination)) {
                missing.add(relativePath);
            }
        }
        return missing;
    }

    private static Path resolveResourcePackDestination(Path minecraftDir, String relativePath) {
        if (minecraftDir == null || !isSafeResourcePackPath(relativePath)) return null;
        Path resourcesDir = minecraftDir.resolve("resources").normalize();
        Path relative;
        try {
            relative = Paths.get(relativePath.replace('\\', '/')).normalize();
        } catch (InvalidPathException invalid) {
            return null;
        }
        if (relative.isAbsolute() || relative.startsWith("..")) return null;
        Path destination = resourcesDir.resolve(relative).normalize();
        return destination.startsWith(resourcesDir) ? destination : null;
    }

    private static void ensureSafeResourceDestination(Path resourcesDir, Path destination) throws IOException {
        if (resourcesDir == null || destination == null) {
            throw new IOException("Resource destination is unavailable.");
        }
        Path root = resourcesDir.toAbsolutePath().normalize();
        Path target = destination.toAbsolutePath().normalize();
        if (target.equals(root) || !target.startsWith(root)) {
            throw new IOException("Unsafe resource destination outside the resources directory: " + target);
        }

        if (Files.isSymbolicLink(root)) {
            throw new IOException("Refusing to write through a symbolic-link resources directory: " + root);
        }
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Resource root is not a directory: " + root);
            }
        } else {
            Files.createDirectories(root);
        }

        Path realRoot = root.toRealPath();
        Path parent = target.getParent();
        Path current = root;
        for (Path segment : root.relativize(parent)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Refusing to write through a symbolic link in resources: " + current);
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Resource path component is not a directory: " + current);
                }
            } else {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException racedCreation) {
                    // Another resource worker may have created the same safe
                    // parent between the existence check and this call.
                }
                if (Files.isSymbolicLink(current)
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unsafe resource path component appeared during download: " + current);
                }
            }
            if (!current.toRealPath().startsWith(realRoot)) {
                throw new IOException("Resource path resolves outside the resources directory: " + current);
            }
        }

        if (Files.isSymbolicLink(target)) {
            throw new IOException("Refusing to replace a symbolic-link resource file: " + target);
        }
        if (!parent.toRealPath().startsWith(realRoot)) {
            throw new IOException("Resource destination resolves outside the resources directory: " + target);
        }
    }

    private static boolean deleteSafeTrackedResourceFile(Path resourcesDir, Path destination) throws IOException {
        if (resourcesDir == null || destination == null) {
            throw new IOException("Tracked resource deletion target is unavailable.");
        }
        Path root = resourcesDir.toAbsolutePath().normalize();
        Path target = destination.toAbsolutePath().normalize();
        if (target.equals(root) || !target.startsWith(root)) {
            throw new IOException("Unsafe tracked resource deletion outside the resources directory: " + target);
        }
        if (Files.isSymbolicLink(root)) {
            throw new IOException("Refusing to delete through a symbolic-link resources directory: " + root);
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Resource root is not a directory: " + root);
        }

        Path realRoot = root.toRealPath();
        Path parent = target.getParent();
        Path current = root;
        for (Path segment : root.relativize(parent)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Refusing to delete through a symbolic link in resources: " + current);
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return false;
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Resource deletion path component is not a directory: " + current);
            }
            if (!current.toRealPath().startsWith(realRoot)) {
                throw new IOException("Resource deletion path resolves outside the resources directory: " + current);
            }
        }

        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
            throw new IOException("Refusing to delete a directory as a tracked resource file: " + target);
        }
        if (!parent.toRealPath().startsWith(realRoot)) {
            throw new IOException("Tracked resource deletion resolves outside the resources directory: " + target);
        }
        return Files.deleteIfExists(target);
    }

    private static boolean isSafeResourcePackPath(String relativePath) {
        if (relativePath == null) return false;
        String normalized = relativePath.replace('\\', '/');
        if (!isResourceFolderPath(normalized) || normalized.endsWith("/")) return false;
        try {
            Path path = Paths.get(normalized).normalize();
            return !path.isAbsolute()
                    && !path.startsWith("..")
                    && path.getNameCount() >= 2
                    && isResourceTopLevelFolder(path.getName(0).toString());
        } catch (InvalidPathException invalid) {
            return false;
        }
    }

    private static boolean isResourceFolderPath(String normalizedRelativePath) {
        return normalizedRelativePath != null
                && (normalizedRelativePath.startsWith("assets/")
                || normalizedRelativePath.startsWith("data/"));
    }

    private static boolean isResourceTopLevelFolder(String firstSegment) {
        return "assets".equals(firstSegment) || "data".equals(firstSegment);
    }

    private static void installLauncherPromoterUpdate(
            Path promoterJar,
            Path payload,
            String versionTag,
            Path instanceRoot) throws IOException {
        if (promoterJar == null) throw new IOException("Launcher promoter jar path is unknown.");
        Path parent = promoterJar.getParent();
        if (parent != null) ensureDir(parent);
        Files.move(payload, promoterJar, StandardCopyOption.REPLACE_EXISTING);
        if (versionTag != null) {
            writeLauncherVersionMarker(promoterJar, versionTag);
            writeLauncherPromoterVersionJson(instanceRoot, versionTag);
        }
    }

    private static String computeGitBlobSha(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            long size = Files.size(path);
            digest.update(("blob " + size + "\0").getBytes(StandardCharsets.UTF_8));
            InputStream in = Files.newInputStream(path);
            byte[] buffer = new byte[64 * 1024];
            try {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            } finally {
                try { in.close(); } catch (IOException ignored) {}
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return hex.toString();
        } catch (GeneralSecurityException unavailable) {
            throw new IOException("SHA-1 is unavailable for Git resource comparison.", unavailable);
        }
    }

    private static String releaseAssetSha256(ReleaseAsset asset) {
        if (asset == null || asset.digest == null) return null;
        Matcher matcher = Pattern.compile("(?i)^sha256:([0-9a-f]{64})$").matcher(asset.digest.trim());
        return matcher.matches() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static String sha256Hex(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream in = Files.newInputStream(path);
            byte[] buffer = new byte[64 * 1024];
            try {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            } finally {
                try { in.close(); } catch (IOException ignored) {}
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return hex.toString();
        } catch (GeneralSecurityException unavailable) {
            throw new IOException("SHA-256 is unavailable for release asset verification.", unavailable);
        }
    }

    private static void verifyReleaseAssetFile(Path file, ReleaseAsset asset) throws IOException {
        if (asset != null && asset.size != null && Files.size(file) != asset.size.longValue()) {
            throw new IOException("Downloaded asset size does not match GitHub release metadata.");
        }
        String expectedSha256 = releaseAssetSha256(asset);
        if (expectedSha256 != null) {
            String actualSha256 = sha256Hex(file);
            if (!expectedSha256.equals(actualSha256)) {
                throw new IOException("Downloaded asset SHA-256 does not match GitHub release metadata.");
            }
        }
    }

    private static void pruneEmptyResourceDirectories(Path start, Path assetsRoot) {
        if (start == null || assetsRoot == null) return;
        Path root = assetsRoot.normalize();
        Path current = start.normalize();
        while (current.startsWith(root) && !current.equals(root)) {
            try {
                Files.delete(current);
            } catch (IOException notEmptyOrUnavailable) {
                break;
            }
            current = current.getParent();
            if (current == null) break;
        }
    }

    private static String encodeUrlComponent(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static String encodeUrlPath(String path) throws IOException {
        String[] segments = path.replace('\\', '/').split("/", -1);
        StringBuilder encoded = new StringBuilder(path.length() + 16);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) encoded.append('/');
            encoded.append(encodeUrlComponent(segments[i]));
        }
        return encoded.toString();
    }

    private static String shortSha(String sha) {
        if (sha == null) return "unknown";
        return sha.length() > 12 ? sha.substring(0, 12) : sha;
    }

    private static void logResourceSyncResult(ResourceSyncResult result) {
        if (result == null) return;
        if (result.success) {
            System.out.println("[mod-updater] Resource pack sync complete (" + result.mode + "): copied="
                    + result.copiedFiles
                    + ", added=" + result.addedFilesDownloaded
                    + ", changed=" + result.changedFilesDownloaded
                    + ", removed=" + result.removedFiles
                    + ", unchanged=" + result.unchangedFiles
                    + ", checkDeferred=" + result.checkDeferred
                    + ", sourceBranch=" + (result.sourceBranch != null ? result.sourceBranch : "?")
                    + ", sourceCommit=" + (result.sourceCommit != null ? shortSha(result.sourceCommit) : "?")
                    + ", sourceUrl=" + (result.sourceUrl != null ? result.sourceUrl : "?"));
            if (!result.missingAssetDetails.isEmpty()) {
                for (int i = 0; i < result.missingAssetDetails.size(); i++) {
                    System.out.println("[mod-updater] Resource asset added or restored: " + result.missingAssetDetails.get(i));
                }
            }
            if (result.suppressedMissingDetails > 0) {
                System.out.println("[mod-updater] Added/restored resource logs truncated: +" + result.suppressedMissingDetails + " more files.");
            }
            if (!result.changedAssetDetails.isEmpty()) {
                for (int i = 0; i < result.changedAssetDetails.size(); i++) {
                    System.out.println("[mod-updater] Resource asset updated: " + result.changedAssetDetails.get(i));
                }
            }
            if (result.suppressedChangedDetails > 0) {
                System.out.println("[mod-updater] Updated resource logs truncated: +" + result.suppressedChangedDetails + " more files.");
            }
            if (!result.removedAssetDetails.isEmpty()) {
                for (int i = 0; i < result.removedAssetDetails.size(); i++) {
                    System.out.println("[mod-updater] Resource asset removed: " + result.removedAssetDetails.get(i));
                }
            }
            if (result.suppressedRemovedDetails > 0) {
                System.out.println("[mod-updater] Removed resource logs truncated: +" + result.suppressedRemovedDetails + " more files.");
            }
            if (!result.refreshedLanguageDetails.isEmpty()) {
                for (int i = 0; i < result.refreshedLanguageDetails.size(); i++) {
                    System.out.println("[mod-updater] Language file refreshed: " + result.refreshedLanguageDetails.get(i));
                }
            }
            if (result.suppressedLanguageDetails > 0) {
                System.out.println("[mod-updater] Language refresh logs truncated: +" + result.suppressedLanguageDetails + " more files.");
            }
        } else {
            String detail = result.describeFailure();
            if (detail != null && detail.length() > 0) {
                System.err.println("[mod-updater] Resource pack sync incomplete: " + detail);
            }
        }
    }
    
    private static ResourceArchiveDownload downloadResourcePackArchive(
            String repo,
            String branch,
            String archiveMirrorUrl,
            Path minecraftDir,
            ResourceSyncMode mode,
            ResourceSyncResult result) throws IOException {
        List<ResourceArchiveCandidate> candidates = buildResourceArchiveCandidates(repo, branch, archiveMirrorUrl);
        if (candidates.isEmpty()) {
            System.err.println("[mod-updater] Resource sync: no archive candidates were generated.");
            return null;
        }
        System.out.println("[mod-updater] Resource sync: will try " + candidates.size() + " archive source candidate(s).");
        for (ResourceArchiveCandidate candidate : candidates) {
            for (int attempt = 1; attempt <= RESOURCE_ARCHIVE_RETRIES; attempt++) {
                Path downloaded = null;
                String label = candidate.url + " [branch=" + candidate.branch + ", try=" + attempt + "/" + RESOURCE_ARCHIVE_RETRIES + "]";
                System.out.println("[mod-updater] Resource sync: trying source " + label);
                try {
                    downloaded = downloadUrlToTempWithTimeout(
                            candidate.url,
                            "resourcepack-" + sanitizeTempName(repo) + "-" + sanitizeTempName(candidate.branch) + "-" + System.currentTimeMillis() + "-" + attempt + ".zip",
                            RESOURCE_ARCHIVE_TIMEOUT_MS);
                    if (!isValidResourcePackArchive(downloaded)) {
                        throw new IOException("Downloaded file is not a valid resource-pack ZIP archive.");
                    }
                    System.out.println("[mod-updater] Resource sync: downloaded archive from "
                            + candidate.url + " (branch=" + candidate.branch
                            + "), staging and verifying before installation...");
                    applyStagedResourcePackArchive(
                            downloaded,
                            repo,
                            candidate.branch,
                            minecraftDir,
                            mode,
                            result);
                    addResourceAttempt(result, label + " -> OK");
                    return new ResourceArchiveDownload(downloaded, candidate.url, candidate.branch);
                } catch (IOException ex) {
                    String err = describeResourceIOException(ex);
                    addResourceAttempt(result, label + " -> FAIL: " + err);
                    result.errors.add(label + " -> " + err);
                    if (isResourceSyncInterrupted(ex)) {
                        if (downloaded != null) {
                            try { Files.deleteIfExists(downloaded); } catch (IOException ignored) {}
                        }
                        throw ex;
                    }
                    if (ex instanceof ResourceInstallException) {
                        if (downloaded != null) {
                            try { Files.deleteIfExists(downloaded); } catch (IOException ignored) {}
                        }
                        throw ex;
                    }
                    boolean retry = !(ex instanceof ResourceArchiveVerificationException)
                            && attempt < RESOURCE_ARCHIVE_RETRIES
                            && isRetryableResourceFailure(ex);
                    if (retry) {
                        System.err.println("[mod-updater] Resource sync: source failed (" + err + "). Retrying same source...");
                    } else {
                        System.err.println("[mod-updater] Resource sync: source exhausted (" + err + "). Moving to next fallback source...");
                    }
                    if (downloaded != null) {
                        try { Files.deleteIfExists(downloaded); } catch (IOException ignored) {}
                    }
                    if (retry) {
                        pauseBeforeResourceRetryDelay(RESOURCE_ARCHIVE_RETRY_BASE_DELAY_MS * attempt);
                    } else {
                        break;
                    }
                }
            }
        }
        return null;
    }
    
    private static List<ResourceArchiveCandidate> buildResourceArchiveCandidates(
            String repo,
            String preferredBranch,
            String archiveMirrorUrl) throws IOException {
        String branch = normalizeResourcePackBranch(preferredBranch);
        List<String> branches = new ArrayList<String>();
        branches.add(branch);
        if ("main".equalsIgnoreCase(branch)) {
            branches.add("master");
        }
        
        List<ResourceArchiveCandidate> urls = new ArrayList<ResourceArchiveCandidate>();
        Set<String> seenUrls = new HashSet<String>();
        boolean mirrorRejected = false;
        for (String b : branches) {
            String expandedMirror = null;
            if (!mirrorRejected) {
                try {
                    expandedMirror = expandResourceArchiveMirrorUrl(archiveMirrorUrl, repo, b);
                } catch (IOException invalidMirror) {
                    mirrorRejected = true;
                    System.err.println("[mod-updater] Ignoring resource archive mirror: "
                            + invalidMirror.getMessage());
                }
            }
            addResourceArchiveCandidate(
                    urls,
                    seenUrls,
                    "https://github.com/" + repo + "/archive/refs/heads/" + encodeUrlPath(b) + ".zip",
                    b);
            addResourceArchiveCandidate(
                    urls,
                    seenUrls,
                    "https://codeload.github.com/" + repo + "/zip/refs/heads/" + encodeUrlPath(b),
                    b);
            addResourceArchiveCandidate(
                    urls,
                    seenUrls,
                    expandedMirror,
                    b);
        }
        return urls;
    }

    private static void addResourceArchiveCandidate(
            List<ResourceArchiveCandidate> candidates,
            Set<String> seenUrls,
            String url,
            String branch) {
        if (url == null || url.length() == 0 || !seenUrls.add(url)) return;
        candidates.add(new ResourceArchiveCandidate(url, branch));
    }

    private static String expandResourceArchiveMirrorUrl(String template, String repo, String branch) throws IOException {
        if (template == null || template.trim().length() == 0) return null;
        String expanded = template.trim()
                .replace("{repo}", encodeUrlPath(repo))
                .replace("{branch}", encodeUrlPath(branch));
        URL parsed;
        try {
            parsed = new URL(expanded);
        } catch (Exception invalid) {
            throw new IOException("Invalid resourcePackArchiveMirrorUrl after placeholder expansion.", invalid);
        }
        if (!"https".equalsIgnoreCase(parsed.getProtocol()) || parsed.getUserInfo() != null) {
            throw new IOException("resourcePackArchiveMirrorUrl must be a public HTTPS URL without embedded credentials.");
        }
        return expanded;
    }
    
    private static String normalizeResourcePackBranch(String branch) {
        if (branch == null) return "main";
        String trimmed = branch.trim();
        return trimmed.length() > 0 ? trimmed : "main";
    }

    private static void applyStagedResourcePackArchive(
            Path zipPath,
            String repo,
            String branch,
            Path minecraftDir,
            ResourceSyncMode mode,
            ResourceSyncResult result) throws IOException {
        ensureDir(minecraftDir);
        Path stagingRoot = Files.createTempDirectory(minecraftDir, ".mcose-resource-stage-");
        try {
            ResourceSyncResult stagingResult = new ResourceSyncResult();
            stagingResult.mode = ResourceSyncMode.FULL;
            try {
                extractResourcePackArchive(
                        zipPath,
                        stagingRoot,
                        ResourceSyncMode.FULL,
                        stagingResult,
                        false);
            } catch (IOException invalidArchive) {
                throw new ResourceArchiveVerificationException(
                        "Resource archive could not be fully staged and verified.",
                        invalidArchive);
            }

            PreparedResourcePackState preparedState = null;
            try {
                preparedState = prepareFullResourcePackState(
                        repo,
                        branch,
                        stagingRoot,
                        minecraftDir,
                        result);
            } catch (ResourceArchiveVerificationException mismatch) {
                throw mismatch;
            } catch (IOException metadataUnavailable) {
                if (isResourceSyncInterrupted(metadataUnavailable)) {
                    throw metadataUnavailable;
                }
                // The complete archive is staged and structurally verified, but
                // GitHub metadata may be the endpoint that is unavailable in the
                // affected region. Invalidate the old manifest before promotion
                // so the next successful metadata pass hashes every live file.
                System.err.println("[mod-updater] Resource archive is staged, but current metadata could not be verified: "
                        + metadataUnavailable.getMessage());
            }

            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException(
                        "Resource archive installation was cancelled before staged files were promoted.");
            }

            if (preparedState == null) {
                result.sourceCommit = null;
                try {
                    Files.deleteIfExists(resourcePackManifestPath(minecraftDir));
                } catch (IOException manifestFailure) {
                    throw new ResourceInstallException(
                            "Could not invalidate unverified resource sync state before installation.",
                            manifestFailure);
                }
            }

            try {
                installStagedResourceFiles(stagingRoot, minecraftDir, mode, result);
            } catch (IOException installFailure) {
                throw new ResourceInstallException(
                        "Could not install the fully staged resource archive.",
                        installFailure);
            }
            if (preparedState != null) {
                try {
                    recordPreparedResourcePackState(repo, minecraftDir, preparedState, result);
                } catch (IOException manifestFailure) {
                    System.err.println("[mod-updater] Full resource sync completed, but incremental state could not be refreshed: "
                            + manifestFailure.getMessage());
                }
            }
        } finally {
            try {
                deleteResourceStagingTree(stagingRoot, minecraftDir);
            } catch (IOException cleanupFailure) {
                System.err.println("[mod-updater] Warning: Could not remove resource staging directory "
                        + stagingRoot + ": " + cleanupFailure.getMessage());
            }
        }
    }

    private static void installStagedResourceFiles(
            Path stagingRoot,
            Path minecraftDir,
            final ResourceSyncMode mode,
            final ResourceSyncResult result) throws IOException {
        final Path stagedResources = stagingRoot.resolve("resources").normalize();
        final Path liveResources = minecraftDir.resolve("resources").normalize();
        if (!Files.isDirectory(stagedResources, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Staged resource archive did not create a resources directory.");
        }

        Files.walkFileTree(stagedResources, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path stagedFile, BasicFileAttributes attrs) throws IOException {
                Path relative = stagedResources.relativize(stagedFile).normalize();
                String relativePath = relative.toString().replace('\\', '/');
                if (!isSafeResourcePackPath(relativePath)) {
                    throw new IOException("Unsafe staged resource path: " + relativePath);
                }
                Path destination = liveResources.resolve(relative).normalize();
                ensureSafeResourceDestination(liveResources, destination);

                boolean isLanguageFile = isLanguageAssetPath(relativePath);
                boolean existedBefore = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
                if (mode == ResourceSyncMode.SMART && existedBefore && !isLanguageFile) {
                    result.skippedExistingFiles++;
                    return FileVisitResult.CONTINUE;
                }

                byte[] previousLanguageBytes = null;
                if (isLanguageFile && existedBefore) {
                    try {
                        previousLanguageBytes = Files.readAllBytes(destination);
                    } catch (IOException ignored) {}
                }

                try {
                    Files.move(
                            stagedFile,
                            destination,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    ensureSafeResourceDestination(liveResources, destination);
                    Files.move(stagedFile, destination, StandardCopyOption.REPLACE_EXISTING);
                }

                result.copiedFiles++;
                if (mode == ResourceSyncMode.FULL) {
                    System.out.println("[mod-updater] Full resource file installed: " + relativePath);
                } else if (!existedBefore) {
                    result.missingFilesCopied++;
                    result.addMissingAssetDetail(relativePath);
                }
                if (isLanguageFile) {
                    result.langFilesRefreshed++;
                    result.addRefreshedLanguageDetail(relativePath);
                    if (existedBefore) {
                        boolean changed = true;
                        try {
                            byte[] currentBytes = Files.readAllBytes(destination);
                            if (previousLanguageBytes != null) {
                                changed = !Arrays.equals(previousLanguageBytes, currentBytes);
                            }
                        } catch (IOException ignored) {}
                        System.out.println("[mod-updater] Language overwrite applied (content "
                                + (changed ? "updated" : "unchanged") + "): " + relativePath);
                    } else {
                        System.out.println("[mod-updater] Language file missing; installed latest version: " + relativePath);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteResourceStagingTree(Path stagingRoot, Path minecraftDir) throws IOException {
        Path root = stagingRoot.toAbsolutePath().normalize();
        Path parent = minecraftDir.toAbsolutePath().normalize();
        Path fileName = root.getFileName();
        if (!root.startsWith(parent)
                || fileName == null
                || !fileName.toString().startsWith(".mcose-resource-stage-")) {
            throw new IOException("Refusing to remove an unrecognized resource staging path: " + root);
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void extractResourcePackArchive(Path zipPath, Path minecraftDir, ResourceSyncMode mode, ResourceSyncResult result) throws IOException {
        extractResourcePackArchive(zipPath, minecraftDir, mode, result, true);
    }

    private static void extractResourcePackArchive(
            Path zipPath,
            Path minecraftDir,
            ResourceSyncMode mode,
            ResourceSyncResult result,
            boolean logChanges) throws IOException {
        Path resourcesDir = minecraftDir.resolve("resources").normalize();
        
        byte[] buf = new byte[64 * 1024];
        int extractedResourceFiles = 0;
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipPath.toFile())));
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                
                String name = entry.getName().replace('\\', '/');
                int slashIdx = name.indexOf('/');
                if (slashIdx < 0 || slashIdx + 1 >= name.length()) {
                    zis.closeEntry();
                    continue;
                }
                
                String relativePath = name.substring(slashIdx + 1);
                if (!isResourceFolderPath(relativePath)) {
                    zis.closeEntry();
                    continue;
                }
                
                Path rel;
                try {
                    rel = Paths.get(relativePath).normalize();
                } catch (InvalidPathException badPath) {
                    zis.closeEntry();
                    continue;
                }
                if (rel.isAbsolute()) {
                    zis.closeEntry();
                    continue;
                }
                
                String relNorm = rel.toString().replace('\\', '/');
                if (relNorm.startsWith("..")) {
                    zis.closeEntry();
                    continue;
                }
                
                Path dest = resourcesDir.resolve(rel).normalize();
                if (!dest.startsWith(resourcesDir)) {
                    zis.closeEntry();
                    continue;
                }
                
                boolean isLangFile = isLanguageAssetPath(relNorm);
                boolean shouldCopy;
                boolean replaceExisting = false;
                if (mode == ResourceSyncMode.FULL) {
                    shouldCopy = true;
                    replaceExisting = true;
                } else if (isLangFile) {
                    shouldCopy = true;
                    replaceExisting = true;
                } else if (Files.exists(dest)) {
                    shouldCopy = false;
                    result.skippedExistingFiles++;
                } else {
                    shouldCopy = true;
                }
                
                if (!shouldCopy) {
                    zis.closeEntry();
                    continue;
                }
                
                boolean existedBefore = Files.exists(dest);
                byte[] previousLangBytes = null;
                if (isLangFile && existedBefore) {
                    try {
                        previousLangBytes = Files.readAllBytes(dest);
                    } catch (IOException ignored) {}
                }
                
                ensureSafeResourceDestination(resourcesDir, dest);
                try {
                    if (replaceExisting) {
                        Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.copy(zis, dest);
                    }
                    extractedResourceFiles++;
                    result.copiedFiles++;
                    if (mode == ResourceSyncMode.FULL && logChanges) {
                        System.out.println("[mod-updater] Full resource file installed: " + relNorm);
                    }
                    if (isLangFile) {
                        result.langFilesRefreshed++;
                        result.addRefreshedLanguageDetail(relNorm);
                        if (existedBefore) {
                            boolean changed = true;
                            try {
                                byte[] currentBytes = Files.readAllBytes(dest);
                                if (previousLangBytes != null) {
                                    changed = !Arrays.equals(previousLangBytes, currentBytes);
                                }
                            } catch (IOException ignored) {}
                            if (changed && logChanges) {
                                System.out.println("[mod-updater] Language overwrite applied (updated content): " + relNorm);
                            } else if (logChanges) {
                                System.out.println("[mod-updater] Language overwrite applied (content unchanged): " + relNorm);
                            }
                        } else if (logChanges) {
                            System.out.println("[mod-updater] Language file missing; installed latest version: " + relNorm);
                        }
                    } else if (mode == ResourceSyncMode.SMART) {
                        result.missingFilesCopied++;
                        result.addMissingAssetDetail(relNorm);
                    }
                } catch (FileAlreadyExistsException alreadyExists) {
                    result.skippedExistingFiles++;
                }
                zis.closeEntry();
            }
        } finally {
            try { zis.close(); } catch (IOException ignored) {}
        }
        if (extractedResourceFiles == 0) {
            throw new IOException("Resource archive did not contain any supported assets/ or data/ files.");
        }
    }
    
    private static boolean isLanguageAssetPath(String relativePath) {
        if (relativePath == null) return false;
        String norm = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        return norm.startsWith("assets/minecraft/lang/") && !norm.endsWith("/");
    }
    
    private static String sanitizeTempName(String name) {
        if (name == null || name.isEmpty()) return "unknown";
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
    
    private static Path downloadUrlToTempWithTimeout(String url, String suggestedName, int timeoutMs) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Resource archive download was interrupted before connecting.");
        }
        String tempPrefix = sanitizeTempName(suggestedName);
        if (tempPrefix.length() > 64) tempPrefix = tempPrefix.substring(0, 64);
        while (tempPrefix.length() < 3) tempPrefix += "_";
        Path tmp = Files.createTempFile(tempPrefix + "-", ".part");
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        boolean completed = false;
        try {
            conn = openHttpConnection(url, timeoutMs, timeoutMs, "ModUpdaterGUI/1.0");
            conn.setUseCaches(false);
            conn.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
            conn.setRequestProperty("Pragma", "no-cache");
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                in = conn.getErrorStream();
                String body = in != null ? readAll(in) : "";
                throw resourceHttpStatusFailure(conn, code, body);
            }

            in = new BufferedInputStream(conn.getInputStream());
            out = new FileOutputStream(tmp.toFile());
            byte[] buf = new byte[64 * 1024];
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Resource archive download was interrupted while reading " + url + ".");
                }
                int n = in.read(buf);
                if (n == -1) break;
                out.write(buf, 0, n);
            }
            completed = true;
            return tmp;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
            if (out != null) {
                try { out.close(); } catch (IOException ignored) {}
            }
            if (conn != null) conn.disconnect();
            if (!completed) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }
    
    private static boolean isValidResourcePackArchive(Path zipPath) {
        if (zipPath == null || !Files.isRegularFile(zipPath)) return false;
        ZipFile zip = null;
        try {
            zip = new ZipFile(zipPath.toFile());
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                int slash = name.indexOf('/');
                if (slash < 0 || slash + 1 >= name.length()) continue;
                String relativePath = name.substring(slash + 1);
                if (isSafeResourcePackPath(relativePath)) return true;
            }
            return false;
        } catch (IOException ignored) {
            return false;
        } finally {
            if (zip != null) {
                try { zip.close(); } catch (IOException ignored) {}
            }
        }
    }
    
    private static void pauseBeforeResourceRetryDelay(long millis) throws IOException {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting to retry a resource archive request.");
        }
    }
    
    /**
     * Installs the macOS LWJGL3 patch that tells Prism to add -XstartOnFirstThread.
     * This is how modern Minecraft handles GLFW's main thread requirement on macOS.
     * The patch file tells Prism Launcher to add the required JVM argument automatically.
     * 
     * @param instanceRoot The Prism instance root directory
     */
    private static void installMacOSPatch(Path instanceRoot) {
        // Only needed on macOS
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) {
            return;
        }
        
        Path patchesDir = instanceRoot.resolve("patches");
        Path patchFile = patchesDir.resolve("lwjgl3-macos.json");
        
        // Check if already installed with current version (1.1.0 adds headless mode)
        if (Files.exists(patchFile)) {
            try {
                String content = new String(Files.readAllBytes(patchFile), "UTF-8");
                if (content.contains("\"version\": \"1.1.0\"") && content.contains("headless")) {
                    return; // Already up to date
                }
                // Old version - delete and reinstall
                Files.delete(patchFile);
                System.out.println("[mod-updater] Updating macOS patch to v1.1.0 (adds headless mode)");
            } catch (Exception e) {
                // If can't read, try to reinstall
            }
        }
        
        try {
            ensureDir(patchesDir);
            
            // Create the patch file that tells Prism to add macOS-specific JVM args
            // XstartOnFirstThread is needed for GLFW, headless=true allows AWT operations
            String patchContent = 
                "{\n" +
                "    \"formatVersion\": 1,\n" +
                "    \"name\": \"LWJGL 3 macOS Support\",\n" +
                "    \"uid\": \"org.lwjgl3.macos.fix\",\n" +
                "    \"version\": \"1.1.0\",\n" +
                "    \"+traits\": [\n" +
                "        \"XstartOnFirstThread\"\n" +
                "    ],\n" +
                "    \"+jvmArgs\": [\n" +
                "        \"-Djava.awt.headless=true\"\n" +
                "    ],\n" +
                "    \"requires\": [],\n" +
                "    \"compatibleJavaMajors\": [8, 11, 17, 21]\n" +
                "}\n";
            
            Files.write(patchFile, patchContent.getBytes("UTF-8"));
            System.out.println("[mod-updater] Installed macOS LWJGL3 patch: " + patchFile);
            
        } catch (Exception e) {
            System.err.println("[mod-updater] Warning: Failed to install macOS patch: " + e.getMessage());
        }
    }
    
    /**
     * Installs the custom net.minecraft.json patch for macOS compatibility.
     * Downloads from GitHub if not already present in the patches directory.
     * This patch is essential for proper game operation on macOS.
     * 
     * @param instanceRoot The Prism instance root directory
     */
    private static void installNetMinecraftJsonPatch(Path instanceRoot) {
        // Only needed on macOS
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) {
            return;
        }
        
        Path patchesDir = instanceRoot.resolve("patches");
        Path patchFile = patchesDir.resolve("net.minecraft.json");
        
        // Check if already installed
        if (Files.exists(patchFile)) {
            System.out.println("[mod-updater] net.minecraft.json patch already installed");
            return;
        }
        
        try {
            ensureDir(patchesDir);
            
            // Download from GitHub releases
            String downloadUrl = "https://github.com/MinecraftOldschoolEdition/net.minecraft.json/releases/download/1.0/net.minecraft.json";
            System.out.println("[mod-updater] Downloading net.minecraft.json from GitHub...");
            
            HttpURLConnection conn = openHttpConnection(downloadUrl, HTTP_TIMEOUT_MS, HTTP_TIMEOUT_MS, "ModUpdaterGUI/1.0");
            conn.setInstanceFollowRedirects(true);
            
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                System.err.println("[mod-updater] Failed to download net.minecraft.json: HTTP " + responseCode);
                return;
            }
            
            // Download and write to patch file
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(patchFile.toFile())) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            System.out.println("[mod-updater] Installed net.minecraft.json patch: " + patchFile);
            
        } catch (Exception e) {
            System.err.println("[mod-updater] Warning: Failed to install net.minecraft.json patch: " + e.getMessage());
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
            HttpURLConnection conn = openHttpConnection(urlStr, HTTP_TIMEOUT_MS, HTTP_TIMEOUT_MS, "ModUpdater/1.0");
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

    private static boolean isUpToDate(Path minecraftDir, Path instanceRoot, String mode, String jarRegex, ReleaseAsset jarAsset, ReleaseAsset serverJarAsset, String latestTag, String jarmodName) throws IOException {
        boolean patchUpToDate = jarAsset == null || isPatchJarUpToDate(minecraftDir, instanceRoot, mode, jarRegex, latestTag, jarmodName);
        boolean serverUpToDate = serverJarAsset == null || isLanServerJarUpToDate(minecraftDir, latestTag);
        return patchUpToDate && serverUpToDate;
    }

    private static boolean isPatchJarUpToDate(Path minecraftDir, Path instanceRoot, String mode, String jarRegex, String latestTag, String jarmodName) throws IOException {
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

    private static boolean isLanServerJarUpToDate(Path minecraftDir, String latestTag) {
        if (minecraftDir == null) return false;
        Path serverJar = minecraftDir.resolve(LAN_SERVER_DIR_NAME).resolve(LAN_SERVER_JAR_NAME);
        if (!Files.isRegularFile(serverJar)) return false;
        InstalledMarker m = readMarker(serverJar);
        return m != null && equalsSafe(m.tag, latestTag);
    }
    
    /**
     * Opens an HTTP/HTTPS connection with consistent timeout/user-agent settings.
     * HTTPS connections use a compatibility trust manager that adds OS trust roots.
     */
    private static HttpURLConnection openHttpConnection(String url, int connectTimeoutMs, int readTimeoutMs, String userAgent) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        if (userAgent != null && !userAgent.isEmpty()) {
            conn.setRequestProperty("User-Agent", userAgent);
        }
        if (conn instanceof HttpsURLConnection) {
            SSLSocketFactory factory = getTlsSocketFactory();
            if (factory != null) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(factory);
            }
        }
        return conn;
    }
    
    private static SSLSocketFactory getTlsSocketFactory() {
        if (TLS_SOCKET_FACTORY != null || TLS_SOCKET_FACTORY_INIT_FAILED) {
            return TLS_SOCKET_FACTORY;
        }
        synchronized (TLS_SOCKET_FACTORY_LOCK) {
            if (TLS_SOCKET_FACTORY != null || TLS_SOCKET_FACTORY_INIT_FAILED) {
                return TLS_SOCKET_FACTORY;
            }
            try {
                List<X509TrustManager> managers = new ArrayList<X509TrustManager>();
                X509TrustManager defaultManager = loadDefaultTrustManager();
                if (defaultManager != null) {
                    managers.add(defaultManager);
                }
                X509TrustManager windowsManager = loadTrustManagerFromKeyStore("Windows-ROOT");
                if (windowsManager != null) {
                    managers.add(windowsManager);
                }
                X509TrustManager osBundleManager = loadTrustManagerFromSystemCaBundle();
                if (osBundleManager != null) {
                    managers.add(osBundleManager);
                }
                if (managers.isEmpty()) {
                    TLS_SOCKET_FACTORY_INIT_FAILED = true;
                    return null;
                }
                
                X509TrustManager merged = managers.size() == 1 ? managers.get(0) : mergeTrustManagers(managers);
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[] { merged }, new SecureRandom());
                TLS_SOCKET_FACTORY = ctx.getSocketFactory();
                return TLS_SOCKET_FACTORY;
            } catch (Exception ex) {
                TLS_SOCKET_FACTORY_INIT_FAILED = true;
                System.err.println("[mod-updater] TLS compatibility initialization failed; using default JVM trust store only: " + ex.getMessage());
                return null;
            }
        }
    }
    
    private static X509TrustManager loadDefaultTrustManager() throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return firstX509TrustManager(tmf.getTrustManagers());
    }
    
    private static X509TrustManager loadTrustManagerFromKeyStore(String keyStoreType) {
        try {
            KeyStore ks = KeyStore.getInstance(keyStoreType);
            ks.load(null, null);
            return loadTrustManager(ks);
        } catch (Exception ignored) {
            return null;
        }
    }
    
    private static X509TrustManager loadTrustManagerFromSystemCaBundle() {
        for (int i = 0; i < OS_CA_BUNDLE_PATHS.length; i++) {
            Path bundlePath = Paths.get(OS_CA_BUNDLE_PATHS[i]);
            if (!Files.isRegularFile(bundlePath)) {
                continue;
            }
            try (InputStream in = Files.newInputStream(bundlePath)) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                Collection<? extends Certificate> certs = cf.generateCertificates(in);
                if (certs == null || certs.isEmpty()) {
                    continue;
                }
                KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, null);
                int idx = 0;
                for (Certificate cert : certs) {
                    ks.setCertificateEntry("os-ca-" + idx++, cert);
                }
                return loadTrustManager(ks);
            } catch (Exception ignored) {
                // Try next candidate path.
            }
        }
        return null;
    }

    private static String describeResourceIOException(IOException failure) {
        String message = failure.getMessage() != null ? failure.getMessage() : failure.toString();
        Throwable cause = failure.getCause();
        if (cause == null || cause == failure) return message;
        String causeMessage = cause.getMessage() != null ? cause.getMessage() : cause.toString();
        return message + " [" + cause.getClass().getSimpleName() + ": " + causeMessage + "]";
    }
    
    private static X509TrustManager loadTrustManager(KeyStore keyStore) throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        return firstX509TrustManager(tmf.getTrustManagers());
    }
    
    private static X509TrustManager firstX509TrustManager(TrustManager[] trustManagers) {
        if (trustManagers == null) {
            return null;
        }
        for (int i = 0; i < trustManagers.length; i++) {
            if (trustManagers[i] instanceof X509TrustManager) {
                return (X509TrustManager) trustManagers[i];
            }
        }
        return null;
    }
    
    private static X509TrustManager mergeTrustManagers(final List<X509TrustManager> managers) {
        return new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                CertificateException last = null;
                for (int i = 0; i < managers.size(); i++) {
                    try {
                        managers.get(i).checkClientTrusted(chain, authType);
                        return;
                    } catch (CertificateException ex) {
                        last = ex;
                    }
                }
                if (last != null) throw last;
                throw new CertificateException("No trust manager accepted the client certificate chain.");
            }
            
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                CertificateException last = null;
                for (int i = 0; i < managers.size(); i++) {
                    try {
                        managers.get(i).checkServerTrusted(chain, authType);
                        return;
                    } catch (CertificateException ex) {
                        last = ex;
                    }
                }
                if (last != null) throw last;
                throw new CertificateException("No trust manager accepted the server certificate chain.");
            }
            
            public X509Certificate[] getAcceptedIssuers() {
                LinkedHashSet<X509Certificate> issuers = new LinkedHashSet<X509Certificate>();
                for (int i = 0; i < managers.size(); i++) {
                    X509Certificate[] certs = managers.get(i).getAcceptedIssuers();
                    if (certs != null && certs.length > 0) {
                        issuers.addAll(Arrays.asList(certs));
                    }
                }
                return issuers.toArray(new X509Certificate[issuers.size()]);
            }
        };
    }

    private static LatestRelease fetchLatestRelease(String repo) throws IOException {
        // Try /releases/latest first
        String url = String.format(GITHUB_API_LATEST, repo);
        HttpURLConnection conn = openHttpConnection(url, HTTP_TIMEOUT_MS, HTTP_TIMEOUT_MS, "ModUpdaterGUI/1.0");
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
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
            HttpURLConnection fallbackConn = openHttpConnection(fallbackUrl, HTTP_TIMEOUT_MS, HTTP_TIMEOUT_MS, "ModUpdaterGUI/1.0");
            fallbackConn.setRequestMethod("GET");
            fallbackConn.setRequestProperty("Accept", "application/vnd.github+json");
            if (token != null && !token.trim().isEmpty()) {
                fallbackConn.setRequestProperty("Authorization", "token " + token.trim());
            }
            int fallbackCode = fallbackConn.getResponseCode();
            InputStream fallbackIn = fallbackCode >= 200 && fallbackCode < 300 ? fallbackConn.getInputStream() : fallbackConn.getErrorStream();
            String fallbackBody = readAll(fallbackIn);
            if (fallbackCode < 200 || fallbackCode >= 300) {
                throw new IOException("GitHub API error: HTTP " + fallbackCode + " " + truncateErrorBody(fallbackBody));
            }
            // Parse first release from array
            return parseFirstReleaseFromArray(fallbackBody);
        }
        
        if (code < 200 || code >= 300) {
            throw new IOException("GitHub API error: HTTP " + code + " " + truncateErrorBody(body));
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
        List<ReleaseAsset> list = new ArrayList<ReleaseAsset>();
        int assetsKey = json != null ? json.indexOf("\"assets\"") : -1;
        int arrayStart = assetsKey >= 0 ? json.indexOf('[', assetsKey) : -1;
        int arrayEnd = arrayStart >= 0 ? findMatchingBracket(json, arrayStart) : -1;
        if (arrayStart >= 0 && arrayEnd >= 0) {
            int cursor = arrayStart + 1;
            while (cursor < arrayEnd) {
                int objectStart = json.indexOf('{', cursor);
                if (objectStart < 0 || objectStart >= arrayEnd) break;
                int objectEnd = findMatchingBrace(json, objectStart);
                if (objectEnd < 0 || objectEnd > arrayEnd) break;
                String object = json.substring(objectStart, objectEnd + 1);
                String url = extractString(object, "\\\"browser_download_url\\\"\\s*:\\s*\\\"(.*?)\\\"");
                if (url != null) {
                    ReleaseAsset asset = new ReleaseAsset();
                    asset.url = url;
                    asset.name = extractString(object, "\\\"name\\\"\\s*:\\s*\\\"(.*?)\\\"");
                    if (asset.name == null || asset.name.length() == 0) {
                        int slash = url.lastIndexOf('/');
                        asset.name = slash >= 0 && slash + 1 < url.length() ? url.substring(slash + 1) : url;
                    }
                    asset.digest = extractString(object, "\\\"digest\\\"\\s*:\\s*\\\"(.*?)\\\"");
                    asset.size = extractLong(object, "\\\"size\\\"\\s*:\\s*([0-9]+)");
                    list.add(asset);
                }
                cursor = objectEnd + 1;
            }
        }

        // Compatibility fallback for older or non-standard release responses.
        if (list.isEmpty()) {
            Pattern p = Pattern.compile("\\\"browser_download_url\\\"\\s*:\\s*\\\"(.*?)\\\"", Pattern.DOTALL);
            Matcher m = p.matcher(json);
            while (m.find()) {
                String url = unescapeJson(m.group(1));
                String name = url;
                int slash = url.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < url.length()) name = url.substring(slash + 1);
                ReleaseAsset asset = new ReleaseAsset();
                asset.name = name;
                asset.url = url;
                list.add(asset);
            }
        }
        return list;
    }

    private static Long extractLong(String text, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        if (!matcher.find()) return null;
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException invalidNumber) {
            return null;
        }
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

    private static ReleaseAsset selectOptionalAsset(List<ReleaseAsset> assets, String assetRegex) {
        if (assetRegex == null || assetRegex.trim().length() == 0) {
            return null;
        }
        return selectAsset(assets, assetRegex);
    }

    private static ReleaseAsset selectExactOptionalAsset(List<ReleaseAsset> assets, String assetRegex) {
        if (assetRegex == null || assetRegex.trim().length() == 0) return null;
        Pattern pattern = Pattern.compile(assetRegex);
        for (ReleaseAsset asset : assets) {
            if (pattern.matcher(asset.name).matches()) return asset;
        }
        return null;
    }

    private static Path downloadToTemp(ProgressUI ui, String url, String suggestedName, double start, double end) throws IOException {
        String tmpDir = System.getProperty("java.io.tmpdir");
        String prefix = suggestedName != null ? suggestedName.replaceAll("[^A-Za-z0-9._-]", "_") : "download";
        while (prefix.length() < 3) prefix = prefix + "_";
        Path tmp = Files.createTempFile(Paths.get(tmpDir), prefix + ".", ".download");
        try {
            HttpURLConnection conn = openHttpConnection(url, HTTP_TIMEOUT_MS, HTTP_TIMEOUT_MS, "ModUpdaterGUI/1.0");
            try {
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
            } finally {
                conn.disconnect();
            }
            return tmp;
        } catch (IOException failure) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw failure;
        } catch (RuntimeException failure) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw failure;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = br.read(buf)) != -1) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }
    
    /** Truncate error body to avoid dumping huge HTML error pages to console */
    private static String truncateErrorBody(String body) {
        if (body == null) return "";
        // If it looks like HTML, just note that
        if (body.trim().startsWith("<!DOCTYPE") || body.trim().startsWith("<html")) {
            return "(HTML error page received)";
        }
        // Truncate long responses
        if (body.length() > 500) {
            return body.substring(0, 500) + "... (truncated)";
        }
        return body;
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
    
    /**
     * Clears all .bak backup files from the jarmods directory.
     * These are the old version jars that were renamed during updates.
     * 
     * @param minecraftDir The .minecraft directory (unused, kept for API compatibility)
     * @param instanceRoot The Prism/MultiMC instance root (for jarmods)
     * @return The number of backup files deleted
     */
    private static int clearBackupFiles(Path minecraftDir, Path instanceRoot) {
        int deleted = 0;
        
        if (instanceRoot == null) {
            System.err.println("[mod-updater] Cannot clear backups: instance root not set");
            return 0;
        }
        
        // Only scan jarmods directory for .bak files
        Path jarmodsDir = instanceRoot.resolve("jarmods");
        
        if (!Files.isDirectory(jarmodsDir)) {
            System.out.println("[mod-updater] No jarmods directory found at: " + jarmodsDir);
            return 0;
        }
        
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jarmodsDir, "*.bak*")) {
            for (Path bakFile : stream) {
                try {
                    Files.deleteIfExists(bakFile);
                    deleted++;
                    System.out.println("[mod-updater] Deleted backup: " + bakFile.getFileName());
                } catch (IOException ex) {
                    System.err.println("[mod-updater] Failed to delete backup " + bakFile + ": " + ex.getMessage());
                }
            }
        } catch (IOException ex) {
            System.err.println("[mod-updater] Failed to scan jarmods directory for backups: " + ex.getMessage());
        }
        
        return deleted;
    }

    /**
     * The required JVM argument for proper window behavior on macOS.
     * This prevents issues with window decorations and focus.
     */
    private static final String REQUIRED_JVM_ARG = "-Djava.awt.headless=true";

    /**
     * Checks if the instance configuration has the required JVM argument.
     * Reads the instance.cfg file from the instance root and looks for the
     * JvmArgs key to see if it contains the required argument.
     * 
     * @param instanceRoot The Prism/MultiMC instance root directory
     * @return true if the argument is present or check can't be performed, false if missing
     */
    private static boolean hasRequiredJvmArg(Path instanceRoot) {
        if (instanceRoot == null) {
            return true; // Can't check, don't show popup
        }
        
        Path instanceCfg = instanceRoot.resolve("instance.cfg");
        if (!Files.isRegularFile(instanceCfg)) {
            return true; // No config file, can't check
        }
        
        try {
            List<String> lines = Files.readAllLines(instanceCfg, StandardCharsets.UTF_8);
            for (String line : lines) {
                // Look for JvmArgs= or OverrideJavaArgs= lines
                if (line.startsWith("JvmArgs=") || line.startsWith("OverrideJavaArgs=")) {
                    // Check if the required arg is in the value
                    if (line.contains(REQUIRED_JVM_ARG)) {
                        return true;
                    }
                }
            }
            // If we found JvmArgs but it doesn't contain our arg, we need to warn
            // Also check if OverrideJavaArgs is enabled (meaning they have custom args)
            boolean hasCustomArgs = false;
            for (String line : lines) {
                if (line.startsWith("OverrideJavaArgs=true")) {
                    hasCustomArgs = true;
                    break;
                }
            }
            // Only warn if they have custom args but not our required one
            if (hasCustomArgs) {
                return false;
            }
            return true; // No custom args, default behavior
        } catch (IOException ex) {
            System.err.println("[mod-updater] Failed to read instance.cfg: " + ex.getMessage());
            return true; // Can't read, don't show popup
        }
    }

    /**
     * Shows a warning popup if the required JVM argument is missing from the instance config.
     * Only shown on macOS where this argument is needed.
     * 
     * @param parent The parent component for the dialog
     * @param instanceRoot The Prism/MultiMC instance root directory
     */
    private static void checkAndWarnJvmArg(Component parent, Path instanceRoot) {
        // Only check on macOS
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) {
            return;
        }
        
        if (!hasRequiredJvmArg(instanceRoot)) {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    JOptionPane.showMessageDialog(parent,
                            "For optimal performance on macOS, it is recommended to add the following\n" +
                            "JVM argument to your instance's Java settings:\n\n" +
                            REQUIRED_JVM_ARG + "\n\n" +
                            "In Prism Launcher: Edit Instance → Settings → Java → Java arguments",
                            "Recommended JVM Argument",
                            JOptionPane.INFORMATION_MESSAGE);
                }
            });
        }
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

    /**
     * Fixed-height dirt navigation strip based on the vanilla launcher's
     * TexturedPanel and LogoPanel geometry and paint path.
     */
    private static final class LauncherNavPanel extends JPanel {
        private static final int NAV_HEIGHT = 100;
        private static final int TEXTURE_TILE_SIZE = 32;
        private static final int LOGO_X = 24;
        private static final int LOGO_Y = 24;
        private static final int BUTTON_WIDTH = 143;
        private static final int BUTTON_HEIGHT = 32;
        private static final int BUTTON_GAP = 10;
        private static final int BUTTON_RIGHT = 24;

        private final JLabel logoLabel;
        private final LegacyButton[] buttons;
        private final Image dirtTile;
        private BufferedImage texturedBackground;

        LauncherNavPanel(Path bgPath, Image logo, LegacyButton... buttons) {
            setOpaque(true);
            setBackground(new Color(60, 43, 29));
            setLayout(null);
            setPreferredSize(new Dimension(100, NAV_HEIGHT));
            setMinimumSize(new Dimension(0, NAV_HEIGHT));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, NAV_HEIGHT));

            BufferedImage dirt = loadLauncherDirtImage(bgPath);
            this.dirtTile = dirt != null
                    ? dirt.getScaledInstance(TEXTURE_TILE_SIZE, TEXTURE_TILE_SIZE, Image.SCALE_AREA_AVERAGING)
                    : null;

            this.logoLabel = new JLabel(logo != null ? new ImageIcon(logo) : null);
            this.logoLabel.setOpaque(false);
            add(this.logoLabel);

            this.buttons = buttons != null ? buttons : new LegacyButton[0];
            Dimension buttonSize = new Dimension(BUTTON_WIDTH, BUTTON_HEIGHT);
            for (LegacyButton button : this.buttons) {
                if (button == null) continue;
                button.setPreferredSize(buttonSize);
                button.setMinimumSize(buttonSize);
                button.setMaximumSize(buttonSize);
                add(button);
            }
        }

        public void doLayout() {
            Icon logo = logoLabel.getIcon();
            int logoWidth = logo != null ? logo.getIconWidth() : 0;
            int logoHeight = logo != null ? logo.getIconHeight() : 0;
            logoLabel.setBounds(LOGO_X, LOGO_Y, logoWidth, logoHeight);

            int buttonY = (NAV_HEIGHT - BUTTON_HEIGHT) / 2;
            int x = getWidth() - BUTTON_RIGHT;
            for (int i = buttons.length - 1; i >= 0; i--) {
                LegacyButton button = buttons[i];
                if (button == null || !button.isVisible()) continue;
                x -= BUTTON_WIDTH;
                button.setBounds(x, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT);
                x -= BUTTON_GAP;
            }
        }

        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            int textureWidth = getWidth() / 2 + 1;
            int textureHeight = getHeight() / 2 + 1;
            if (texturedBackground == null
                    || texturedBackground.getWidth() != textureWidth
                    || texturedBackground.getHeight() != textureHeight) {
                texturedBackground = renderVanillaLauncherDirt(textureWidth, textureHeight);
            }
            g0.drawImage(
                    texturedBackground,
                    0,
                    0,
                    textureWidth * 2,
                    textureHeight * 2,
                    null);
        }

        private BufferedImage renderVanillaLauncherDirt(int width, int height) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try {
                if (dirtTile != null) {
                    for (int x = 0; x <= width / TEXTURE_TILE_SIZE; x++) {
                        for (int y = 0; y <= height / TEXTURE_TILE_SIZE; y++) {
                            g.drawImage(dirtTile, x * TEXTURE_TILE_SIZE, y * TEXTURE_TILE_SIZE, null);
                        }
                    }
                } else {
                    g.setColor(getBackground());
                    g.fillRect(0, 0, width, height);
                }

                // These are the exact overlays from vanilla TexturedPanel:
                // a subtle light top edge followed by a bottom-darkening shade.
                g.setPaint(new GradientPaint(
                        0, 0, new Color(0x20FFFFFF, true),
                        0, 1, new Color(0, true)));
                g.fillRect(0, 0, width, 1);
                g.setPaint(new GradientPaint(
                        0, 0, new Color(0, true),
                        0, height, new Color(0x60000000, true)));
                g.fillRect(0, 0, width, height);
            } finally {
                g.dispose();
            }
            return image;
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
        public void log(String s) { canvas.setSubtask(s); }
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
            canvas.setSubtask(s);
        }
    }

    private static final class ProgressCanvas extends JPanel {
        private static final int TEXTURE_TILE_SIZE = 32;

        private final Image dirtTile;
        private BufferedImage frameBuffer;
        private int percentage;
        private String phase = "Initializing loader";
        private String subtask = "";

        ProgressCanvas(Path bgPath) {
            setOpaque(true);
            setBackground(Color.BLACK);
            BufferedImage dirt = loadLauncherDirtImage(bgPath);
            this.dirtTile = dirt != null
                    ? dirt.getScaledInstance(TEXTURE_TILE_SIZE, TEXTURE_TILE_SIZE, Image.SCALE_AREA_AVERAGING)
                    : null;
        }

        void setProgress(double progress) {
            this.percentage = Math.max(0, Math.min(100, (int) Math.round(progress * 100.0)));
            repaint();
        }

        void setPhase(String phase) {
            this.phase = phase != null ? phase : "";
            this.subtask = "";
            repaint();
        }

        void setSubtask(String subtask) {
            this.subtask = subtask != null ? subtask : "";
            repaint();
        }

        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            int width = getWidth() / 2;
            int height = getHeight() / 2;
            if (width <= 0 || height <= 0) return;

            if (frameBuffer == null
                    || frameBuffer.getWidth() != width
                    || frameBuffer.getHeight() != height) {
                frameBuffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            }

            Graphics2D g = frameBuffer.createGraphics();
            try {
                if (dirtTile != null) {
                    for (int x = 0; x <= width / TEXTURE_TILE_SIZE; x++) {
                        for (int y = 0; y <= height / TEXTURE_TILE_SIZE; y++) {
                            g.drawImage(dirtTile, x * TEXTURE_TILE_SIZE, y * TEXTURE_TILE_SIZE, null);
                        }
                    }
                } else {
                    g.setColor(new Color(60, 43, 29));
                    g.fillRect(0, 0, width, height);
                }

                g.setColor(Color.LIGHT_GRAY);
                String message = "Updating Minecraft";
                g.setFont(new Font(null, Font.BOLD, 20));
                FontMetrics metrics = g.getFontMetrics();
                g.drawString(
                        message,
                        width / 2 - metrics.stringWidth(message) / 2,
                        height / 2 - metrics.getHeight() * 2);

                g.setFont(new Font(null, Font.PLAIN, 12));
                metrics = g.getFontMetrics();
                message = phase != null ? phase : "";
                g.drawString(
                        message,
                        width / 2 - metrics.stringWidth(message) / 2,
                        height / 2 + metrics.getHeight());
                message = subtask != null ? subtask : "";
                g.drawString(
                        message,
                        width / 2 - metrics.stringWidth(message) / 2,
                        height / 2 + metrics.getHeight() * 2);

                int progressWidth = width - 128;
                int completedWidth = percentage * progressWidth / 100;
                g.setColor(Color.BLACK);
                g.fillRect(64, height - 64, progressWidth + 1, 5);
                g.setColor(new Color(32768));
                g.fillRect(64, height - 64, completedWidth, 4);
                g.setColor(new Color(2138144));
                g.fillRect(65, height - 63, completedWidth - 2, 1);
            } finally {
                g.dispose();
            }

            Graphics2D screen = (Graphics2D) g0;
            Object interpolation = screen.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            screen.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            screen.drawImage(frameBuffer, 0, 0, width * 2, height * 2, null);
            if (interpolation != null) {
                screen.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            }
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
    private static final class NewsPage {
        final String html;
        final URL baseUrl;
        
        NewsPage(String html, URL baseUrl) {
            this.html = html;
            this.baseUrl = baseUrl;
        }
    }
    private static final class ReleaseAsset {
        String name;
        String url;
        String digest;
        Long size;
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

    private static BufferedImage loadLauncherDirtImage(Path fallbackBgPath) {
        InputStream resource = null;
        try {
            resource = ModUpdaterGUI.class.getResourceAsStream("/dirt.png");
            if (resource != null) {
                BufferedImage bundled = ImageIO.read(resource);
                if (bundled != null) return bundled;
            }
        } catch (Exception ignored) {
        } finally {
            if (resource != null) {
                try { resource.close(); } catch (IOException ignored) {}
            }
        }

        Path developmentCopy = Paths.get("src", "dirt.png");
        if (Files.isRegularFile(developmentCopy)) {
            try {
                BufferedImage dirt = ImageIO.read(developmentCopy.toFile());
                if (dirt != null) return dirt;
            } catch (Exception ignored) {
            }
        }

        if (fallbackBgPath != null && Files.isRegularFile(fallbackBgPath)) {
            try { return ImageIO.read(fallbackBgPath.toFile()); } catch (Exception ignored) {}
        }
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

            int arc = 4; // flatter corners, matching the classic Swing controls

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
                "  \"digest\": \"" + safe(asset.digest) + "\",\n" +
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
            m.digest = extractString(text, "\\\"digest\\\"\\s*:\\s*\\\"(.*?)\\\"");
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
        String digest;
    }
}


