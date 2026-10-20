package com.accessdroid.termux;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot con referencias @pN calculado en el propio servicio.
 *
 * Antes el CLI descargaba el árbol crudo y lo procesaba con Python (arranque
 * del intérprete ~0.6s + parseo). Si el snapshot se calcula aquí, la respuesta
 * llega lista y el CLI solo la imprime: una orden en vez de tres.
 *
 * El formato de salida es idéntico al que generaba amctl-snap.py, para que la
 * skill, los docs y los ejemplos sigan siendo válidos.
 */
final class Snap {

    /** Nodo del árbol de accesibilidad (solo los "con contenido"). */
    static final class Node {
        int depth;
        String cls = "";
        String vid = "";
        String txt = "";
        String desc = "";
        int l, t, r, b;
        boolean clk, scr, edt, chk, foc;
        String win = "";        // "", "ime", "sys", "ovl", "div", "mag"
        String winPkg = "";     // paquete de la ventana extra

        int cx() { return (l + r) / 2; }
        int cy() { return (t + b) / 2; }
        boolean interactive() { return clk || edt || chk; }
        boolean hasText() { return !txt.isEmpty() || !desc.isEmpty(); }
    }

    /** Un elemento del snapshot, con su etiqueta ya resuelta. */
    static final class Pick {
        Node node;
        String label;
        String ref;
    }

    private Snap() { }

    /**
     * Selecciona los elementos a mostrar y calcula sus etiquetas.
     * - allMode: incluye los nodos con texto aunque no sean interactivos.
     * - filter: substring (sin distinguir mayúsculas) sobre etiqueta o id.
     */
    static List<Pick> select(List<Node> nodes, boolean allMode, String filter) {
        List<Pick> picks = new ArrayList<>();
        String flt = filter == null ? null : filter.toLowerCase();
        for (int i = 0; i < nodes.size(); i++) {
            Node nd = nodes.get(i);
            boolean keep = nd.interactive() || (allMode && nd.hasText());
            if (!keep) continue;
            String label = labelOf(nodes, i);
            label = label.replace("\n", " ").trim();
            if (flt != null && !flt.isEmpty()) {
                String lid = nd.vid == null ? "" : nd.vid.toLowerCase();
                if (!label.toLowerCase().contains(flt) && !lid.contains(flt)) continue;
            }
            Pick p = new Pick();
            p.node = nd;
            p.label = label;
            p.ref = "p" + (picks.size() + 1);
            picks.add(p);
        }
        return picks;
    }

    /** Texto de un nodo: el suyo, o el de sus hijos si no tiene. */
    static String labelOf(List<Node> nodes, int i) {
        Node nd = nodes.get(i);
        if (!nd.txt.isEmpty()) return nd.txt;
        if (!nd.desc.isEmpty()) return nd.desc;
        return childText(nodes, i, 4, 70);
    }

    /** Concatena los textos de los descendientes (como el parser de antes). */
    static String childText(List<Node> nodes, int i, int limit, int width) {
        int depth = nodes.get(i).depth;
        List<String> parts = new ArrayList<>();
        for (int j = i + 1; j < nodes.size(); j++) {
            Node nd = nodes.get(j);
            if (nd.depth <= depth) break;
            String val = !nd.txt.isEmpty() ? nd.txt : nd.desc;
            if (val.isEmpty()) continue;
            if (parts.contains(val)) continue;
            parts.add(val);
            if (parts.size() >= limit) break;
        }
        String s = String.join(" · ", parts);
        return s.length() > width ? s.substring(0, width) : s;
    }

    /** Línea de snapshot de un elemento: "@p1 [Class] 'texto' #id (x,y) [edt]". */
    static String renderLine(Pick p) {
        Node nd = p.node;
        StringBuilder sb = new StringBuilder();
        sb.append('@').append(p.ref);
        sb.append(" [").append(nd.cls).append(']');
        if (!p.label.isEmpty()) {
            sb.append(" '").append(p.label.replace("'", "´")).append('\'');
        }
        if (nd.vid != null && !nd.vid.isEmpty()) {
            sb.append(" #").append(nd.vid.substring(nd.vid.lastIndexOf('/') + 1));
        }
        sb.append('(').append(nd.cx()).append(',').append(nd.cy()).append(')');
        if (nd.edt) sb.append("[edt]").append(nd.foc ? "[FOCUS]" : "");
        if (nd.chk) sb.append("[check]");
        if (nd.scr && !nd.clk) sb.append("[scroll]");
        if (!nd.win.isEmpty()) sb.append('[').append(winTag(nd.win)).append(']');
        return sb.toString();
    }

    static String winTag(String w) {
        if ("overlay".equals(w)) return "ovl";
        if ("system".equals(w)) return "sys";
        return w;
    }

    /** Snapshot completo, tal y como lo imprimía amctl-snap.py. */
    static String render(String pkg, List<Node> nodes, List<Pick> picks,
                         boolean allMode, int display) {
        StringBuilder sb = new StringBuilder();
        String dtag = display == 0 ? "" : " — display " + display;
        String mode = allMode ? "todos" : "interactivos";
        sb.append("Pantalla").append(dtag).append(": ")
          .append(pkg == null || pkg.isEmpty() ? "?" : pkg)
          .append(" — ").append(picks.size()).append(" elementos (")
          .append(mode).append(") de ").append(nodes.size()).append(" nodos\n");
        boolean extra = false;
        for (Pick p : picks) {
            sb.append(renderLine(p)).append('\n');
            if (!p.node.win.isEmpty()) extra = true;
        }
        if (allMode && picks.size() > 120) {
            sb.append("tip: -a mostró ").append(picks.size())
              .append(" — usa 'amctl snap -a --filter TEXTO' para reducir\n");
        }
        sb.append("refs: amctl tapref @pN  |  re-snap tras cada cambio de pantalla");
        if (extra) {
            sb.append("  |  [ime]/[ovl]/[sys]=ventanas extra (teclado, toolbars)");
        }
        sb.append('\n');
        return sb.toString();
    }

    /** Árbol crudo, con el mismo formato que el fichero accessdroid-tree.txt. */
    static String renderTree(String pkg, List<Node> nodes, int display) {
        StringBuilder sb = new StringBuilder();
        sb.append("# display=").append(display).append('\n');
        sb.append("# pkg=").append(pkg == null ? "" : pkg).append('\n');
        String lastWin = "";
        for (Node nd : nodes) {
            if (!nd.win.isEmpty() && !nd.win.equals(lastWin)) {
                sb.append("# win=").append(nd.win).append(" pkg=").append(nd.winPkg).append('\n');
                lastWin = nd.win;
            }
            sb.append(renderTreeLine(nd)).append('\n');
        }
        return sb.toString();
    }

    private static String renderTreeLine(Node nd) {
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < nd.depth; i++) indent.append("  ");
        StringBuilder flags = new StringBuilder();
        if (nd.clk) flags.append(" clk=1");
        if (nd.scr) flags.append(" scr=1");
        if (nd.edt) flags.append(" edt=1");
        if (nd.chk) flags.append(" chk=1");
        if (nd.foc) flags.append(" foc=1");
        return String.format("%s[%s] id=%s bounds=Rect(%d, %d - %d, %d) txt=%s desc=%s%s",
            indent, nd.cls, nd.vid == null ? "" : nd.vid, nd.l, nd.t, nd.r, nd.b,
            nd.txt, nd.desc, flags);
    }
}
