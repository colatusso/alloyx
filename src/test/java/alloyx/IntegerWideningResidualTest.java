// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Residual Integer-widening contexts. Apex implicitly widens an Integer to a Decimal OR a Double;
 * Java widens to neither boxed type on its own. The Decimal widen already fires at most slots (var
 * init, return, field/arg). These probes pin the missed parallels surfaced by corpus diagnostics:
 *
 * <ul>
 *   <li>a {@code Double} parameter / constructor / typed-sObject-field receiving an Integer literal
 *       (the Double parallel of the existing Decimal arg/field coercion);</li>
 *   <li>a Map/List LITERAL whose declared element type is Decimal/Double, with Integer values.</li>
 * </ul>
 *
 * All identifiers are invented fixtures — nothing schema-specific.
 */
class IntegerWideningResidualTest {

    @TempDir
    Path dir;

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    private Object run(String name, String body) throws Exception {
        return Workspace.compile(List.of(probe(name, body))).load(name).getMethod("go").invoke(null);
    }

    @Test
    void doubleParameterAcceptsIntegerArgument() throws Exception {
        // a Double param called with an Integer literal — Apex widens; emitter must too
        Object v = run("Dbl", """
            public class Dbl {
                static Double scale(Double factor) { return factor * 2; }
                public static Double go() {
                    return scale(100);
                }
            }
            """);
        assertEquals(Double.valueOf(200.0), v);
    }

    @Test
    void doubleConstructorParamAcceptsIntegerArgument() throws Exception {
        Object v = run("DblCtor", """
            public class DblCtor {
                Double amount;
                DblCtor(Double amount) { this.amount = amount; }
                public static Double go() {
                    DblCtor h = new DblCtor(50);
                    return h.amount;
                }
            }
            """);
        assertEquals(Double.valueOf(50.0), v);
    }

    @Test
    void mapLiteralWidensIntegerValuesToDecimal() throws Exception {
        // new Map<String, Decimal>{'a' => 0, 'b' => 10} — the Integer values widen to Decimal
        Object v = run("MapLit", """
            public class MapLit {
                public static Decimal go() {
                    Map<String, Decimal> m = new Map<String, Decimal>{'a' => 0, 'b' => 10};
                    return m.get('b');
                }
            }
            """);
        assertEquals(0, new java.math.BigDecimal("10").compareTo((java.math.BigDecimal) v));
    }

    @Test
    void listLiteralWidensIntegerValuesToDecimal() throws Exception {
        Object v = run("ListLit", """
            public class ListLit {
                public static Decimal go() {
                    List<Decimal> nums = new List<Decimal>{ 1, 2, 3 };
                    return nums.get(0);
                }
            }
            """);
        assertEquals(0, new java.math.BigDecimal("1").compareTo((java.math.BigDecimal) v));
    }

    @Test
    void doubleLocalVarAndReassignAcceptIntegerLiteral() throws Exception {
        // Double d = 0; d = 5; — both the init and the reassignment widen Integer to Double
        Object v = run("DblVar", """
            public class DblVar {
                public static Double go() {
                    Double d = 0;
                    d = 5;
                    return d;
                }
            }
            """);
        assertEquals(Double.valueOf(5.0), v);
    }

    @Test
    void crossClassDoubleFieldAssignWidensIntegerLiteral() throws Exception {
        // obj.rate = 7; where rate is a Double user field — widen via the member-type index
        Object v = run("Holder2", """
            public class Holder2 {
                Double rate;
                public static Double go() {
                    Holder2 h = new Holder2();
                    h.rate = 7;
                    return h.rate;
                }
            }
            """);
        assertEquals(Double.valueOf(7.0), v);
    }

    @Test
    void existingDecimalArgWideningUnaffected() throws Exception {
        // regression guard: a Decimal param + Integer arg still widens (Decimal.valueOf)
        String java = Transpiler.transpile(Parser.parse("""
            public class Keep {
                static Decimal id(Decimal d) { return d; }
                public static Decimal go() { return id(5); }
            }
            """)).source();
        assertTrue(java.contains("Decimal.valueOf(5)"), java);
    }
}
