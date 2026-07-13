# Bundled fonts

`roboto_hebrew_*.ttf` and `roboto_mono_hebrew_regular.ttf` are **not** upstream
Roboto. They are Roboto with **Noto Sans Hebrew merged into them**, because
stock Roboto ships 927 glyphs (Latin, Greek, Cyrillic) and **no Hebrew** — and
neither Compose nor JasperReports performs glyph fallback across a font family,
so Hebrew text rendered as empty boxes anywhere the host OS could not silently
rescue it (i.e. in the browser).

How they were produced:

1. `NotoSansHebrew-{Regular,Medium,Bold}` instanced from the upstream variable
   font, then scaled from its 1000-unit em to Roboto's 2048.
2. Merged into the matching Roboto weight, Roboto first — so Roboto wins every
   codepoint it already covers and only the Hebrew block is added.
3. Roboto's `hhea`/`OS/2` vertical metrics copied back over the merged face, so
   baseline and line height are unchanged and no screen reflowed.

Result: 1,078 glyphs per weight, one face, identical on all five platforms.

## Licences

- **Roboto** — Apache License 2.0 (Google).
- **Noto Sans Hebrew** — SIL Open Font License 1.1, `OFL-NotoSansHebrew.txt`
  in this directory. It declares **no Reserved Font Name**, so a merged
  derivative is permitted; the merged files are distributed under the OFL.
