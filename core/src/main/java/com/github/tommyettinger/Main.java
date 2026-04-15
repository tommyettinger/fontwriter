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
        String command = config.specialCommand;

        if ("bulk".equals(command)) {
            String inPath = config.specialCommandPath != null ? config.specialCommandPath : "input";
            FileHandle[] files = Gdx.files.local(inPath).list(
                    (dir, name) -> name.endsWith("ttf") || name.endsWith("otf"));
            FontwriterConfig.Mode[] fields = {FontwriterConfig.Mode.STANDARD, FontwriterConfig.Mode.SDF, FontwriterConfig.Mode.MSDF};
            for (FileHandle file : files) {
                for (FontwriterConfig.Mode field : fields) {
                    FontwriterConfig bulkConfig = new FontwriterConfig();
                    bulkConfig.fontPath = file.path();
                    bulkConfig.mode = field;
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
        } else if ("preview".equals(command)) {
            String inPath = config.specialCommandPath != null ? config.specialCommandPath : "fonts";
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
        } else if ("ubj".equals(command)) {
            String inPath = config.specialCommandPath != null ? config.specialCommandPath : "fonts";
            FileHandle[] files = Gdx.files.local(inPath).list(
                    (dir, name) -> name.endsWith("json"));
            for (FileHandle file : files) {
                convertToUBJSON(file);
            }
        } else if ("lzma".equals(command)) {
            String inPath = config.specialCommandPath != null ? config.specialCommandPath : "fonts";
            FileHandle[] files = Gdx.files.local(inPath).list(
                    (dir, name) -> name.endsWith("json"));
            for (FileHandle file : files) {
                convertToLzma(file);
            }
        }
    }

    /**
     * Verifies that an external binary exists and is executable before
     * attempting to run it. Prints a descriptive error message and exits
     * if the binary is missing or not executable.
     *
     * @param binaryPath the relative path to the binary (e.g. "distbin/mac-arm64/msdf-atlas-gen")
     * @param binaryName a human-readable name for error messages (e.g. "msdf-atlas-gen")
     */
    private void verifyBinary(String binaryPath, String binaryName) {
        File binaryFile = new File(Gdx.files.getLocalStoragePath(), binaryPath);
        if (!binaryFile.exists()) {
            CliMessages.printBinaryNotFound(binaryName, binaryFile.getAbsolutePath());
            System.exit(1);
        }
        if (!binaryFile.canExecute()) {
            CliMessages.printBinaryNotExecutable(binaryName, binaryPath,
                    binaryFile.getAbsolutePath(), SharedLibraryLoader.os);
            System.exit(1);
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
            FileHandle[] langFiles = resolveLangFiles(config.langPath);

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

        verifyBinary(archPath + atlasGenBinary, "msdf-atlas-gen");

        ProcessBuilder builder = new ProcessBuilder(commandList);
        System.out.println("Running command: " + String.join(" ", builder.command()));

        builder.directory(new File(Gdx.files.getLocalStoragePath()));
        builder.inheritIO();
        while (true) {
            try {
                commandList.set(commandList.size() - 3, String.valueOf(size));
                commandList.set(commandList.size() - 8, mode == FontwriterConfig.Mode.SDF ? String.valueOf(size * 0.15f) : String.valueOf(size * 0.1));
                builder.command(commandList);
                System.out.print("Trying size: " + size + "... ");
                int exitCode = builder.start().waitFor();
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
            } catch (IOException e) {
                CliMessages.printBinaryRunFailed("msdf-atlas-gen", e.getMessage(), SharedLibraryLoader.os);
                System.exit(1);
                break;
            } catch (InterruptedException e) {
                CliMessages.printBinaryInterrupted("msdf-atlas-gen", e.getMessage());
                System.exit(1);
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
        verifyBinary(archPath + oxipngBinary, "oxipng");

        List<String> oxiCmd = new ArrayList<>();
        oxiCmd.add(archPath + oxipngBinary);
        oxiCmd.add("-o");
        oxiCmd.add("6");
        oxiCmd.add("--ng");
        oxiCmd.add("-s");
        oxiCmd.add(imageFile.path());

        builder.command(oxiCmd);
        System.out.println("Running command: " + String.join(" ", builder.command()));

        try {
            int exitCode = builder.start().waitFor();
            if (exitCode != 0) {
                CliMessages.printBinaryExitFailure("oxipng", exitCode);
                System.exit(exitCode);
            }
        } catch (IOException e) {
            CliMessages.printBinaryRunFailed("oxipng", e.getMessage(), SharedLibraryLoader.os);
            System.exit(1);
        } catch (InterruptedException e) {
            CliMessages.printBinaryInterrupted("oxipng", e.getMessage());
            System.exit(1);
        }
        if (fullPreview) {
            oxiCmd.set(oxiCmd.size() - 1, fullPreviewFile.path());
            builder.command(oxiCmd);
            System.out.println("Running command: " + String.join(" ", builder.command()));
            try {
                int exitCode = builder.start().waitFor();
                if (exitCode != 0) {
                    CliMessages.printBinaryExitFailure("oxipng", exitCode);
                    System.exit(exitCode);
                }
            } catch (IOException e) {
                CliMessages.printBinaryRunFailed("oxipng", e.getMessage(), SharedLibraryLoader.os);
                System.exit(1);
            } catch (InterruptedException e) {
                CliMessages.printBinaryInterrupted("oxipng", e.getMessage());
                System.exit(1);
            }

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
     * Resolves the --lang value into an array of files to read.
     * Three modes are supported:
     * <ol>
     *   <li><b>Glob pattern</b> (contains {@code *} or {@code ?}):
     *       The parent directory is listed and filenames are matched
     *       against the glob portion. Example: {@code "i18n/*.txt"},
     *       {@code "i18n/*"}.</li>
     *   <li><b>Single file</b> (path points to an existing file):
     *       Returns an array with just that one file.</li>
     *   <li><b>Folder</b> (path points to an existing directory):
     *       Reads all files in that directory (excluding hidden
     *       dot-files). Equivalent to passing the folder with "/*".</li>
     * </ol>
     *
     * @param langPath the raw --lang value from the user
     * @return matched files, or null/empty if nothing was found
     */
    private FileHandle[] resolveLangFiles(String langPath) {
        // --- Mode 1: Glob pattern (contains * or ?) ---
        if (langPath.contains("*") || langPath.contains("?")) {
            // Split into parent directory + filename pattern.
            // e.g. "i18n/*.txt" → parent="i18n", pattern="*.txt"
            // e.g. "i18n/*"          → parent="i18n", pattern="*"
            int lastSep = Math.max(langPath.lastIndexOf('/'), langPath.lastIndexOf('\\'));
            String parentPath;
            String globPattern;
            if (lastSep >= 0) {
                parentPath = langPath.substring(0, lastSep);
                globPattern = langPath.substring(lastSep + 1);
            } else {
                parentPath = ".";
                globPattern = langPath;
            }

            FileHandle parentDir = Gdx.files.absolute(parentPath);
            if (!parentDir.exists()) parentDir = Gdx.files.local(parentPath);
            if (!parentDir.exists() || !parentDir.isDirectory()) {
                CliMessages.printLangParentMissing(parentPath);
                return null;
            }

            // Convert glob to regex: escape dots, * → .*, ? → .
            final String regex = "^" + globPattern
                    .replace(".", "\\.")
                    .replace("*", ".*")
                    .replace("?", ".")
                    + "$";

            FileHandle[] matched = parentDir.list((d, name) -> name.matches(regex));
            if (matched == null || matched.length == 0) {
                CliMessages.printLangGlobNoMatch(globPattern, parentDir.path());
            } else {
                System.out.println("  Matched " + matched.length + " file(s) from pattern '" + langPath + "'.");
            }
            return matched;
        }

        // --- Mode 2 & 3: resolve as absolute or local path ---
        FileHandle resolved = Gdx.files.absolute(langPath);
        if (!resolved.exists()) resolved = Gdx.files.local(langPath);

        if (!resolved.exists()) {
            CliMessages.printLangPathMissing(langPath);
            return null;
        }

        // --- Mode 2: Single file ---
        if (!resolved.isDirectory()) {
            System.out.println("  Using single file: " + resolved.path());
            return new FileHandle[]{resolved};
        }

        // --- Mode 3: Folder — read all files in the directory ---
        FileHandle[] langFiles = resolved.list((d, name) -> !name.startsWith("."));
        if (langFiles == null || langFiles.length == 0) {
            CliMessages.printLangFolderEmpty(langPath);
        } else {
            System.out.println("  Found " + langFiles.length + " file(s) in folder '" + langPath + "'.");
        }
        return langFiles;
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

        // Verify oxipng binary — this method is also called from --preview,
        // which does not go through mainProcess(), so we must check here.
        verifyBinary(archPath + oxipngBinary, "oxipng");

        ProcessBuilder builder = new ProcessBuilder(oxiCmd);
        builder.directory(new File(Gdx.files.getLocalStoragePath()));
        builder.inheritIO();
        System.out.println("Running command: " + String.join(" ", builder.command()));
        try {
            int exitCode = builder.start().waitFor();
            if (exitCode != 0) {
                CliMessages.printBinaryExitFailure("oxipng", exitCode);
                System.exit(exitCode);
            }
        } catch (IOException e) {
            CliMessages.printBinaryRunFailed("oxipng", e.getMessage(), SharedLibraryLoader.os);
            System.exit(1);
        } catch (InterruptedException e) {
            CliMessages.printBinaryInterrupted("oxipng", e.getMessage());
            System.exit(1);
        }
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
