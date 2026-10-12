# web-platform-tests

This folder holds JavaScript tests from [web-platform-tests/wpt](https://github.com/web-platform-tests/wpt) at commit `1d99362b6bca46cc06e731ece8e022f0f1c21695`, unchanged. `WebPlatformTest` runs them in the script layer of an EPUB chapter.

## The tests

- `url`: the tests of the WHATWG URL Standard, from the folder `url`. Every `url-*.any.js` and `urlsearchparams-*.any.js` file that tests `URL` or `URLSearchParams` without a worker, a window or the `<a>` element.
- `webidl`: the tests of the `DOMException` of Web IDL, from the folder `webidl/ecmascript-binding/es-exceptions`. Every `DOMException-*.any.js` file.
- `encoding`: the tests of `TextEncoder` and `TextDecoder` of the Encoding Standard, from the folder `encoding`, with the two scripts of `encoding/resources` that they load. Every `*.any.js` file except three:
    - `replacement-encodings.any.js` and `unsupported-encodings.any.js` read their data through `XMLHttpRequest`, which the scripts of a book do not have.
    - `idlharness.any.js` needs the IDL files and the idlharness.js of web-platform-tests.
- `common/subset-tests.js`, which `single-byte-decoder.any.js` loads.
- `html/webappapis/atob/base64.any.js`, the tests of `atob` and `btoa`, and `fetch/data-urls/resources/base64.json`, the data that it fetches.
- `FileAPI`: the tests of `Blob`, `File`, `FileReader` and blob URLs, from the folder `FileAPI`. Every `*.any.js` file of `blob`, `file` and `reading-data-section`, with `fileReader.any.js`, `unicode.any.js` and `url/url-format.any.js`. The other files of `url` need a window, an iframe, `fetch` or `XMLHttpRequest`. `idlharness.any.js` needs the IDL files and the idlharness.js of web-platform-tests.
- `common/gc.js`, which `Blob-stream.any.js` loads.
- `html/dom`: the reflection tests of HTML, which check each IDL attribute of each element against its content attribute. The nine `reflection-*.html` pages, with `reflection.js`, the two harness scripts and the nine `elements-*.js` tables that they load.
- `html/semantics/interfaces.html` and `interfaces.js`, the tests of the interface of each element that `document.createElement` makes.
- `dom/nodes`, two groups of tests of the DOM Standard:
    - The tests of `querySelector`, `querySelectorAll`, `matches`, `webkitMatchesSelector` and `closest`, which run the selectors of `selectors.js` and some of their own. These are the twelve pages that the first `dom/nodes` command below fetches, the scripts that they load, and the HTML and XHTML documents that their frames load.
    - The tests of attributes: `Attr`, `NamedNodeMap`, the attribute methods of `Element`, `createAttribute`, and the validation of names. These are the thirteen pages that the second `dom/nodes` command below fetches, with `attributes.js` and `productions.js`, which they load.

The data that `url-constructor`, `url-origin` and `url-setters` fetch is the test data of the URL parser in `kitepdf-epub/src/jvmTest/resources/wpt-url`, from the same commit.

The test files are under the 3-Clause BSD License of the web-platform-tests contributors, in `LICENSE.md`.

## How the tests run

`WebPlatformTest` runs each test file in a chapter of its own:

- `harness.js` runs first. It stands in for testharness.js, and it logs the count after every test has completed.
- The scripts that a file names in a `// META: script=` line run before the file.
- A file named with a `?` query, such as `single-byte-decoder.any.js?TextDecoder`, runs with that query as `location.search`. This is the variant that web-platform-tests runs.
- A page (a `.html` file) runs in a chapter served as `text/html`. Its `<script>` elements run in order, each from the folder of the page or from the root of this folder. `harness.js` takes the place of testharness.js and testharnessreport.js.
- A page of `dom/nodes`, whose tests query its own markup, is the chapter itself. It is served as XHTML when it is a `.xht` or `.xhtml` page.
- A chapter of a book has no frames. So a page whose tests run in the document of a frame runs as that document. Its scripts run at the end of the body until they leave the body, and the `load` event of the frame gets the document of the chapter.

These files of this folder are not from web-platform-tests:

- `harness.js` has `setup` with `single_test`, `done`, `assert_idl_attribute`, and `assert_throws_dom`, which gets the `DOMException` of the realm as its second argument and also takes a legacy code name such as `INDEX_SIZE_ERR`. It also has the `subsetTestByKey` of `/common/subset-tests-by-key.js`.
- `sab.js` stands in for `/common/sab.js`, whose own copy makes a shared buffer through `WebAssembly.Memory`.
- `blob-support.js` stands in for `FileAPI/support/Blob.js`, with `then` in place of `await`.

## Take a newer copy

Run this at the commit of the test data:

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
for f in embedded forms grouping metadata misc obsolete sections tabular text; do
  curl --create-dirs -o html/dom/reflection-$f.html $W/html/dom/reflection-$f.html
  curl -o html/dom/elements-$f.js $W/html/dom/elements-$f.js
done
for f in reflection original-harness new-harness; do
  curl -o html/dom/$f.js $W/html/dom/$f.js
done
for f in interfaces.html interfaces.js; do
  curl --create-dirs -o html/semantics/$f $W/html/semantics/$f
done
for f in selectors.js ParentNode-querySelector-All.js ParentNode-querySelector-All.html \
  ParentNode-querySelector-All-xht.xht ParentNode-querySelector-All-content.html \
  ParentNode-querySelector-All-content.xht ParentNode-querySelector-case-insensitive.html \
  ParentNode-querySelector-escapes.html ParentNode-querySelector-scope.html \
  ParentNode-querySelectors-exclusive.html ParentNode-querySelectors-namespaces.html \
  ParentNode-querySelectors-space-and-dash-attribute-value.html Element-matches.js \
  Element-matches-init.js Element-matches.html Element-webkitMatchesSelector.html \
  Element-matches-namespaced-elements.html Element-closest.html; do
  curl --create-dirs -o dom/nodes/$f $W/dom/nodes/$f
done
for f in attributes.html attributes.js productions.js Attr-prefix.html Attr-prefix-xhtml.xhtml \
  Document-createAttribute.html Element-hasAttribute.html Element-hasAttributes.html \
  Element-removeAttribute.html Element-removeAttributeNS.html Element-setAttribute.html \
  Element-setAttribute-crbug-1138487.html Element-setAttributeNodeNS.html \
  attributes-namednodemap.html name-validation.html; do
  curl -o dom/nodes/$f $W/dom/nodes/$f
done
```
