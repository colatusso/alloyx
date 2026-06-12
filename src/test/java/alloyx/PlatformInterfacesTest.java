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
 * The Apex platform interfaces a real codebase implements — Database.Batchable&lt;sObject&gt;
 * with Stateful/AllowsCallouts markers, Schedulable, Queueable, Iterable/Iterator, Comparable —
 * map to REAL Java interfaces in the runtime. Before this, the transpiler had no model for them,
 * so each collapsed to the dynamic SObject CLASS and javac rejected the class with
 * "interface expected here". Each test transpiles a representative declaration and compiles it;
 * compilation succeeding == the interface family is modeled. A couple also instantiate and invoke
 * the implementor to prove the generated class is a working Java type, not just type-checked.
 */
class PlatformInterfacesTest {

    @TempDir
    Path dir;

    private Path probe(String name, String body) throws Exception {
        Path f = dir.resolve(name + ".cls");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void batchableWithQueryLocatorStartAndScopeExecute() throws Exception {
        // The full Batchable<sObject> contract: QueryLocator start(BatchableContext),
        // void execute(BatchableContext, List<sObject>), void finish(BatchableContext).
        Path p = probe("AccountBatch", """
            public class AccountBatch implements Database.Batchable<sObject> {
                public Database.QueryLocator start(Database.BatchableContext bc) {
                    return null;
                }
                public void execute(Database.BatchableContext bc, List<sObject> scope) {
                }
                public void finish(Database.BatchableContext bc) {
                }
            }
            """);
        Workspace.compile(List.of(p)).load("AccountBatch"); // compiles + loads as a Java type
    }

    @Test
    void batchableStatefulAndCalloutsMarkersCombine() throws Exception {
        // Batchable plus the two most common markers, all on one class — each must be an interface.
        Path p = probe("SyncBatch", """
            public class SyncBatch implements Database.Batchable<sObject>, Database.Stateful, Database.AllowsCallouts {
                private Integer processed = 0;
                public Database.QueryLocator start(Database.BatchableContext bc) {
                    return null;
                }
                public void execute(Database.BatchableContext bc, List<sObject> scope) {
                    processed = processed + scope.size();
                }
                public void finish(Database.BatchableContext bc) {
                }
            }
            """);
        Workspace.compile(List.of(p)).load("SyncBatch");
    }

    @Test
    void schedulableExecuteRunsLocally() throws Exception {
        // Schedulable maps to an interface; the body is plain domain logic, so it runs.
        Path p = probe("NightlyJob", """
            public class NightlyJob implements Schedulable {
                public Integer ran = 0;
                public void execute(SchedulableContext sc) {
                    ran = 7;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("NightlyJob");
        Object job = c.getDeclaredConstructor().newInstance();
        // SchedulableContext has no local model; pass null — execute() doesn't touch it.
        c.getMethod("execute", alloyx.runtime.SchedulableContext.class).invoke(job, (Object) null);
        // fields transpile package-private (the transpiler drops the Apex visibility modifier),
        // so read via the declared field.
        java.lang.reflect.Field ran = c.getDeclaredField("ran");
        ran.setAccessible(true);
        assertEquals(7, ran.get(job));
    }

    @Test
    void queueableExecuteCompilesAndLoads() throws Exception {
        Path p = probe("EnqueueWork", """
            public class EnqueueWork implements Queueable {
                public void execute(QueueableContext qc) {
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("EnqueueWork");
        assertTrue(alloyx.runtime.Queueable.class.isAssignableFrom(c),
            "the generated class must really implement the Queueable interface");
    }

    @Test
    void iterableReturnsAnIterator() throws Exception {
        // Iterable<X> hands out Iterator<X>; both are runtime interfaces (not java.util/lang,
        // because Apex's hasNext() returns Boolean, not the primitive boolean).
        Path p = probe("NumberRange", """
            public class NumberRange implements Iterable<Account> {
                public Iterator<Account> iterator() {
                    return null;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("NumberRange");
        assertTrue(alloyx.runtime.Iterable.class.isAssignableFrom(c),
            "the generated class must really implement the Iterable interface");
    }

    @Test
    void customIteratorImplementsHasNextAndNext() throws Exception {
        // A standalone Iterator<X> implementation (hasNext/next) — Boolean hasNext(), X next().
        Path p = probe("AccountCursor", """
            public class AccountCursor implements Iterator<Account> {
                private Boolean done = false;
                public Boolean hasNext() {
                    return !done;
                }
                public Account next() {
                    done = true;
                    return null;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("AccountCursor");
        Object it = c.getDeclaredConstructor().newInstance();
        assertEquals(Boolean.TRUE, c.getMethod("hasNext").invoke(it));
    }

    @Test
    void comparableCompareToRunsLocally() throws Exception {
        // Comparable maps to a runtime interface whose compareTo returns Integer (Apex shape),
        // NOT java.lang.Comparable (which needs primitive int). The body is plain logic, so it runs.
        Path p = probe("Money", """
            public class Money implements Comparable {
                public Integer amount;
                public Money(Integer a) {
                    amount = a;
                }
                public Integer compareTo(Object o) {
                    Money other = (Money) o;
                    if (amount < other.amount) { return -1; }
                    if (amount > other.amount) { return 1; }
                    return 0;
                }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("Money");
        Object five = c.getDeclaredConstructor(Integer.class).newInstance(5);
        Object nine = c.getDeclaredConstructor(Integer.class).newInstance(9);
        assertEquals(-1, c.getMethod("compareTo", Object.class).invoke(five, nine));
        assertTrue(alloyx.runtime.Comparable.class.isAssignableFrom(c),
            "the generated class must really implement the Comparable interface");
    }
}
