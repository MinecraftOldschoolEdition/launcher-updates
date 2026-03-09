import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Cross-platform Java CLI updater for Prism/MultiMC Minecraft instances.
 * 
 * This is a command-line tool that automatically downloads and installs mod updates
 * from GitHub releases. It's designed to be run as a pre-launch command in Prism Launcher
 * or similar Minecraft launchers.
 *
 * FEATURES:
 * - Fetches the latest release from a GitHub repository
 * - Selects the correct asset file using regex pattern matching
 * - Supports two installation modes:
 *   1. "mods" mode: Installs jar files to the mods/ directory
 *   2. "clientJar" mode: Replaces the client minecraft.jar (for legacy installs)
 * - Creates timestamped backups before replacing files
 * - Supports non-interactive mode (--yes flag) for automated updates
 * - Dry-run mode to preview changes without applying them
 *
 * BUILD INSTRUCTIONS (requires Java 8+):
 *   javac -encoding UTF-8 -d out src/ModUpdater.java
 *   jar cfe mod-updater.jar ModUpdater -C out .
 *
 * USAGE EXAMPLE:
 *   java -jar mod-updater.jar --repo YourOrg/YourRepo \
 *     --assetRegex "YourMod-.*\\.jar" --mode mods --minecraftDir "%MC_DIR%" --yes
 *
 * COMMAND LINE OPTIONS:
 *   --repo          Required. GitHub repository in "owner/repo" format
 *   --assetRegex    Required. Regex pattern to match the desired release asset
 *   --mode          Optional. "mods" (default) or "clientJar"
 *   --minecraftDir  Optional. Path to the .minecraft directory
 *   --instanceDir   Optional. Path to the Prism/MultiMC instance directory
 *   --clientJarPath Optional. Explicit path to minecraft.jar (for clientJar mode)
 *   --yes, -y       Optional. Skip confirmation prompts (non-interactive mode)
 *   --dryRun        Optional. Preview changes without applying them
 *
 * ENVIRONMENT VARIABLES:
 *   MC_DIR          Alternative way to specify the Minecraft directory
 *   GITHUB_TOKEN    Optional. GitHub API token for higher rate limits
 */
public final class ModUpdater {

    // =========================================================================
    // CONSTANTS
    // =========================================================================

    /** HTTP connection timeout in milliseconds */
    private static final int HTTP_TIMEOUT_MS = 15000;
    
    /** GitHub API endpoint template for fetching the latest release */
    private static final String GITHUB_API_LATEST = "https://api.github.com/repos/%s/releases/latest";
    
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
    // MAIN ENTRY POINT
    // =========================================================================

    /**
     * Main entry point for the CLI updater.
     * 
     * Workflow:
     * 1. Parse command-line arguments
     * 2. Resolve the Minecraft directory path
     * 3. Fetch the latest release info from GitHub
     * 4. Find the matching asset using the provided regex
     * 5. Download and install the update (with backup)
     * 
     * @param args Command-line arguments
     */
    public static void main(String[] args) {
        try {
            // Parse all command-line arguments into a key-value map
            Map<String, String> opts = parseArgs(args);

            // Extract required options (will throw if missing)
            String repo = required(opts, "--repo");           // GitHub repo: "owner/repo"
            String assetRegex = required(opts, "--assetRegex"); // Regex to match asset filename
            
            // Extract optional options with defaults
            String mode = opts.getOrDefault("--mode", "mods"); // Installation mode
            boolean yes = opts.containsKey("--yes") || opts.containsKey("-y") || opts.containsKey("--auto");
            boolean dryRun = opts.containsKey("--dryRun");

            // Resolve the Minecraft directory from various sources
            // Priority: --minecraftDir > MC_DIR env var > --instanceDir
            Path minecraftDir = resolveMinecraftDir(
                opts.get("--minecraftDir"),
                opts.get("--instanceDir"),
                getenv("MC_DIR")
            );

            if (minecraftDir == null) {
                throw new IllegalArgumentException("Unable to resolve Minecraft directory. Provide --minecraftDir, --instanceDir, or MC_DIR env var.");
            }

            // Fetch release info from GitHub API
            LatestRelease release = fetchLatestRelease(repo);
            
            // Find the asset that matches our regex pattern
            ReleaseAsset asset = selectAsset(release.assets, assetRegex);
            if (asset == null) {
                throw new IllegalStateException("No asset matches regex '" + assetRegex + "' in latest release.");
            }

            log("Latest: " + release.tag);
            log("Asset:  " + asset.name);

            // Handle "mods" mode - install to mods/ directory
            if ("mods".equalsIgnoreCase(mode)) {
                Path modsDir = minecraftDir.resolve("mods");
                ensureDir(modsDir);

                // Check if we already have this exact version installed
                Path existing = findExistingMatching(modsDir, assetRegex);
                if (existing != null && existing.getFileName().toString().equals(asset.name)) {
                    log("Already up to date: " + existing);
                    return;
                }

                // Ask for confirmation unless --yes was provided
                if (!yes) {
                    if (!confirm("Update mod in '" + modsDir + "'?")) return;
                }

                // Dry-run mode: just show what would happen
                if (dryRun) {
                    log("[dry-run] Would download and install to: " + modsDir.resolve(asset.name));
                    return;
                }

                // Download the new version to a temp file
                Path downloaded = downloadToTemp(asset.url, asset.name);

                // Backup existing version if present
                if (existing != null) {
                    Path backup = withUniqueSuffix(existing, ".bak");
                    log("Backing up existing -> " + backup);
                    Files.move(existing, backup, StandardCopyOption.REPLACE_EXISTING);
                }

                // Move downloaded file to final destination
                Path dest = modsDir.resolve(asset.name);
                log("Installing -> " + dest);
                Files.move(downloaded, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                log("Done.");
                
            // Handle "clientJar" mode - replace minecraft.jar directly
            } else if ("clientJar".equalsIgnoreCase(mode)) {
                String clientJarArg = opts.get("--clientJarPath");
                Path clientJarPath = resolveClientJarPath(minecraftDir, clientJarArg);
                if (clientJarPath == null) {
                    throw new IllegalArgumentException("Cannot resolve client jar path. Pass --clientJarPath or ensure 'bin/minecraft.jar' exists under " + minecraftDir);
                }

                log("Target client jar: " + clientJarPath);

                if (!yes) {
                    if (!confirm("Replace client jar? A backup will be created.")) return;
                }

                if (dryRun) {
                    log("[dry-run] Would replace client jar at: " + clientJarPath);
                    return;
                }

                // Download, backup, and replace
                Path downloaded = downloadToTemp(asset.url, asset.name);
                Path backup = withUniqueSuffix(clientJarPath, ".bak");
                log("Backing up -> " + backup);
                Files.copy(clientJarPath, backup, StandardCopyOption.REPLACE_EXISTING);
                log("Updating client jar...");
                Files.move(downloaded, clientJarPath, StandardCopyOption.REPLACE_EXISTING);
                log("Client jar updated.");
            } else {
                throw new IllegalArgumentException("Unsupported --mode: " + mode);
            }
        } catch (Exception e) {
            logErr(e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    // =========================================================================
    // ARGUMENT PARSING
    // =========================================================================

    /**
     * Parses command-line arguments into a key-value map.
     * 
     * Supports two types of arguments:
     * - Flags (--yes, -y, --dryRun): stored with value "true"
     * - Options (--repo value): stored with the following argument as value
     * 
     * @param args Raw command-line arguments
     * @return Map of argument names to values
     */
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            // Handle boolean flags (no value expected)
            if ("--yes".equals(a) || "-y".equals(a) || "--dryRun".equals(a) || "--auto".equals(a)) {
                map.put(a, "true");
            // Handle options with values
            } else if (a.startsWith("--")) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + a);
                map.put(a, args[++i]);
            } else {
                throw new IllegalArgumentException("Unknown argument: " + a);
            }
        }
        return map;
    }

    /**
     * Gets a required option value, throwing if not present.
     * 
     * @param opts Parsed options map
     * @param key Option key to retrieve
     * @return Option value
     * @throws IllegalArgumentException if option is missing or empty
     */
    private static String required(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException("Missing required option: " + key);
        return v;
    }

    // =========================================================================
    // PATH RESOLUTION
    // =========================================================================

    /**
     * Resolves the Minecraft directory from multiple possible sources.
     * 
     * Priority order:
     * 1. Explicit --minecraftDir argument
     * 2. MC_DIR environment variable
     * 3. Derived from --instanceDir (looks for .minecraft or minecraft subdirectory)
     * 
     * @param minecraftDirArg Value of --minecraftDir if provided
     * @param instanceDirArg Value of --instanceDir if provided
     * @param mcDirEnv Value of MC_DIR environment variable
     * @return Resolved path, or null if unable to resolve
     */
    private static Path resolveMinecraftDir(String minecraftDirArg, String instanceDirArg, String mcDirEnv) {
        try {
            // Try explicit minecraft directory first
            if (minecraftDirArg != null) {
                Path p = Paths.get(minecraftDirArg);
                if (Files.isDirectory(p)) return p.toAbsolutePath();
            }
            // Try environment variable
            if (mcDirEnv != null && !mcDirEnv.trim().isEmpty()) {
                Path p = Paths.get(mcDirEnv);
                if (Files.isDirectory(p)) return p.toAbsolutePath();
            }
            // Try deriving from instance directory
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

    /**
     * Resolves the path to the client minecraft.jar file.
     * 
     * Used in "clientJar" mode for legacy Minecraft installations.
     * 
     * @param minecraftDir The .minecraft directory
     * @param clientJarArg Explicit path if provided via --clientJarPath
     * @return Path to minecraft.jar, or null if not found
     */
    private static Path resolveClientJarPath(Path minecraftDir, String clientJarArg) {
        try {
            // Use explicit path if provided
            if (clientJarArg != null) {
                Path p = Paths.get(clientJarArg);
                if (Files.isRegularFile(p)) return p.toAbsolutePath();
            }
            // Default location: bin/minecraft.jar
            if (minecraftDir != null) {
                Path p = minecraftDir.resolve("bin").resolve("minecraft.jar");
                if (Files.isRegularFile(p)) return p.toAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        return null;
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
        if (!Files.isDirectory(dir)) Files.createDirectories(dir);
    }

    /**
     * Creates a unique backup filename by appending a timestamp.
     * 
     * Example: "mod.jar" -> "mod.jar.bak.1699123456789"
     * 
     * @param path Original file path
     * @param suffix Suffix to append (e.g., ".bak")
     * @return New path with unique suffix
     */
    private static Path withUniqueSuffix(Path path, String suffix) {
        String name = path.getFileName().toString();
        String stamp = String.valueOf(System.currentTimeMillis());
        Path parent = path.getParent();
        return parent.resolve(name + suffix + "." + stamp);
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
                logErr("TLS compatibility initialization failed; using default JVM trust store only: " + ex.getMessage());
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

    /**
     * Finds an existing file in a directory that matches the given regex.
     * 
     * If multiple files match, returns the most recently modified one.
     * This is used to find the currently installed version of a mod.
     * 
     * @param dir Directory to search in
     * @param assetRegex Regex pattern to match filenames
     * @return Path to matching file, or null if none found
     */
    private static Path findExistingMatching(Path dir, String assetRegex) throws IOException {
        final Pattern p = Pattern.compile(assetRegex);
        try {
            File[] files = dir.toFile().listFiles();
            if (files == null) return null;
            
            // Track the newest matching file
            Path newest = null;
            long newestTs = Long.MIN_VALUE;
            
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

    // =========================================================================
    // GITHUB API INTERACTION
    // =========================================================================

    /**
     * Fetches the latest release information from a GitHub repository.
     * 
     * Uses the GitHub REST API to get release metadata including:
     * - Release tag/version
     * - List of downloadable assets
     * 
     * If the /releases/latest endpoint returns 404 (no release marked as "latest"),
     * falls back to /releases and uses the first (most recent) release.
     * 
     * Supports GITHUB_TOKEN environment variable for authenticated requests
     * (higher rate limits).
     * 
     * @param repo Repository in "owner/repo" format
     * @return LatestRelease object with release metadata
     * @throws IOException If API request fails
     */
    private static LatestRelease fetchLatestRelease(String repo) throws IOException {
        // Try /releases/latest endpoint first
        String url = String.format(GITHUB_API_LATEST, repo);
        HttpURLConnection conn = openHttpConnection(url, HTTP_TIMEOUT_MS, HTTP_TIMEOUT_MS, "ModUpdater/1.0");
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        
        // Add auth token if available (for higher rate limits)
        String token = getenv("GITHUB_TOKEN");
        if (token != null && !token.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "token " + token.trim());
        }
        
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(in);
        
        // If /releases/latest returns 404, fall back to /releases list
        if (code == 404) {
            String fallbackUrl = "https://api.github.com/repos/" + repo + "/releases";
            HttpURLConnection fallbackConn = openHttpConnection(fallbackUrl, HTTP_TIMEOUT_MS, HTTP_TIMEOUT_MS, "ModUpdater/1.0");
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
            return parseFirstReleaseFromArray(fallbackBody);
        }
        
        if (code < 200 || code >= 300) {
            throw new IOException("GitHub API error: HTTP " + code + " " + truncateErrorBody(body));
        }
        return parseLatestRelease(body);
    }
    
    /**
     * Parses the first release from a JSON array of releases.
     * 
     * Used as a fallback when /releases/latest returns 404.
     * The first element in the array is the most recent release.
     * 
     * @param json JSON array string from GitHub API
     * @return Parsed release information
     */
    private static LatestRelease parseFirstReleaseFromArray(String json) {
        int start = json.indexOf('[');
        if (start < 0) return parseLatestRelease(json);
        
        // Find the first { after the [
        int braceStart = json.indexOf('{', start);
        if (braceStart < 0) return new LatestRelease();
        
        // Find the matching closing }
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
        
        // Extract and parse just the first release object
        String firstRelease = json.substring(braceStart, braceEnd + 1);
        return parseLatestRelease(firstRelease);
    }

    /**
     * Reads all content from an input stream into a string.
     * 
     * @param in Input stream to read
     * @return Complete content as a string
     */
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

    // =========================================================================
    // JSON PARSING (minimal, no external dependencies)
    // =========================================================================

    /**
     * Parses a GitHub release JSON object into a LatestRelease.
     * 
     * Uses simple regex-based parsing to avoid external JSON library dependencies.
     * This keeps the updater lightweight and easy to deploy.
     * 
     * @param json JSON string representing a release object
     * @return Parsed release information
     */
    private static LatestRelease parseLatestRelease(String json) {
        LatestRelease r = new LatestRelease();
        r.tag = extractString(json, "\"tag_name\"\\s*:\\s*\"(.*?)\"");
        r.assets = extractAssets(json);
        return r;
    }

    /**
     * Extracts a string value from JSON using a regex pattern.
     * 
     * @param text JSON text to search
     * @param regex Pattern with a capture group for the value
     * @return Extracted and unescaped value, or null if not found
     */
    private static String extractString(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        if (m.find()) return unescapeJson(m.group(1));
        return null;
    }

    /**
     * Extracts the list of assets from a release JSON object.
     * 
     * Parses the "assets" array and extracts name and download URL for each.
     * 
     * @param json Release JSON object
     * @return List of release assets
     */
    private static List<ReleaseAsset> extractAssets(String json) {
        List<ReleaseAsset> list = new ArrayList<ReleaseAsset>();
        
        // Find the assets array
        int idx = json.indexOf("\"assets\"");
        if (idx < 0) return list;
        int startArray = json.indexOf('[', idx);
        if (startArray < 0) return list;
        int endArray = findMatchingBracket(json, startArray);
        if (endArray < 0) return list;
        String assetsArray = json.substring(startArray + 1, endArray);

        // Extract each asset object
        Pattern objPat = Pattern.compile("\\{(.*?)\\}", Pattern.DOTALL);
        Matcher m = objPat.matcher(assetsArray);
        while (m.find()) {
            String obj = m.group(1);
            String name = extractString(obj, "\"name\"\\s*:\\s*\"(.*?)\"");
            String url = extractString(obj, "\"browser_download_url\"\\s*:\\s*\"(.*?)\"");
            if (name != null && url != null) {
                ReleaseAsset a = new ReleaseAsset();
                a.name = name;
                a.url = url;
                list.add(a);
            }
        }
        return list;
    }

    /**
     * Finds the index of the closing bracket matching an opening bracket.
     * 
     * @param s String to search
     * @param openIdx Index of the opening bracket '['
     * @return Index of matching ']', or -1 if not found
     */
    private static int findMatchingBracket(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * Unescapes common JSON escape sequences.
     * 
     * Handles: quotes, backslashes, slashes, backspace, formfeed, newline, 
     * carriage return, tab, and unicode escapes.
     * 
     * @param s JSON-escaped string
     * @return Unescaped string
     */
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
                    // Handle unicode escape sequence (backslash-u followed by 4 hex digits)
                    String hex = s.substring(i + 2, i + 6);
                    try { out.append((char) Integer.parseInt(hex, 16)); } 
                    catch (NumberFormatException ignored) { out.append('?'); }
                    i += 5;
                } else { out.append(n); i++; }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Selects an asset from the list that matches the given regex.
     * 
     * @param assets List of available assets
     * @param assetRegex Pattern to match against asset names
     * @return First matching asset, or null if none match
     */
    private static ReleaseAsset selectAsset(List<ReleaseAsset> assets, String assetRegex) {
        Pattern p = Pattern.compile(assetRegex);
        for (ReleaseAsset a : assets) {
            if (p.matcher(a.name).find()) return a;
        }
        return null;
    }

    // =========================================================================
    // DOWNLOAD
    // =========================================================================

    /**
     * Downloads a file from a URL to a temporary location.
     * 
     * The file is downloaded to the system temp directory with the suggested name.
     * This allows atomic move operations when installing to the final destination.
     * 
     * @param url URL to download from
     * @param suggestedName Filename to use in temp directory
     * @return Path to downloaded file
     * @throws IOException If download fails
     */
    private static Path downloadToTemp(String url, String suggestedName) throws IOException {
        String tmpDir = System.getProperty("java.io.tmpdir");
        Path tmp = Paths.get(tmpDir, suggestedName);
        
        HttpURLConnection conn = openHttpConnection(url, HTTP_TIMEOUT_MS, HTTP_TIMEOUT_MS, "ModUpdater/1.0");
        
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            InputStream err = conn.getErrorStream();
            String body = err != null ? readAll(err) : "";
            throw new IOException("Download failed: HTTP " + code + "\n" + body);
        }
        
        // Stream download to temp file
        InputStream in = new BufferedInputStream(conn.getInputStream());
        FileOutputStream out = new FileOutputStream(tmp.toFile());
        byte[] buf = new byte[64 * 1024]; // 64KB buffer
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            try { out.close(); } catch (IOException ignored) {}
        }
        return tmp;
    }

    // =========================================================================
    // USER INTERACTION
    // =========================================================================

    /**
     * Prompts the user for yes/no confirmation.
     * 
     * Default is yes if user presses Enter without input.
     * 
     * @param question Question to display
     * @return true if user confirmed, false otherwise
     */
    private static boolean confirm(String question) throws IOException {
        System.out.print(question + " [Y/n]: ");
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        String line = br.readLine();
        if (line == null || line.trim().isEmpty()) return true; // Default: yes
        String s = line.trim().toLowerCase();
        return s.startsWith("y");
    }

    // =========================================================================
    // UTILITIES
    // =========================================================================

    /**
     * Safely gets an environment variable, returning null on security exceptions.
     */
    private static String getenv(String key) {
        try { return System.getenv(key); } catch (SecurityException ignored) { return null; }
    }

    /** Logs a message to stdout */
    private static void log(String s) { System.out.println(s); }
    
    /** Logs an error message to stderr */
    private static void logErr(String s) { if (s != null) System.err.println(s); }

    // =========================================================================
    // DATA CLASSES
    // =========================================================================

    /**
     * Represents a GitHub release with its tag and downloadable assets.
     */
    private static final class LatestRelease {
        /** Release tag/version (e.g., "v1.0.0") */
        String tag;
        /** List of downloadable files attached to this release */
        List<ReleaseAsset> assets;
    }

    /**
     * Represents a downloadable asset attached to a GitHub release.
     */
    private static final class ReleaseAsset {
        /** Filename of the asset */
        String name;
        /** Direct download URL */
        String url;
    }
}
