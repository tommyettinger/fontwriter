package com.github.tommyettinger;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.SharedLibraryLoader;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Helpers for launching the bundled native binaries (msdf-atlas-gen
 * and oxipng).
 * <p>
 * Each call verifies that the binary exists and is executable, then
 * runs it with {@link ProcessBuilder#inheritIO() inheritIO} and the
 * given working directory. All common failure modes (missing binary,
 * non-executable binary, {@link IOException}, {@link InterruptedException})
 * print a user-facing message via {@link CliMessages} and terminate
 * the JVM with exit code 1 — callers never need to handle those cases.
 * <p>
 * Two flavors are exposed:
 * <ul>
 *   <li>{@link #run(String, String, List, File)} returns the process
 *       exit code so the caller can react to it (used by the
 *       msdf-atlas-gen retry loop, which shrinks the font size on
 *       non-zero exit).</li>
 *   <li>{@link #runOrExit(String, String, List, File)} additionally
 *       terminates the JVM on any non-zero exit code (used by the
 *       oxipng sites, where a failed run is unrecoverable).</li>
 * </ul>
 */
final class BinaryExec {

    private BinaryExec() {} // utility class

    /**
     * Verifies the binary, runs it, and returns its exit code. Exits
     * the JVM with code 1 if the binary is missing, not executable,
     * throws an {@link IOException}, or the current thread is
     * interrupted while waiting.
     *
     * @param binaryPath relative path to the binary (e.g.
     *                   {@code "distbin/mac-arm64/msdf-atlas-gen"})
     * @param binaryName human-readable name for error messages
     * @param command    full command line; {@code command.get(0)} is
     *                   typically {@code binaryPath}
     * @param workingDir working directory for the child process
     * @return the process exit code
     */
    public static int run(String binaryPath, String binaryName, List<String> command, File workingDir) {
        verify(binaryPath, binaryName);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDir);
        builder.inheritIO();
        try {
            return builder.start().waitFor();
        } catch (IOException e) {
            CliMessages.printBinaryRunFailed(binaryName, e.getMessage(), SharedLibraryLoader.os);
            System.exit(1);
        } catch (InterruptedException e) {
            CliMessages.printBinaryInterrupted(binaryName, e.getMessage());
            System.exit(1);
        }
        return -1; // unreachable; System.exit above
    }

    /**
     * Like {@link #run}, but additionally terminates the JVM with the
     * process exit code if it is non-zero. Use this for steps whose
     * failure is unrecoverable.
     */
    public static void runOrExit(String binaryPath, String binaryName, List<String> command, File workingDir) {
        int exitCode = run(binaryPath, binaryName, command, workingDir);
        if (exitCode != 0) {
            CliMessages.printBinaryExitFailure(binaryName, exitCode);
            System.exit(exitCode);
        }
    }

    /**
     * Checks that the binary at {@code binaryPath} exists and is
     * executable. Prints a user-facing diagnostic and exits with code
     * 1 otherwise.
     */
    private static void verify(String binaryPath, String binaryName) {
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
}
