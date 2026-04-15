package com.github.tommyettinger;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.ByteArray;
import com.badlogic.gdx.utils.StreamUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Writes a libGDX {@link Pixmap} to a PNG file using an 8-bit indexed
 * color model (a 256-entry palette plus a single byte per pixel in the
 * IDAT stream).
 * <p>
 * This is a specialized encoder used by fontwriter to shrink the font
 * atlas PNGs. The atlas is grayscale coverage data where only the
 * alpha channel carries information, so the RGB channels can collapse
 * into a single palette keyed by the low byte of each source pixel.
 * The result is typically ~4x smaller than a naive RGBA PNG for the
 * same visual output, before oxipng is even run.
 * <p>
 * The writer is <b>stateful</b>: internal line buffers and the
 * {@link Deflater} are reused between calls to avoid reallocating when
 * processing many atlases in a row (e.g. during {@code --bulk}). A
 * single instance should be reused for the lifetime of the run. Not
 * thread-safe — use one instance per thread.
 */
class IndexedPngWriter {

    // PNG magic number (first 8 bytes of every PNG file).
    private static final byte[] SIGNATURE = {(byte)137, 80, 78, 71, 13, 10, 26, 10};

    // PNG chunk type identifiers (4-byte ASCII codes, big-endian).
    private static final int IHDR = 0x49484452, IDAT = 0x49444154, IEND = 0x49454E44,
            PLTE = 0x504C5445, TRNS = 0x74524E53;

    private static final byte COLOR_INDEXED = 3;
    private static final byte COMPRESSION_DEFLATE = 0;
    private static final byte INTERLACE_NONE = 0;
    private static final byte FILTER_NONE = 0;

    private final ChunkBuffer buffer = new ChunkBuffer(65536);
    private final Deflater deflater = new Deflater(0);
    private ByteArray curLineBytes;
    private ByteArray prevLineBytes;
    private int lastLineLen;

    /**
     * Writes {@code pm} as an indexed PNG to {@code file}, overwriting
     * any existing contents. Each of the 256 palette entries takes its
     * RGB from the top 24 bits of {@code rgba}; the alpha/index byte
     * varies 0–255. The low byte of every source pixel is used as the
     * palette index — callers must ensure the source pixmap satisfies
     * that assumption (fontwriter's atlas pipeline does).
     *
     * @param file destination file; will be overwritten
     * @param pm   source pixmap
     * @param rgba 32-bit color whose top 24 bits provide the palette's
     *             shared RGB values
     */
    public void write(FileHandle file, Pixmap pm, int rgba) {
        final int w = pm.getWidth(), h = pm.getHeight();
        OutputStream output = file.write(false);
        try {
            DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
            DataOutputStream dataOutput = new DataOutputStream(output);
            try {
                dataOutput.write(SIGNATURE);

                buffer.writeInt(IHDR);
                buffer.writeInt(w);
                buffer.writeInt(h);
                buffer.writeByte(8); // 8 bits per component.
                buffer.writeByte(COLOR_INDEXED);
                buffer.writeByte(COMPRESSION_DEFLATE);
                buffer.writeByte(FILTER_NONE);
                buffer.writeByte(INTERLACE_NONE);
                buffer.endChunk(dataOutput);

                buffer.writeInt(PLTE);
                for (int i = 0; i < 256; i++) {
                    buffer.write(rgba >>> 24);
                    buffer.write(rgba >>> 16 & 255);
                    buffer.write(rgba >>>  8 & 255);
                }
                buffer.endChunk(dataOutput);

                buffer.writeInt(TRNS);
                for (int i = 0; i < 256; i++) {
                    buffer.write(i);
                }
                buffer.endChunk(dataOutput);

                buffer.writeInt(IDAT);
                deflater.reset();

                int lineLen = w;
                byte[] curLine, prevLine;
                if (curLineBytes == null) {
                    curLine = (curLineBytes = new ByteArray(lineLen)).items;
                    prevLine = (prevLineBytes = new ByteArray(lineLen)).items;
                } else {
                    curLine = curLineBytes.ensureCapacity(lineLen);
                    prevLine = prevLineBytes.ensureCapacity(lineLen);
                    for (int i = 0, n = lastLineLen; i < n; i++) {
                        prevLine[i] = 0;
                    }
                }

                lastLineLen = lineLen;

                int color;
                boolean noWarningNeeded = false;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        color = pm.getPixel(x, y);
                        // this block may need to be commented out if a font uses non-white grayscale colors.
                        if(noWarningNeeded || ((color & 255) != 0 && (color & 0xFFFFFF00) != 0xFFFFFF00)) {
                            System.out.println("PROBLEM WITH " + file);
                            System.out.printf("Problem color: 0x%08X\n", color);
                            System.out.println("Position: " + x + "," + y);
                            noWarningNeeded = true;
                        }
                        curLine[x] = (byte) (color & 255);
                    }

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, lineLen);

                    byte[] temp = curLine;
                    curLine = prevLine;
                    prevLine = temp;
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);

                buffer.writeInt(IEND);
                buffer.endChunk(dataOutput);

                output.flush();
            } catch (IOException e) {
                Gdx.app.error("transparency", e.getMessage());
            }
        } finally {
            StreamUtils.closeQuietly(output);
        }
    }

    /**
     * A {@link DataOutputStream} wrapping a {@link ByteArrayOutputStream}
     * and a {@link CRC32}, used to accumulate PNG chunk payloads. On
     * {@link #endChunk(DataOutputStream)} the wrapper writes the chunk
     * length, the buffered payload, and the CRC to the given target
     * stream, then resets both the buffer and the CRC for the next chunk.
     */
    private static class ChunkBuffer extends DataOutputStream {
        final ByteArrayOutputStream buffer;
        final CRC32 crc;

        ChunkBuffer(int initialSize) {
            this(new ByteArrayOutputStream(initialSize), new CRC32());
        }

        private ChunkBuffer(ByteArrayOutputStream buffer, CRC32 crc) {
            super(new CheckedOutputStream(buffer, crc));
            this.buffer = buffer;
            this.crc = crc;
        }

        public void endChunk(DataOutputStream target) throws IOException {
            flush();
            target.writeInt(buffer.size() - 4);
            buffer.writeTo(target);
            target.writeInt((int) crc.getValue());
            buffer.reset();
            crc.reset();
        }
    }
}
