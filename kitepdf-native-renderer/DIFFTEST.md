# Differential rendering harness (Phase 0)

The correctness scoreboard. It renders each corpus PDF page with **KitePDF** and
with **MuPDF** (`mutool draw`, the in-repo oracle), pixel-diffs the two, and
ranks the worst-rendering pages first. This is the measurement system every
later correctness/completeness push is graded against.

## Run

```bash
./gradlew :kitepdf-native-renderer:jvmTest --tests "*DifferentialTest*"
```

Outputs land in `kitepdf-native-renderer/build/difftest/`:

```
build/difftest/
  inputs/          synthetic fixtures materialized to disk
  out/<doc>/       p<n>.kite.png · p<n>.ref.png · p<n>.diff.png  (red = divergence)
  report.md        worst-first table with scores + image links
```

Open `report.md` and start at the top — that's the worst-rendering page.

## Knobs (Gradle `-D` system properties)

| Property | Default | Meaning |
|---|---|---|
| `kitepdf.mutool` | _auto_ | explicit `mutool` binary path |
| `kitepdf.corpus` | `./corpus` | extra real-world PDF directory |
| `kitepdf.diff.dpi` | `96` | render density for both engines |
| `kitepdf.diff.budget` | `0.50` | max per-page MAE before the gate fails |
| `kitepdf.difftest.out` | `build/difftest` | output directory |

Example — tighten the gate and crank density once correctness improves:

```bash
./gradlew :kitepdf-native-renderer:jvmTest \
  -Dkitepdf.diff.dpi=150 -Dkitepdf.diff.budget=0.10
```

## The gates

1. **Render success** — KitePDF must not throw on any page.
2. **Non-blank** — synthetic fixtures must produce visible output.
3. **Regression budget** — _only when the oracle is present_ — no page may
   exceed `kitepdf.diff.budget`. The default is deliberately lenient; Phase 0's
   job is the scoreboard, not a tight gate. Lower it as the score drops.

## The corpus

- **Synthetic fixtures** (`SyntheticPdfs.kt`) always run: text, vector
  fills/strokes/curves, transparency, multi-page. Deterministic, no external
  files, and both engines render the same bytes — so any divergence is a real
  KitePDF gap.
- **Drop-in real-world PDFs**: put `.pdf` files in `kitepdf-native-renderer/corpus/`
  (or point `-Dkitepdf.corpus` elsewhere). Only the first
  `DiffHarness.MAX_PAGES_PER_DOC` pages of each are scored, to bound runtime.

## The oracle (`mutool`)

Located automatically from `-Dkitepdf.mutool`, `$MUTOOL`, the in-repo build, or
`$PATH`. To build it from the bundled source **without** the optional MuJS
dependency (which isn't vendored):

```bash
make -C mupdf-master build=release HAVE_X11=no HAVE_GLUT=no mujs=no -j4
# → mupdf-master/build/release/mutool
```

`mujs=no` disables MuPDF's JavaScript engine (`-DFZ_ENABLE_JS=0`); rendering
doesn't use it. Without any oracle the harness still runs as a KitePDF-only
smoke pass and emits the report.
