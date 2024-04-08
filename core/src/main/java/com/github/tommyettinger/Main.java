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
import com.github.tommyettinger.textra.ColorLookup;
import com.github.tommyettinger.textra.Font;
import com.github.tommyettinger.textra.Layout;
import com.github.tommyettinger.textra.utils.ColorUtils;
import com.github.tommyettinger.textra.utils.StringUtils;

import java.awt.FontFormatException;
import java.io.*;
import java.lang.StringBuilder;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
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
            System.out.println("You must pass at least the following parameters:");
            System.out.println(" - the path to a font file (it can be TTF or OTF),");
            System.out.println(" - the mode to use ('msdf', 'sdf', 'mtsdf', 'psdf', or 'standard'),");
            System.out.println(" - the initial size to try (as an int or double)");
            System.out.println("Optionally, you can pass the following parameters after those:");
            System.out.println(" - the image size (it defaults to 2048x2048, and must be separated by 'x')");
            System.out.println(" - a color name or hex code, optionally in quotes to use TextraTypist color description");
            System.out.println();
            System.out.println("For example, you could use this full command:");
            System.out.println("java -jar fontwriter-1.0.1.jar Gentium.ttf standard 63");
            System.out.println("or this one:");
            System.out.println("java -jar fontwriter-1.0.1.jar \"Ostrich Black.ttf\" standard 425 2048x2048 \"dark dullest violet-blue\"");
            System.out.println();
            System.out.println("Both will write the complete contents of the font, at different font sizes, and");
            System.out.println("the second command will write an extra preview of all glyphs with dark blue text.");
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
        FileHandle cmap = Gdx.files.local(fontFileName + ".cmap.txt");
        if(!cmap.exists()) {
            System.out.println("Building character map...");
            StringBuilder sb = new StringBuilder(1024);
            try {
                java.awt.Font af = java.awt.Font.createFont(TRUETYPE_FONT, new File(args[0]));
                int[] weirdChars = {0x200C, 0x200D, 0x200E, 0x200F, 0x2028, 0x2029, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x206A, 0x206B, 0x206C, 0x206D, 0x206E, 0x206F};
                for (int i = 32; i < 65536; i++) {
                    if(Arrays.binarySearch(weirdChars, i) < 0 && af.canDisplay(i))
                        sb.append(i).append(' ');
                }
                if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
            } catch (FontFormatException | IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
            cmap.writeString(sb.toString(), false, "UTF-8");
        }
        long size = Math.round(Double.parseDouble(args[2]));
        String imageSize = args.length > 3 ? args[3].replace('x', ' ') : "2048 2048";
        boolean fullPreview = args.length > 4;
        int fullPreviewColor;
        if(fullPreview)
            fullPreviewColor = stringToColor(args[4].replaceAll("['\"]", ""));
        else
            fullPreviewColor = -1;
        System.out.println("Generating structured JSON font and PNG using msdf-atlas-gen...");
        String cmd = "distbin/msdf-atlas-gen -font \"" + fontFileName + "\" -charset \"" + fontFileName + ".cmap.txt\"" +
                " -type "+("standard".equals(args[1]) ? "softmask" : args[1])+" -imageout \"fonts/"+fontName+"-"+args[1]+".png\" -json \"fonts/"+fontName+"-"+args[1]+".json\" " +
                "-pxrange " + Math.pow(Math.log(size) * 0.31, 4.8) + " -dimensions " + imageSize + " -size " + size;
        ProcessBuilder builder =
                new ProcessBuilder(cmd.split(" "));
        List<String> commandList = builder.command();
        builder.directory(new File(Gdx.files.getLocalStoragePath()));
        builder.inheritIO();
        while (true) {
            try {
                commandList.set(commandList.size()-1, String.valueOf(size));
                commandList.set(commandList.size()-6, String.valueOf(Math.pow(Math.log(size) * 0.31, 4.8)));
                builder.command(commandList);
                int exitCode = builder.start().waitFor();
                if (exitCode != 0) {
                    if (--size <= 0) {
                        System.out.println("msdf-atlas-gen failed, returning exit code " + exitCode + "; terminating.");
                        System.exit(exitCode);
                        break;
                    }
                } else {
                    System.out.println("Using size " + size + ".");
                    break;
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                System.exit(1);
                break;
            }
        }

        System.out.println("Applying changes for improved TextraTypist usage...");
        FileHandle imageFile = Gdx.files.local("fonts/"+fontName+"-"+args[1]+".png");
        if(fullPreview)
        {
            FileHandle fullPreviewFile = Gdx.files.local("previews/full-"+args[4]+"-"+fontName+"-"+args[1]+".png");
            imageFile.copyTo(fullPreviewFile);
            process(fullPreviewFile, fullPreviewColor);
        }
        process(imageFile);

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
        if (fullPreview) {
            builder.command(("distbin/oxipng -o 6 -s \"previews/full-"+args[4]+"-"+fontName+"-"+args[1]+".png\"").split(" "));
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

        }
        System.out.println("Creating a preview...");
        Font font = new Font("fonts/"+fontName+"-"+args[1]+".json",
                new TextureRegion(new Texture("fonts/"+fontName+"-"+args[1]+".png")), 0f, 0f, 0f, 0f, true, true);
        font.setTextureFilter();
        font.scaleTo(font.originalCellWidth*36f/font.originalCellHeight, 36f);
        font.resizeDistanceField(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());

        layout.setBaseColor(Color.DARK_GRAY);
        layout.setMaxLines(20);
        layout.setEllipsis(" and so on and so forth...");
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

    private int stringToColor(String str) {
        if (str != null) {
            // Try to parse named color
            ColorLookup lookup = ColorLookup.DESCRIPTIVE;
            int namedColor = lookup.getRgba(str);
            if (namedColor != 256) {
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

    private void process (FileHandle file) {
        process(file, -1);
    }
    private void process (FileHandle file, int rgba) {
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