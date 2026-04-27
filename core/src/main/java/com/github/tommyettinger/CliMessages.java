package com.github.tommyettinger;

import com.badlogic.gdx.utils.Os;

/**
 * All user-facing CLI messages for fontwriter: help text, version output,
 * and error-guidance blocks for binary execution and --lang resolution
 * failures.
 * <p>
 * Every method here is a pure printer — it writes to {@code System.out}
 * or {@code System.err} and returns. None of them call {@code System.exit()};
 * the caller decides whether to terminate.
 * <p>
 * Kept as a separate class so {@link Main} stays focused on the font
 * generation pipeline rather than presentation.
 */
final class CliMessages {

    private static final String JAR_NAME = "fontwriter-2.2.16.0-SNAPSHOT.jar";

    private CliMessages() {} // utility class — not instantiable

    // ---------------------------------------------------------------
    //  Help & version
    // ---------------------------------------------------------------

    /** Prints the full {@code --help} usage block to {@code System.out}. */
    public static void printHelp() {
        String jar = JAR_NAME;
        System.out.println("Usage: java -jar " + jar + " <font> <mode> <size> [options]");
        System.out.println();
        System.out.println("Generate bitmap fonts for libGDX / TextraTypist.");
        System.out.println();

        // --- Required arguments ---
        System.out.println("Required arguments:");
        System.out.println("  <font>     Path to a .ttf or .otf font file (local or absolute).");
        System.out.println();
        System.out.println("  <mode>     Rendering mode. One of:");
        System.out.println("               standard  — non-distance-field bitmap. Scales down well.");
        System.out.println("                           Works everywhere including BitmapFont. (default)");
        System.out.println("               sdf       — signed distance field. Scales up nicely; supports");
        System.out.println("                           outline effects. Has small file sizes.");
        System.out.println("               msdf      — multichannel SDF. Best upscaling quality, but");
        System.out.println("                           files are large.");
//        System.out.println("               mtsdf     — multichannel + true SDF hybrid.");
//        System.out.println("               psdf      — pseudo SDF.");
        System.out.println();
        System.out.println("  <size>     Initial font size to try, in pixels (integer or decimal).");
        System.out.println("             Recommended: 60 for most fonts. Use 200-280 for sharper");
        System.out.println("             results (needs more atlas space). Large charsets (CJK) may");
        System.out.println("             need 30-55 to fit within the atlas dimensions.");
        System.out.println("             If too large, the generator retries with smaller sizes.");
        System.out.println();

        // --- Options ---
        System.out.println("Options (can be given in any order after the required arguments):");
        System.out.println();
        System.out.println("  -s WxH");
        System.out.println("  --image-size WxH   Output image dimensions.");
        System.out.println("                     Default: 2048x2048 (or 4096x4096 for 30000+ chars).");
        System.out.println();
        System.out.println("  -c COLOR");
        System.out.println("  --color COLOR      Generate an extra full-glyph preview with the given");
        System.out.println("                     text color. Accepts named colors ('black',");
        System.out.println("                     'dark dullest violet-blue') or hex ('#E74200').");
        System.out.println();
        System.out.println("  -C NAME");
        System.out.println("  --charset NAME     Use a predefined character set instead of including");
        System.out.println("                     every character in the font. Available sets:");
        System.out.println("                       ascii     — Basic ASCII (32-126). English only.");
        System.out.println("                       latin     — ASCII + Latin-1 + Latin Extended-A.");
        System.out.println("                                   Western/Central/Eastern European.");
        System.out.println("                       latin-ext — Latin + Extended-B + Additional.");
        System.out.println("                                   Vietnamese, Welsh, rare romanizations.");
        System.out.println("                       cyrillic  — Latin + Cyrillic. Russian, Ukrainian, etc.");
        System.out.println("                       greek     — Latin + Greek.");
        System.out.println("                       all       — Every character in the font (32-65535).");
        System.out.println("                     Default: 'all' (every visible character in the font).");
        System.out.println("                     Can be overridden with a specific set, or replaced");
        System.out.println("                     entirely by using --lang instead (see below).");
        System.out.println();
        System.out.println("  -l PATH");
        System.out.println("  --lang PATH        I18N source for character extraction. Accepts:");
        System.out.println("                       Folder:  --lang i18n/de");
        System.out.println("                         Reads all files in that folder.");
        System.out.println("                       Pattern: --lang \"i18n/*.txt\" or --lang \"i18n/strings_*\"");
        System.out.println("                         Matches files against the glob (* and ? wildcards).");
        System.out.println("                       File:    --lang i18n/de/strings.properties");
        System.out.println("                         Reads that single file.");
        System.out.println("                     Characters found (plus ASCII 32-126 baseline) determine");
        System.out.println("                     which glyphs to include. Only active when explicitly passed.");
        System.out.println();
        System.out.println("  -h");
        System.out.println("  --help             Show this help message and exit.");
        System.out.println();
        System.out.println("  -v");
        System.out.println("  --version          Show version and exit.");
        System.out.println();

        // --- Character set fallback hierarchy ---
        System.out.println("Character set resolution (first match wins):");
        System.out.println("  1. --charset given  -> use that predefined set.");
        System.out.println("  2. --lang given     -> extract chars from matched files (folder, glob, or file).");
        System.out.println("  3. neither given    -> include ALL visible characters in the font.");
        System.out.println("  If --charset and --lang are both given, --charset takes priority.");
        System.out.println();

        // --- Examples with explanations ---
        System.out.println("Examples:");
        System.out.println();
        System.out.println("  java -jar " + jar + " Gentium.ttf standard 60");
        System.out.println("    Font: Gentium.ttf (relative path). Mode: standard (bitmap, no SDF).");
        System.out.println("    Size: starts at 60px. Charset: all visible chars (no --charset/--lang).");
        System.out.println("    Image: 2048x2048 (default). No extra color preview.");
        System.out.println();
        System.out.println("  java -jar " + jar + " MyFont.otf msdf 200 --image-size 4096x4096");
        System.out.println("    Font: MyFont.otf. Mode: msdf. Size: starts at 200px.");
        System.out.println("    Charset: all visible chars. Image: 4096x4096 (explicit).");
        System.out.println("    No extra color preview.");
        System.out.println();
        System.out.println("  java -jar " + jar + " MyFont.otf sdf 60 --charset latin --color black");
        System.out.println("    Font: MyFont.otf. Mode: sdf. Size: starts at 60px.");
        System.out.println("    Charset: latin (ASCII + Latin-1 + Latin Extended-A) via --charset.");
        System.out.println("    Image: 2048x2048 (default). Extra color preview in black.");
        System.out.println();
        System.out.println("  java -jar " + jar + " MyFont.otf standard 60 --lang i18n/de");
        System.out.println("    Font: MyFont.otf. Mode: standard. Size: starts at 60px.");
        System.out.println("    Charset: reads all files in i18n/de/, includes only characters");
        System.out.println("    found there (plus ASCII 32-126 baseline).");
        System.out.println("    Image: 2048x2048 (default). No extra color preview.");
        System.out.println();

        // --- Output files ---
        System.out.println("Output files (written to fonts/ and previews/ folders):");
        System.out.println("  fonts/<name>-<mode>.png                 Font texture atlas.");
        System.out.println("  fonts/<name>-<mode>.json                Structured JSON font descriptor.");
        System.out.println("  fonts/<name>-<mode>.dat                 LZB-compressed descriptor (not recommended).");
        System.out.println("  fonts/<name>-<mode>.ubj                 UBJSON binary descriptor (smaller than .json).");
        System.out.println("  fonts/<name>-<mode>.json.lzma           LZMA-compressed .json descriptor (recommended).");
        System.out.println("  fonts/<name>-<mode>.ubj.lzma            LZMA-compressed .ubj descriptor (smallest).");
        System.out.println("  previews/<name>-<mode>.png (preview)    Text rendering preview (always generated).");
        System.out.println("  previews/full-<color>-<name>-<mode>.png Full-glyph color preview (only with --color).");
        System.out.println("  You only need one descriptor file (.json, .dat, .ubj, etc.).");
        System.out.println();

        // --- Legacy positional syntax ---
        System.out.println("Legacy positional syntax (DEPRECATED — will be removed in a future version):");
        System.out.println("  java -jar " + jar + " <font> <mode> <size> [WxH] [color] [langPath]");
        System.out.println();

        // --- Batch commands ---
        System.out.println("Batch commands:");
        System.out.println("  --bulk [folder]      Process every .ttf/.otf in folder (default: 'input').");
        System.out.println("  --preview [folder]   Generate previews for .json fonts (default: 'fonts').");
        System.out.println("  --ubj [folder]       Convert .json fonts to .ubj + .ubj.lzma (default: 'fonts').");
        System.out.println("  --lzma [folder]      Compress .json fonts with LZMA (default: 'fonts').");
    }

    /** Prints the version line to {@code System.out}. */
    public static void printVersion() {
        System.out.println("fontwriter " + JAR_NAME.replace("fontwriter-", "").replace(".jar", ""));
    }

    // ---------------------------------------------------------------
    //  Binary execution errors
    //  Shared guidance for msdf-atlas-gen and oxipng failures.
    // ---------------------------------------------------------------

    /**
     * Binary not found on disk. Suggests re-extracting the distribution.
     */
    public static void printBinaryNotFound(String binaryName, String absolutePath) {
        System.err.println("Error: " + binaryName + " not found at: " + absolutePath);
        System.err.println("Make sure the fontwriter distribution is fully extracted and the distbin/ folder");
        System.err.println("is present alongside the JAR file.");
    }

    /**
     * Binary exists but can't be executed. Gives platform-specific guidance
     * (chmod on macOS/Linux, Gatekeeper allow on macOS).
     */
    public static void printBinaryNotExecutable(String binaryName, String binaryPath,
                                                 String absolutePath, Os os) {
        System.err.println("Error: " + binaryName + " exists but is not executable: " + absolutePath);
        if (os == Os.MacOsX) {
            System.err.println("On macOS, you may need to:");
            System.err.println("  1. Run: chmod +x " + binaryPath);
            System.err.println("  2. Allow the binary in System Settings -> Privacy & Security -> Allow");
            System.err.println("     (macOS may block unsigned binaries on first run)");
        } else if (os == Os.Linux) {
            System.err.println("On Linux, run: chmod +x " + binaryPath);
        }
    }

    /**
     * Binary ran but threw IOException (usually Gatekeeper or sandbox).
     * Prints the error message plus macOS-specific Gatekeeper guidance.
     */
    public static void printBinaryRunFailed(String binaryName, String errorMessage, Os os) {
        System.err.println("Error: Failed to run " + binaryName + ": " + errorMessage);
        if (os == Os.MacOsX) {
            System.err.println("On macOS, the binary may have been blocked by Gatekeeper.");
            System.err.println("Open System Settings -> Privacy & Security and look for a prompt to allow it.");
        }
    }

    /** Binary's wait() was interrupted. */
    public static void printBinaryInterrupted(String binaryName, String errorMessage) {
        System.err.println("Error: " + binaryName + " was interrupted: " + errorMessage);
    }

    /** Binary exited with a non-zero exit code. */
    public static void printBinaryExitFailure(String binaryName, int exitCode) {
        System.out.println(binaryName + " failed, returning exit code " + exitCode + "; terminating.");
    }

    // ---------------------------------------------------------------
    //  --lang resolution errors
    //  All emitted when resolveLangFiles() or its caller can't find
    //  anything to read.
    // ---------------------------------------------------------------

    /**
     * The --lang value resolved to zero files (all three modes failed).
     * Printed from the mainProcess() lang branch.
     */
    public static void printLangNoMatches(String langPath) {
        System.err.println("Error: --lang '" + langPath + "' matched no files.");
        System.err.println("Check that the path exists and contains readable files.");
        System.err.println("  Folder:  --lang i18n/de            (reads all files in the folder)");
        System.err.println("  Pattern: --lang \"i18n/*.txt\"        (glob with * or ? wildcards)");
        System.err.println("  File:    --lang i18n/strings.properties");
    }

    /** Glob mode: the directory containing the glob pattern doesn't exist. */
    public static void printLangParentMissing(String parentPath) {
        System.err.println("Error: parent directory '" + parentPath + "' does not exist.");
    }

    /** Glob mode: the pattern matched no files in the parent directory. */
    public static void printLangGlobNoMatch(String globPattern, String parentDirPath) {
        System.err.println("Error: no files matched pattern '" + globPattern
                + "' in '" + parentDirPath + "'.");
    }

    /** Single file / folder mode: the path doesn't exist. */
    public static void printLangPathMissing(String langPath) {
        System.err.println("Error: --lang path '" + langPath + "' does not exist.");
    }

    /** Folder mode: the directory exists but contains no readable files. */
    public static void printLangFolderEmpty(String langPath) {
        System.err.println("Error: no files found in folder '" + langPath + "'.");
    }
}
