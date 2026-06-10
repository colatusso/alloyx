// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The sObject scanner must collect real referenced sObjects and nothing else —
 * notably not a constructor's bogus "return type" (the parser parks the access
 * modifier there), which would otherwise trigger a wasted describe on "public".
 */
class SObjectScanTest {

    @Test
    void constructorModifierIsNotCollected() {
        ClassDecl c = Parser.parse(
            "public class Product { public String sku; public Product(String sku) { this.sku = sku; } }");
        Set<String> refs = SObjectScan.referenced(List.of(c));
        assertFalse(refs.contains("public"), "modifiers must not be collected as types: " + refs);
    }

    @Test
    void realSObjectsAreCollected() {
        ClassDecl c = Parser.parse(
            "public class Svc { public void go() { List<Account> a = [SELECT Id FROM Contact]; Account x = new Account(); } }");
        Set<String> refs = SObjectScan.referenced(List.of(c));
        assertTrue(refs.contains("Account"), refs.toString());
        assertTrue(refs.contains("Contact"), "SOQL FROM object must be collected: " + refs);
    }
}
