# Bundled fonts

| file | licence |
|---|---|
| `roboto_{regular,medium,bold}.ttf`, `roboto_mono_regular.ttf` | Apache License 2.0 (Google) — upstream, unmodified |
| `noto_sans_hebrew_{regular,medium,bold}.ttf` | SIL Open Font License 1.1 — `OFL-NotoSansHebrew.txt` |

## Why there is a second family at all

Roboto ships **927 glyphs**: Latin, Greek, Cyrillic. It has **no Hebrew**, no
Arabic and no CJK. Hebrew is one of the app's UI languages, so a face that covers
it has to be bundled.

The Noto Sans Hebrew weights are static instances of the upstream variable font,
chosen to line up with Roboto's Normal / Medium / Bold. They are **not merged**
into Roboto — the app picks between the two per script run at render time
(`core/presentation/.../design_system/FontFallback.kt`). That is what keeps a
future Chinese face from costing Greek users a multi-megabyte download: a font is
downloaded only because it is listed in the chain.

## Adding a script

1. Drop the TTF in `core/presentation/src/commonMain/composeResources/font/`.
2. Add one `ScriptFont` entry to `fallbackFontFamilies()`, with the codepoint
   ranges it covers.
3. Record its licence here.

Nothing else changes — **except reports**: JasperReports does no glyph fallback,
so a report that must print the new script also needs the face registered in
`reportcore/.../fonts/roboto/jasperreports-fonts.xml`. `FontCoverageTest` states
that limitation out loud.
