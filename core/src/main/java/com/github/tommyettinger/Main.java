package com.github.tommyettinger;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.*;
import com.badlogic.gdx.utils.compression.Lzma;
import com.github.tommyettinger.textra.ColorLookup;
import com.github.tommyettinger.textra.Font;
import com.github.tommyettinger.textra.Layout;
import com.github.tommyettinger.textra.utils.LZBCompression;
import com.github.tommyettinger.textra.utils.StringUtils;

import java.io.*;
import java.lang.StringBuilder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.awt.Font.TRUETYPE_FONT;

/** {@link com.badlogic.gdx.ApplicationListener} implementation shared by all platforms. */
public class Main extends ApplicationAdapter {

    private final IndexedPngWriter indexedPngWriter = new IndexedPngWriter();
    private FontwriterConfig config;

    private SpriteBatch batch;

    private final Layout layout = new Layout().setTargetWidth(1200);
    private static final String text = "Fonts can be rendered normally,{CURLY BRACKETS ARE IGNORED} but using [[tags], you can..."
            + "\n[#E74200]...use CSS-style hex colors like [*]#E74200[*]..."
            + "\n[darker purple blue]...use color names or descriptions, like [/]darker purple blue[/]...[ ]"
            + "\n[_]...and use [!]effects[!][_]!"
            + "\nNormal, [*]bold[*], [/]oblique[/] (like italic), [*][/]bold oblique[ ],"
            + "\n[_]underline (even for multiple words)[_], [~]strikethrough (same)[ ],"
            + "\nscaling: [%50]very [%75]small [%100]to [%150]quite [%200]large[ ], notes: [.]sub-[.], [=]mid-[=], and [^]super-[^]script,"
            + "\ncapitalization changes: [;]Each cap, [,]All lower, [!]Caps lock[ ],"
            + "\n[?small caps][*]Special[*][?] [?whiten][/]Effects[/][?][#]: [?shadow]drop shadow[?], [?jostle]RaNsoM nOtE[?], [?error]spell check[?]..."
            + "\nWelcome to the [TEAL][?neon]Structured JSON Zone[ ]!";

    String archPath, atlasGenBinary, oxipngBinary = "oxipng";

    public Main(String[] args) {
        try {
            this.config = ConfigParser.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }

        if (config.helpRequested) {
            CliMessages.printHelp();
            System.exit(0);
        }

        if (config.versionRequested) {
            CliMessages.printVersion();
            System.exit(0);
        }

        switch (SharedLibraryLoader.os){
            case Windows: archPath = "distbin/win-x64/";
                atlasGenBinary = "msdf-atlas-gen.exe";
                oxipngBinary = "oxipng.exe";
                break;
            case MacOsX: archPath = SharedLibraryLoader.architecture == Architecture.ARM
                ? "distbin/mac-arm64/"
                : "distbin/mac-x64/";
                atlasGenBinary = "msdf-atlas-gen";
                break;
            default:
                archPath = "distbin/linux-x64/";
                atlasGenBinary = "msdf-atlas-gen";
        }

    }

    @Override
    public void create() {
        batch = new SpriteBatch();
        Gdx.files.local("fonts").mkdirs();
        Gdx.files.local("previews").mkdirs();

        if (config.specialCommand != null) {
            runSpecialCommand();
        } else {
            mainProcess();
        }

        Gdx.app.exit();
    }

    private void runSpecialCommand() {
        FontwriterConfig.SpecialCommand command = config.specialCommand;
        String inPath = config.resolveSpecialCommandPath();

        switch (command) {
            case BULK: {
                FileHandle[] files = Gdx.files.local(inPath).list(
                        (dir, name) -> name.endsWith("ttf") || name.endsWith("otf"));
                FontwriterConfig.Mode[] modes = {FontwriterConfig.Mode.STANDARD, FontwriterConfig.Mode.SDF, FontwriterConfig.Mode.MSDF};
                for (FileHandle file : files) {
                    for (FontwriterConfig.Mode m : modes) {
                        FontwriterConfig bulkConfig = new FontwriterConfig();
                        bulkConfig.fontPath = file.path();
                        bulkConfig.mode = m;
                        if (file.name().startsWith("Go-Noto")) {
                            bulkConfig.initialSize = "30";
                            bulkConfig.imageSize = "4096x4096";
                        } else {
                            bulkConfig.initialSize = "280";
                            bulkConfig.imageSize = "2048x2048";
                        }
                        bulkConfig.color = "black";
                        this.config = bulkConfig;
                        mainProcess();
                    }
                }
                break;
            }
            case PREVIEW: {
                FileHandle[] files = Gdx.files.local(inPath).list(
                        (dir, name) -> name.endsWith("json"));
                for (FileHandle file : files) {
                    String filePath = file.path().substring(0, file.path().lastIndexOf('-'));
                    String fileMode = file.nameWithoutExtension().substring(file.nameWithoutExtension().lastIndexOf('-') + 1);
                    String fontName = filePath.substring(Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\')) + 1);

                    FontwriterConfig previewConfig = new FontwriterConfig();
                    previewConfig.fontPath = filePath;
                    previewConfig.mode = FontwriterConfig.Mode.fromString(fileMode);
                    this.config = previewConfig;
                    makePreview(inPath + "/", fontName);
                }
                break;
            }
            case UBJ: {
                FileHandle[] files = Gdx.files.local(inPath).list(
                        (dir, name) -> name.endsWith("json"));
                for (FileHandle file : files) {
                    convertToUBJSON(file);
                }
                break;
            }
            case LZMA: {
                FileHandle[] files = Gdx.files.local(inPath).list(
                        (dir, name) -> name.endsWith("json"));
                for (FileHandle file : files) {
                    convertToLzma(file);
                }
                break;
            }
        }
    }

    public void mainProcess() {
        String fontFileName = config.fontPath;
        FontwriterConfig.Mode mode = config.mode;
        FileHandle fontHandle = Gdx.files.absolute(fontFileName);
        if (!fontHandle.exists()) {
            fontHandle = Gdx.files.local(fontFileName);
        }
        String fontName = fontHandle.nameWithoutExtension();
        FileHandle cmap = fontHandle.sibling(fontHandle.name() + ".cmap.txt");
        int cmapLength;

        // ---------------------------------------------------------------
        //  Character set resolution — fallback hierarchy:
        //
        //  1. --charset given  → use that predefined set
        //  2. --lang given     → extract chars from matched files (folder, glob, or file)
        //                        (plus ASCII 32–126 baseline)
        //  3. neither given    → all visible chars (codepoints 32–65535)
        //
        //  See FontwriterConfig.resolveCharsetStrategy() for the logic.
        // ---------------------------------------------------------------
        FontwriterConfig.CharsetStrategy charsetStrategy = config.resolveCharsetStrategy();
        IntSet charSet = new IntSet(65536);

        if (charsetStrategy == FontwriterConfig.CharsetStrategy.PRESET) {
            System.out.println("Building character map from predefined charset: " + config.charset + "...");
            populateCharset(charSet, config.charset);
        } else if (charsetStrategy == FontwriterConfig.CharsetStrategy.LANG) {
            System.out.println("Building character map from I18N source: " + config.langPath + "...");
            // Baseline: ASCII + extended ASCII (32–255)
            for (int i = 32; i <= 255; i++) {
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

        // Weird/control chars to exclude
        int[] weirdChars =
            {0x200C, 0x200D, 0x200E, 0x200F, 0x2028, 0x2029, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x206A,
                0x206B, 0x206C, 0x206D, 0x206E, 0x206F};

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
                    Arrays.binarySearch(weirdChars, code) >= 0) {
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
        cmapLength = sb.length();
        long size = Math.round(Double.parseDouble(config.initialSize));
        size = Math.min(cmapLength >= 30000 ? 55 : 280, size);
        String imageSize = config.resolveImageSize(cmapLength);
        boolean fullPreview = config.hasPreviewColor();
        int fullPreviewColor;
        if (fullPreview)
            fullPreviewColor = stringToColor(config.color);
        else {
            fullPreviewColor = -1;
        }
        System.out.println("Generating structured JSON font and PNG using msdf-atlas-gen...");
        List<String> commandList = new ArrayList<>();
        commandList.add(archPath + atlasGenBinary);
        commandList.add("-font");
        commandList.add(fontFileName);
        commandList.add("-charset");
        commandList.add(fontFileName + ".cmap.txt");
        commandList.add("-type");
        commandList.add(mode.atlasGenType);
        commandList.add("-imageout");
        commandList.add("fonts/" + fontName + "-" + mode + ".png");
        commandList.add("-json");
        commandList.add("fonts/" + fontName + "-" + mode + ".json");
        commandList.add("-pxrange");
        commandList.add(String.valueOf(mode == FontwriterConfig.Mode.SDF ? size * 0.15f : size * 0.09f));
        commandList.add("-dimensions");
        String[] dims = imageSize.trim().split("[x ]+");
        commandList.add(dims[0]);
        commandList.add(dims[1]);
        commandList.add("-size");
        commandList.add(String.valueOf(size));
        commandList.add("-outerpxpadding");
        commandList.add("1");

        File workingDir = new File(Gdx.files.getLocalStoragePath());
        System.out.println("Running command: " + String.join(" ", commandList));
        while (true) {
            commandList.set(commandList.size() - 3, String.valueOf(size));
            commandList.set(commandList.size() - 8, mode == FontwriterConfig.Mode.SDF ? String.valueOf(size * 0.15f) : String.valueOf(size * 0.1));
            System.out.print("Trying size: " + size + "... ");
            int exitCode = BinaryExec.run(archPath + atlasGenBinary, "msdf-atlas-gen", commandList, workingDir);
            if (exitCode != 0) {
                long failedSize = size;
                if (--size <= 0) {
                    System.err.println("Error: msdf-atlas-gen could not fit glyphs into the atlas at any size "
                        + "(last attempted: " + failedSize + "). Terminating.");
                    System.exit(exitCode);
                    break;
                }
            } else {
                System.out.println("\nSuccessfully generated atlas using font size " + size + ".");
                break;
            }
        }

        System.out.println("Compressing .JSON file (optional)...");
        FileHandle jsonHandle = Gdx.files.local("fonts/" + fontName + "-" + mode + ".json");
        convertToUBJSON(jsonHandle);
        convertToLzma(jsonHandle);
        ByteArray ba = LZBCompression.compressToByteArray(jsonHandle.readString("UTF8"));
        Gdx.files.local("fonts/" + fontName + "-" + mode + ".dat").writeBytes(ba.items, 0, ba.size, false);

        System.out.println("Applying changes for improved TextraTypist usage...");
        FileHandle imageFile = Gdx.files.local("fonts/" + fontName + "-" + mode + ".png");
        FileHandle fullPreviewFile = imageFile;
        if (fullPreview) {
            fullPreviewFile = Gdx.files.local("previews/full-" + config.color + "-" + fontName + "-" + mode + ".png");
            imageFile.copyTo(fullPreviewFile);
            process(fullPreviewFile, fullPreviewColor);
        }
        process(imageFile);

        System.out.println("Optimizing result with oxipng...");

        List<String> oxiCmd = new ArrayList<>();
        oxiCmd.add(archPath + oxipngBinary);
        oxiCmd.add("-o");
        oxiCmd.add("6");
        oxiCmd.add("--ng");
        oxiCmd.add("-s");
        oxiCmd.add(imageFile.path());

        System.out.println("Running command: " + String.join(" ", oxiCmd));
        BinaryExec.runOrExit(archPath + oxipngBinary, "oxipng", oxiCmd, workingDir);

        if (fullPreview) {
            oxiCmd.set(oxiCmd.size() - 1, fullPreviewFile.path());
            System.out.println("Running command: " + String.join(" ", oxiCmd));
            BinaryExec.runOrExit(archPath + oxipngBinary, "oxipng", oxiCmd, workingDir);
        }
        makePreview("fonts/", fontName);

        // --- Summary: list all generated files with full paths ---
        System.out.println();
        System.out.println("Done! Generated files:");
        String basePath = "fonts/" + fontName + "-" + mode;
        String[] extensions = {".png", ".json", ".dat", ".ubj", ".json.lzma", ".ubj.lzma"};
        for (String ext : extensions) {
            FileHandle f = Gdx.files.local(basePath + ext);
            if (f.exists()) {
                System.out.println("  " + f.file().getAbsolutePath());
            }
        }
        FileHandle previewFile = Gdx.files.local("previews/" + fontName + "-" + mode + ".png");
        if (previewFile.exists()) {
            System.out.println("  " + previewFile.file().getAbsolutePath());
        }
        if (fullPreview) {
            FileHandle colorPreview = Gdx.files.local("previews/full-" + config.color + "-" + fontName + "-" + mode + ".png");
            if (colorPreview.exists()) {
                System.out.println("  " + colorPreview.file().getAbsolutePath());
            }
        }
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

    private void makePreview(String inPath, String fontName) {
        FontwriterConfig.Mode mode = config.mode;
        System.out.println("Creating a preview for " + fontName + "-" + mode + "...");
        Texture fontTexture = new Texture(inPath+fontName+"-"+mode+".png");
        Font font = new Font(inPath+fontName+"-"+mode+".json",
                new TextureRegion(fontTexture), 0f, 0f, 0f, 0f,
                true, true);
        if(fontTexture.getWidth() >= 1024 && fontTexture.getHeight() >= 1024)
            font.scaleHeightTo(32f);
        else
            font.scale(Math.round(512f / fontTexture.getHeight()))
                .setTextureFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
                .useIntegerPositions(true); // for pixel fonts
        font.resizeDistanceField(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());

        layout.setBaseColor(Color.DARK_GRAY);
        layout.setMaxLines(20);
        layout.setEllipsis(" and so on and so forth...");
        font.markup(text, layout);

        ScreenUtils.clear(0.75f, 0.75f, 0.75f, 1f);
        float x = Gdx.graphics.getBackBufferWidth() * 0.5f;
        float y = (Gdx.graphics.getBackBufferHeight() + layout.getHeight()) * 0.5f;
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

        FileHandle previewFile = Gdx.files.local("previews/" + fontName+"-"+mode + ".png");
        PixmapIO.writePNG(previewFile, pm, 0, true);

        List<String> oxiCmd = new ArrayList<>();
        oxiCmd.add(archPath + oxipngBinary);
        oxiCmd.add("-o");
        oxiCmd.add("6");
        oxiCmd.add("--ng");
        oxiCmd.add("-s");
        oxiCmd.add(previewFile.path());

        System.out.println("Running command: " + String.join(" ", oxiCmd));
        BinaryExec.runOrExit(archPath + oxipngBinary, "oxipng", oxiCmd,
                new File(Gdx.files.getLocalStoragePath()));
    }

    private void convertToUBJSON(FileHandle inFile){
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

    private void convertToLzma(FileHandle inFile){
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
        process(file, 256);
    }
    private void process (FileHandle file, int rgba) {
        if (!file.exists()) {
            System.out.println("The specified file " + file + " does not exist; skipping.");
            return;
        }
        Pixmap pm = new Pixmap(file);

        final int w = pm.getWidth(), h = pm.getHeight();
        if (rgba == 256) {
            for (int x = w - 3; x < w; x++) {
                for (int y = h - 3; y < h; y++) {
                    int color = pm.getPixel(x, y);
                    if (!((color & 0xFF) == 0 || (color >>> 8) == 0)) {
                        throw new GdxRuntimeException("Had a transparency problem with " + file.name());
                    }
                }
            }
            rgba = -1;
        }
        pm.setColor(-1);
        pm.fillRectangle(w - 3, h - 3, 3, 3);
        if(config.mode == FontwriterConfig.Mode.MSDF){
            PixmapIO.writePNG(file, pm, 0, false);
            return;
        }

        indexedPngWriter.write(file, pm, rgba);
    }

}
