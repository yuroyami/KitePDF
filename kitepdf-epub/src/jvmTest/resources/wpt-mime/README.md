# web-platform-tests MIME type data

The test data of the MIME Sniffing Standard's MIME type parser, from
[web-platform-tests/wpt](https://github.com/web-platform-tests/wpt) at commit
`1d99362b6bca46cc06e731ece8e022f0f1c21695`, folder `mimesniff/mime-types/resources`, unchanged:

- `mime-types.json`: inputs with the MIME type each parses to, serialized, or null for a
  failure, and for some the encoding its charset names.
- `generated-mime-types.json`: the same, generated for every control and non-ASCII code point
  in each part of a MIME type.

`WhatwgMimeTypeTest` runs both against the MIME type parser of the script layer, which
`FileReader.readAsText` reads the charset of a blob's type through (#533). The files are under
the 3-Clause BSD License of the web-platform-tests contributors, in `LICENSE.md`.

To take a newer copy:

```
C=<commit>
for f in mime-types.json generated-mime-types.json; do
  curl -o $f https://raw.githubusercontent.com/web-platform-tests/wpt/$C/mimesniff/mime-types/resources/$f
done
```
