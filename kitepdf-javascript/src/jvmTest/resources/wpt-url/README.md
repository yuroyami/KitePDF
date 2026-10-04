# web-platform-tests URL tests

The JavaScript tests of the WHATWG URL Standard, from
[web-platform-tests/wpt](https://github.com/web-platform-tests/wpt) at commit
`1d99362b6bca46cc06e731ece8e022f0f1c21695`, folder `url`, unchanged: every `url-*.any.js` and
`urlsearchparams-*.any.js` file that tests `URL` or `URLSearchParams` without a worker, a
window or the `<a>` element.

`UrlWptTest` runs each file in a chapter of its own, between two files of this folder that are
not from web-platform-tests: `harness.js`, which stands in for testharness.js, and `report.js`,
which logs the count. The data that `url-constructor`, `url-origin` and `url-setters` fetch
is the parser's test data in `kitepdf-epub/src/jvmTest/resources/wpt-url`, from the same commit.
The test files are under the 3-Clause BSD License of the web-platform-tests contributors, in
`LICENSE.md`.

To take a newer copy, at the commit the data is at:

```
C=<commit>
for f in url-constructor url-origin url-setters url-searchparams url-statics-canparse \
  url-statics-parse url-tojson urlsearchparams-append urlsearchparams-constructor \
  urlsearchparams-delete urlsearchparams-foreach urlsearchparams-get urlsearchparams-getall \
  urlsearchparams-has urlsearchparams-set urlsearchparams-size urlsearchparams-sort \
  urlsearchparams-stringifier; do
  curl -o $f.any.js https://raw.githubusercontent.com/web-platform-tests/wpt/$C/url/$f.any.js
done
```
