# fontwriter
Generates and optimizes Structured JSON fonts for TextraTypist.

## Wait, what?

[TextraTypist](https://github.com/tommyettinger/textratypist) is a... fairly complete replacement
of the BitmapFont code in [libGDX](https://libgdx.com). It supports various extra features, including
rendering signed distance field (SDF) and multi-channel signed distance field (MSDF) fonts out-of-the-box.
These are ways of rendering fonts using a different shader internally, and allow one font image to scale
smoothly to much larger sizes without losing much quality. However, generating these fonts can be a
challenge; MSDF fonts in particular only had a few options (such as
[msdf-gdx-gen](https://github.com/maltaisn/msdf-gdx-gen), an inspiration for this project), and most of the
existing options had to clumsily wrap the one utility for generating MSDF fonts... one character at a time.
Having to stitch together sometimes hundreds of individual textures, one per char, tended to cause issues
where letters would "wobble" up and down over a sentence, especially for smaller chars or fonts with many
chars. The author of MSDF and most utilities surrounding it relatively-recently wrote 
[msdf-atlas-gen](https://github.com/Chlumsky/msdf-atlas-gen), which can output to several font file formats,
but not the AngelCode BMFont format that libGDX and TextraTypist can natively read. It can produce JSON,
though! The structured JSON it produces can be read in at least by TextraTypist to produce working fonts.
Support for loading structured JSON is present in TextraTypist snapshot builds and is expected in 0.10.1
or 0.11.0 (the next release).

## OK, how do I use this?

For now, this is Windows-only. I would need to build some tools for other platforms to get this to work
on Linux or macOS.

If you have the JAR from the releases, unzip it so the other files it came with are all in the same folder
structure. Then, you can enter the directory with that holds `fontwriter-1.0.1.jar` and run
`java -jar fontwriter-1.0.1.jar "MyFont.ttf" msdf 60` , where "MyFont.ttf" can be any path to a .ttf file
or probably also an .otf file. "MyFont.ttf" doesn't have to be in the same folder if you give it an absolute
path (on Windows, you can drag and drop a file after typing `java -jar fontwriter-1.0.0.jar ` to enter its
absolute path). The second parameter, `msdf`, can also be `sdf` or `standard`. You might just want `standard`
for many reasons; even though it won't scale up nicely, it will scale down fairly well, and you can
interchange `standard` fonts using FontFamily or using colorful emoji. On the other hand is `msdf`, which
scales up very well, but cannot be used with colorful emoji or other fonts to the same extent. Then `sdf`
is somewhere in the middle; it works somewhat well with emoji, though it doesn't handle their partially
transparent edge very well, scales up nicely, and unfortunately doesn't work well with other fonts. The
"60" parameter is a size, I think measured in pt or px. It isn't necessarily going to be used as-is. You
can optionally specify a size of image to write (the default is 2048x2048, and fonts that only use ASCII
probably don't need that much space) as the next parameter. After that you can optionally specify a color
by name (such as "black" or "red") or RGB hex code (such as "BB3311"; RGBA also works but alpha is
ignored), which will write an extra preview of all chars using that color. The last argument is there so
that you can quickly see all chars, even on a white background.

Running that command will try the size you give it first, and if it can't fit all chars in the font into
a 2048x2048 (or other size, if specified) image, it will reduce the size and try again, repeatedly. Once
it can fit everything, it saves the file into `fonts/`, then starts doing some TextraTypist-related
processing. It paints a small "block" of solid white pixels into the lower right corner, then (if using
`sdf` or `standard`) optimizes the image so that it only uses the alpha channel with white pixels. That
last step helps texture filtering; without it, fully transparent pixels are fully-transparent black rather
than fully-transparent white, and mixing with black will darken sometimes even if the mixed color is
transparent (with white usually doesn't do this). It then optimizes the image in `fonts/` with `oxipng`,
and generates a preview image that it places in `previews/`. If you specified a color name or hex code,
it also writes a preview of all chars in that color. It then optimizes the preview image(s), and then
you're done!

## License

This uses the [Apache License v2.0](LICENSE).

The included msdf-atlas-gen uses the
[MIT License](https://github.com/Chlumsky/msdf-atlas-gen/blob/master/LICENSE.txt). The version used here
isn't an identical binary to the one distributed at that repo;
[my fork](https://github.com/tommyettinger/msdf-atlas-gen) has a small change to default to using a little
spacing between chars. This feature will probably be in a future msdf-atlas-gen release, because most of the
code is already there for it, but I can't say that for certain.

The included oxipng also uses the [MIT License](https://github.com/shssoichiro/oxipng/blob/master/LICENSE).

