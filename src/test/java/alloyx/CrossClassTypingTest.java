// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cross-class typing that needs no schema: the member-type index (built over the whole compile
 * set) lets the typer type another class's method return / field / param. Covers concat-vs-arith,
 * Decimal widening on a cross-class field assign, a cross-class Decimal param coercion, and a
 * single-class regression through a retrocompat transpile overload. Invented names only.
 */
class CrossClassTypingTest {

    @TempDir
    Path dir;

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void stringReturningMethodOnAnotherClass_staysConcat() throws Exception {
        // Greeter.label() returns String; Consumer does g.label() + 'x'. With g.label() typed
        // String cross-class, '+' is concatenation — not (mis)routed into Decimal arithmetic.
        Path greeter = probe("Greeter", """
            public class Greeter {
                public String label() { return 'hi'; }
            }
            """);
        Path consumer = probe("Consumer", """
            public class Consumer {
                public static String shout(Greeter g) {
                    return g.label() + 'x';
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(greeter, consumer));
        Class<?> greeterCls = c.load("Greeter");
        Object g = greeterCls.getDeclaredConstructor().newInstance();
        Object result = c.load("Consumer").getMethod("shout", greeterCls).invoke(null, g);
        assertEquals("hix", result);
    }

    @Test
    void integerLiteralIntoAnotherClassDecimalField_widens() throws Exception {
        // Cart.cost is Decimal; Buyer assigns the int literal 333 to cart.cost. Apex widens
        // Integer -> Decimal; without coercion the BigDecimal field assign won't compile.
        Path cart = probe("Cart", """
            public class Cart {
                public Decimal cost;
            }
            """);
        Path buyer = probe("Buyer", """
            public class Buyer {
                public static Decimal price(Cart cart) {
                    cart.cost = 333;
                    return cart.cost;
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(cart, buyer));
        Class<?> cartCls = c.load("Cart");
        Object cartInst = cartCls.getDeclaredConstructor().newInstance();
        Object result = c.load("Buyer").getMethod("price", cartCls).invoke(null, cartInst);
        assertEquals(new BigDecimal("333"), result);
    }

    @Test
    void integerArgIntoAnotherClassDecimalParam_coerces() throws Exception {
        // Wallet.pay(Decimal) called from Payer as w.pay(5): the Integer arg must coerce to
        // Decimal via the cross-class param-type lookup, or the call won't bind to pay(BigDecimal).
        Path wallet = probe("Wallet", """
            public class Wallet {
                public Decimal pay(Decimal amount) { return amount; }
            }
            """);
        Path payer = probe("Payer", """
            public class Payer {
                public static Decimal charge(Wallet w) {
                    return w.pay(5);
                }
            }
            """);
        Workspace.Compiled c = Workspace.compile(List.of(wallet, payer));
        Class<?> walletCls = c.load("Wallet");
        Object w = walletCls.getDeclaredConstructor().newInstance();
        Object result = c.load("Payer").getMethod("charge", walletCls).invoke(null, w);
        assertEquals(new BigDecimal("5"), result);
    }

    @Test
    void singleClassTranspile_unchangedThroughRetrocompatOverload() throws Exception {
        // Regression: the retrocompat transpile(cls, userClasses, schema, typedSObjects, memberIndex)
        // overload builds the member index from the single decl, preserving today's behavior —
        // a same-class Decimal param call still coerces and nothing else shifts.
        ClassDecl cls = Parser.parse("""
            public class Solo {
                public Decimal pay(Decimal amount) { return amount; }
                public Decimal go() { return pay(7); }
            }
            """);
        String src = Transpiler.transpile(
            cls, java.util.Set.of("Solo"), (o, f) -> null, java.util.Set.of(),
            java.util.Map.of()).source();
        // the bare same-class call pay(7) widens the Integer literal to Decimal
        assertTrue(src.contains("pay(Decimal.valueOf(7))"), src);
    }
}
