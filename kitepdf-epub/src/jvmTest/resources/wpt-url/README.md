# web-platform-tests URL data

The test data of the WHATWG URL Standard, from
[web-platform-tests/wpt](https://github.com/web-platform-tests/wpt) at commit
`1d99362b6bca46cc06e731ece8e022f0f1c21695`, folder `url/resources`, unchanged:

- `urltestdata.json`: inputs and bases with the URL each parses to, or a failure.
- `urltestdata-javascript-only.json`: the same, for inputs only JavaScript can write, with lone
  surrogates.
- `setters_tests.json`: what each setter of the `URL` class does to a URL.
- `toascii.json`: host names through the domain parser.

`WhatwgUrlTest` runs `urltestdata.json`, `setters_tests.json` and `toascii.json` against the URL
parser of the script layer (#520), and `UrlWptTest` in `kitepdf-javascript` hands the first
three to the JavaScript tests of the same commit, which it keeps in its own test resources.
The files are under the 3-Clause BSD License of the web-platform-tests contributors, in
`LICENSE.md`.

To take a newer copy:

```
C=<commit>
for f in urltestdata.json urltestdata-javascript-only.json setters_tests.json toascii.json; do
  curl -o $f https://raw.githubusercontent.com/web-platform-tests/wpt/$C/url/resources/$f
done
```
