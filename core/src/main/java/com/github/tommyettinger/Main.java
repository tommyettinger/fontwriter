package com.github.tommyettinger;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.utils.*;
import com.github.tommyettinger.textra.utils.LZBCompression;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link com.badlogic.gdx.ApplicationListener} implementation shared by all platforms.
 * <p>
 * Main owns the top-level font generation pipeline. On startup it parses
 * the CLI args into a {@link FontwriterConfig}, detects the host OS to
 * pick the correct {@code distbin/} directory for the bundled
 * msdf-atlas-gen and oxipng binaries, and then — once libGDX has
 * created a GL context in {@link #create()} — either dispatches to
 * {@link #runBatchCommand()} (for {@code --bulk}, {@code --preview},
 * {@code --ubj}, {@code --lzma}) or runs {@link #mainProcess()} once
 * for the single font requested on the command line.
 * <p>
 * The heavy lifting is delegated to small single-purpose classes in
 * this package: {@link CharMapBuilder}, {@link FontwriterUtils},
 * {@link PreviewRenderer}, {@link IndexedPngWriter}, {@link BinaryExec},
 * and {@link LangFileResolver}. Main itself is just the orchestrator.
 */
public class Main extends ApplicationAdapter {

    private final IndexedPngWriter indexedPngWriter = new IndexedPngWriter();
    private FontwriterConfig config;
    private PreviewRenderer previewRenderer;

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
        previewRenderer = new PreviewRenderer(archPath, oxipngBinary);
        Gdx.files.local("fonts").mkdirs();
        Gdx.files.local("previews").mkdirs();

        if (config.batchCommand != null) {
            runBatchCommand();
        } else {
            mainProcess();
        }

        Gdx.app.exit();
    }

    private void runBatchCommand() {
        FontwriterConfig.BatchCommand command = config.batchCommand;
        String inPath = config.resolveBatchCommandPath();

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
                    previewRenderer.render(config, inPath + "/", fontName);
                }
                break;
            }
            case UBJ: {
                FileHandle[] files = Gdx.files.local(inPath).list(
                        (dir, name) -> name.endsWith("json"));
                for (FileHandle file : files) {
                    FontwriterUtils.convertToUBJSON(file);
                }
                break;
            }
            case LZMA: {
                FileHandle[] files = Gdx.files.local(inPath).list(
                        (dir, name) -> name.endsWith("json"));
                for (FileHandle file : files) {
                    FontwriterUtils.convertToLzma(file);
                }
                break;
            }
        }
    }

    /**
     * Runs the full single-font generation pipeline for the currently
     * active {@link #config}. This is the method that actually produces
     * the deliverables for one {@code (font, mode)} pair.
     * <p>
     * Step [5] is the one non-obvious part: msdf-atlas-gen fails with a
     * non-zero exit code when the requested glyphs don't fit into the
     * chosen image dimensions at the chosen font size. Rather than
     * asking the user to guess, Main retries with {@code size - 1} on
     * every failure and gives up only when size drops to zero. The
     * {@code -pxrange} argument is recomputed on every retry because
     * it's derived from the current size.
     * <p>
     * This method assumes it's being called on the libGDX render thread
     * because step [9] (preview rendering) uses a shared {@link SpriteBatch}
     * and reads back the GL back buffer.
     */
    public void mainProcess() {
        // [1] Resolve font file handle (absolute path preferred, local fallback)
        String fontFileName = config.fontPath;
        FontwriterConfig.Mode mode = config.mode;
        FileHandle fontHandle = Gdx.files.absolute(fontFileName);
        if (!fontHandle.exists()) {
            fontHandle = Gdx.files.local(fontFileName);
        }
        String fontName = fontHandle.nameWithoutExtension();

        // [2] Build the character map (cmap) file for msdf-atlas-gen
        FileHandle cmap = fontHandle.sibling(fontHandle.name() + ".cmap.txt");
        int cmapLength = CharMapBuilder.build(config, fontFileName, cmap);

        // [3] Pick initial atlas size and image dimensions
        long size = Math.round(Double.parseDouble(config.initialSize));
        size = Math.min(cmapLength >= 30000 ? 55 : 280, size);
        String imageSize = config.resolveImageSize(cmapLength);
        boolean fullPreview = config.hasPreviewColor();
        int fullPreviewColor;
        if (fullPreview)
            fullPreviewColor = FontwriterUtils.stringToColor(config.color);
        else {
            fullPreviewColor = -1;
        }

        // [4] Assemble the msdf-atlas-gen command
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

        // [5] Run msdf-atlas-gen, shrinking the font size on failure until it fits
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

        // [6] Compress the generated JSON into UBJ, LZMA, and LZB (.dat) companion files
        System.out.println("Compressing .JSON file (optional)...");
        FileHandle jsonHandle = Gdx.files.local("fonts/" + fontName + "-" + mode + ".json");
        FontwriterUtils.convertToUBJSON(jsonHandle);
        FontwriterUtils.convertToLzma(jsonHandle);
        ByteArray ba = LZBCompression.compressToByteArray(jsonHandle.readString("UTF8"));
        Gdx.files.local("fonts/" + fontName + "-" + mode + ".dat").writeBytes(ba.items, 0, ba.size, false);

        // [7] Post-process the atlas PNG (stamp marker corner, optional color preview, palette convert)
        System.out.println("Applying changes for improved TextraTypist usage...");
        FileHandle imageFile = Gdx.files.local("fonts/" + fontName + "-" + mode + ".png");
        FileHandle fullPreviewFile = imageFile;
        if (fullPreview) {
            fullPreviewFile = Gdx.files.local("previews/full-" + config.color + "-" + fontName + "-" + mode + ".png");
            imageFile.copyTo(fullPreviewFile);
            process(fullPreviewFile, fullPreviewColor);
        }
        process(imageFile, NO_COLOR_OVERRIDE);

        // [8] Optimize the atlas PNG (and color preview, if any) with oxipng
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
        // [9] Render the documentation preview PNG for the generated font
        previewRenderer.render(config, "fonts/", fontName);

        // [10] Print a summary listing every file produced for this font
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
     * Sentinel value for {@link #process(FileHandle, int)} meaning
     * "no caller-supplied color": the method then validates that the
     * bottom-right 3x3 corner of the atlas is safe to stamp over and
     * falls back to opaque white ({@code -1}) as the palette RGB.
     * Chosen as 256 because all real RGBA8888 values fit in 32 bits,
     * so 256 is outside any legal color.
     */
    private static final int NO_COLOR_OVERRIDE = 256;

    /**
     * Post-processes an atlas PNG generated by msdf-atlas-gen so that it
     * works correctly as a TextraTypist bitmap font texture.
     * <p>
     * Two things happen here:
     * <ol>
     *   <li>The bottom-right 3x3 corner is stamped opaque white. This
     *       corner is reserved as a solid pixel that TextraTypist can
     *       sample for color-tinted effects and rectangle fills without
     *       having to allocate a second texture. If the caller did not
     *       supply an explicit palette color ({@link #NO_COLOR_OVERRIDE}),
     *       the corner is first checked to make sure no real glyph
     *       pixels will be overwritten; finding any non-transparent
     *       pixels there is treated as a fatal error.</li>
     *   <li>MSDF atlases are left as full RGBA PNGs. STANDARD and SDF
     *       atlases are instead rewritten through {@link IndexedPngWriter}
     *       as 8-bit palette PNGs, which is drastically smaller for the
     *       single-channel data these modes produce.</li>
     * </ol>
     *
     * @param file the atlas PNG to rewrite in place
     * @param rgba either an RGBA8888 palette color for the color preview,
     *             or {@link #NO_COLOR_OVERRIDE} for the normal atlas path
     */
    private void process (FileHandle file, int rgba) {
        if (!file.exists()) {
            System.out.println("The specified file " + file + " does not exist; skipping.");
            return;
        }
        Pixmap pm = new Pixmap(file);

        final int w = pm.getWidth(), h = pm.getHeight();
        if (rgba == NO_COLOR_OVERRIDE) {
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
