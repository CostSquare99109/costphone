package com.accessdroid.termux;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Test diferencial: el nuevo indice de etiquetas (O(n)) tiene que dar
 * EXACTAMENTE las mismas etiquetas que el metodo antiguo (O(n²)).
 *
 *   javac -d /tmp/snaptest Snap.java SnapDiffTest.java
 *   java -cp /tmp/snaptest SnapDiffTest
 */
public class SnapDiffTest {

    // ---- implementacion antigua (copia literal de la anterior) ----
    static String oldLabelOf(List<Snap.Node> nodes, int i) {
        Snap.Node nd = nodes.get(i);
        if (!nd.txt.isEmpty()) return nd.txt;
        if (!nd.desc.isEmpty()) return nd.desc;
        return oldChildText(nodes, i, 4, 70);
    }

    static String oldChildText(List<Snap.Node> nodes, int i, int limit, int width) {
        int depth = nodes.get(i).depth;
        List<String> parts = new ArrayList<>();
        for (int j = i + 1; j < nodes.size(); j++) {
            Snap.Node nd = nodes.get(j);
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

    static String[] WORDS = {
        "A", "B", "C", "D", "Hola", "Enviar", "Chat", "hola", "", "  ",
        "Mensaje largo con muchas palabras para pasar del ancho de corte",
        "Ñandú", "😀", "·", "x"
    };

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int trees = args.length > 1 ? Integer.parseInt(args[1]) : 3000;
        Random rnd = new Random(seed);
        int mismatches = 0;
        int checked = 0;

        for (int t = 0; t < trees; t++) {
            int n = 1 + rnd.nextInt(120);
            List<Snap.Node> nodes = new ArrayList<>();
            // arbol en pre-orden: la profundidad nunca baja mas de 1
            int depth = 0;
            for (int i = 0; i < n; i++) {
                Snap.Node nd = new Snap.Node();
                nd.depth = depth;
                // texto propio a veces, descripcion a veces
                String w = WORDS[rnd.nextInt(WORDS.length)];
                if (rnd.nextInt(3) != 0) nd.txt = w;
                if (rnd.nextInt(4) == 0) nd.desc = WORDS[rnd.nextInt(WORDS.length)];
                nodes.add(nd);
                // profundidad siguiente
                int r = rnd.nextInt(10);
                if (r < 4) depth++;              // baja
                else if (r < 7 && depth > 0) depth--;   // sube
                // si no, mismo nivel
            }
            Snap.Index idx = Snap.index(nodes);
            for (int i = 0; i < nodes.size(); i++) {
                String oldL = oldLabelOf(nodes, i);
                String newL = idx.labelOf(i);
                checked++;
                if (!oldL.equals(newL)) {
                    if (mismatches < 10) {
                        System.out.println("MISMATCH en arbol " + t + " nodo " + i
                                + " depth=" + nodes.get(i).depth);
                        System.out.println("  old: [" + oldL + "]");
                        System.out.println("  new: [" + newL + "]");
                        for (int k = 0; k < nodes.size(); k++) {
                            System.out.println("    " + k + " d" + nodes.get(k).depth
                                    + " txt=" + nodes.get(k).txt + " desc=" + nodes.get(k).desc);
                        }
                    }
                    mismatches++;
                }
            }
        }
        System.out.println("arboles=" + trees + " etiquetas comprobadas=" + checked
                + " discrepancias=" + mismatches);
        if (mismatches > 0) System.exit(1);
        System.out.println("IDENTICO ✓");
    }
}
