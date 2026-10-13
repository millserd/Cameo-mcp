package com.cameomcp.plugin.tools.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of the parts of Python's {@code difflib} used for spelling suggestions:
 * {@code SequenceMatcher.ratio()} (Ratcliff/Obershelp matching blocks, no junk heuristic for
 * short strings) and {@code get_close_matches}.
 */
final class SequenceMatcher {

    private SequenceMatcher() {
    }

    /** {@code SequenceMatcher(None, a, b).ratio()}. */
    static double ratio(String a, String b) {
        int la = a.length();
        int lb = b.length();
        if (la + lb == 0) {
            return 1.0;
        }
        return 2.0 * matches(a, b) / (la + lb);
    }

    /*
     * Total size of the matching blocks (difflib get_matching_blocks): find the longest common block,
     * then recurse on the unmatched regions to its left and right. A work queue replaces recursion;
     * processing order does not affect the total.
     */
    static int matches(String a, String b) {
        // b2j: character -> ascending positions in b, so longestMatch can scan candidate j values in order.
        Map<Character, List<Integer>> b2j = new HashMap<>();
        for (int j = 0; j < b.length(); j++) {
            b2j.computeIfAbsent(b.charAt(j), k -> new ArrayList<>()).add(j);
        }
        // b.length() >= 200 would enable the "popular element" junk heuristic; words never get there,
        // but keep the behaviour faithful for long inputs.
        if (b.length() >= 200) {
            int ntest = b.length() / 100 + 1;
            b2j.values().removeIf(list -> list.size() > ntest);
        }
        int total = 0;
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{0, a.length(), 0, b.length()});
        while (!queue.isEmpty()) {
            int[] q = queue.poll();
            int alo = q[0];
            int ahi = q[1];
            int blo = q[2];
            int bhi = q[3];
            int[] m = longestMatch(a, b, b2j, alo, ahi, blo, bhi);
            int i = m[0];
            int j = m[1];
            int k = m[2];
            if (k > 0) {
                total += k;
                if (alo < i && blo < j) {
                    queue.add(new int[]{alo, i, blo, j});
                }
                if (i + k < ahi && j + k < bhi) {
                    queue.add(new int[]{i + k, ahi, j + k, bhi});
                }
            }
        }
        return total;
    }

    /*
     * difflib find_longest_match without junk: dynamic programming over a[alo:ahi] where j2len[j]
     * is the length of the match ending at a[i-1], b[j-1]. Ties keep the earliest block in a.
     */
    private static int[] longestMatch(String a, String b, Map<Character, List<Integer>> b2j,
                                      int alo, int ahi, int blo, int bhi) {
        int besti = alo;
        int bestj = blo;
        int bestsize = 0;
        Map<Integer, Integer> j2len = new HashMap<>();
        for (int i = alo; i < ahi; i++) {
            Map<Integer, Integer> newj2len = new HashMap<>();
            List<Integer> js = b2j.get(a.charAt(i));
            if (js != null) {
                for (int j : js) {
                    if (j < blo) {
                        continue;
                    }
                    if (j >= bhi) {
                        break;
                    }
                    int k = j2len.getOrDefault(j - 1, 0) + 1;
                    newj2len.put(j, k);
                    if (k > bestsize) {
                        besti = i - k + 1;
                        bestj = j - k + 1;
                        bestsize = k;
                    }
                }
            }
            j2len = newj2len;
        }
        // Extend the block over characters dropped from b2j by the popularity heuristic (difflib does the same).
        while (besti > alo && bestj > blo && a.charAt(besti - 1) == b.charAt(bestj - 1)) {
            besti--;
            bestj--;
            bestsize++;
        }
        while (besti + bestsize < ahi && bestj + bestsize < bhi && a.charAt(besti + bestsize) == b.charAt(bestj + bestsize)) {
            bestsize++;
        }
        return new int[]{besti, bestj, bestsize};
    }

    /** difflib quick_ratio: an upper bound on ratio() from character multiset overlap, ignoring order. */
    private static double quickRatio(String a, String b) {
        Map<Character, Integer> full = new HashMap<>();
        for (int i = 0; i < b.length(); i++) {
            full.merge(b.charAt(i), 1, Integer::sum);
        }
        Map<Character, Integer> avail = new HashMap<>();
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            int n = avail.containsKey(c) ? avail.get(c) : full.getOrDefault(c, 0);
            avail.put(c, n - 1);
            if (n > 0) {
                matches++;
            }
        }
        int t = a.length() + b.length();
        return t == 0 ? 1.0 : 2.0 * matches / t;
    }

    /** {@code difflib.get_close_matches(word, possibilities, n, cutoff)}. */
    static List<String> closeMatches(String word, List<String> possibilities, int n, double cutoff) {
        List<Object[]> result = new ArrayList<>();
        for (String x : possibilities) {
            int la = x.length();
            int lb = word.length();
            // real_quick_ratio, quick_ratio, ratio: progressively more expensive bounds, so most candidates are rejected cheaply.
            double realQuick = la + lb == 0 ? 1.0 : 2.0 * Math.min(la, lb) / (la + lb);
            if (realQuick < cutoff) {
                continue;
            }
            if (quickRatio(x, word) < cutoff) {
                continue;
            }
            double r = ratio(x, word);
            if (r >= cutoff) {
                result.add(new Object[]{r, x});
            }
        }
        // Best score first; ties broken by reverse string order, matching Python's heapq.nlargest on (score, word) tuples.
        result.sort((p, q) -> {
            int c = Double.compare((Double) q[0], (Double) p[0]);
            return c != 0 ? c : ((String) q[1]).compareTo((String) p[1]);
        });
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, result.size()); i++) {
            out.add((String) result.get(i)[1]);
        }
        return out;
    }
}
