package com.github.tommyettinger;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.*;
import com.badlogic.gdx.utils.viewport.StretchViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.github.tommyettinger.textra.Font;
import com.github.tommyettinger.textra.Layout;

import java.awt.*;
import java.io.*;
import java.lang.StringBuilder;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static java.awt.Font.TRUETYPE_FONT;

/** {@link com.badlogic.gdx.ApplicationListener} implementation shared by all platforms. */
public class Main extends ApplicationAdapter {
    static private final byte[] SIGNATURE = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
    static private final int IHDR = 0x49484452, IDAT = 0x49444154, IEND = 0x49454E44,
            PLTE = 0x504C5445, TRNS = 0x74524E53;
    static private final byte COLOR_INDEXED = 3;
    static private final byte COMPRESSION_DEFLATE = 0;
    static private final byte INTERLACE_NONE = 0;
    static private final byte FILTER_NONE = 0;

    private final ChunkBuffer buffer = new ChunkBuffer(65536);
    private final Deflater deflater = new Deflater(0);
    private ByteArray curLineBytes;
    private ByteArray prevLineBytes;
    private int lastLineLen;
    private final String[] args;

    private SpriteBatch batch;
    private Viewport viewport;
    private Layout layout = new Layout().setTargetWidth(1200);
    private static final String text = "Fonts can be rendered normally,{CURLY BRACKETS ARE IGNORED} but using [[tags], you can..."
            + "\n[#E74200]...use CSS-style hex colors like [*]#E74200[*]..."
            + "\n[darker purple blue]...use color names or descriptions, like [/]darker purple blue[/]...[ ]"
            + "\n[_]...and use [!]effects[!][_]!"
            + "\nNormal, [*]bold[*], [/]oblique[/] (like italic), [*][/]bold oblique[ ],"
            + "\n[_]underline (even for multiple words)[_], [~]strikethrough (same)[ ],"
            + "\nscaling: [%50]very [%75]small [%100]to [%150]quite [%200]large[ ], notes: [.]sub-[.], [=]mid-[=], and [^]super-[^]script,"
            + "\ncapitalization changes: [;]Each cap, [,]All lower, [!]Caps lock[ ],"
            + "\n[%^small caps][*]Special[*][%] [%^whiten][/]Effects[/][%]: [%?shadow]drop shadow[%], [%?jostle]RaNsoM nOtE[%], [%?error]spell check[%]..."
            + "\nWelcome to the [_][*][TEAL]Textra Zone[ ]!";


    public Main(String[] args) {
        if(args == null || args.length == 0) {
            System.out.println("This tool needs some parameters!");
            System.exit(1);
        }
        this.args = args;

    }
    @Override
    public void create() {
        batch = new SpriteBatch();
        viewport = new StretchViewport(1200, 675);
        Gdx.files.local("fonts").mkdirs();
        Gdx.files.local("previews").mkdirs();
        String fontFileName = args[0], fontName = fontFileName.substring(Math.max(fontFileName.lastIndexOf('/'), fontFileName.lastIndexOf('\\')) + 1, fontFileName.lastIndexOf('.'));
        System.out.println("Building character map...");
        StringBuilder sb = new StringBuilder(1024);
        try {
            java.awt.Font af = java.awt.Font.createFont(TRUETYPE_FONT, new File(args[0]));
            for (int i = 32; i < 65536; i++) {
                if(af.canDisplay(i)) sb.append(i).append(' ');
            }
            if(sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
        } catch (FontFormatException | IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
        Gdx.files.local(fontFileName + ".cmap.txt").writeString(sb.toString(), false, "UTF-8");

        System.out.println("Generating structured JSON font and PNG using msdf-atlas-gen...");
        String cmd = "distbin/msdf-atlas-gen -font \"" + fontFileName + "\" -charset " + fontFileName + ".cmap.txt" +
                " -type "+("standard".equals(args[1]) ? "softmask" : args[1])+" -imageout \"fonts/"+fontName+"-"+args[1]+".png\" -json \"fonts/"+fontName+"-"+args[1]+".json\" " +
                "-pxrange 8 -dimensions 2048 2048 -size " + args[2];
        ProcessBuilder builder =
                new ProcessBuilder(cmd.split(" "));
        builder.directory(new File(Gdx.files.getLocalStoragePath()));
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        try {
            int exitCode = builder.start().waitFor();
            if(exitCode != 0) {
                System.out.println("msdf-atlas-gen failed, returning exit code " + exitCode + "; terminating.");
                System.exit(exitCode);
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println("Applying changes for improved TextraTypist usage...");
        process(Gdx.files.local("fonts/"+fontName+"-"+args[1]+".png"));

        System.out.println("Optimizing result with oxipng...");
        builder.command(("distbin/oxipng -o 6 -s \"fonts/"+fontName+"-"+args[1]+".png\"").split(" "));
        try {
            int exitCode = builder.start().waitFor();
            if(exitCode != 0) {
                System.out.println("oxipng failed, returning exit code " + exitCode + "; terminating.");
                System.exit(exitCode);
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println("Creating a preview...");
        Font font = new Font("fonts/"+fontName+"-"+args[1]+".json",
                new TextureRegion(new Texture("fonts/"+fontName+"-"+args[1]+".png")), 0f, 0f, 0f, 0f, true, true);
        font.resizeDistanceField(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        font.scaleTo(font.originalCellWidth*36f/font.originalCellHeight, 36f/font.originalCellHeight);

        layout.setBaseColor(Color.DARK_GRAY);
        layout.setMaxLines(20);
        layout.setEllipsis(" and so on and so forth...");
//            font.markup("[%300][#44DD22]digital[%]\n[#66EE55]just numeric things \n"
//                    , layout);
        font.markup(text, layout);

        ScreenUtils.clear(0.75f, 0.75f, 0.75f, 1f);
        float x = Gdx.graphics.getBackBufferWidth() * 0.5f;
        float y = (Gdx.graphics.getBackBufferHeight() + layout.getHeight()) * 0.5f - font.descent * font.scaleY;
        batch.begin();
        font.enableShader(batch);
        font.drawGlyphs(batch, layout, x, y, Align.center);
        batch.end();

        // Modified Pixmap.createFromFrameBuffer() code that uses RGB instead of RGBA
        Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
        final Pixmap pm = new Pixmap(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), Pixmap.Format.RGB888);
        ByteBuffer pixels = pm.getPixels();
        Gdx.gl.glReadPixels(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), GL20.GL_RGB, GL20.GL_UNSIGNED_BYTE, pixels);
        // End Pixmap.createFromFrameBuffer() modified code

        PixmapIO.writePNG(Gdx.files.local("previews/" + fontName+"-"+args[1] + ".png"), pm, 0, true);

        builder.command(("distbin/oxipng -o 6 -s \"previews/"+fontName+"-"+args[1]+".png\"").split(" "));
        try {
            int exitCode = builder.start().waitFor();
            if(exitCode != 0) {
                System.out.println("oxipng failed, returning exit code " + exitCode + "; terminating.");
                System.exit(exitCode);
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            System.exit(1);
        }


        Gdx.app.exit();
    }

    private void process (FileHandle file) {
        if(!file.exists()) {
            System.out.println("The specified file " + file + " does not exist; skipping.");
            return;
        }
        Pixmap pm = new Pixmap(file);

        final int w = pm.getWidth(), h = pm.getHeight();
        for (int x = w - 3; x < w; x++) {
            for (int y = h - 3; y < h; y++) {
                int color = pm.getPixel(x, y);
                if (!((color & 0xFF) == 0 || (color >>> 8) == 0)) {
                    throw new GdxRuntimeException("Had a transparency problem with " + file.name());
                }
            }
        }
        pm.setColor(-1);
        pm.fillRectangle(w - 3, h - 3, 3, 3);
        if("msdf".equals(args[1])){
            PixmapIO.writePNG(file, pm, 0, false);
            return;
        }

        OutputStream output = file.write(false);
        try {
            DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
            DataOutputStream dataOutput = new DataOutputStream(output);
            try {
                dataOutput.write(SIGNATURE);

                buffer.writeInt(IHDR);
                buffer.writeInt(pm.getWidth());
                buffer.writeInt(pm.getHeight());
                buffer.writeByte(8); // 8 bits per component.
                buffer.writeByte(COLOR_INDEXED);
                buffer.writeByte(COMPRESSION_DEFLATE);
                buffer.writeByte(FILTER_NONE);
                buffer.writeByte(INTERLACE_NONE);
                buffer.endChunk(dataOutput);

                buffer.writeInt(PLTE);
                for (int i = 0; i < 256; i++) {
                    buffer.write(255);
                    buffer.write(255);
                    buffer.write(255);
                }
                buffer.endChunk(dataOutput);

                buffer.writeInt(TRNS);

                for (int i = 0; i < 256; i++) {
                    buffer.write(i);
                }

                buffer.endChunk(dataOutput);
                buffer.writeInt(IDAT);
                deflater.reset();

                int lineLen = pm.getWidth();
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

    static class ChunkBuffer extends DataOutputStream {
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