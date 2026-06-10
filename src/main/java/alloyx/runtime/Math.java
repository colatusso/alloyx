// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex Math. Named "Math" so transpiled code reads like Apex; the single-type
 * import shadows java.lang.Math, which we qualify internally.
 */
public class Math {
    /** Apex Math.mod: remainder with the sign of the dividend (Java int % matches). */
    public static Integer mod(Integer a, Integer b) {
        return a % b;
    }

    public static Long mod(Long a, Long b) {
        return a % b;
    }

    public static Integer abs(Integer x) {
        return java.lang.Math.abs(x);
    }

    public static Integer max(Integer a, Integer b) {
        return java.lang.Math.max(a, b);
    }

    public static Integer min(Integer a, Integer b) {
        return java.lang.Math.min(a, b);
    }

    /** Apex Math.round(Double) -> nearest Integer (this code relies on int width). */
    public static Integer round(Double d) {
        return (int) java.lang.Math.round(d);
    }

    /** Apex Math.random(): a Double in [0, 1). */
    public static Double random() {
        return java.lang.Math.random();
    }
}
