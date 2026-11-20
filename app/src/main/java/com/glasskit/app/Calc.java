package com.glasskit.app;

/** Kalkulator kecil untuk carian: + - * / % ( ) dan perpuluhan. Pulangkan null jika bukan ungkapan. */
final class Calc {
    private final String s;
    private int i;

    private Calc(String s) { this.s = s; }

    static String eval(String q) {
        if (q == null) return null;
        String t = q.replace(" ", "").replace("x", "*").replace("×", "*").replace("÷", "/").replace(",", ".");
        if (t.length() < 3 || !t.matches("[0-9.+\\-*/%()]+") || !t.matches(".*[0-9].*[+\\-*/%].*[0-9].*")) return null;
        try {
            Calc c = new Calc(t);
            double v = c.expr();
            if (c.i != t.length() || Double.isNaN(v) || Double.isInfinite(v)) return null;
            if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
            String r = String.format(java.util.Locale.US, "%.6f", v).replaceAll("0+$", "");
            return r.endsWith(".") ? r.substring(0, r.length() - 1) : r;
        } catch (Exception e) {
            return null;
        }
    }

    private double expr() {
        double v = term();
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '+') { i++; v += term(); } else if (c == '-') { i++; v -= term(); } else break;
        }
        return v;
    }

    private double term() {
        double v = factor();
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '*') { i++; v *= factor(); } else if (c == '/') { i++; v /= factor(); } else if (c == '%') { i++; v = v / 100.0; } else break;
        }
        return v;
    }

    private double factor() {
        if (s.charAt(i) == '-') { i++; return -factor(); }
        if (s.charAt(i) == '(') {
            i++;
            double v = expr();
            if (s.charAt(i) != ')') throw new IllegalStateException();
            i++;
            return v;
        }
        int st = i;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        return Double.parseDouble(s.substring(st, i));
    }
}
