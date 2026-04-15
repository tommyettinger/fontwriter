package com.github.tommyettinger;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

/**
 * Resolves the {@code --lang} CLI value into an array of files whose
 * contents are then scanned to build the font's character map.
 * <p>
 * Three input modes are supported:
 * <ol>
 *   <li><b>Glob pattern</b> (contains {@code *} or {@code ?}) — the
 *       parent directory is listed and filenames are matched against
 *       the glob portion, e.g. {@code "i18n/*.txt"} or
 *       {@code "i18n/*"}.</li>
 *   <li><b>Single file</b> — the path points to an existing file; an
 *       array with just that file is returned.</li>
 *   <li><b>Folder</b> — the path points to an existing directory; all
 *       non-hidden files inside it are returned. Equivalent to passing
 *       the folder with {@code "/*"}.</li>
 * </ol>
 * User-facing diagnostics for missing paths, empty folders, and
 * unmatched globs are routed through {@link CliMessages}. On any of
 * those failures the returned array is either {@code null} or empty,
 * and the caller is expected to fall back to a default charset.
 */
final class LangFileResolver {

    private LangFileResolver() {} // utility class

    /**
     * Resolves the {@code --lang} value into an array of files to read.
     *
     * @param langPath the raw {@code --lang} value from the user
     * @return matched files, or {@code null}/empty if nothing was found
     */
    public static FileHandle[] resolve(String langPath) {
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
}
