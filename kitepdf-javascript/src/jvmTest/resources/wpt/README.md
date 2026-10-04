# web-platform-tests

JavaScript tests from [web-platform-tests/wpt](https://github.com/web-platform-tests/wpt) at
commit `1d99362b6bca46cc06e731ece8e022f0f1c21695`, unchanged:

- `url`: from the folder `url`, the tests of the WHATWG URL Standard. Every `url-*.any.js` and
  `urlsearchparams-*.any.js` file that tests `URL` or `URLSearchParams` without a worker, a
  window or the `<a>` element.
- `webidl`: from the folder `webidl/ecmascript-binding/es-exceptions`, the tests of the
  `DOMException` of Web IDL. Every `DOMException-*.any.js` file.
- `encoding`: from the folder `encoding`, the tests of `TextEncoder` and `TextDecoder` of the
  Encoding Standard, with the two scripts of `encoding/resources` they load. Every `*.any.js`
  file but three: `replacement-encodings.any.js` and `unsupported-encodings.any.js` read their
  data through `XMLHttpRequest`, which a book's scripts do not have, and `idlharness.any.js`
  needs the IDL files and idlharness.js of web-platform-tests.
- `common/subset-tests.js`, which `single-byte-decoder.any.js` loads.
- `html/webappapis/atob/base64.any.js`, the tests of `atob` and `btoa`, and
  `fetch/data-urls/resources/base64.json`, the data it fetches.
- `FileAPI`: from the folder `FileAPI`, the tests of `Blob`, `File`, `FileReader` and blob URLs.
  Every `*.any.js` file of `blob`, `file` and `reading-data-section`, with `fileReader.any.js`,
  `unicode.any.js` and `url/url-format.any.js`. The other files of `url` need a window, an
  iframe, `fetch` or `XMLHttpRequest`, and `idlharness.any.js` needs the IDL files and
  idlharness.js of web-platform-tests.
- `common/gc.js`, which `Blob-stream.any.js` loads.

`WebPlatformTest` runs each file in a chapter of its own, after `harness.js`, a file of this
folder that is not from web-platform-tests: it stands in for testharness.js, and logs the count
once every test has completed. The scripts a file names in a `// META: script=` line run before
it. `sab.js` stands in for `/common/sab.js`, whose own copy makes a shared buffer through
`WebAssembly.Memory`; `blob-support.js` stands in for `FileAPI/support/Blob.js`, whose own copy
writes its promise tests as async functions, which KiteJS cannot parse yet; and `harness.js` has
the `subsetTestByKey` of `/common/subset-tests-by-key.js`. A file named with a `?` query, such as
`single-byte-decoder.any.js?TextDecoder`, runs with that query as `location.search`, the
variant web-platform-tests runs it in. The data that `url-constructor`, `url-origin` and
`url-setters` fetch is the parser's test data in `kitepdf-epub/src/jvmTest/resources/wpt-url`,
from the same commit. The test files are under the 3-Clause BSD License of the
web-platform-tests contributors, in `LICENSE.md`.

To take a newer copy, at the commit the data is at:

```
C=<commit>
for f in url-constructor url-origin url-setters url-searchparams url-statics-canparse \
  url-statics-parse url-tojson urlsearchparams-append urlsearchparams-constructor \
  urlsearchparams-delete urlsearchparams-foreach urlsearchparams-get urlsearchparams-getall \
  urlsearchparams-has urlsearchparams-set urlsearchparams-size urlsearchparams-sort \
  urlsearchparams-stringifier; do
  curl -o url/$f.any.js https://raw.githubusercontent.com/web-platform-tests/wpt/$C/url/$f.any.js
done
for f in DOMException-constants DOMException-constructor-and-prototype \
  DOMException-constructor-behavior DOMException-custom-bindings; do
  curl -o webidl/$f.any.js \
    https://raw.githubusercontent.com/web-platform-tests/wpt/$C/webidl/ecmascript-binding/es-exceptions/$f.any.js
done
W=https://raw.githubusercontent.com/web-platform-tests/wpt/$C
for f in api-basics api-invalid-label api-replacement-encodings api-surrogates-utf8 encodeInto \
  iso-2022-jp-decoder single-byte-decoder textdecoder-arguments textdecoder-byte-order-marks \
  textdecoder-copy textdecoder-eof textdecoder-fatal-single-byte textdecoder-fatal-streaming \
  textdecoder-fatal textdecoder-ignorebom textdecoder-labels textdecoder-mistakes \
  textdecoder-streaming textdecoder-utf16-surrogates textencoder-constructor-non-utf \
  textencoder-utf16-surrogates; do
  curl -o encoding/$f.any.js $W/encoding/$f.any.js
done
for f in encodings single-byte-decoder; do
  curl -o encoding/resources/$f.js $W/encoding/resources/$f.js
done
curl -o common/subset-tests.js $W/common/subset-tests.js
curl -o html/webappapis/atob/base64.any.js $W/html/webappapis/atob/base64.any.js
curl -o fetch/data-urls/resources/base64.json $W/fetch/data-urls/resources/base64.json
for f in blob/Blob-array-buffer blob/Blob-bytes blob/Blob-constructor-detached-buffer \
  blob/Blob-constructor-endings blob/Blob-constructor blob/Blob-newobject blob/Blob-slice-overflow \
  blob/Blob-slice blob/Blob-stream blob/Blob-text blob/Blob-textStream \
  file/File-constructor-endings file/File-constructor fileReader unicode url/url-format \
  reading-data-section/Determining-Encoding reading-data-section/FileReader-event-handler-attributes \
  reading-data-section/FileReader-multiple-reads reading-data-section/filereader_abort \
  reading-data-section/filereader_error reading-data-section/filereader_events \
  reading-data-section/filereader_readAsArrayBuffer reading-data-section/filereader_readAsBinaryString \
  reading-data-section/filereader_readAsDataURL reading-data-section/filereader_readAsText \
  reading-data-section/filereader_readAsText_blob_type_charset reading-data-section/filereader_readystate \
  reading-data-section/filereader_result; do
  curl --create-dirs -o FileAPI/$f.any.js $W/FileAPI/$f.any.js
done
curl -o common/gc.js $W/common/gc.js
```
