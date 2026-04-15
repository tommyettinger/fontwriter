package com.github.tommyettinger;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.UBJsonWriter;
import com.badlogic.gdx.utils.compression.Lzma;
import com.github.tommyettinger.textra.ColorLookup;
import com.github.tommyettinger.textra.utils.StringUtils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Miscellaneous pure helpers used by the main font generation
 * pipeline.
 * <p>
 * Everything in here is stateless and has no dependency on {@link Main}
 * instance state, so it's grouped into one place rather than cluttering
 * the main class with small private helpers. Each method is
 * independently testable; collectively they cover the CLI color
 * parsing and the JSON→UBJ/LZMA conversions that follow atlas
 * generation.
 */
final class FontwriterUtils {

    private FontwriterUtils() {} // utility class

    /**
     * Parses a color string from the CLI into an RGBA8888 integer.
     * <p>
     * Accepted forms (checked in order):
     * <ol>
     *   <li>A descriptive name or phrase understood by TextraTypist's
     *       {@link ColorLookup#DESCRIPTIVE} lookup
     *       (e.g. {@code "darker purple blue"}).</li>
     *   <li>An HTML-style hex string with a leading {@code #} —
     *       {@code #RGB}, {@code #RRGGBB}, or {@code #RRGGBBAA}.</li>
     *   <li>A bare hex string — {@code RGB}, {@code RRGGBB}, or
     *       {@code RRGGBBAA}.</li>
     * </ol>
     * The short 3-digit forms are expanded by duplicating each nibble
     * (so {@code "#F3A"} becomes {@code 0xFF33AAFF}). Strings shorter
     * than 3 characters, and {@code null}, fall through and return
     * {@code -1} (opaque white).
     *
     * @param str the color string, or {@code null}
     * @return the parsed RGBA8888 color, or {@code -1} (opaque white)
     *         if {@code str} is {@code null}, empty, or unparseable
     */
    public static int stringToColor(String str) {
        if (str != null) {
            // Try to parse named color
            ColorLookup lookup = ColorLookup.DESCRIPTIVE;
            int namedColor = lookup.getRgba(str);
            if (namedColor != Main.NO_COLOR_OVERRIDE) {
                return namedColor;
            }
            // Try to parse hex
            if (str.length() >= 3) {
                if (str.startsWith("#")) {
                    if (str.length() >= 9) return StringUtils.intFromHex(str, 1, 9);
                    if (str.length() >= 7) return StringUtils.intFromHex(str, 1, 7) << 8 | 0xFF;
                    if (str.length() >= 4) {
                        int rgb = StringUtils.intFromHex(str, 1, 4);
                        return
                                (rgb << 20 & 0xF0000000) | (rgb << 16 & 0x0F000000) |
                                        (rgb << 16 & 0x00F00000) | (rgb << 12 & 0x000F0000) |
                                        (rgb << 12 & 0x0000F000) | (rgb <<  8 & 0x00000F00) |
                                        0xFF;
                    }
                } else {
                    if (str.length() >= 8) return StringUtils.intFromHex(str, 0, 8);
                    if (str.length() >= 6) return StringUtils.intFromHex(str, 0, 6) << 8 | 0xFF;
                    int rgb = StringUtils.intFromHex(str, 0, 3);
                    return
                            (rgb << 20 & 0xF0000000) | (rgb << 16 & 0x0F000000) |
                                    (rgb << 16 & 0x00F00000) | (rgb << 12 & 0x000F0000) |
                                    (rgb << 12 & 0x0000F000) | (rgb <<  8 & 0x00000F00) |
                                    0xFF;
                }
            }
        }

        return -1; // white
    }

    /**
     * Converts a JSON file to UBJSON alongside it, then LZMA-compresses
     * the UBJSON. Given {@code foo.json} this produces {@code foo.ubj}
     * and {@code foo.ubj.lzma} as siblings.
     *
     * @param inFile the source JSON file; left untouched
     * @throws RuntimeException wrapping any {@link IOException} raised
     *         by the UBJ or LZMA pipelines
     */
    public static void convertToUBJSON(FileHandle inFile) {
        try {
            FileHandle
                outFile = inFile.sibling(inFile.nameWithoutExtension() + ".ubj"),
                outLzmaFile = inFile.sibling(inFile.nameWithoutExtension() + ".ubj.lzma");
            UBJsonWriter ubWriter = new UBJsonWriter(outFile.write(false));
            ubWriter.value(new JsonReader().parse(inFile));
            ubWriter.close();

            BufferedInputStream bais = new BufferedInputStream(outFile.read());
            OutputStream lzmaOut = outLzmaFile.write(false);
            Lzma.compress(bais, lzmaOut);
            lzmaOut.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * LZMA-compresses a JSON file alongside itself. Given
     * {@code foo.json} this produces {@code foo.json.lzma} as a sibling.
     *
     * @param inFile the source JSON file; left untouched
     * @throws RuntimeException wrapping any {@link IOException} raised
     *         by the LZMA pipeline
     */
    public static void convertToLzma(FileHandle inFile) {
        try {
            FileHandle outLzmaFile = inFile.sibling(inFile.nameWithoutExtension() + ".json.lzma");

            BufferedInputStream bais = new BufferedInputStream(inFile.read());
            OutputStream lzmaOut = outLzmaFile.write(false);
            Lzma.compress(bais, lzmaOut);
            lzmaOut.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
