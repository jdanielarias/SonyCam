import io, os, re, json, sys

sys.stdout.reconfigure(encoding="utf-8")
D = os.path.dirname(os.path.abspath(__file__)) + "/ref3"

def cells(line):
    # markdown row: | a | b | c |  -> [a,b,c]
    s = line.strip()
    assert s.startswith("|") and s.endswith("|")
    return [c.strip() for c in s[1:-1].split("|")]

headers = {}          # page -> model list
rows = []             # (page, code, name, {model: bool})
bad = []

for page in range(80, 120):
    f = "%s/page_%04d.md" % (D, page)
    if not os.path.exists(f):
        continue
    lines = [l for l in io.open(f, encoding="utf-8").read().splitlines() if l.strip().startswith("|")]
    cur_models = None
    for l in lines:
        c = cells(l)
        if len(c) >= 3 and c[0] == "Code" and c[1] == "Name":
            cur_models = c[2:]
            headers[page] = cur_models
            continue
        if set("".join(c)) <= set("- "):     # separator row
            continue
        if not re.match(r"^0x[0-9A-Fa-f]{4}$", c[0]):
            continue
        if cur_models is None:
            bad.append((page, "data row before any header", c[0]))
            continue
        marks = c[2:]
        if len(marks) != len(cur_models):
            bad.append((page, "column count %d != header %d" % (len(marks), len(cur_models)), c[0]))
            continue
        name = re.sub(r"\s*_\s*", "", c[1]).strip()
        rows.append((page, c[0].upper(), name,
                     {m: (v.strip() == "✓") for m, v in zip(cur_models, marks)}))

print("páginas con cabecera:", len(headers))
sets = {tuple(v) for v in headers.values()}
print("cabeceras distintas:", len(sets))
for s in sets:
    print("  columnas:", len(s))
    print("  modelos:", ", ".join(s))
print("filas de datos:", len(rows))
print("filas descartadas:", len(bad))
for b in bad[:10]:
    print("   !", b)

json.dump([{"page": p, "code": c, "name": n, "models": m} for p, c, n, m in rows],
          io.open(os.path.dirname(os.path.abspath(__file__)) + "/compat.json", "w", encoding="utf-8"),
          ensure_ascii=False, indent=1)
