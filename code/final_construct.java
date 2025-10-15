import java.util.*;
import java.io.*;

/**
 * BTN sampling cycle — fixed start/end node, time budget.
 * Simplified model without start node selection.
 */
public class final_construct{

    public static void main(String[] args) throws Exception {
        // ---- Paths ----
        String base = "/Users/carissamayo/Desktop/UW_work/SEFS 540/Project/";
        File parcelsO = new File(base + "node_data.txt");       // "<node>, <risk>"
        File adjO     = new File(base + "node_adjacency.txt");  // "<i>, <j>"
        String outLP  = base + "model_cycle_fixed.lp";

        // ---- Problem constants ----
        final double travelPerNode = 0.5;
        final double samplePerNode = 2.0;
        final double timeBudget    = 15.0;
        
        // ---- FIXED START/END NODE ----
        // Choose a node that's likely near Penn Cove or Triangle Cove
        // You can change this to any node ID from your node_data.txt
        final String START_NODE = "57";  // Change this to your desired start node

        // ---- Read nodes + risk ----
        List<String> nodes = new ArrayList<>();
        Map<String, Double> risk = new HashMap<>();
        try (Scanner sc = new Scanner(parcelsO)) {
            while (sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;
                String[] t = line.split(",\\s*");
                String v = t[0];
                double r = Double.parseDouble(t[1]);
                nodes.add(v);
                risk.put(v, r);
            }
        }
        int N = nodes.size();
        Set<String> nodeSet = new HashSet<>(nodes);
        
        // Verify start node exists
        if (!nodeSet.contains(START_NODE)) {
            throw new RuntimeException("Start node " + START_NODE + " not found in node data!");
        }

        // ---- Read adjacencies as UNDIRECTED ----
        LinkedHashSet<String> undirected = new LinkedHashSet<>();
        try (Scanner sc = new Scanner(adjO)) {
            while (sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;
                String[] t = line.split(",\\s*");
                if (t.length < 2) continue;
                String a = t[0], b = t[1];
                if (!nodeSet.contains(a) || !nodeSet.contains(b) || a.equals(b)) continue;
                String i = a.compareTo(b) <= 0 ? a : b;
                String j = a.compareTo(b) <= 0 ? b : a;
                undirected.add(i + "|" + j);
            }
        }

        // ---- Create directed arcs ----
        List<String[]> arcs = new ArrayList<>();
        Map<String, LinkedHashSet<String>> outNbr = new HashMap<>();
        Map<String, LinkedHashSet<String>> inNbr  = new HashMap<>();
        for (String v : nodes) { 
            outNbr.put(v, new LinkedHashSet<>()); 
            inNbr.put(v, new LinkedHashSet<>()); 
        }

        for (String key : undirected) {
            String[] p = key.split("\\|");
            String i = p[0], j = p[1];
            arcs.add(new String[]{i, j});
            arcs.add(new String[]{j, i});
            outNbr.get(i).add(j); inNbr.get(j).add(i);
            outNbr.get(j).add(i); inNbr.get(i).add(j);
        }

        // ---- Write LP ----
        try (PrintWriter w = new PrintWriter(new FileWriter(outLP))) {
            w.println("\\ BTN sampling cycle — fixed start/end node: " + START_NODE);
            
            // Objective
            w.println("Maximize");
            {
                StringBuilder sb = new StringBuilder("  obj:");
                boolean first = true;
                for (String v : nodes) {
                    if (!first) sb.append(" +");
                    sb.append(" ").append(risk.get(v)).append(" x").append(v);
                    first = false;
                }
                w.println(sb.toString());
            }

            w.println("\nSubject To");

            // Force start node to be visited
            w.printf("  FixedStart: y%s = 1%n", START_NODE);

            // Degree constraints: for each v, in_v = out_v = y_v (closed tour)
            for (String v : nodes) {
                // in(v) = y_v
                if (!inNbr.get(v).isEmpty()) {
                    StringBuilder sb = new StringBuilder("  InEq_").append(v).append(":");
                    boolean first = true;
                    for (String i : inNbr.get(v)) {
                        if (!first) sb.append(" +");
                        sb.append(" e").append(i).append("_").append(v);
                        first = false;
                    }
                    sb.append(" - y").append(v).append(" = 0");
                    w.println(sb.toString());
                }
                
                // out(v) = y_v
                if (!outNbr.get(v).isEmpty()) {
                    StringBuilder sb = new StringBuilder("  OutEq_").append(v).append(":");
                    boolean first = true;
                    for (String j : outNbr.get(v)) {
                        if (!first) sb.append(" +");
                        sb.append(" e").append(v).append("_").append(j);
                        first = false;
                    }
                    sb.append(" - y").append(v).append(" = 0");
                    w.println(sb.toString());
                }
            }

            // Arc-node coupling: e_ij ≤ y_i and e_ij ≤ y_j
            for (String[] a : arcs) {
                String i = a[0], j = a[1];
                w.printf("  ArcFrom_%s_%s: e%s_%s - y%s <= 0%n", i, j, i, j, i);
                w.printf("  ArcTo_%s_%s: e%s_%s - y%s <= 0%n", i, j, i, j, j);
            }

            // MTZ subtour elimination: 1 <= u_v <= N when y_v=1
            // u_start = 1 (fixed)
            w.printf("  MTZ_Start: u%s = 1%n", START_NODE);
            
            for (String v : nodes) {
                if (v.equals(START_NODE)) continue;
                w.printf("  MTZ_LB_%s: u%s - y%s >= 0%n", v, v, v);
                w.printf("  MTZ_UB_%s: u%s - %d y%s <= 0%n", v, v, N, v);
            }
            
            // MTZ ordering: u_i + 1 <= u_j + N(1 - e_ij)
            for (String[] a : arcs) {
                String i = a[0], j = a[1];
                if (j.equals(START_NODE)) continue; // Don't constrain incoming to start
                w.printf("  MTZ_%s_%s: u%s - u%s + %d e%s_%s <= %d%n",
                        i, j, i, j, N, i, j, N - 1);
            }

            // Sampling only on visited nodes
            for (String v : nodes) {
                w.printf("  SampleIfOnPath_%s: x%s - y%s <= 0%n", v, v, v);
            }

            // Time budget
            {
                StringBuilder sb = new StringBuilder("  TimeBudget:");
                boolean first = true;
                for (String v : nodes) {
                    if (!first) sb.append(" +");
                    sb.append(" ").append(travelPerNode).append(" y").append(v)
                      .append(" + ").append(samplePerNode).append(" x").append(v);
                    first = false;
                }
                sb.append(" <= ").append(timeBudget);
                w.println(sb.toString());
            }

            // ---- Variables ----
            w.println("\nBinary");
            {
                StringBuilder line = new StringBuilder("  ");
                int cnt = 0;
                for (String v : nodes) {
                    line.append("x").append(v).append(" y").append(v).append(" ");
                    if (++cnt % 10 == 0) { 
                        w.println(line.toString()); 
                        line = new StringBuilder("  "); 
                    }
                }
                if (line.length() > 2) w.println(line.toString());
            }
            {
                StringBuilder line = new StringBuilder("  ");
                int cnt = 0;
                for (String[] a : arcs) {
                    line.append("e").append(a[0]).append("_").append(a[1]).append(" ");
                    if (++cnt % 10 == 0) { 
                        w.println(line.toString()); 
                        line = new StringBuilder("  "); 
                    }
                }
                if (line.length() > 2) w.println(line.toString());
            }

            w.println("\nGeneral");
            {
                StringBuilder line = new StringBuilder("  ");
                int cnt = 0;
                for (String v : nodes) {
                    if (v.equals(START_NODE)) continue; // u_start is fixed to 1
                    line.append("u").append(v).append(" ");
                    if (++cnt % 16 == 0) { 
                        w.println(line.toString()); 
                        line = new StringBuilder("  "); 
                    }
                }
                if (line.length() > 2) w.println(line.toString());
            }

            w.println("\nEnd");
        }

        System.out.println("Wrote CPLEX/LP model: " + outLP);
        System.out.println("Nodes: " + N + "  Arcs: " + arcs.size() + "  Start node: " + START_NODE);
        System.out.println("\nTo solve:");
        System.out.println("  cplex -c \"read " + outLP + "\" \"optimize\" \"write model_cycle_fixed.sol\"");
    }
}
