package io.github.erkko68.filament.ffm;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Locates and {@link System#load(String) loads} the combined {@code libfilament-c}.
 *
 * <p>Search order:
 * <ol>
 *   <li>{@code filament.library.path} system property — load from that directory as-is.</li>
 *   <li>JAR resource {@code natives/<platform>-<arch>/} — extracted once into a
 *       content-hash-keyed cache dir under {@code filament.data.path}
 *       (default {@code ~/.filament-kmp}), then reused across JVM starts. Extraction is
 *       guarded by a cross-process file lock; cache dirs for older builds are purged after
 *       {@code filament.data.cleanup.days} (default 30, {@code <= 0} disables).</li>
 *   <li>{@code System.loadLibrary} — dev fallback for a lib on {@code java.library.path}.</li>
 * </ol>
 *
 * <p>The jextract-generated {@link FilamentC} class resolves symbols through
 * {@code SymbolLookup.loaderLookup()}, which finds symbols in native libraries loaded by
 * the classloader of the generated class. Because this loader lives in the same module
 * (hence the same classloader) as the generated bindings, loading the image here makes
 * every {@code Fila*} symbol resolvable. A single loaded image also keeps Filament's
 * process-global singletons (e.g. EntityManager) to one instance.
 */
public final class FilamentLoader {

    private static final String LIB_NAME = "filament-c";
    private static volatile boolean sLoaded = false;

    private FilamentLoader() {
    }

    /** Idempotently loads {@code libfilament-c}. Safe to call from multiple entry points. */
    public static synchronized void load() {
        if (sLoaded) return;
        try {
            findAndLoad();
            sLoaded = true;
        } catch (Throwable e) {
            // Throwable, not Exception: System.load throws UnsatisfiedLinkError (an Error).
            throw new RuntimeException("Failed to load native library '" + LIB_NAME + "': " + e, e);
        }
    }

    private static void findAndLoad() throws Exception {
        String libFileName = platformLibFileName();

        // First try: explicit directory override.
        String libraryPath = System.getProperty("filament.library.path");
        if (libraryPath != null) {
            loadOrCopy(new File(libraryPath, libFileName));
            return;
        }

        String resourcePath = "/natives/" + platformArch() + "/" + libFileName;
        if (FilamentLoader.class.getResource(resourcePath) == null) {
            // Dev fallback: allow a manually placed library on java.library.path.
            try {
                System.loadLibrary(LIB_NAME);
                return;
            } catch (UnsatisfiedLinkError e) {
                throw new FileNotFoundException("Native library not found in resources: "
                        + resourcePath + " (and loadLibrary failed: " + e.getMessage() + ")");
            }
        }

        // Second try: extract-once into a cache dir keyed by the library's content hash,
        // so a rebuilt lib (new hash) never collides with a cached one.
        File cacheRoot = new File(System.getProperty("filament.data.path",
                System.getProperty("user.home") + File.separator + ".filament-kmp"));
        String hash = resourceHash(resourcePath);
        File libDir = new File(cacheRoot, LIB_NAME + "-" + hash);
        File lib = extractIfNeeded(cacheRoot, libDir, libFileName, () -> resourceStream(resourcePath));
        libDir.setLastModified(System.currentTimeMillis()); // mark used, for cleanup
        loadOrCopy(lib);
        cleanupStaleCaches(cacheRoot, libDir);
    }

    private static String platformArch() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        String platform;
        if (os.contains("win")) {
            platform = "windows";
        } else if (os.contains("mac") || os.contains("darwin")) {
            platform = "macos";
        } else if (os.contains("nux") || os.contains("nix") || os.contains("aix")) {
            platform = "linux";
        } else {
            throw new UnsupportedOperationException("Unsupported OS: " + os);
        }
        String archDir = (arch.contains("aarch64") || arch.contains("arm64")) ? "arm64" : "x64";
        return platform + "-" + archDir;
    }

    private static String platformLibFileName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return LIB_NAME + ".dll";
        if (os.contains("mac") || os.contains("darwin")) return "lib" + LIB_NAME + ".dylib";
        return "lib" + LIB_NAME + ".so";
    }

    private static InputStream resourceStream(String resourcePath) {
        InputStream is = FilamentLoader.class.getResourceAsStream(resourcePath);
        if (is == null) throw new IllegalStateException("Resource vanished: " + resourcePath);
        return is;
    }

    /**
     * Cache key for the packaged library: the build stamps a {@code <lib>.sha256} resource
     * next to it; if absent (repackaged jar), hash the resource stream at runtime.
     */
    private static String resourceHash(String resourcePath) throws Exception {
        try (InputStream stamp = FilamentLoader.class.getResourceAsStream(resourcePath + ".sha256")) {
            if (stamp != null) {
                return new String(stamp.readAllBytes()).trim().substring(0, 16);
            }
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = resourceStream(resourcePath)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) >= 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest()).substring(0, 16);
    }

    private static final Object EXTRACT_LOCK = new Object();

    /**
     * Extracts {@code libFileName} into {@code libDir} unless already present. Concurrent
     * JVMs are serialized by a lock file in {@code cacheRoot} (in-process threads by a
     * monitor — overlapping FileLocks in one JVM throw instead of blocking); the write goes
     * through a temp file + atomic move so readers never observe a partial library.
     */
    static File extractIfNeeded(File cacheRoot, File libDir, String libFileName,
                                Supplier<InputStream> content) throws IOException {
        File lib = new File(libDir, libFileName);
        if (lib.isFile()) return lib;
        if (!libDir.isDirectory() && !libDir.mkdirs() && !libDir.isDirectory()) {
            throw new IOException("Cannot create cache dir: " + libDir);
        }
        File lockFile = new File(cacheRoot, ".lock");
        synchronized (EXTRACT_LOCK) {
            try (RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
                 FileLock ignored = raf.getChannel().lock()) {
                if (lib.isFile()) return lib; // another process extracted while we waited
                File tmp = File.createTempFile(LIB_NAME, ".tmp", libDir);
                try (InputStream is = content.get()) {
                    Files.copy(is, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(tmp.toPath(), lib.toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp.toPath(), lib.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return lib;
    }

    /**
     * A native image can only be loaded by one classloader per JVM; on the classic
     * "already loaded in another classloader" error (e.g. two IDE plugins), load a
     * private temp copy instead.
     */
    private static void loadOrCopy(File lib) throws IOException {
        try {
            System.load(lib.getAbsolutePath());
        } catch (UnsatisfiedLinkError e) {
            String msg = e.getMessage();
            if (msg == null || !msg.contains("already loaded in another classloader")) throw e;
            File copyDir = Files.createTempDirectory("filament").toFile();
            File copy = new File(copyDir, lib.getName());
            Files.copy(lib.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
            copy.deleteOnExit();
            copyDir.deleteOnExit();
            System.load(copy.getAbsolutePath());
        }
    }

    /** Best-effort purge of cache dirs from older builds that haven't been used recently. */
    static void cleanupStaleCaches(File cacheRoot, File currentLibDir) {
        int days = Integer.getInteger("filament.data.cleanup.days", 30);
        if (days <= 0) return;
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
        File[] dirs = cacheRoot.listFiles(f ->
                f.isDirectory() && f.getName().startsWith(LIB_NAME + "-") && !f.equals(currentLibDir));
        if (dirs == null) return;
        for (File dir : dirs) {
            if (dir.lastModified() >= cutoff) continue;
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete(); // flat layout; ignore failures
            dir.delete();
        }
    }
}
