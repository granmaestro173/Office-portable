#!/usr/bin/env python3
"""
Fill missing bold / italic / bold-italic faces of alias rows in AllFonts.js.

Alias rows such as "Calibri" point only at the regular face of a substitute
(Carlito). When a document asks for bold Calibri, the engine falls back for the
missing face and shapes with one font while drawing with another, so every glyph
comes out shifted (garbled headings and bold text).

For each row whose regular file belongs to a family that has a full row of its
own, copy that family's missing faces into the alias row. Only the registry
changes. Every position used already belongs to the canonical family's row, so
the catalog invariant (the family name inside the file at P belongs to a row
that points at P) still holds.

Usage: fill-font-faces.py <www-dir>
"""
import io
import json
import re
import sys

from fontTools.ttLib import TTFont

KEY = [160, 102, 214, 32, 20, 150, 71, 250, 149, 105, 184, 80, 176, 65, 73, 72]

www = sys.argv[1]
path = www + "/sdkjs/common/AllFonts.js"
src = open(path, encoding="utf-8-sig").read()


def find_array(name):
    m = re.search(r'(window\["' + name + r'"\]\s*=\s*)(\[.*?\])(;)', src, re.S)
    return m, json.loads(m.group(2))


_, files = find_array("__fonts_files")
infos_match, infos = find_array("__fonts_infos")

family_cache = {}


def family(pos):
    if pos not in family_cache:
        data = bytearray(open(www + "/fonts/" + files[pos], "rb").read())
        for i in range(min(32, len(data))):
            data[i] ^= KEY[i % 16]
        try:
            font = TTFont(io.BytesIO(bytes(data)), fontNumber=0, lazy=True)
            family_cache[pos] = font["name"].getDebugName(1)
        except Exception:
            family_cache[pos] = None
    return family_cache[pos]


rows_by_name = {}
for row in infos:
    rows_by_name.setdefault(row[0], []).append(row)

changed = []
for row in infos:
    regular = row[1]
    if regular < 0:
        continue
    missing = [slot for slot in (3, 5, 7) if row[slot] < 0]
    if not missing:
        continue
    fam = family(regular)
    if not fam or fam == row[0]:
        continue
    canon = next((r for r in rows_by_name.get(fam, []) if r[1] == regular), None)
    if canon is None:
        continue
    filled = []
    for slot in missing:
        if canon[slot] >= 0:
            row[slot] = canon[slot]
            row[slot + 1] = canon[slot + 1]
            filled.append({3: "italic", 5: "bold", 7: "bold-italic"}[slot])
    if filled:
        changed.append((row[0], fam, filled))

body = "[\n" + ",\n".join(json.dumps(r, ensure_ascii=False, separators=(",", ":")) for r in infos) + "\n]"
out = src[: infos_match.start(2)] + body + src[infos_match.end(2):]
open(path, "w", encoding="utf-8").write(out)

print(f"Filled faces for {len(changed)} alias rows:")
for name, fam, filled in changed:
    print(f"  {name} -> {fam}: {', '.join(filled)}")
