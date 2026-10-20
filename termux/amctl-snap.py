#!/usr/bin/env python3
"""amctl-snap — convierte accessdroid-tree.txt en un snapshot compacto con refs @pN.

Uso: amctl-snap.py [-a|--all] [--filter TEXTO] [--display N] [ruta-tree]

Salida:
  stdout: lista de elementos interactivos (estilo agent-browser snapshot -i)
  ~/.cache/amctl/snapshot-map[-dN].tsv: ref -> coords + id (para 'amctl tapref')
"""
import os
import re
import sys

TREE = "/sdcard/Download/accessdroid-tree.txt"
CACHE = os.path.expanduser("~/.cache/amctl")

NODE_RE = re.compile(
    r'^(\s*)\[([^\]]+)\] id=(\S*) '
    r'bounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\) '
    r'txt=(.*?) desc=(.*)$')
FLAG_RE = re.compile(r' (clk|scr|edt|chk|foc)=1$')


def parse(path):
    pkg = ""
    win = ""          # "" = ventana principal; "ime"/"ovl"/"sys"/"app+" etc
    nodes = []
    empty_of = ""     # id del display que el servicio declara vacío
    with open(path, encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if line.startswith("# d") and "vacío" in line:
                # el servicio dice que ese display no tiene ventanas propias:
                # no es que el servicio esté caído, es que no hay nada que leer
                empty_of = line[2:].split(":")[0].strip()
                break
            if line.startswith("# pkg="):
                pkg = line[len("# pkg="):].strip()
                win = ""
                continue
            if line.startswith("# win="):
                # línea "# win=ime pkg=com.xxx" desde el servicio (ventanas extra)
                m = re.match(r"# win=(\S+)(?: pkg=(.*))?", line)
                if m:
                    win = m.group(1)
                    if not pkg and m.group(2):
                        pkg = m.group(2)
                continue
            m = NODE_RE.match(line)
            if not m:
                continue
            indent, cls, vid, l, t, r, b, txt, desc = m.groups()
            # el volcado antiguo escribía "id=null" cuando el nodo no tenía id
            if vid == "null":
                vid = ""
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
                "txt": txt, "desc": desc, "flags": flags, "win": win,
            })
    return pkg, nodes, empty_of


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
    display = 0
    skip = set()
    if "--filter" in args:
        k = args.index("--filter")
        filt = args[k + 1].lower() if k + 1 < len(args) else None
        skip.add(k)
        skip.add(k + 1)
    if "--display" in args:
        k = args.index("--display")
        if k + 1 < len(args) and args[k + 1].isdigit():
            display = int(args[k + 1])
        skip.add(k)
        skip.add(k + 1)
    mapfile = os.path.join(
        CACHE, "snapshot-map.tsv" if display == 0 else f"snapshot-map-d{display}.tsv")
    path = next((a for i, a in enumerate(args) if i not in skip and not a.startswith("-")), TREE)
    if not os.path.isfile(path):
        print(f"(no existe {path} — ejecuta: amctl tree)")
        return 1

    pkg, nodes, empty_of = parse(path)
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

    if empty_of:
        d = display if display else empty_of.lstrip("d")
        print(f"Pantalla — display {d}: vacío (sin ventanas propias). El servicio va bien: "
              f"no hay nada en esa pantalla todavía. Lánzale algo con: amctl -d {d} launch <pkg>")
        return 1

    if not picks:
        dtag = "" if display == 0 else f" — display {display}"
        print(f"Pantalla{dtag}: {pkg or '?'} — sin elementos interactivos "
              "(¿servicio activado / app >= v1.0.8?)")
        return 1

    os.makedirs(CACHE, exist_ok=True)
    lines = []
    with open(mapfile, "w", encoding="utf-8") as mf:
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
            if nd["win"]:
                wtag = nd["win"]
                bits.append("[" + ("ovl" if wtag == "overlay" else "sys" if wtag == "system" else wtag) + "]")
            lines.append(" ".join(bits))
            mf.write(f"{ref}\t{nd['cx']}\t{nd['cy']}\t{nd['l']}\t{nd['t']}"
                     f"\t{nd['r']}\t{nd['b']}\t{nd['id']}\t{label}\t{nd['win']}\n")

    mode = "todos" if all_mode else "interactivos"
    dtag = "" if display == 0 else f" — display {display}"
    print(f"Pantalla{dtag}: {pkg or '?'} — {len(picks)} elementos ({mode}) de {len(nodes)} nodos")
    for line in lines:
        print(line)
    if all_mode and len(picks) > 120:
        print(f"tip: -a mostró {len(picks)} — usa 'amctl snap -a --filter TEXTO' para reducir")
    print(f"refs: amctl tapref @pN  |  re-snap tras cada cambio de pantalla"
          + ("  |  [ime]/[ovl]/[sys]=ventanas extra (teclado, toolbars)" if any(nd["win"] for nd, _ in picks) else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
