package com.github.tommyettinger;

/**
 * Parses CLI arguments into a {@link FontwriterConfig}.
 * <p>
 * Supports two invocation styles:
 * <ol>
 *   <li><b>Named flags</b> (preferred): optional arguments use
 *       {@code --flag value} after the three required positional
 *       arguments, in any order.</li>
 *   <li><b>Legacy positional</b> (deprecated): all six arguments are
 *       given in the original fixed order for backward compatibility
 *       with existing scripts. Will be removed in a future version.</li>
 * </ol>
 * The parser auto-detects which style is in use: if any argument after
 * the first three starts with "-", named-flag mode is used; otherwise
 * legacy positional mode is assumed.
 * <p>
 * Batch commands ({@code --bulk}, {@code --preview}, {@code --ubj},
 * {@code --lzma}) are detected first and short-circuit the rest of
 * the parsing.
 */
public class ConfigParser {

    private ConfigParser() {} // utility class

    /**
     * Parses the raw CLI argument array into a fully populated
     * {@link FontwriterConfig}.
     *
     * @param args the raw argument array from {@code main()}; may be null or empty
     * @return a populated config; never null
     * @throws IllegalArgumentException if required arguments are missing
     *         or an unrecognized flag is encountered
     */
    public static FontwriterConfig parse(String[] args) {
        FontwriterConfig config = new FontwriterConfig();

        // --- No arguments or explicit help request ---
        if (args == null || args.length == 0) {
            config.helpRequested = true;
            return config;
        }

        String first = args[0];

        // --- Help (--help is primary; -h is shorthand) ---
        if ("--help".equals(first) || "-h".equals(first)) {
            config.helpRequested = true;
            return config;
        }

        // --- Version (--version is primary; -v is shorthand) ---
        if ("--version".equals(first) || "-v".equals(first)) {
            config.versionRequested = true;
            return config;
        }

        // --- Batch commands ---
        FontwriterConfig.BatchCommand batch = FontwriterConfig.BatchCommand.fromFlag(first);
        if (batch != null) {
            config.batchCommand = batch;
            if (args.length > 1) {
                config.batchCommandPath = args[1];
            }
            return config;
        }

        // --- Standard font generation: need at least 3 args ---
        if (args.length < 3) {
            config.helpRequested = true;
            return config;
        }

        // First three are always positional.
        config.fontPath = args[0];
        config.mode = FontwriterConfig.Mode.fromString(args[1]);
        config.initialSize = args[2];

        // Detect whether the optional arguments use named flags or
        // legacy positional style. If any arg starting at index 3
        // begins with "-", we treat them all as flags.
        //
        // BACKWARD COMPATIBILITY — DEPRECATED:
        // The legacy positional path exists only so that existing scripts
        // using the old "<font> <mode> <size> <WxH> <color> <langPath>"
        // syntax continue to work. It is scheduled for removal in a
        // future version. New callers should always use named flags
        // (--image-size, --color, --lang, --charset).
        boolean hasFlags = false;
        for (int i = 3; i < args.length; i++) {
            if (args[i].startsWith("-")) {
                hasFlags = true;
                break;
            }
        }

        if (hasFlags) {
            parseFlags(args, 3, config);
        } else if (args.length > 3) {
            // TODO: Remove legacy positional parsing in a future version.
            System.err.println("Warning: positional optional arguments are deprecated. "
                    + "Use --image-size, --color, --lang, --charset instead. "
                    + "See --help for details.");
            parseLegacyPositional(args, config);
        }

        return config;
    }

    /**
     * Parses named flags starting at the given index.
     * <p>
     * Primary flags (preferred):
     * <ul>
     *   <li>{@code --image-size} — output image dimensions</li>
     *   <li>{@code --color} — preview text color</li>
     *   <li>{@code --lang} — I18N translation folder path</li>
     *   <li>{@code --charset} — predefined character set name</li>
     * </ul>
     * Short flags (aliases for longer flags):
     * <ul>
     *   <li>{@code -s} → {@code --image-size}</li>
     *   <li>{@code -c} → {@code --color}</li>
     *   <li>{@code -l} → {@code --lang}</li>
     *   <li>{@code -C} → {@code --charset}</li>
     * </ul>
     */
    private static void parseFlags(String[] args, int startIndex, FontwriterConfig config) {
        int i = startIndex;
        while (i < args.length) {
            String flag = args[i];

            // Resolve short flags to their canonical form.
            String canonical = resolveShortFlag(flag);

            switch (canonical) {
                case "--image-size":
                    config.imageSize = requireValue(args, i, flag);
                    i += 2;
                    break;
                case "--color":
                    config.color = requireValue(args, i, flag).replaceAll("['\"]", "");
                    i += 2;
                    break;
                case "--lang":
                    config.langPath = requireValue(args, i, flag);
                    i += 2;
                    break;
                case "--charset":
                    config.charset = FontwriterConfig.Charset.fromString(requireValue(args, i, flag));
                    config.charsetExplicitlySet = true;
                    i += 2;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Unrecognized option: " + flag
                            + "\nUse --help to see available options.");
            }
        }

        // --charset and --lang are mutually exclusive; --charset wins.
        if (config.charsetExplicitlySet && config.langPath != null) {
            System.err.println("Warning: both --charset and --lang were given. "
                    + "--charset takes priority; --lang will be ignored.");
        }
    }

    /**
     * Parses the legacy positional format for backward compatibility.
     * <p>
     * <b>DEPRECATED:</b> This method exists only to support the old
     * positional syntax. It will be removed in a future version.
     * Use named flags (--image-size, --color, --lang) instead.
     * <pre>
     *   args[3] → imageSize
     *   args[4] → color
     *   args[5] → langPath
     * </pre>
     */
    private static void parseLegacyPositional(String[] args, FontwriterConfig config) {
        if (args.length > 3) {
            config.imageSize = args[3];
        }
        if (args.length > 4) {
            String rawColor = args[4].replaceAll("['\"]", "");
            if (rawColor.length() > 1) {
                config.color = rawColor;
            }
        }
        if (args.length > 5) {
            config.langPath = args[5];
        }
    }

    /**
     * Returns {@code args[flagIndex + 1]}, or throws if it's missing
     * or looks like another flag.
     */
    private static String requireValue(String[] args, int flagIndex, String flagName) {
        int valueIndex = flagIndex + 1;
        if (valueIndex >= args.length) {
            throw new IllegalArgumentException(
                    "Option " + flagName + " requires a value.\n"
                    + "Use --help to see available options.");
        }
        String value = args[valueIndex];
        if (value.startsWith("-") && !value.startsWith("#")) {
            // Allow hex colors like "#E74200" but reject flags
            throw new IllegalArgumentException(
                    "Option " + flagName + " requires a value, but got: " + value + "\n"
                    + "Use --help to see available options.");
        }
        return value;
    }

    /**
     * Maps single-char flags to their canonical long form.
     * Returns the flag unchanged if it's not a known shorthand.
     * <p>
     */
    private static String resolveShortFlag(String flag) {
        switch (flag) {
            case "-s":
                return "--image-size";
            case "-c":
                return "--color";
            case "-l":
                return "--lang";
            case "-C":
                return "--charset";
            default:
                return flag;
        }
    }

}
