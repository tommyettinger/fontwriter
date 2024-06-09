package com.github.tommyettinger.lwjgl3;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.ByteArray;
import com.github.tommyettinger.textra.utils.LZBCompression;

/**
 * <pre>
 * 51566530 of JSON became
 *  7481665 of LZB-compressed DAT files.
 * </pre>
 */
public class CompressorTool extends ApplicationAdapter {
    @Override
    public void create() {
        FileHandle[] jsons = Gdx.files.local("knownFonts/").list(".json");
        long uncompressedSize = 0L, compressedSize = 0L;
        for(FileHandle json : jsons){
            uncompressedSize += json.length();
            ByteArray ba = LZBCompression.compressToByteArray(json.readString("UTF8"));
            FileHandle dat = json.sibling(json.nameWithoutExtension() + ".dat");
            dat.writeBytes(ba.items, 0, ba.size, false);
            compressedSize += dat.length();
        }
        System.out.printf("%20d of JSON became\n"+
                          "%20d of LZB-compressed DAT files.\n", uncompressedSize, compressedSize);
        System.exit(0);
    }

    public static void main(String[] args) {
        if (StartupHelper.startNewJvmIfRequired()) return; // This handles macOS support and helps on Windows.
        new Lwjgl3Application(new CompressorTool(), getDefaultConfiguration());
    }

    private static Lwjgl3ApplicationConfiguration getDefaultConfiguration() {
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.disableAudio(true);
        configuration.setTitle("Compressor Tool");
        configuration.useVsync(true);
        configuration.setWindowedMode(800, 600);
        configuration.setWindowIcon("libgdx128.png", "libgdx64.png", "libgdx32.png", "libgdx16.png");
        return configuration;
    }

}
