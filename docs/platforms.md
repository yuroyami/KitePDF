# Platform support

Each KitePDF artifact builds for its own set of Kotlin Multiplatform targets. This page lists them. [Getting started](getting-started.md) shows how to add each artifact.

## Targets

The seven format modules (`kitepdf`, `kitepdf-core`, `kitepdf-pdf`, `kitepdf-epub`, `kitepdf-cbz`, `kitepdf-svg` and `kitepdf-xps`) share one set of targets. The other artifacts build for fewer targets. Check this table before you add an artifact to a shared source set.

| Target | Format modules | `-net` | `-javascript` | `-compose-viewer` | `-media` | `-native-renderer` | `-skia-renderer` |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| Android | yes | yes | yes | yes | yes | yes | yes |
| JVM | yes | yes | yes | yes | yes | yes | yes |
| iOS arm64, simulator arm64 | yes | yes | yes | yes | yes | yes | yes |
| iOS x64 | yes | yes | yes | no | no | yes | yes |
| macOS arm64 | yes | yes | yes | yes | no | yes | yes |
| tvOS arm64, simulator arm64 | yes | yes | no | no | no | yes | yes |
| watchOS arm32, arm64, device arm64, simulator arm64 | yes | yes | no | no | no | no | no |
| Linux x64, arm64 | yes | yes | yes | no | no | no | yes |
| Windows (mingwX64) | yes | yes | yes | no | no | no | no |
| Android Native arm32, arm64, x86, x64 | yes | no | no | no | no | no | no |
| JS | browser, Node | browser, Node | browser, Node | browser | no | browser | browser |
| wasmJs | browser, Node | browser, Node | browser, Node | browser | no | no | browser |
| wasmWasi | Node | no | no | no | no | no | no |

No artifact builds for Intel macOS, tvOS x64 or watchOS x64, because Kotlin deprecated those targets.

## Why the targets differ

- `kitepdf-compose-viewer` builds for every target that Compose Multiplatform publishes.
- `kitepdf-media` builds for the targets of KitePlayer, the media player that it uses.
- `kitepdf-javascript` builds for the targets of KiteJS, the script engine that it uses.
- `kitepdf-skia-renderer` builds for the targets of Skiko, the Kotlin binding for Skia. Skiko has no build for Windows native or watchOS.
- `kitepdf-native-renderer` draws with the 2D API of each platform: `Graphics2D` on the JVM, `android.graphics.Canvas` on Android, CoreGraphics on Apple platforms and Canvas2D in the browser. On wasmJs, use the Skia renderer.

## Android minimum API levels

Each artifact needs its own minimum API level. The `minSdk` of your app must be at least the highest level of the artifacts that it adds.

| Artifact | `minSdk` | Reason |
| --- | :---: | --- |
| Format modules, `-net`, `-javascript` | 21 | |
| `kitepdf-compose-viewer` | 24 | Compose needs API 24. |
| `kitepdf-skia-renderer` | 24 | Skiko needs API 24. |
| `kitepdf-media` | 26 | KitePlayer needs API 26. |
| `kitepdf-native-renderer` | 29 | Blend modes need `Paint.setBlendMode`, which came in API 29. |

The Skia renderer on Android also needs an extra Maven repository. See [Skia on every platform](rendering.md#skia-on-every-platform).

## What CI tests

CI compiles every target in the table on each push and pull request. It runs the tests on these targets:

| Target | Tests that CI runs |
| --- | --- |
| JVM | All artifacts, plus the pixel comparisons with MuPDF and PDFium |
| Android | Host tests on the JVM for the format modules, `-javascript`, `-compose-viewer` and `-native-renderer`. No test runs on a device or an emulator. |
| iOS simulator arm64 | The format modules except `kitepdf`, and `-javascript` |
| macOS arm64 | The format modules, `-javascript` and `-native-renderer` |
| JS | The format modules, `-net` and `-javascript` on Node, and `-native-renderer` in headless Chrome |
| Linux x64 | The format modules and `-javascript` |
| wasmJs | The format modules and `-javascript` on Node |
| wasmWasi | The format modules on Node |

CI compiles an artifact that a row does not name, but it does not test that artifact on that target. The other targets (iOS x64, tvOS, watchOS, Linux arm64, Windows and Android Native) are compiled but not tested.
