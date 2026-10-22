# web-platform-tests URL data

This folder holds the test data of the WHATWG URL Standard. The files come unchanged from [web-platform-tests/wpt](https://github.com/web-platform-tests/wpt) at commit `1d99362b6bca46cc06e731ece8e022f0f1c21695`, folder `url/resources`:

- `urltestdata.json`: inputs and bases, each with the URL that it parses to, or a failure.
- `urltestdata-javascript-only.json`: the same, for inputs that only JavaScript can write, with lone surrogates.
- `setters_tests.json`: what each setter of the `URL` class does to a URL.
- `toascii.json`: host names, through the domain parser.

Two tests use these files:

- `WhatwgUrlTest` runs `urltestdata.json`, `setters_tests.json` and `toascii.json` against the URL parser of the script layer.
- `WebPlatformTest` in `kitepdf-javascript` gives the first three files to the JavaScript tests of the same commit. It keeps those tests in its own test resources.

The files are under the 3-Clause BSD License of the web-platform-tests contributors, in `LICENSE.md`.

To take a newer copy:

```
C=<commit>
for f in urltestdata.json urltestdata-javascript-only.json setters_tests.json toascii.json; do
  curl -o $f https://raw.githubusercontent.com/web-platform-tests/wpt/$C/url/resources/$f
done
```
