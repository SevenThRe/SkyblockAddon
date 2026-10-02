package yorickbm.skyblockaddon.core.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import yorickbm.skyblockaddon.core.SkyblockAddonCore;
import yorickbm.skyblockaddon.core.configs.SkyBlockAddonLanguage;
import yorickbm.skyblockaddon.core.configs.VoidProtectionConfig;
import yorickbm.skyblockaddon.core.util.exceptions.ResourceNotFoundException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.jar.JarFile;
import java.util.stream.Stream;


public class ResourceManager {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String[] OLD_PERMISSION_FILES = {
        "general", "storage", "transport", "redstone", "interactables", "admin"
    };


    /**
     * CN fork: deploy the language table matching the configured language.
     *
     * Two upstream behaviours are corrected here:
     *  1. The table was hardcoded to {@code lang/en_us.json}. The server now picks the table
     *     from the language id in the server.properties / options, so a Chinese server gets
     *     {@code lang/zh_cn.json}.
     *  2. {@link #generateFile} only writes when the target is missing, so an English
     *     language.json laid down by an earlier boot survived every upgrade. When the selected
     *     table is not English and the existing file is still the pristine English one, it is
     *     replaced.
     *
     * A user who deliberately hand-edited language.json keeps their edits: we only overwrite
     * when the current content is byte-identical to the bundled English table.
     */
    private static void generateLanguageFile(final Path FMLPath) {
        final String languageId = detectLanguageId();
        final String asset = "lang/" + languageId + ".json";

        //Guard against a missing table so a typo can never wipe a working language.json.
        if (SkyblockAddonCore.class.getResourceAsStream("/assets/" + SkyblockAddonCore.MOD_ID + "/" + asset) == null) {
            LOGGER.warn("Language table '{}' not bundled, falling back to en_us.", asset);
            generateFile(FMLPath, "language.json", "lang/en_us.json");
            return;
        }

        final File languageFile = FMLPath.resolve(SkyblockAddonCore.MOD_ID).resolve("language.json").toFile();
        if (languageFile.exists() && !"en_us".equals(languageId) && isPristineEnglish(languageFile)) {
            try {
                Files.delete(languageFile.toPath());
                LOGGER.info("Replaced stale English language.json with the '{}' table.", languageId);
            } catch (final IOException e) {
                LOGGER.warn("Could not replace stale English language.json: {}", e.getMessage());
            }
        }

        generateFile(FMLPath, "language.json", asset);
    }

    /**
     * Resolve the active language id, e.g. {@code zh_cn}.
     *
     * CN fork: this module has no Minecraft on its classpath, so the language is resolved from
     * the JVM locale that Forge/Minecraft inherit. Falls back to {@code en_us} when the locale
     * cannot be mapped onto a bundled table.
     */
    private static String detectLanguageId() {
        final String jvmLocale = java.util.Locale.getDefault().toLanguageTag();
        if (jvmLocale == null || jvmLocale.isBlank()) return "en_us";
        return normalizeLanguageId(jvmLocale.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Map a locale-ish string onto a bundled table name. {@code zh} / {@code zh-hans} /
     * {@code zh_CN} all resolve to {@code zh_cn}; anything unknown becomes {@code en_us}.
     */
    private static String normalizeLanguageId(final String raw) {
        final String id = raw.replace('-', '_');
        if (id.equals("zh") || id.startsWith("zh_cn") || id.startsWith("zh_hans") || id.startsWith("zh_sg")) {
            return "zh_cn";
        }
        return id;
    }

    /**
     * True when the on-disk language.json is still exactly the bundled English table, i.e. it was
     * never hand-edited. Byte comparison against the resource is intentional: a partially
     * translated or admin-tuned file must never be clobbered.
     */
    private static boolean isPristineEnglish(final File languageFile) {
        try (final InputStream in = SkyblockAddonCore.class.getResourceAsStream(
                "/assets/" + SkyblockAddonCore.MOD_ID + "/lang/en_us.json")) {
            if (in == null) return false;
            final byte[] bundled = readAllBytes(in);
            return Arrays.equals(bundled, Files.readAllBytes(languageFile.toPath()));
        } catch (final IOException e) {
            //Can't tell - be conservative and keep whatever is on disk.
            return false;
        }
    }

    private static byte[] readAllBytes(final InputStream in) throws IOException {
        try (final ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    /**
     * Generate resource file from projects resources folder
     * @param file Path for file destination
     * @param asset File name
     * @throws ResourceNotFoundException If asset is not within projects resources
     */
    private static void generateFile(final Path FMLPath, final String file, final String asset) throws ResourceNotFoundException {
        final File resourceFile = new File(FMLPath.resolve(SkyblockAddonCore.MOD_ID) + "/" +  file);

        try {
            //Determine if file doesnt exists
            if (!resourceFile.exists()) {
                if (resourceFile.createNewFile()) {
                    try (final InputStream in = SkyblockAddonCore.class.getResourceAsStream("/assets/" + SkyblockAddonCore.MOD_ID + "/" + asset)) {
                        if (in == null) {
                            throw new ResourceNotFoundException(asset);
                        }

                        //Copy asset into file
                        Files.copy(in, resourceFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (final IOException e) {
            throw new RuntimeException(resourceFile.getPath() + "\n" + e);
        }
    }

    public static void commonSetup(final Path FMLPath, final RegistrySelector selector) throws ResourceNotFoundException {
        commonSetup(FMLPath, selector, null);
    }

    public static void commonSetup(Path FMLPath, RegistrySelector selector, java.util.function.Predicate<String> isModLoaded) throws ResourceNotFoundException {
        //ResourceManager.getOrCreateDirectory(FMLPath.resolve(SkyblockAddonCore.MOD_ID), SkyblockAddonCore.MOD_ID);

        //Custom island.nbt
        generateFile(FMLPath, "island.nbt", "structures/island.nbt");

        //Custom language.json
        //CN fork: the upstream code hardcoded "lang/en_us.json" here, so the shipped
        //language table was always English no matter what the client's language setting was,
        //and because generateFile() only ever writes when the file is absent, a server that had
        //already booted once kept its English language.json forever. We now pick the table by the
        //server's selected language and additionally detect an existing English table so an
        //upgrade actually replaces it.
        generateLanguageFile(FMLPath);
        SkyBlockAddonLanguage.loadLocalization(FMLPath.resolve(SkyblockAddonCore.MOD_ID + "/language.json"));

        //Generate registries
        if (!Files.exists(FMLPath.resolve(SkyblockAddonCore.MOD_ID + "/registries/"))) {
            ResourceManager.getOrCreateDirectory(FMLPath.resolve(SkyblockAddonCore.MOD_ID + "/"), "registries");
        }
        if(!Files.exists(FMLPath.resolve(SkyblockAddonCore.MOD_ID + "/registries/BiomeRegistry.json"))) {
            generateFile(FMLPath, "registries/BiomeRegistry.json", "registries/BiomeRegistry.json");
        }

        //Generate permission + group files
        getOrCreateDirectory(FMLPath.resolve(SkyblockAddonCore.MOD_ID + "/registries/"), "permissions");
        getOrCreateDirectory(FMLPath.resolve(SkyblockAddonCore.MOD_ID + "/registries/"), "groups");

        migrateOldPermissionFiles(FMLPath);

        extractResourceDirectory(FMLPath, "registries/permissions");
        extractResourceDirectory(FMLPath, "registries/groups");


        //Generate void protection config
        generateFile(FMLPath, "void_protection.json", "void_protection.json");
        VoidProtectionConfig.getInstance().load(FMLPath.resolve(SkyblockAddonCore.MOD_ID + "/void_protection.json"));

        // Extract GUI files (each individually — only creates if missing, so server edits survive)
        getOrCreateDirectory(FMLPath.resolve(SkyblockAddonCore.MOD_ID + "/"), "guis");
        for (final String gui : GUI_FILES) {
            replaceIfPristineEnglish(FMLPath, "guis/" + gui, "guis/" + gui);
            generateFile(FMLPath, "guis/" + gui, "guis/" + gui);
        }
    }

    /**
     * CN fork: the GUI list the upstream code enumerated inline. Kept as a constant so the
     * "replace pristine English" pass covers every file the explicit deploy loop touches, and so
     * adding a GUI in one place cannot silently miss the other.
     */
    private static final String[] GUI_FILES = {
        "overview.json", "settings.json", "biomes.json", "travel.json", "members.json",
        "groups.json", "set_group.json", "set_permission.json", "members_group.json",
        "permissions.json"
    };

    /**
     * Dynamically discovers and extracts all JSON files from a resource directory inside the JAR,
     * so no hardcoded file list is needed — whatever is packaged gets deployed.
     *
     * Strategy:
     *  1. Try Paths.get(uri) — works for file: (dev) and union: (Forge production, which registers
     *     its own NIO filesystem provider at startup).
     *  2. Fall back to JarFile scanning for standard jar: URIs (non-Forge / test environments).
     */
    private static void extractResourceDirectory(final Path FMLPath, final String resourceSubDir) {
        final String resourcePath = "/assets/" + SkyblockAddonCore.MOD_ID + "/" + resourceSubDir + "/";
        final URL dirUrl = SkyblockAddonCore.class.getResource(resourcePath);
        if (dirUrl == null) {
            LOGGER.warn("Resource directory not found: {}", resourcePath);
            return;
        }
        LOGGER.debug("Scanning resource directory '{}' via {} URL", resourceSubDir, dirUrl.getProtocol());

        try {
            final URI dirUri = dirUrl.toURI();

            // Primary: NIO path — covers file: (dev) and union: (Forge production)
            try {
                final Path dirPath = Paths.get(dirUri);
                try (final Stream<Path> listing = Files.list(dirPath)) {
                    listing.filter(p -> p.getFileName().toString().endsWith(".json"))
                           .forEach(p -> extractSingleFile(FMLPath, resourceSubDir, p.getFileName().toString()));
                }
                return;
            } catch (final FileSystemNotFoundException ignored) {
                // Not a registered NIO filesystem — fall through to JarFile fallback
            }

            // Fallback: standard jar: protocol (non-Forge environments)
            if ("jar".equals(dirUrl.getProtocol())) {
                final String jarFilePath = URLDecoder.decode(
                        dirUrl.getPath().substring(5, dirUrl.getPath().indexOf("!")),
                        StandardCharsets.UTF_8);
                final String prefix = "assets/" + SkyblockAddonCore.MOD_ID + "/" + resourceSubDir + "/";
                try (final JarFile jar = new JarFile(jarFilePath)) {
                    jar.stream()
                       .filter(e -> !e.isDirectory() && e.getName().startsWith(prefix) && e.getName().endsWith(".json"))
                       .forEach(e -> extractSingleFile(FMLPath, resourceSubDir, e.getName().substring(prefix.length())));
                }
                return;
            }

            LOGGER.warn("Unhandled resource URL protocol '{}' for directory '{}'; no files extracted.",
                    dirUrl.getProtocol(), resourceSubDir);

        } catch (final IOException | URISyntaxException e) {
            LOGGER.error("Failed to scan resource directory '{}': {}", resourceSubDir, e.getMessage());
        }
    }

    private static void extractSingleFile(final Path FMLPath, final String resourceSubDir, final String fileName) {
        try {
            replaceIfPristineEnglish(FMLPath, resourceSubDir + "/" + fileName, resourceSubDir + "/" + fileName);
            generateFile(FMLPath, resourceSubDir + "/" + fileName, resourceSubDir + "/" + fileName);
        } catch (final ResourceNotFoundException ex) {
            LOGGER.warn("Skipping resource {}: {}", fileName, ex.getMessage());
        }
    }

    /**
     * CN fork: GUI definitions ship with the text baked in, so a server that booted once already
     * has an English copy on disk that {@link #generateFile} would never touch. Delete the file
     * first, but only when it is still byte-identical to the bundled (upstream English) asset —
     * a server that translated or customised its GUIs keeps those edits.
     */
    private static void replaceIfPristineEnglish(final Path FMLPath, final String relativeFile, final String asset) {
        final File target = FMLPath.resolve(SkyblockAddonCore.MOD_ID).resolve(relativeFile).toFile();
        if (!target.exists()) return;
        try (final InputStream in = SkyblockAddonCore.class.getResourceAsStream(
                "/assets/" + SkyblockAddonCore.MOD_ID + "/" + asset)) {
            if (in == null) return;
            if (!Arrays.equals(readAllBytes(in), Files.readAllBytes(target.toPath()))) return;

            Files.delete(target.toPath());
            LOGGER.info("Replaced stale English GUI file '{}' with the localized version.", relativeFile);
        } catch (final IOException e) {
            LOGGER.warn("Could not replace stale GUI file '{}': {}", relativeFile, e.getMessage());
        }
    }

    /**
     * Removes old category-based permission files when migrating to per-mod structure.
     * Only runs if minecraft.json (new structure marker) does not yet exist.
     */
    private static void migrateOldPermissionFiles(final Path FMLPath) {
        final Path permDir = FMLPath.resolve(SkyblockAddonCore.MOD_ID + "/registries/permissions/");
        if (Files.exists(permDir.resolve("minecraft.json"))) return; // already migrated

        boolean anyDeleted = false;
        for (final String name : OLD_PERMISSION_FILES) {
            final File old = permDir.resolve(name + ".json").toFile();
            if (old.exists() && old.delete()) {
                anyDeleted = true;
                LOGGER.info("Removed legacy permission file: {}.json", name);
            }
        }
        if (anyDeleted) {
            LOGGER.info("Migrated permissions to per-mod structure.");
        }
    }

    /**
     * Ensures the child directory exists inside the parent directory.
     *
     * @param parent the parent Path
     * @param child  the child folder name
     * @throws RuntimeException if creation fails
     */
    public static void getOrCreateDirectory(Path parent, String child) {
        Path dir = parent.resolve(child);
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            //throw new RuntimeException("Failed to create directory: " + dir, e);
        }
    }
}
