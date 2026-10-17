#!/usr/bin/env python3
"""amctl-snap — convierte accessdroid-tree.txt en un snapshot compacto con refs @pN.

Uso: amctl-snap.py [-a|--all] [--filter TEXTO] [ruta-tree]

Salida:
  stdout: lista de elementos interactivos (estilo agent-browser snapshot -i)
  ~/.cache/amctl/snapshot-map.tsv: ref -> coords + id (para 'amctl tapref')
"""
import os
import re
import sys

TREE = "/sdcard/Download/accessdroid-tree.txt"
CACHE = os.path.expanduser("~/.cache/amctl")
MAP = os.path.join(CACHE, "snapshot-map.tsv")

NODE_RE = re.compile(
    r'^(\s*)\[([^\]]+)\] id=(\S*) '
    r'bounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\) '
    r'txt=(.*?) desc=(.*)$')
FLAG_RE = re.compile(r' (clk|scr|edt|chk|foc)=1$')


def parse(path):
    pkg = ""
    nodes = []
    with open(path, encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if line.startswith("# pkg="):
                pkg = line[len("# pkg="):].strip()
                continue
            m = NODE_RE.match(line)
            if not m:
                continue
            indent, cls, vid, l, t, r, b, txt, desc = m.groups()
            l, t, r, b = int(l), int(t), int(r), int(b)
            flags = set()
            while True:
                fm = FLAG_RE.search(desc)
                if not fm:
                    break
                flags.add(fm.group(1))
                desc = desc[:fm.start()]
            nodes.append({
                "depth": len(indent) // 2, "cls": cls, "id": vid,
                "l": l, "t": t, "r": r, "b": b,
                "cx": (l + r) // 2, "cy": (t + b) // 2,
                "txt": txt, "desc": desc, "flags": flags,
            })
    return pkg, nodes


def child_text(nodes, i, limit=4, width=70):
    depth = nodes[i]["depth"]
    parts = []
    for j in range(i + 1, len(nodes)):
        nd = nodes[j]
        if nd["depth"] <= depth:
            break
        val = nd["txt"] or nd["desc"]
        if val and val not in parts:
            parts.append(val)
            if len(parts) >= limit:
                break
    return " · ".join(parts)[:width]


def main():
    args = sys.argv[1:]
    all_mode = any(a in ("-a", "--all") for a in args)
    filt = None
    if "--filter" in args:
        k = args.index("--filter")
        filt = args[k + 1].lower() if k + 1 < len(args) else None
    path = next((a for a in args if not a.startswith("-") and a != filt), TREE)
    if not os.path.isfile(path):
        print(f"(no existe {path} — ejecuta: amctl tree)")
        return 1

    pkg, nodes = parse(path)
    picks = []
    for i, nd in enumerate(nodes):
        interactive = bool(nd["flags"] & {"clk", "edt", "chk"})
        has_text = bool(nd["txt"] or nd["desc"])
        if not (interactive or (all_mode and has_text)):
            continue
        label = nd["txt"] or nd["desc"] or child_text(nodes, i)
        label = label.replace("\n", " ").strip()
        if filt and filt not in label.lower() and filt not in nd["id"].lower():
            continue
        picks.append((nd, label))

    if not picks:
        print(f"Pantalla: {pkg or '?'} — sin elementos interactivos "
              "(¿servicio activado / app >= v1.0.8?)")
        return 1

    os.makedirs(CACHE, exist_ok=True)
    lines = []
    with open(MAP, "w", encoding="utf-8") as mf:
        for n, (nd, label) in enumerate(picks, 1):
            ref = f"p{n}"
            short_id = nd["id"].split("/")[-1] if nd["id"] else ""
            bits = [f"@{ref}", f"[{nd['cls']}]"]
            if label:
                bits.append("'" + label.replace("'", "´") + "'")
            if short_id:
                bits.append("#" + short_id)
            bits.append(f"({nd['cx']},{nd['cy']})")
            if "edt" in nd["flags"]:
                bits.append("[edt]" + ("[FOCUS]" if "foc" in nd["flags"] else ""))
            if "chk" in nd["flags"]:
                bits.append("[check]")
            if "scr" in nd["flags"] and "clk" not in nd["flags"]:
                bits.append("[scroll]")
            lines.append(" ".join(bits))
            mf.write(f"{ref}\t{nd['cx']}\t{nd['cy']}\t{nd['l']}\t{nd['t']}"
                     f"\t{nd['r']}\t{nd['b']}\t{nd['id']}\t{label}\n")

    mode = "todos" if all_mode else "interactivos"
    print(f"Pantalla: {pkg or '?'} — {len(picks)} elementos ({mode}) de {len(nodes)} nodos")
    for line in lines:
        print(line)
    print("refs: amctl tapref @pN  |  re-snap tras cada cambio de pantalla")
    return 0


if __name__ == "__main__":
    sys.exit(main())
