package com.github.tommyettinger;

import java.util.Locale;

/**
 * Holds all resolved parameters for a fontwriter run.
 * <p>
 * This class serves as the single source of truth for every configurable value
 * in a font generation invocation. It is populated once by the CLI argument
 * parser and then read (never mutated) by the rest of the pipeline.
 * <p>
 * <b>Usage (standard font generation):</b>
 * <pre>
 *   java -jar fontwriter.jar MyFont.otf msdf 60
 *   java -jar fontwriter.jar MyFont.otf msdf 60 --image-size 4096x4096
 *   java -jar fontwriter.jar MyFont.otf msdf 60 --lang i18n/de --color black
 *   java -jar fontwriter.jar MyFont.otf sdf 200 --charset latin
 * </pre>
 * <b>Usage (special commands):</b>
 * <pre>
 *   java -jar fontwriter.jar --bulk [folder]
 *   java -jar fontwriter.jar --preview [folder]
 *   java -jar fontwriter.jar --ubj [folder]
 *   java -jar fontwriter.jar --lzma [folder]
 * </pre>
 *
 * <h3>Character set resolution — fallback hierarchy</h3>
 * <p>
 * The character set included in the generated font is determined by the
 * following priority chain. The first matching rule wins:
 * <ol>
 *   <li>If {@code --charset} is given, use that predefined character set.</li>
 *   <li>If {@code --lang} is given, read the specified files (folder, glob,
 *       or single file) and include every character found in them
 *       (plus ASCII 32–255 as a baseline).</li>
 *   <li>If neither is given, include <b>all visible characters</b> in the font
 *       (codepoints 32–65535 that pass {@code canDisplay()}).</li>
 * </ol>
 * This means a bare invocation with no charset/lang flags produces the largest
 * possible font — every glyph the font file contains. Adding {@code --lang}
 * narrows it to what your translations actually use. Adding {@code --charset}
 * narrows it to a well-known fixed set regardless of font contents.
 */
public class FontwriterConfig {

    // ---------------------------------------------------------------
    //  Enums for fixed-choice parameters.
    // ---------------------------------------------------------------

    /**
     * Distance field rendering mode passed to msdf-atlas-gen.
     * <p>
     * Each value maps to a specific msdf-atlas-gen {@code -type} argument.
     * {@link #STANDARD} is the most compatible (works with BitmapFont),
     * {@link #MSDF} gives the best upscaling quality, and {@link #SDF}
     * is a middle ground that supports outline effects.
     */
    public enum Mode {
        /** Non-distance-field bitmap. Scales down well. Works everywhere
         *  including BitmapFont. Recommended for general use.
         *  Internally converted to "softmask" for msdf-atlas-gen. */
        STANDARD("standard", "softmask"),

        /** Signed distance field. Scales up nicely, supports outline
         *  effects via shaders. Good middle ground. */
        SDF("sdf", "sdf"),

        /** Multichannel signed distance field. Best upscaling quality,
         *  but looks odd with colorful emoji. */
        MSDF("msdf", "msdf"),

        /** Multichannel + true SDF hybrid. Not currently loaded
         * by TextraTypist. */
        MTSDF("mtsdf", "mtsdf"),

        /** Pseudo signed distance field. Not currently loaded
         * by TextraTypist. */
        PSDF("psdf", "psdf");

        /** The user-facing CLI name (e.g. "standard", "msdf"). */
        public final String cliName;

        /** The value passed to msdf-atlas-gen's {@code -type} flag. */
        public final String atlasGenType;

        Mode(String cliName, String atlasGenType) {
            this.cliName = cliName;
            this.atlasGenType = atlasGenType;
        }

        /**
         * Resolves a CLI string to a Mode enum value.
         * @param value the user-provided mode string (case-insensitive)
         * @return the matching Mode
         * @throws IllegalArgumentException if the value is not recognized
         */
        public static Mode fromString(String value) {
            String lower = value.toLowerCase(Locale.ROOT);
            for (Mode m : values()) {
                if (m.cliName.equals(lower)) return m;
            }
            throw new IllegalArgumentException(
                    "Unknown mode: '" + value + "'. "
                    + "Valid values: standard, sdf, msdf, mtsdf, psdf");
        }

        @Override
        public String toString() {
            return cliName;
        }
    }

    /**
     * Predefined character set. Controls which Unicode codepoints are
     * included in the generated font atlas.
     * <p>
     * Every set includes ASCII 32–126 as a baseline. Larger sets add
     * Unicode blocks on top of that. {@link #ALL} includes every
     * displayable codepoint in the font (32–65535).
     */
    public enum Charset {
        /** Basic ASCII (codepoints 32–126). English only. Smallest set. */
        ASCII("ascii"),

        /** ASCII + Latin-1 Supplement (160–255) + Latin Extended-A (256–383).
         *  Covers Western/Central/Eastern European languages: English,
         *  Spanish, French, German, Portuguese, Italian, Polish, Czech, etc. */
        LATIN("latin"),

        /** Latin + Latin Extended-B (384–591) + Latin Extended Additional
         *  (7680–7935). Covers Vietnamese, Welsh, and less common
         *  romanizations. */
        LATIN_EXT("latin-ext"),

        /** Latin + Cyrillic block (1024–1279). Covers Russian, Ukrainian,
         *  Bulgarian, Serbian, etc. */
        CYRILLIC("cyrillic"),

        /** Latin + Greek and Coptic block (880–1023). Covers modern Greek. */
        GREEK("greek"),

        /** Every codepoint 32–65535 present in the font. This is the
         *  default when neither {@code --charset} nor {@code --lang}
         *  is given. */
        ALL("all");

        /** The user-facing CLI name (e.g. "latin", "cyrillic"). */
        public final String cliName;

        Charset(String cliName) {
            this.cliName = cliName;
        }

        /**
         * Resolves a CLI string to a Charset enum value.
         * @param value the user-provided charset string (case-insensitive)
         * @return the matching Charset
         * @throws IllegalArgumentException if the value is not recognized
         */
        public static Charset fromString(String value) {
            String lower = value.toLowerCase(Locale.ROOT);
            for (Charset c : values()) {
                if (c.cliName.equals(lower)) return c;
            }
            throw new IllegalArgumentException(
                    "Unknown charset: '" + value + "'. "
                    + "Valid values: ascii, latin, latin-ext, cyrillic, greek, all");
        }

        @Override
        public String toString() {
            return cliName;
        }
    }

    /**
     * Describes which strategy the charset resolution used.
     * See the class-level Javadoc for the fallback hierarchy.
     */
    public enum CharsetStrategy {
        /** An explicit {@code --charset} was given. */
        PRESET,
        /** An explicit {@code --lang} was given (files/folder/glob). */
        LANG,
        /** Neither was given; use all visible characters in the font. */
        ALL
    }

    // ---------------------------------------------------------------
    //  Special commands — mutually exclusive with standard generation.
    //  When one of these is set, the three required positional args
    //  are not needed.
    // ---------------------------------------------------------------

    /**
     * When true, the user asked for --help / -h.
     * Print usage and exit.
     */
    public boolean helpRequested = false;

    /**
     * When true, the user asked for --version / -v.
     * Print version string and exit.
     */
    public boolean versionRequested = false;

    /**
     * Special command keyword, if any: "bulk", "preview", "ubj", or "lzma".
     * Null when running normal font generation.
     */
    public String specialCommand = null;

    /**
     * Folder path used by special commands (--bulk, --preview, --ubj, --lzma).
     * Each command has its own default if this is null:
     *   --bulk    → "input"
     *   --preview → "fonts"
     *   --ubj     → "fonts"
     *   --lzma    → "fonts"
     */
    public String specialCommandPath = null;

    // ---------------------------------------------------------------
    //  Required positional arguments (standard font generation).
    // ---------------------------------------------------------------

    /**
     * Path to the font file (.ttf or .otf).
     * Can be an absolute path or relative to the working directory.
     * <p>
     * <b>Required.</b> No default.
     * <p>
     * Example: {@code "Gentium.ttf"}, {@code "/home/user/fonts/MyFont.otf"}
     */
    public String fontPath = null;

    /**
     * Distance field rendering mode.
     * <p>
     * Recommended: {@link Mode#STANDARD} for general use and BitmapFont
     * compatibility. Use {@link Mode#MSDF} for fonts that need to scale up
     * cleanly, or {@link Mode#SDF} for a middle ground that supports
     * outline effects.
     * <p>
     * <b>Required.</b> No default.
     */
    public Mode mode = null;

    /**
     * Initial font size to attempt (in pixels).
     * If the font cannot fit at this size, the generator retries with
     * progressively smaller values until it succeeds or reaches zero.
     * <p>
     * Recommended: {@code 60} for most fonts as a starting point.
     * Larger values (e.g. 200–280) produce sharper results but require
     * more atlas space. Very large character sets (e.g. CJK) may need
     * smaller values (30–55) to fit within the atlas dimensions.
     * <p>
     * Parsed from the CLI as a double but stored as the raw string
     * so that {@code Math.round(Double.parseDouble(...))} is applied
     * at the point of use, matching existing behavior.
     * <p>
     * <b>Required.</b> No default.
     */
    public String initialSize = null;

    // ---------------------------------------------------------------
    //  Optional flags (standard font generation).
    //  These can be given in any order after the three positional args.
    // ---------------------------------------------------------------

    /**
     * Output image dimensions as "WxH" (e.g. "2048x2048", "4096x4096").
     * <p>
     * <b>Flag:</b> {@code --image-size <WxH>}
     * <p>
     * <b>Default:</b> {@code null} — resolved at runtime to "2048x2048"
     * (or "4096x4096" when the character map has 30 000+ entries).
     */
    public String imageSize = null;

    /**
     * Preview text color as a named color or hex code.
     * When non-null, an extra full-glyph preview PNG is generated.
     * Accepts TextraTypist descriptive names (e.g. "dark dullest violet-blue")
     * or CSS-style hex ("#E74200", "#FF8800FF").
     * <p>
     * <b>Flag:</b> {@code --color <color>}
     * <p>
     * <b>Default:</b> {@code null} — no full-glyph preview is generated.
     * (A text-rendering preview is always generated regardless.)
     */
    public String color = null;

    /**
     * I18N source for character extraction. Accepts three forms:
     * <ul>
     *   <li><b>Folder:</b> {@code "i18n/de"} — reads all files in that
     *       directory (excluding hidden dot-files).</li>
     *   <li><b>Glob pattern:</b> {@code "i18n/*.txt"} or
     *       {@code "i18n/strings_*"} — matches files against the pattern.
     *       The {@code *} wildcard matches any sequence of characters
     *       within a single path component (standard glob behavior).</li>
     *   <li><b>Single file:</b> {@code "i18n/de/strings.properties"} —
     *       reads only that one file.</li>
     * </ul>
     * In every case, all characters found in the matched files (plus
     * ASCII 32–255 as a baseline) are included in the generated font.
     * <p>
     * If the path does not exist, the pattern matches no files, or the
     * folder is empty, the program exits with an error. There is no
     * silent fallback — the user explicitly asked for lang-based
     * character extraction, so it must succeed.
     * <p>
     * <b>Detection logic:</b> if the value contains {@code *} or
     * {@code ?}, it is treated as a glob pattern. Otherwise, if it
     * points to an existing directory, it is treated as a folder.
     * Otherwise, it is treated as a single file path.
     * <p>
     * <b>Flag:</b> {@code --lang <path|pattern>}
     * <p>
     * <b>Default:</b> {@code null} — lang scanning is NOT performed.
     * When null (and --charset is also null), the generator falls back
     * to including all visible characters in the font (codepoints
     * 32–65535). See the charset resolution hierarchy in the class
     * Javadoc.
     */
    public String langPath = null;

    /**
     * Predefined character set. Controls which Unicode codepoints are
     * included in the generated font atlas.
     * <p>
     * <b>Flag:</b> {@code --charset <name>}
     * <p>
     * <b>Default:</b> {@link Charset#ALL} (every visible character in
     * the font). Can be overridden with a specific set, or replaced
     * entirely by using {@code --lang} instead.
     */
    public Charset charset = Charset.ALL;

    // ---------------------------------------------------------------
    //  Convenience queries.
    // ---------------------------------------------------------------

    /**
     * Returns true when this config represents a standard font generation
     * run (not a special command, not --help, not --version).
     */
    public boolean isStandardRun() {
        return !helpRequested && !versionRequested && specialCommand == null;
    }

    /**
     * Returns true when a preview color was specified, meaning an extra
     * full-glyph preview image should be generated.
     */
    public boolean hasPreviewColor() {
        return color != null && color.length() > 1;
    }

    /**
     * Returns the image size string with 'x' replaced by a space,
     * suitable for passing to msdf-atlas-gen's -dimensions flag.
     * Applies the default based on character map length if no explicit
     * size was given.
     *
     * @param cmapLength the length of the built character map string
     * @return image dimensions as "W H" (space-separated)
     */
    public String resolveImageSize(int cmapLength) {
        if (imageSize != null) {
            return imageSize.replace('x', ' ');
        }
        return cmapLength >= 30000 ? "4096 4096" : "2048 2048";
    }

    /**
     * Determines which charset strategy to use based on the fallback
     * hierarchy:
     * <ol>
     *   <li>{@code --charset} explicitly given (not the default ALL)
     *       → {@link CharsetStrategy#PRESET}</li>
     *   <li>{@code --lang} given → {@link CharsetStrategy#LANG}</li>
     *   <li>Neither → uses the default charset ({@link Charset#ALL}),
     *       reported as {@link CharsetStrategy#PRESET}</li>
     * </ol>
     * When {@code --lang} is given, it takes priority over the default
     * charset. When {@code --charset} is explicitly set to something
     * other than ALL, it takes priority over {@code --lang}.
     */
    public CharsetStrategy resolveCharsetStrategy() {
        if (charsetExplicitlySet) return CharsetStrategy.PRESET;
        if (langPath != null) return CharsetStrategy.LANG;
        return CharsetStrategy.PRESET; // uses the default (ALL)
    }

    /**
     * Tracks whether the user explicitly passed {@code --charset} on
     * the command line. When false, the charset field holds the default
     * ({@link Charset#ALL}) and can be overridden by {@code --lang}.
     */
    public boolean charsetExplicitlySet = false;

    @Override
    public String toString() {
        if (!isStandardRun()) {
            if (helpRequested) return "FontwriterConfig{--help}";
            if (versionRequested) return "FontwriterConfig{--version}";
            return "FontwriterConfig{--" + specialCommand
                    + (specialCommandPath != null ? " " + specialCommandPath : "")
                    + "}";
        }
        StringBuilder sb = new StringBuilder("FontwriterConfig{");
        sb.append("font=").append(fontPath);
        sb.append(", mode=").append(mode);
        sb.append(", size=").append(initialSize);
        if (imageSize != null) sb.append(", imageSize=").append(imageSize);
        if (color != null) sb.append(", color=").append(color);
        if (charset != null) sb.append(", charset=").append(charset);
        if (langPath != null) sb.append(", lang=").append(langPath);
        sb.append('}');
        return sb.toString();
    }
}
