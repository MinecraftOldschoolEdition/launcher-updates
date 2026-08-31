import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ResourceDownloadRegionalFallbackTest {
    private static final String REPO = "test/repo";
    private static final String BRANCH = "main";
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String RESOURCE_PATH = "assets/minecraft/regional-fallback.txt";
    private static final String TRACKED_DELETE_PATH = "assets/old/removed-resource.txt";
    private static final int BLOB_FALLBACK_FILE_COUNT = 8;
    private static final String ARCHIVE_MIRROR_TEMPLATE =
            "https://resources.example.test/{repo}/{branch}/resourcepack.zip";
    private static final byte[] RESOURCE_BYTES = "regional fallback works".getBytes(StandardCharsets.UTF_8);
    private static final byte[] STALE_RESOURCE_BYTES = "stale regional mirror".getBytes(StandardCharsets.UTF_8);

    private static volatile Scenario scenario;
    private static final AtomicInteger rawAttempts = new AtomicInteger();
    private static final AtomicInteger cdnAttempts = new AtomicInteger();
    private static final AtomicInteger apiBlobAttempts = new AtomicInteger();
    private static final AtomicInteger metadataAttempts = new AtomicInteger();
    private static final AtomicInteger codeloadAttempts = new AtomicInteger();
    private static final AtomicInteger archiveAttempts = new AtomicInteger();
    private static final AtomicInteger mirrorAttempts = new AtomicInteger();

    private enum Scenario {
        BLOB_HOSTS_BLOCKED,
        CDN_HOST_BLOCKED,
        TRANSIENT_ENDPOINT_ERRORS,
        METADATA_API_BLOCKED,
        ALL_GITHUB_BLOCKED,
        MIRROR_CONTENT_MISMATCH,
        MIRROR_EXTRA_FILE,
        CORRUPT_FIRST_ARCHIVE,
        STALE_MIRROR_DURING_OUTAGE,
        TRACKED_DELETE_SYMLINK,
        TREE_METADATA_BLOCKED,
        CORRUPT_ARCHIVE,
        INTERRUPTED_METADATA,
        INTERRUPTED_ARCHIVE_METADATA,
        INTERRUPTED_AFTER_ARCHIVE_METADATA
    }

    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new FakeHandler() : null);

        AssertionError firstFailure = null;
        try {
            verifyBlobMirrorFallback();
        } catch (AssertionError failure) {
            firstFailure = failure;
        }
        try {
            verifyMetadataArchiveFallback();
        } catch (AssertionError failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        try {
            verifyProviderCircuitBreaker();
        } catch (AssertionError failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        try {
            verifyIndependentArchiveMirror();
        } catch (AssertionError failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        try {
            verifyUnverifiedArchiveDoesNotClaimCommit();
        } catch (AssertionError failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        try {
            verifyMismatchedMirrorRejectedBeforeReplacement();
        } catch (AssertionError failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        try {
            verifyExtraMirrorFileRejected();
        } catch (AssertionError failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        try {
            verifyCorruptFirstArchiveFallsThrough();
        } catch (AssertionError failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        try {
            verifyUnverifiedArchiveInvalidatesManifest();
        } catch (AssertionError failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        try {
            verifyCorruptArchivePreservesLiveResources();
        } catch (AssertionError failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        try {
            verifyTransientRetry();
        } catch (AssertionError failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        try {
            verifyInterruptionDoesNotFallback();
        } catch (AssertionError failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        try {
            verifyInterruptionDuringArchiveVerificationDoesNotPromote();
        } catch (AssertionError failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        try {
            verifyInterruptionBeforeArchivePromotionDoesNotInstall();
        } catch (AssertionError failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        try {
            verifySymlinkDestinationRejected();
        } catch (AssertionError failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        try {
            verifyIncrementalDeletionRejectsSymlinkParent();
        } catch (AssertionError failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        try {
            verifyPreparedDeletionRejectsSymlinkParent();
        } catch (AssertionError failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        if (firstFailure != null) throw firstFailure;
        System.out.println("REGIONAL_RESOURCE_FALLBACK_OK");
    }

    private static void verifyBlobMirrorFallback() throws Exception {
        scenario = Scenario.BLOB_HOSTS_BLOCKED;
        rawAttempts.set(0);
        cdnAttempts.set(0);
        apiBlobAttempts.set(0);
        Path root = Files.createTempDirectory("resource-blob-fallback-");
        try {
            Object result = invokeSmartSync(root);
            assertSuccessfulDownload(result, root, "independent per-file mirror");
            if (cdnAttempts.get() == 0) {
                throw new AssertionError("The commit-pinned jsDelivr file mirror was not attempted.");
            }
            for (int i = 0; i < BLOB_FALLBACK_FILE_COUNT; i++) {
                Path downloaded = root.resolve("resources").resolve(blobFallbackPath(i));
                if (!Files.isRegularFile(downloaded)
                        || !Arrays.equals(RESOURCE_BYTES, Files.readAllBytes(downloaded))) {
                    throw new AssertionError("Circuit-breaker fallback did not install " + blobFallbackPath(i));
                }
            }
            if (rawAttempts.get() != 0 || apiBlobAttempts.get() != 0) {
                throw new AssertionError("GitHub delivery hosts were used even though the independent mirror succeeded.");
            }
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyMetadataArchiveFallback() throws Exception {
        scenario = Scenario.METADATA_API_BLOCKED;
        codeloadAttempts.set(0);
        Path root = Files.createTempDirectory("resource-metadata-fallback-");
        try {
            Path stale = root.resolve("resources").resolve(RESOURCE_PATH);
            Files.createDirectories(stale.getParent());
            Files.write(stale, "stale resource bytes".getBytes(StandardCharsets.UTF_8));
            Object result = invokeSmartSync(root);
            assertSuccessfulDownload(result, root, "archive recovery when metadata API is unavailable");
            if (codeloadAttempts.get() == 0) {
                throw new AssertionError("The codeload archive contingency was not attempted.");
            }
            if (Files.exists(root.resolve("resources").resolve(".mcose-resource-sync.properties"))) {
                throw new AssertionError("Metadata-blocked archive recovery must not advance the incremental manifest.");
            }
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyProviderCircuitBreaker() throws Exception {
        scenario = Scenario.CDN_HOST_BLOCKED;
        rawAttempts.set(0);
        cdnAttempts.set(0);
        Path root = Files.createTempDirectory("resource-provider-circuit-");
        try {
            Object result = invokeSmartSync(root);
            assertSuccessfulDownload(result, root, "provider circuit breaker");
            for (int i = 0; i < BLOB_FALLBACK_FILE_COUNT; i++) {
                Path downloaded = root.resolve("resources").resolve(blobFallbackPath(i));
                if (!Files.isRegularFile(downloaded)
                        || !Arrays.equals(RESOURCE_BYTES, Files.readAllBytes(downloaded))) {
                    throw new AssertionError("Circuit-breaker fallback did not install " + blobFallbackPath(i));
                }
            }
            if (cdnAttempts.get() > 4) {
                throw new AssertionError("Blocked CDN was retried for queued files; attempts=" + cdnAttempts.get());
            }
            if (rawAttempts.get() != BLOB_FALLBACK_FILE_COUNT) {
                throw new AssertionError("GitHub raw fallback did not serve every queued file; attempts="
                        + rawAttempts.get());
            }
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyIndependentArchiveMirror() throws Exception {
        scenario = Scenario.ALL_GITHUB_BLOCKED;
        mirrorAttempts.set(0);
        Path root = Files.createTempDirectory("resource-independent-archive-");
        try {
            Object result = invokeSmartSync(root, ARCHIVE_MIRROR_TEMPLATE);
            assertSuccessfulDownload(result, root, "independent archive mirror");
            if (mirrorAttempts.get() == 0) {
                throw new AssertionError("The configured independent archive mirror was not attempted.");
            }
            Field sourceUrl = result.getClass().getDeclaredField("sourceUrl");
            sourceUrl.setAccessible(true);
            String expected = "https://resources.example.test/test/repo/main/resourcepack.zip";
            if (!expected.equals(sourceUrl.get(result))) {
                throw new AssertionError("Independent archive source was not retained in the result.");
            }
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyUnverifiedArchiveDoesNotClaimCommit() throws Exception {
        scenario = Scenario.TREE_METADATA_BLOCKED;
        Path root = Files.createTempDirectory("resource-unverified-commit-");
        try {
            Object result = invokeSmartSync(root);
            assertSuccessfulDownload(result, root, "tree-metadata archive recovery");
            Field sourceCommit = result.getClass().getDeclaredField("sourceCommit");
            sourceCommit.setAccessible(true);
            if (sourceCommit.get(result) != null) {
                throw new AssertionError("Unverified archive falsely reported a verified source commit.");
            }
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyMismatchedMirrorRejectedBeforeReplacement() throws Exception {
        scenario = Scenario.MIRROR_CONTENT_MISMATCH;
        metadataAttempts.set(0);
        Path root = Files.createTempDirectory("resource-mirror-mismatch-");
        byte[] original = "known-good-live-resource".getBytes(StandardCharsets.UTF_8);
        try {
            Path live = root.resolve("resources").resolve(RESOURCE_PATH);
            Files.createDirectories(live.getParent());
            Files.write(live, original);
            Throwable failure = invokeSmartSyncExpectFailure(root, ARCHIVE_MIRROR_TEMPLATE);
            if (failure.getMessage() == null || failure.getMessage().indexOf("did not match") < 0) {
                throw new AssertionError("Mirror mismatch was not reported clearly: " + failure);
            }
            if (!Arrays.equals(original, Files.readAllBytes(live))) {
                throw new AssertionError("Mismatched mirror replaced the known-good live resource.");
            }
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyExtraMirrorFileRejected() throws Exception {
        scenario = Scenario.MIRROR_EXTRA_FILE;
        metadataAttempts.set(0);
        Path root = Files.createTempDirectory("resource-mirror-extra-");
        try {
            Throwable failure = invokeSmartSyncExpectFailure(root, ARCHIVE_MIRROR_TEMPLATE);
            if (failure.getMessage() == null || failure.getMessage().indexOf("untracked file") < 0) {
                throw new AssertionError("Extra mirror file was not reported clearly: " + failure);
            }
            if (Files.exists(root.resolve("resources/assets/minecraft/untracked-extra.txt"))) {
                throw new AssertionError("Untracked mirror file was promoted into live resources.");
            }
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyCorruptFirstArchiveFallsThrough() throws Exception {
        scenario = Scenario.CORRUPT_FIRST_ARCHIVE;
        codeloadAttempts.set(0);
        Path root = Files.createTempDirectory("resource-corrupt-first-archive-");
        try {
            Object result = invokeSmartSync(root);
            assertSuccessfulDownload(result, root, "fully verified archive fallback");
            if (codeloadAttempts.get() == 0) {
                throw new AssertionError("A corrupt first archive prevented the next provider from being attempted.");
            }
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyUnverifiedArchiveInvalidatesManifest() throws Exception {
        Path root = Files.createTempDirectory("resource-stale-mirror-recovery-");
        try {
            scenario = Scenario.BLOB_HOSTS_BLOCKED;
            Object initial = invokeSmartSync(root);
            assertSuccessfulDownload(initial, root, "initial tracked resource sync");
            Path manifest = root.resolve("resources/.mcose-resource-sync.properties");
            if (!Files.isRegularFile(manifest)) {
                throw new AssertionError("Initial resource sync did not create a tracked manifest.");
            }

            scenario = Scenario.STALE_MIRROR_DURING_OUTAGE;
            Object outage = invokeSmartSync(root, ARCHIVE_MIRROR_TEMPLATE);
            Field success = outage.getClass().getDeclaredField("success");
            success.setAccessible(true);
            if (!success.getBoolean(outage)) {
                throw new AssertionError("Trusted archive was not installed during the metadata outage.");
            }
            Path live = root.resolve("resources").resolve(RESOURCE_PATH);
            if (!Arrays.equals(STALE_RESOURCE_BYTES, Files.readAllBytes(live))) {
                throw new AssertionError("Stale-mirror outage fixture was not installed as expected.");
            }
            if (Files.exists(manifest)) {
                throw new AssertionError("Unverified archive promotion left a false current manifest behind.");
            }

            scenario = Scenario.BLOB_HOSTS_BLOCKED;
            Object recovered = invokeSmartSync(root);
            assertSuccessfulDownload(recovered, root, "same-commit integrity recovery");
        } finally {
            deleteTree(root);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void verifyCorruptArchivePreservesLiveResources() throws Exception {
        scenario = Scenario.CORRUPT_ARCHIVE;
        Path root = Files.createTempDirectory("resource-corrupt-archive-");
        byte[] original = "known-good-before-corrupt-archive".getBytes(StandardCharsets.UTF_8);
        try {
            Path live = root.resolve("resources").resolve(RESOURCE_PATH);
            Files.createDirectories(live.getParent());
            Files.write(live, original);
            Path archive = root.resolve("corrupt-resourcepack.zip");
            Files.write(archive, corruptResourceArchive());

            Class<?> updater = Class.forName("ModUpdaterGUI");
            Class<? extends Enum> modeType = (Class<? extends Enum>) Class.forName("ModUpdaterGUI$ResourceSyncMode");
            Class<?> resultType = Class.forName("ModUpdaterGUI$ResourceSyncResult");
            java.lang.reflect.Constructor<?> resultConstructor = resultType.getDeclaredConstructor();
            resultConstructor.setAccessible(true);
            Object result = resultConstructor.newInstance();
            Method apply = updater.getDeclaredMethod(
                    "applyStagedResourcePackArchive",
                    Path.class,
                    String.class,
                    String.class,
                    Path.class,
                    modeType,
                    resultType);
            apply.setAccessible(true);
            try {
                apply.invoke(null, archive, REPO, BRANCH, root, Enum.valueOf(modeType, "FULL"), result);
                throw new AssertionError("Corrupt resource archive unexpectedly succeeded.");
            } catch (InvocationTargetException expected) {
                if (!(expected.getCause() instanceof IOException)) {
                    throw new AssertionError("Unexpected corrupt-archive failure type: " + expected.getCause());
                }
            }
            if (!Arrays.equals(original, Files.readAllBytes(live))) {
                throw new AssertionError("Corrupt archive partially replaced live resources.");
            }
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyTransientRetry() throws Exception {
        scenario = Scenario.TRANSIENT_ENDPOINT_ERRORS;
        rawAttempts.set(0);
        cdnAttempts.set(0);
        apiBlobAttempts.set(0);
        metadataAttempts.set(0);
        Path root = Files.createTempDirectory("resource-transient-retry-");
        try {
            Object result = invokeSmartSync(root);
            assertSuccessfulDownload(result, root, "bounded retries after transient endpoint failures");
            if (rawAttempts.get() < 2 && cdnAttempts.get() < 2 && apiBlobAttempts.get() < 2) {
                throw new AssertionError("No resource endpoint was retried after a transient failure.");
            }
            if (metadataAttempts.get() < 2) {
                throw new AssertionError("Transient resource metadata failure was not retried.");
            }
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyInterruptionDoesNotFallback() throws Exception {
        scenario = Scenario.INTERRUPTED_METADATA;
        archiveAttempts.set(0);
        Path root = Files.createTempDirectory("resource-interrupted-");
        try {
            Class<?> updater = Class.forName("ModUpdaterGUI");
            @SuppressWarnings("rawtypes")
            Class<? extends Enum> modeType = (Class<? extends Enum>) Class.forName("ModUpdaterGUI$ResourceSyncMode");
            Method sync = updater.getDeclaredMethod("syncResourcePack", String.class, String.class, Path.class,
                    modeType, boolean.class, long.class);
            sync.setAccessible(true);
            try {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object smart = Enum.valueOf(modeType, "SMART");
                sync.invoke(null, REPO, BRANCH, root, smart, true, 0L);
                throw new AssertionError("Interrupted metadata sync unexpectedly succeeded.");
            } catch (InvocationTargetException expected) {
                if (!(expected.getCause() instanceof IOException)) {
                    throw new AssertionError("Unexpected interruption failure type: " + expected.getCause());
                }
                if (!Thread.currentThread().isInterrupted()) {
                    throw new AssertionError("Resource sync did not preserve the interruption flag.");
                }
                if (archiveAttempts.get() != 0) {
                    throw new AssertionError("Archive recovery started after cancellation.");
                }
            } finally {
                Thread.interrupted();
            }
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyInterruptionDuringArchiveVerificationDoesNotPromote() throws Exception {
        scenario = Scenario.INTERRUPTED_ARCHIVE_METADATA;
        metadataAttempts.set(0);
        Path root = Files.createTempDirectory("resource-archive-verification-interrupted-");
        byte[] original = "known-good-before-archive-cancellation".getBytes(StandardCharsets.UTF_8);
        byte[] manifest = "manifest-must-remain".getBytes(StandardCharsets.UTF_8);
        try {
            Path live = root.resolve("resources").resolve(RESOURCE_PATH);
            Path manifestPath = root.resolve("resources/.mcose-resource-sync.properties");
            Files.createDirectories(live.getParent());
            Files.write(live, original);
            Files.write(manifestPath, manifest);

            Throwable failure = invokeSmartSyncExpectFailure(root, null);
            if (!Thread.currentThread().isInterrupted()) {
                throw new AssertionError("Archive metadata cancellation did not preserve the interruption flag: "
                        + failure);
            }
            if (!Arrays.equals(original, Files.readAllBytes(live))) {
                throw new AssertionError("Cancelled archive verification promoted staged resource bytes.");
            }
            if (!Arrays.equals(manifest, Files.readAllBytes(manifestPath))) {
                throw new AssertionError("Cancelled archive verification invalidated the existing manifest.");
            }
        } finally {
            Thread.interrupted();
            deleteTree(root);
        }
    }

    private static void verifyInterruptionBeforeArchivePromotionDoesNotInstall() throws Exception {
        scenario = Scenario.INTERRUPTED_AFTER_ARCHIVE_METADATA;
        metadataAttempts.set(0);
        Path root = Files.createTempDirectory("resource-archive-promotion-interrupted-");
        byte[] original = "known-good-before-promotion-cancellation".getBytes(StandardCharsets.UTF_8);
        byte[] manifest = "promotion-manifest-must-remain".getBytes(StandardCharsets.UTF_8);
        try {
            Path live = root.resolve("resources").resolve(RESOURCE_PATH);
            Path manifestPath = root.resolve("resources/.mcose-resource-sync.properties");
            Files.createDirectories(live.getParent());
            Files.write(live, original);
            Files.write(manifestPath, manifest);

            Throwable failure = invokeSmartSyncExpectFailure(root, null);
            if (!Thread.currentThread().isInterrupted()) {
                throw new AssertionError("Pre-promotion cancellation did not preserve the interruption flag: "
                        + failure);
            }
            if (!Arrays.equals(original, Files.readAllBytes(live))) {
                throw new AssertionError("Pre-promotion cancellation installed staged resource bytes.");
            }
            if (!Arrays.equals(manifest, Files.readAllBytes(manifestPath))) {
                throw new AssertionError("Pre-promotion cancellation changed the existing manifest.");
            }
        } finally {
            Thread.interrupted();
            deleteTree(root);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void verifySymlinkDestinationRejected() throws Exception {
        Path root = Files.createTempDirectory("resource-symlink-escape-");
        try {
            Path outside = root.resolve("outside");
            Path assets = root.resolve("resources").resolve("assets");
            Files.createDirectories(outside);
            Files.createDirectories(assets);
            Files.createSymbolicLink(assets.resolve("minecraft"), outside);
            Path archive = root.resolve("resourcepack.zip");
            Files.write(archive, resourceArchive());

            Class<?> updater = Class.forName("ModUpdaterGUI");
            Class<? extends Enum> modeType = (Class<? extends Enum>) Class.forName("ModUpdaterGUI$ResourceSyncMode");
            Class<?> resultType = Class.forName("ModUpdaterGUI$ResourceSyncResult");
            java.lang.reflect.Constructor<?> resultConstructor = resultType.getDeclaredConstructor();
            resultConstructor.setAccessible(true);
            Object result = resultConstructor.newInstance();
            Method extract = updater.getDeclaredMethod(
                    "extractResourcePackArchive", Path.class, Path.class, modeType, resultType);
            extract.setAccessible(true);
            try {
                extract.invoke(null, archive, root, Enum.valueOf(modeType, "FULL"), result);
                throw new AssertionError("Resource extraction followed a symlink outside resources.");
            } catch (InvocationTargetException expected) {
                if (!(expected.getCause() instanceof IOException)) {
                    throw new AssertionError("Unexpected symlink rejection type: " + expected.getCause());
                }
            }
            if (Files.exists(outside.resolve("regional-fallback.txt"))) {
                throw new AssertionError("Resource extraction wrote outside the resources directory.");
            }
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyIncrementalDeletionRejectsSymlinkParent() throws Exception {
        scenario = Scenario.TRACKED_DELETE_SYMLINK;
        Path root = Files.createTempDirectory("resource-incremental-delete-symlink-");
        try {
            Path outsideFile = prepareTrackedDeletionSymlink(root);
            Object result = invokeSmartSync(root);
            assertSuccessfulDownload(result, root, "link-safe incremental deletion recovery");
            if (!Files.isRegularFile(outsideFile)) {
                throw new AssertionError("Incremental tracked deletion escaped through a symlink parent.");
            }
        } finally {
            deleteTree(root);
        }
    }

    private static void verifyPreparedDeletionRejectsSymlinkParent() throws Exception {
        scenario = Scenario.TRACKED_DELETE_SYMLINK;
        Path root = Files.createTempDirectory("resource-prepared-delete-symlink-");
        try {
            Path outsideFile = prepareTrackedDeletionSymlink(root);
            Object result = invokeFullSync(root);
            assertSuccessfulDownload(result, root, "link-safe post-archive deletion");
            if (!Files.isRegularFile(outsideFile)) {
                throw new AssertionError("Prepared-state tracked deletion escaped through a symlink parent.");
            }
        } finally {
            deleteTree(root);
        }
    }

    private static Path prepareTrackedDeletionSymlink(Path root) throws Exception {
        Path outside = root.resolve("outside-delete-target");
        Path outsideFile = outside.resolve("removed-resource.txt");
        Files.createDirectories(outside);
        Files.write(outsideFile, "must remain outside resources".getBytes(StandardCharsets.UTF_8));
        Path assets = root.resolve("resources/assets");
        Files.createDirectories(assets);
        Files.createSymbolicLink(assets.resolve("old"), outside);

        String encodedPath = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(TRACKED_DELETE_PATH.getBytes(StandardCharsets.UTF_8));
        String previousCommit = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String manifest = "format=1\n"
                + "repo=" + REPO + "\n"
                + "branch=" + BRANCH + "\n"
                + "commit=" + previousCommit + "\n"
                + "checkedAt=0\n"
                + "file." + encodedPath + "=" + gitBlobSha("previous".getBytes(StandardCharsets.UTF_8)) + "\n";
        Files.write(root.resolve("resources/.mcose-resource-sync.properties"),
                manifest.getBytes(StandardCharsets.UTF_8));
        return outsideFile;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object invokeSmartSync(Path root) throws Exception {
        return invokeSmartSync(root, null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object invokeSmartSync(Path root, String archiveMirrorUrl) throws Exception {
        Class<?> updater = Class.forName("ModUpdaterGUI");
        Class<? extends Enum> modeType = (Class<? extends Enum>) Class.forName("ModUpdaterGUI$ResourceSyncMode");
        Method sync = updater.getDeclaredMethod("syncResourcePack", String.class, String.class, Path.class,
                modeType, boolean.class, long.class, String.class);
        sync.setAccessible(true);
        try {
            return sync.invoke(null, REPO, BRANCH, root, Enum.valueOf(modeType, "SMART"), true, 0L,
                    archiveMirrorUrl);
        } catch (InvocationTargetException failure) {
            throw new AssertionError("Expected regional fallback to recover from " + scenario
                    + ", but sync failed: " + failure.getCause(), failure.getCause());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object invokeFullSync(Path root) throws Exception {
        Class<?> updater = Class.forName("ModUpdaterGUI");
        Class<? extends Enum> modeType = (Class<? extends Enum>) Class.forName("ModUpdaterGUI$ResourceSyncMode");
        Method sync = updater.getDeclaredMethod("syncResourcePack", String.class, String.class, Path.class,
                modeType, boolean.class, long.class, String.class);
        sync.setAccessible(true);
        try {
            return sync.invoke(null, REPO, BRANCH, root, Enum.valueOf(modeType, "FULL"), true, 0L, null);
        } catch (InvocationTargetException failure) {
            throw new AssertionError("Expected full resource sync to complete safely, but it failed: "
                    + failure.getCause(), failure.getCause());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Throwable invokeSmartSyncExpectFailure(Path root, String archiveMirrorUrl) throws Exception {
        Class<?> updater = Class.forName("ModUpdaterGUI");
        Class<? extends Enum> modeType = (Class<? extends Enum>) Class.forName("ModUpdaterGUI$ResourceSyncMode");
        Method sync = updater.getDeclaredMethod("syncResourcePack", String.class, String.class, Path.class,
                modeType, boolean.class, long.class, String.class);
        sync.setAccessible(true);
        try {
            sync.invoke(null, REPO, BRANCH, root, Enum.valueOf(modeType, "SMART"), true, 0L,
                    archiveMirrorUrl);
            throw new AssertionError("Expected resource sync to fail for " + scenario + ".");
        } catch (InvocationTargetException expected) {
            return expected.getCause();
        }
    }

    private static void assertSuccessfulDownload(Object result, Path root, String contingency) throws Exception {
        Field success = result.getClass().getDeclaredField("success");
        success.setAccessible(true);
        if (!success.getBoolean(result)) {
            throw new AssertionError("Resource sync did not report success through " + contingency + ".");
        }
        Path downloaded = root.resolve("resources").resolve(RESOURCE_PATH);
        if (!Files.isRegularFile(downloaded) || !Arrays.equals(RESOURCE_BYTES, Files.readAllBytes(downloaded))) {
            throw new AssertionError("Resource bytes were not installed through " + contingency + ".");
        }
    }

    private static Response responseFor(URL url) throws IOException {
        String value = url.toString();
        String refUrl = "https://api.github.com/repos/" + REPO + "/git/ref/heads/" + BRANCH;
        String treeUrl = "https://api.github.com/repos/" + REPO + "/git/trees/" + COMMIT + "?recursive=1";
        String blobUrl = "https://api.github.com/repos/" + REPO + "/git/blobs/" + gitBlobSha(RESOURCE_BYTES);
        String rawPrefix = "https://raw.githubusercontent.com/" + REPO + "/" + COMMIT + "/";
        String cdnPrefix = "https://cdn.jsdelivr.net/gh/" + REPO + "@" + COMMIT + "/";
        String rawUrl = rawPrefix + RESOURCE_PATH;
        String cdnFileUrl = cdnPrefix + RESOURCE_PATH;
        String githubArchiveUrl = "https://github.com/" + REPO + "/archive/refs/heads/" + BRANCH + ".zip";
        String codeloadArchiveUrl = "https://codeload.github.com/" + REPO + "/zip/refs/heads/" + BRANCH;
        String independentArchiveUrl = "https://resources.example.test/test/repo/main/resourcepack.zip";

        if (value.contains("/archive/refs/heads/") || value.contains("/zip/refs/heads/")) {
            archiveAttempts.incrementAndGet();
        }

        if (scenario == Scenario.BLOB_HOSTS_BLOCKED
                || scenario == Scenario.CDN_HOST_BLOCKED
                || scenario == Scenario.TRANSIENT_ENDPOINT_ERRORS) {
            if (value.equals(refUrl)) {
                if (scenario == Scenario.TRANSIENT_ENDPOINT_ERRORS
                        && metadataAttempts.incrementAndGet() == 1) {
                    return Response.error(503, "temporary metadata edge failure");
                }
                return Response.ok(("{\"object\":{\"sha\":\"" + COMMIT + "\"}}").getBytes(StandardCharsets.UTF_8));
            }
            if (value.equals(treeUrl)) {
                String tree = resourceTreeJson(scenario == Scenario.BLOB_HOSTS_BLOCKED
                        || scenario == Scenario.CDN_HOST_BLOCKED
                        ? BLOB_FALLBACK_FILE_COUNT
                        : 1);
                return Response.ok(tree.getBytes(StandardCharsets.UTF_8));
            }
            if (scenario == Scenario.BLOB_HOSTS_BLOCKED) {
                if (isBlobFallbackUrl(value, rawPrefix)) {
                    rawAttempts.incrementAndGet();
                    return Response.error(451, "raw host unavailable in this region");
                }
                if (value.equals(blobUrl)) return Response.error(403, "GitHub API delivery unavailable");
                if (isBlobFallbackUrl(value, cdnPrefix)) {
                    cdnAttempts.incrementAndGet();
                    return Response.ok(RESOURCE_BYTES);
                }
            } else if (scenario == Scenario.CDN_HOST_BLOCKED) {
                if (isBlobFallbackUrl(value, cdnPrefix)) {
                    cdnAttempts.incrementAndGet();
                    return Response.error(451, "CDN host unavailable in this region");
                }
                if (isBlobFallbackUrl(value, rawPrefix)) {
                    rawAttempts.incrementAndGet();
                    return Response.ok(RESOURCE_BYTES);
                }
            } else {
                if (value.equals(rawUrl)) return transientThenSuccess(rawAttempts);
                if (value.equals(cdnFileUrl)) return transientThenSuccess(cdnAttempts);
                if (value.equals(blobUrl)) return transientThenSuccess(apiBlobAttempts);
            }
        } else if (scenario == Scenario.ALL_GITHUB_BLOCKED) {
            if (value.equals(independentArchiveUrl)) {
                mirrorAttempts.incrementAndGet();
                return Response.ok(resourceArchive());
            }
            if (url.getHost().endsWith("github.com") || url.getHost().endsWith("githubusercontent.com")) {
                return Response.error(451, "GitHub is unavailable in this region");
            }
        } else if (scenario == Scenario.MIRROR_CONTENT_MISMATCH
                || scenario == Scenario.MIRROR_EXTRA_FILE) {
            if (value.equals(refUrl)) {
                if (metadataAttempts.incrementAndGet() <= 2) {
                    return Response.error(503, "metadata unavailable during initial sync");
                }
                return Response.ok(("{\"object\":{\"sha\":\"" + COMMIT + "\"}}").getBytes(StandardCharsets.UTF_8));
            }
            if (value.equals(treeUrl)) {
                return Response.ok(resourceTreeJson(1).getBytes(StandardCharsets.UTF_8));
            }
            if (value.equals(independentArchiveUrl)) {
                return Response.ok(scenario == Scenario.MIRROR_CONTENT_MISMATCH
                        ? zipWithEntry(
                                "resourcepack-main/" + RESOURCE_PATH,
                                "stale-or-tampered-mirror".getBytes(StandardCharsets.UTF_8))
                        : resourceArchiveWithExtra());
            }
            if (url.getHost().endsWith("github.com")) {
                return Response.error(451, "GitHub archive hosts unavailable");
            }
        } else if (scenario == Scenario.CORRUPT_FIRST_ARCHIVE) {
            if (value.equals(refUrl)) return Response.error(403, "GitHub metadata API unavailable");
            if (value.equals(githubArchiveUrl)) return Response.ok(corruptResourceArchive());
            if (value.equals(codeloadArchiveUrl)) {
                codeloadAttempts.incrementAndGet();
                return Response.ok(resourceArchive());
            }
        } else if (scenario == Scenario.STALE_MIRROR_DURING_OUTAGE) {
            if (value.equals(independentArchiveUrl)) {
                mirrorAttempts.incrementAndGet();
                return Response.ok(zipWithEntry(
                        "resourcepack-main/" + RESOURCE_PATH,
                        STALE_RESOURCE_BYTES));
            }
            if (url.getHost().endsWith("github.com") || url.getHost().endsWith("githubusercontent.com")) {
                return Response.error(451, "GitHub is unavailable in this region");
            }
        } else if (scenario == Scenario.TRACKED_DELETE_SYMLINK) {
            if (value.equals(refUrl)) {
                return Response.ok(("{\"object\":{\"sha\":\"" + COMMIT + "\"}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (value.equals(treeUrl)) {
                return Response.ok(resourceTreeJson(1).getBytes(StandardCharsets.UTF_8));
            }
            if (value.equals(cdnFileUrl)) return Response.ok(RESOURCE_BYTES);
            if (value.equals(githubArchiveUrl)) return Response.ok(resourceArchive());
        } else if (scenario == Scenario.TREE_METADATA_BLOCKED) {
            if (value.equals(refUrl)) {
                return Response.ok(("{\"object\":{\"sha\":\"" + COMMIT + "\"}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (value.equals(treeUrl)) return Response.error(451, "GitHub tree metadata unavailable");
            if (value.equals(githubArchiveUrl)) return Response.ok(resourceArchive());
        } else if (scenario == Scenario.INTERRUPTED_ARCHIVE_METADATA) {
            if (value.equals(refUrl)) {
                metadataAttempts.incrementAndGet();
                return Response.error(403, "initial metadata unavailable");
            }
            if (value.equals(githubArchiveUrl)) return Response.ok(resourceArchive());
        } else if (scenario == Scenario.INTERRUPTED_AFTER_ARCHIVE_METADATA) {
            if (value.equals(refUrl)) {
                if (metadataAttempts.incrementAndGet() == 1) {
                    return Response.error(403, "initial metadata unavailable");
                }
                return Response.ok(("{\"object\":{\"sha\":\"" + COMMIT + "\"}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (value.equals(treeUrl)) {
                return Response.ok(resourceTreeJson(1).getBytes(StandardCharsets.UTF_8));
            }
            if (value.equals(githubArchiveUrl)) return Response.ok(resourceArchive());
        } else {
            if (value.equals(refUrl)) return Response.error(403, "GitHub metadata API unavailable");
            if (value.equals(githubArchiveUrl)) return Response.ok(nonResourceArchive());
            if (value.equals(codeloadArchiveUrl)) {
                codeloadAttempts.incrementAndGet();
                return Response.ok(resourceArchive());
            }
            if (url.getHost().endsWith("github.com")) return Response.error(451, "GitHub host unavailable in this region");
        }
        return Response.error(404, "fixture has no response for " + value);
    }

    private static boolean isBlobFallbackUrl(String value, String prefix) {
        return value.equals(prefix + RESOURCE_PATH)
                || (value.startsWith(prefix + "assets/minecraft/regional-fallback-") && value.endsWith(".txt"));
    }

    private static String resourceTreeJson(int fileCount) throws IOException {
        StringBuilder tree = new StringBuilder("{\"tree\":[");
        for (int i = 0; i < fileCount; i++) {
            if (i > 0) tree.append(',');
            String path = fileCount == 1 ? RESOURCE_PATH : blobFallbackPath(i);
            tree.append("{\"path\":\"").append(path)
                    .append("\",\"type\":\"blob\",\"sha\":\"")
                    .append(gitBlobSha(RESOURCE_BYTES)).append("\"}");
        }
        return tree.append("],\"truncated\":false}").toString();
    }

    private static String blobFallbackPath(int index) {
        return index == 0 ? RESOURCE_PATH : "assets/minecraft/regional-fallback-" + index + ".txt";
    }

    private static Response transientThenSuccess(AtomicInteger attempts) {
        return attempts.incrementAndGet() == 1
                ? Response.error(503, "temporary regional edge failure")
                : Response.ok(RESOURCE_BYTES);
    }

    private static byte[] resourceArchive() throws IOException {
        return zipWithEntry("resourcepack-main/" + RESOURCE_PATH, RESOURCE_BYTES);
    }

    private static byte[] nonResourceArchive() throws IOException {
        return zipWithEntry("resourcepack-main/README.md", "not a resource pack".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] resourceArchiveWithExtra() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes);
        try {
            zip.putNextEntry(new ZipEntry("resourcepack-main/" + RESOURCE_PATH));
            zip.write(RESOURCE_BYTES);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("resourcepack-main/assets/minecraft/untracked-extra.txt"));
            zip.write("unexpected extra".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } finally {
            zip.close();
        }
        return bytes.toByteArray();
    }

    private static byte[] zipWithEntry(String name, byte[] contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes);
        try {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(contents);
            zip.closeEntry();
        } finally {
            zip.close();
        }
        return bytes.toByteArray();
    }

    private static byte[] corruptResourceArchive() throws IOException {
        byte[] first = "first-entry-is-valid".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second-entry-will-fail-crc".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes);
        try {
            writeStoredEntry(zip, "resourcepack-main/" + RESOURCE_PATH, first);
            writeStoredEntry(zip, "resourcepack-main/assets/minecraft/corrupt-later.txt", second);
        } finally {
            zip.close();
        }
        byte[] archive = bytes.toByteArray();
        int payload = indexOf(archive, second);
        if (payload < 0) throw new IOException("Could not locate stored ZIP payload for corruption fixture.");
        archive[payload + second.length / 2] ^= 0x01;
        return archive;
    }

    private static void writeStoredEntry(ZipOutputStream zip, String name, byte[] contents) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(contents);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(contents.length);
        entry.setCompressedSize(contents.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(contents);
        zip.closeEntry();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static String gitBlobSha(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
            digest.update(bytes);
            StringBuilder hex = new StringBuilder(40);
            for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (Exception failure) {
            throw new IOException(failure);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static final class Response {
        final int status;
        final byte[] body;

        Response(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }

        static Response ok(byte[] body) {
            return new Response(200, body);
        }

        static Response error(int status, String body) {
            return new Response(status, body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class FakeHandler extends URLStreamHandler {
        protected URLConnection openConnection(URL url) throws IOException {
            return new FakeConnection(url, responseFor(url));
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final Response response;

        FakeConnection(URL url, Response response) {
            super(url);
            this.response = response;
        }

        public int getResponseCode() throws IOException {
            if ((scenario == Scenario.INTERRUPTED_METADATA
                    || (scenario == Scenario.INTERRUPTED_ARCHIVE_METADATA
                    && metadataAttempts.get() >= 2))
                    && url.toString().contains("/git/ref/heads/")) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("fixture interrupted metadata request");
            }
            return response.status;
        }

        public InputStream getInputStream() throws IOException {
            if (response.status < 200 || response.status >= 300) throw new IOException("HTTP " + response.status);
            if (scenario == Scenario.INTERRUPTED_AFTER_ARCHIVE_METADATA
                    && url.toString().contains("/git/trees/")) {
                Thread.currentThread().interrupt();
            }
            return new ByteArrayInputStream(response.body);
        }

        public InputStream getErrorStream() {
            return new ByteArrayInputStream(response.body);
        }

        public void connect() {}
        public void disconnect() {}
        public boolean usingProxy() { return false; }
    }
}
