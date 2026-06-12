// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alloyx.runtime.SchemaProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code Account.SObjectType} convergence (the property form, see emitProp) rewrites a bare,
 * UNSHADOWED sObject TYPE name to {@code new SObjectType("Account")}. It must NEVER fire on an
 * instance/this-rooted access of a member that merely happens to be NAMED {@code sObjectType}, nor
 * on an assignment TARGET — those are ordinary field reads/writes. A user class may legitimately
 * declare a field called {@code sObjectType} (any casing); reading or writing it stays an instance
 * member access, while a real {@code Account.SObjectType} type-name usage still converges.
 */
class SObjectTypeFieldShadowTest {

    @TempDir
    Path dir;

    private static final SchemaProvider NO_SCHEMA = new SchemaProvider() {
        @Override public String fieldType(String s, String f) { return null; }
        @Override public boolean isDescribed(String s) { return false; }
        @Override public String canonicalField(String s, String f) { return f; }
        @Override public Map<String, String> fields(String s) { return null; }
    };

    private String transpile(String src) {
        ClassDecl cls = Parser.parse(src);
        return Transpiler.transpile(cls, Set.of(cls.name()), NO_SCHEMA, Set.of()).source();
    }

    @Test
    void thisRootedAssignmentToSObjectTypeField_staysInstanceWrite() {
        // this.sObjectType = sObjectType must be a plain field WRITE, never the token rewrite
        // (javac would otherwise reject `new SObjectType("this") = ...`: not a variable).
        String java = transpile("""
            public class LookupSearchResult {
                private String sObjectType;
                public LookupSearchResult(String id, String sObjectType) {
                    this.sObjectType = sObjectType;
                }
            }
            """);
        assertTrue(java.contains("this.sObjectType = sObjectType"), java);
        assertFalse(java.contains("new SObjectType("), java);
    }

    @Test
    void thisRootedReadOfSObjectTypeField_staysInstanceRead() {
        // String s = this.sObjectType; is an instance READ of the field, not a converged token.
        String java = transpile("""
            public class LookupSearchResult {
                private String sObjectType;
                public String getType() { return this.sObjectType; }
            }
            """);
        assertTrue(java.contains("return this.sObjectType"), java);
        assertFalse(java.contains("new SObjectType("), java);
    }

    @Test
    void lookupResultShape_compilesCtorSetsFieldGetterReadsBack() throws Exception {
        // end to end: the ctor stores the param into the field, the getter reads it back.
        Path p = dir.resolve("LookupSearchResult.cls");
        Files.writeString(p, """
            public class LookupSearchResult {
                private String sObjectType;
                public LookupSearchResult(String id, String sObjectType) {
                    this.sObjectType = sObjectType;
                }
                public String getSObjectType() { return this.sObjectType; }
            }
            """);
        Class<?> c = Workspace.compile(List.of(p)).load("LookupSearchResult");
        Object inst = c.getConstructor(String.class, String.class).newInstance("001", "Account");
        assertEquals("Account", c.getMethod("getSObjectType").invoke(inst));
    }

    @Test
    void realTypeNameSObjectType_stillConverges() {
        // the genuine type-name usage must keep converging onto the runtime SObjectType token.
        String java = transpile("""
            public class C {
                public static Object t() { return Account.SObjectType; }
            }
            """);
        assertTrue(java.contains("new SObjectType(\"Account\")"), java);
    }
}
