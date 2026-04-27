package com.github.tommyettinger;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.IntSet;

import java.io.File;
import java.util.Arrays;

import static java.awt.Font.TRUETYPE_FONT;

/**
 * Builds the character map (cmap) passed to msdf-atlas-gen.
 * <p>
 * The cmap is a space-separated list of decimal codepoints that the
 * generated atlas must contain. Building it has three stages:
 * <ol>
 *   <li><b>Source selection</b> — which codepoints are candidates? The
 *       {@link FontwriterConfig#resolveCharsetStrategy() strategy}
 *       chooses between a predefined preset (e.g. LATIN, CYRILLIC),
 *       the union of characters found in {@code --lang} files, or
 *       the full BMP range 32–65535.</li>
 *   <li><b>Filtering</b> — control characters, C1 controls, and
 *       bidirectional/formatting codepoints are removed, and each
 *       candidate is checked against the actual AWT {@link java.awt.Font}
 *       via {@link java.awt.Font#canDisplay(int) canDisplay} so the
 *       atlas only requests glyphs the TTF can produce.</li>
 *   <li><b>Serialization</b> — the surviving codepoints are written
 *       to the cmap file as space-separated decimals.</li>
 * </ol>
 * The builder prints progress to stdout and routes {@code --lang}
 * failure diagnostics through {@link CliMessages}. On fatal errors
 * (no lang matches, unreadable font file) it terminates the JVM with
 * exit code 1.
 */
final class CharMapBuilder {

    private CharMapBuilder() {} // utility class

    /**
     * Bidirectional/formatting and similar "weird" codepoints that
     * should never end up in the atlas even if the font advertises
     * glyphs for them. Must be kept sorted for
     * {@link Arrays#binarySearch(int[], int) binarySearch}.
     */
    private static final int[] WEIRD_CHARS =
        {0x200C, 0x200D, 0x200E, 0x200F, 0x2028, 0x2029, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x206A,
            0x206B, 0x206C, 0x206D, 0x206E, 0x206F};

    /**
     * Resolves the character set, filters it against the TTF, writes
     * the resulting cmap file, and returns its length in characters.
     *
     * @param config       parsed CLI configuration (supplies the
     *                     charset strategy, preset, and {@code --lang}
     *                     path)
     * @param fontFileName filesystem path to the source TTF; used to
     *                     open an AWT {@link java.awt.Font} for the
     *                     {@code canDisplay} pass
     * @param cmap         destination {@link FileHandle} for the
     *                     generated cmap file; overwritten
     * @return the length in characters of the written cmap content
     *         (used by callers to pick the initial atlas size)
     */
    public static int build(FontwriterConfig config, String fontFileName, FileHandle cmap) {
        FontwriterConfig.CharsetStrategy charsetStrategy = config.resolveCharsetStrategy();
        IntSet charSet = new IntSet(65536);

        if (charsetStrategy == FontwriterConfig.CharsetStrategy.PRESET) {
            System.out.println("Building character map from predefined charset: " + config.charset + "...");
            populateCharset(charSet, config.charset);
        } else if (charsetStrategy == FontwriterConfig.CharsetStrategy.LANG) {
            System.out.println("Building character map from I18N source: " + config.langPath + "...");
            // Baseline: ASCII (32–126)
            for (int i = 32; i <= 126; i++) {
                charSet.add(i);
            }

            // --lang accepts three forms:
            //   1. Glob pattern  — contains * or ? → match files against the pattern
            //   2. Single file   — path points to an existing file → read that one file
            //   3. Folder        — path points to a directory → read all files in it
            FileHandle[] langFiles = LangFileResolver.resolve(config.langPath);

            if (langFiles != null && langFiles.length > 0) {
                for (FileHandle f : langFiles) {
                    try {
                        String content = f.readString("UTF-8");
                        for (int i = 0; i < content.length(); i++) {
                            charSet.add(content.charAt(i));
                        }
                        System.out.println("  Read " + f.path() + " (" + content.length() + " chars)");
                    } catch (Exception e) {
                        System.err.println("Failed to read " + f.path() + ": " + e.getMessage());
                    }
                }
                System.out.println("  Unique characters found: " + charSet.size);
            } else {
                CliMessages.printLangNoMatches(config.langPath);
                System.exit(1);
            }
        } else {
            // "all" — no --charset, no --lang: include every character in the font.
            System.out.println("Building character map from all visible characters in the font...");
            for (int i = 32; i < 65536; i++) {
                charSet.add(i);
            }
        }

        // Filter only displayable characters
        IntArray displayableChars = new IntArray(charSet.size);
        // If there are a lot of chars, don't report every one that this can't display.
        boolean reasonableCharMap = charSet.size < 10000;
        try {
            java.awt.Font af = java.awt.Font.createFont(TRUETYPE_FONT, new File(fontFileName));
            IntSet.IntSetIterator iter = charSet.iterator();
            while (iter.hasNext) {
                int code = iter.next();
                // Skip control chars and weird formatting chars
                if (code < 32 || (code >= 0x7F && code <= 0x9F) ||         /* C1 controls */
                    Arrays.binarySearch(WEIRD_CHARS, code) >= 0) {
                    continue;
                }

                if (af.canDisplay(code)) {
                    displayableChars.add(code);
                } else if (reasonableCharMap) {
                    char ch = (char) code;
                    String printable = (ch >= 32 && ch <= 126) ? String.valueOf(ch) : "\\u" + String.format("%04X", code);
                    System.out.println("Font cannot display code " + code + " (" + printable + ")");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        // Build final string without trailing space
        StringBuilder sb = new StringBuilder(4096);

        for (int i = 0, n = displayableChars.size; i < n; i++) {
            sb.append(displayableChars.get(i));
            if (i + 1 < n) {
                sb.append(' ');
            }
        }

        cmap.writeString(sb.toString(), false, "UTF-8");
        return sb.length();
    }

    /**
     * Populates the given IntSet with codepoints for a predefined charset.
     * Every set includes ASCII 32–126 as a baseline.
     *
     * @param charSet the set to populate (not cleared first)
     * @param charset the predefined charset to apply
     */
    private static void populateCharset(IntSet charSet, FontwriterConfig.Charset charset) {
        // ASCII baseline (32–126) — always included
        for (int i = 32; i <= 126; i++) {
            charSet.add(i);
        }

        switch (charset) {
            case ASCII:
                // Already done above
                break;

            case LATIN:
                // Latin-1 Supplement (160–255) + Latin Extended-A (256–383)
                for (int i = 160; i <= 383; i++) {
                    charSet.add(i);
                }
                break;

            case LATIN_EXT:
                // Latin-1 Supplement (160–255) + Latin Extended-A (256–383)
                // + Latin Extended-B (384–591) + Latin Extended Additional (7680–7935)
                for (int i = 160; i <= 591; i++) {
                    charSet.add(i);
                }
                for (int i = 7680; i <= 7935; i++) {
                    charSet.add(i);
                }
                break;

            case CYRILLIC:
                // Latin (160–383) + Cyrillic (1024–1279)
                for (int i = 160; i <= 383; i++) {
                    charSet.add(i);
                }
                for (int i = 1024; i <= 1279; i++) {
                    charSet.add(i);
                }
                break;

            case GREEK:
                // Latin (160–383) + Greek and Coptic (880–1023)
                for (int i = 160; i <= 383; i++) {
                    charSet.add(i);
                }
                for (int i = 880; i <= 1023; i++) {
                    charSet.add(i);
                }
                break;

            case ALL:
            default:
                for (int i = 32; i < 65536; i++) {
                    charSet.add(i);
                }
                break;
        }
    }
}
