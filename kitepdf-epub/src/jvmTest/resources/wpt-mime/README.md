# web-platform-tests MIME type data

This folder holds the test data of the MIME type parser of the MIME Sniffing Standard. The files come unchanged from [web-platform-tests/wpt](https://github.com/web-platform-tests/wpt) at commit `1d99362b6bca46cc06e731ece8e022f0f1c21695`, folder `mimesniff/mime-types/resources`:

- `mime-types.json`: inputs, each with the serialized MIME type that it parses to, or null for a failure. Some inputs also give the encoding that their charset names.
- `generated-mime-types.json`: the same, generated for every control code point and every non-ASCII code point in each part of a MIME type.

`WhatwgMimeTypeTest` runs both files against the MIME type parser of the script layer. `FileReader.readAsText` uses that parser to read the charset of the type of a blob.

The files are under the 3-Clause BSD License of the web-platform-tests contributors, in `LICENSE.md`.

To take a newer copy:

```
C=<commit>
for f in mime-types.json generated-mime-types.json; do
  curl -o $f https://raw.githubusercontent.com/web-platform-tests/wpt/$C/mimesniff/mime-types/resources/$f
done
```
