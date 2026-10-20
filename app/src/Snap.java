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
        return select(index(nodes), allMode, filter);
    }

    static List<Pick> select(Index idx, boolean allMode, String filter) {
        List<Pick> picks = new ArrayList<>();
        String flt = filter == null ? null : filter.toLowerCase();
        List<Node> nodes = idx.nodes;
        for (int i = 0; i < nodes.size(); i++) {
            Node nd = nodes.get(i);
            boolean keep = nd.interactive() || (allMode && nd.hasText());
            if (!keep) continue;
            String label = idx.labelOf(i);
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

    /**
     * Primer nodo cuyo texto (o id) contiene "needle".
     *
     * Es la búsqueda que hace waitfor en cada iteración. Recorre los nodos
     * SIN construir las etiquetas de los que no tienen texto propio: solo
     * se unen los textos de los descendientes cuando el nodo no trae el
     * suyo. Así el sondeo cada 120 ms no cuesta O(n²).
     *
     * Solo se miran los nodos que serían "picks" en modo allMode (los que
     * tienen texto o son interactivos), que es el criterio del waitfor
     * anterior: si un nodo no sale en el snapshot, tampoco puede aparecer.
     */
    static int findMatch(Index idx, String needle) {
        String n = needle.toLowerCase();
        List<Node> nodes = idx.nodes;
        for (int i = 0; i < nodes.size(); i++) {
            Node nd = nodes.get(i);
            if (!nd.interactive() && !nd.hasText()) continue;
            String own = !nd.txt.isEmpty() ? nd.txt : nd.desc;
            String label = !own.isEmpty() ? own : idx.joinChildren(i);
            String lid = nd.vid == null ? "" : nd.vid;
            // mismo criterio que el waitfor anterior: etiqueta + " " + id
            if (containsIgnoreCase(label, n)) return i;
            if (!lid.isEmpty() && containsIgnoreCase(lid, n)) return i;
        }
        return -1;
    }

    private static boolean containsIgnoreCase(String hay, String lowerNeedle) {
        return hay.toLowerCase().contains(lowerNeedle);
    }

    /* -------------------------------------------------------------- */
    /*  Índice de etiquetas: O(n) en vez de O(n²)                     */
    /* -------------------------------------------------------------- */

    /**
     * Índice de un árbol de nodos: precalcula los textos de los
     * descendientes de cada nodo.
     *
     * Antes, labelOf() llamaba a childText(), que recorría TODOS los
     * descendientes por cada nodo: O(n²). Con 3500 nodos eso son millones
     * de comparaciones de cadenas y el snapshot se arrastra (cientos de ms
     * o segundos). Aquí los textos se propagan de abajo arriba en una sola
     * pasada: O(n).
     */
    static final class Index {
        final List<Node> nodes;
        /** Para cada nodo: hasta 4 textos distintos de sus descendientes. */
        private final List<String[]> texts;
        private final int[] parent;

        Index(List<Node> nodes) {
            this.nodes = nodes;
            int n = nodes.size();
            parent = new int[n];
            texts = new ArrayList<>(n);

            // 1) ancestro más cercano en la lista (para cada nodo)
            int[] stack = new int[n];
            int sp = 0;
            for (int i = 0; i < n; i++) {
                int d = nodes.get(i).depth;
                while (sp > 0 && nodes.get(stack[sp - 1]).depth >= d) sp--;
                parent[i] = sp > 0 ? stack[sp - 1] : -1;
                stack[sp++] = i;
            }

            // 2) propagación de abajo arriba: los hijos (índice mayor) se
            //    resuelven antes que su padre.
            for (int i = 0; i < n; i++) texts.add(EMPTY);
            for (int i = n - 1; i >= 0; i--) {
                Node nd = nodes.get(i);
                String raw = !nd.txt.isEmpty() ? nd.txt : nd.desc;
                // mine = contribución de los hijos = textos de los descendientes
                String[] li = cap4(texts.get(i));
                texts.set(i, li);
                int p = parent[i];
                if (p >= 0) {
                    // la contribución de este nodo a su padre es su texto
                    // propio seguido de los de sus descendientes
                    String[] contrib = prepend(raw, li);
                    // al recorrer en reversa, anteponer deja el orden final
                    // en pre-orden (igual que el childText de antes)
                    texts.set(p, distinct(concat(contrib, texts.get(p))));
                }
            }
        }

        private static final String[] EMPTY = new String[0];

        /** Concatena dos listas. */
        private static String[] concat(String[] a, String[] b) {
            String[] out = new String[a.length + b.length];
            System.arraycopy(a, 0, out, 0, a.length);
            System.arraycopy(b, 0, out, a.length, b.length);
            return out;
        }

        /** Quita repetidos conservando el orden (tope holgado de 8). */
        private static String[] distinct(String[] a) {
            List<String> out = new ArrayList<>(8);
            for (int i = 0; i < a.length; i++) {
                String s = a[i];
                if (s == null || s.isEmpty()) continue;
                if (out.contains(s)) continue;
                out.add(s);
                if (out.size() >= 8) break;
            }
            return out.toArray(new String[0]);
        }

        /** Pone "s" delante, sin repetir. */
        private static String[] prepend(String s, String[] a) {
            if (s == null || s.isEmpty()) return a;
            List<String> out = new ArrayList<>(8);
            out.add(s);
            for (int i = 0; i < a.length; i++) {
                String x = a[i];
                if (x == null || x.isEmpty()) continue;
                if (out.contains(x)) continue;
                out.add(x);
                if (out.size() >= 8) break;
            }
            return out.toArray(new String[0]);
        }

        private static String[] cap4(String[] a) {
            if (a.length <= 4) return a;
            String[] out = new String[4];
            System.arraycopy(a, 0, out, 0, 4);
            return out;
        }

        /** Textos de los descendientes del nodo i (como el childText de antes). */
        String[] childTexts(int i) { return texts.get(i); }

        /** Etiqueta de un nodo: su texto, o el de sus hijos si no tiene. */
        String labelOf(int i) {
            Node nd = nodes.get(i);
            if (!nd.txt.isEmpty()) return nd.txt;
            if (!nd.desc.isEmpty()) return nd.desc;
            return joinChildren(i);
        }

        /** Une los textos de los descendientes con " · " (y recorta a 70). */
        String joinChildren(int i) {
            String[] arr = texts.get(i);
            if (arr.length == 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < arr.length; k++) {
                if (k > 0) sb.append(" · ");
                sb.append(arr[k]);
            }
            String s = sb.toString();
            return s.length() > 70 ? s.substring(0, 70) : s;
        }
    }

    /** Construye el índice de etiquetas de un árbol. */
    static Index index(List<Node> nodes) {
        return new Index(nodes);
    }

    /** Texto de un nodo: el suyo, o el de sus hijos si no tiene. */
    static String labelOf(List<Node> nodes, int i) {
        Node nd = nodes.get(i);
        if (!nd.txt.isEmpty()) return nd.txt;
        if (!nd.desc.isEmpty()) return nd.desc;
        return childText(nodes, i, 4, 70);
    }

    /** Concatena los textos de los descendientes (versión lineal, sin índice). */
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
