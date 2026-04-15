package com.github.tommyettinger;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import com.github.tommyettinger.textra.Font;
import com.github.tommyettinger.textra.Layout;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the per-font preview PNG used as documentation of what
 * each generated atlas actually looks like at runtime.
 * <p>
 * Unlike the other extractions in this package, PreviewRenderer is a
 * <b>stateful</b> class rather than a static utility: it owns its
 * own {@link SpriteBatch} and reusable {@link Layout}, and it must
 * run on the libGDX render thread because it draws into the back
 * buffer and then calls {@code glReadPixels} to capture the result.
 * A single instance is expected to be constructed once per
 * application run, after the GL context is ready.
 * <p>
 * After rendering, the captured pixels are written to
 * {@code previews/&lt;fontName&gt;-&lt;mode&gt;.png} and then optimized
 * in place with oxipng via {@link BinaryExec#runOrExit}.
 */
final class PreviewRenderer {

    /**
     * Markup text used for every preview image. It exercises most of
     * TextraTypist's renderer (colors, bold/italic/underline, scale,
     * sub/superscript, case transforms, effects, neon, etc.) so the
     * generated preview gives a reasonably representative sample of
     * what the font can do.
     */
    private static final String TEXT = "Fonts can be rendered normally,{CURLY BRACKETS ARE IGNORED} but using [[tags], you can..."
            + "\n[#E74200]...use CSS-style hex colors like [*]#E74200[*]..."
            + "\n[darker purple blue]...use color names or descriptions, like [/]darker purple blue[/]...[ ]"
            + "\n[_]...and use [!]effects[!][_]!"
            + "\nNormal, [*]bold[*], [/]oblique[/] (like italic), [*][/]bold oblique[ ],"
            + "\n[_]underline (even for multiple words)[_], [~]strikethrough (same)[ ],"
            + "\nscaling: [%50]very [%75]small [%100]to [%150]quite [%200]large[ ], notes: [.]sub-[.], [=]mid-[=], and [^]super-[^]script,"
            + "\ncapitalization changes: [;]Each cap, [,]All lower, [!]Caps lock[ ],"
            + "\n[?small caps][*]Special[*][?] [?whiten][/]Effects[/][?][#]: [?shadow]drop shadow[?], [?jostle]RaNsoM nOtE[?], [?error]spell check[?]..."
            + "\nWelcome to the [TEAL][?neon]Structured JSON Zone[ ]!";

    private final SpriteBatch batch;
    private final Layout layout;
    private final String archPath;
    private final String oxipngBinary;

    /**
     * Constructs a renderer. Must be called on the libGDX render
     * thread, because it allocates a {@link SpriteBatch}. The
     * per-font configuration is passed to {@link #render} instead of
     * captured here, so a single instance can handle a sequence of
     * fonts (as in {@code --bulk} mode, where the active config
     * rotates between fonts).
     *
     * @param archPath     platform-specific {@code distbin/} directory
     *                     containing the oxipng executable
     * @param oxipngBinary bare filename of the oxipng executable
     *                     ({@code oxipng} or {@code oxipng.exe})
     */
    PreviewRenderer(String archPath, String oxipngBinary) {
        this.archPath = archPath;
        this.oxipngBinary = oxipngBinary;
        this.batch = new SpriteBatch();
        this.layout = new Layout().setTargetWidth(1200);
    }

    /**
     * Renders a preview for the given font and writes it to
     * {@code previews/&lt;fontName&gt;-&lt;mode&gt;.png}, then runs
     * oxipng over the result.
     *
     * @param config   configuration for this particular font (used
     *                 only for {@link FontwriterConfig#mode})
     * @param inPath   directory (with trailing slash) containing the
     *                 {@code <fontName>-<mode>.png} and
     *                 {@code <fontName>-<mode>.json} pair
     * @param fontName base name of the font (no extension)
     */
    public void render(FontwriterConfig config, String inPath, String fontName) {
        FontwriterConfig.Mode mode = config.mode;
        System.out.println("Creating a preview for " + fontName + "-" + mode + "...");
        Texture fontTexture = new Texture(inPath + fontName + "-" + mode + ".png");
        Font font = new Font(inPath + fontName + "-" + mode + ".json",
                new TextureRegion(fontTexture), 0f, 0f, 0f, 0f,
                true, true);
        if (fontTexture.getWidth() >= 1024 && fontTexture.getHeight() >= 1024)
            font.scaleHeightTo(32f);
        else
            font.scale(Math.round(512f / fontTexture.getHeight()))
                .setTextureFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
                .useIntegerPositions(true); // for pixel fonts
        font.resizeDistanceField(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());

        layout.setBaseColor(Color.DARK_GRAY);
        layout.setMaxLines(20);
        layout.setEllipsis(" and so on and so forth...");
        font.markup(TEXT, layout);

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

        FileHandle previewFile = Gdx.files.local("previews/" + fontName + "-" + mode + ".png");
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
}
