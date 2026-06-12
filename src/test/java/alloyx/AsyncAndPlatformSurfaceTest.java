// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Rafael Colatusso
package alloyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Items 2/4/5: the runtime surface for Database batch/savepoint/lead-convert/count, the
 * ApexPages controller types, and the System async/job/context statics. Org-bound operations
 * degrade honestly (Unsupported.notLocal); the faithful-local cases (isFuture/isBatch == false,
 * LeadConvert setters round-tripping, ApexPages.addMessage storing, currentPage() params) behave.
 */
class AsyncAndPlatformSurfaceTest {

    // --- Item 2: Database -----------------------------------------------------------------------

    @Test
    void databaseExecuteBatch_notLocal_caseInsensitiveRouting() {
        // executeBatch orchestration is out of scope; both the scope and no-scope overloads degrade.
        assertThrows(UnsupportedOperationException.class,
            () -> alloyx.runtime.Database.executeBatch(new Object()));
        assertThrows(UnsupportedOperationException.class,
            () -> alloyx.runtime.Database.executeBatch(new Object(), 200));
    }

    @Test
    void databaseSavepoint_isOpaque_rollbackIsHonest() {
        // setSavepoint() hands back an opaque Savepoint; rollback can't faithfully undo org state
        // locally, so it degrades rather than silently lying about the data.
        alloyx.runtime.Database.Savepoint sp = alloyx.runtime.Database.setSavepoint();
        assertTrue(sp != null);
        assertThrows(UnsupportedOperationException.class,
            () -> alloyx.runtime.Database.rollback(sp));
    }

    @Test
    void leadConvertSettersRoundTrip_conversionNotLocal() {
        // LeadConvert is pure data: setters store, getters read back. convertLead itself is org-bound.
        alloyx.runtime.Database.LeadConvert lc = new alloyx.runtime.Database.LeadConvert();
        lc.setLeadId("00Q000000000001");
        lc.setConvertedStatus("Closed - Converted");
        lc.setDoNotCreateOpportunity(true);
        assertEquals("00Q000000000001", lc.getLeadId());
        assertEquals("Closed - Converted", lc.getConvertedStatus());
        assertEquals(Boolean.TRUE, lc.getDoNotCreateOpportunity());
        assertThrows(UnsupportedOperationException.class,
            () -> alloyx.runtime.Database.convertLead(lc));
    }

    @Test
    void countQuery_notLocalWhenUnconnected() {
        // with no connected org the count can't run; it degrades clearly.
        assertThrows(UnsupportedOperationException.class,
            () -> alloyx.runtime.Database.countQuery("SELECT COUNT() FROM Account"));
    }

    // --- Item 4: ApexPages ----------------------------------------------------------------------

    @Test
    void apexPagesAddMessage_getMessages_hasMessages_storeLocally() {
        alloyx.runtime.ApexPages.clearMessages(); // isolate from other tests' messages
        assertFalse(alloyx.runtime.ApexPages.hasMessages());
        alloyx.runtime.ApexPages.Message m = new alloyx.runtime.ApexPages.Message(
            alloyx.runtime.ApexPages.Severity.ERROR, "boom");
        alloyx.runtime.ApexPages.addMessage(m);
        assertTrue(alloyx.runtime.ApexPages.hasMessages());
        assertEquals(1, alloyx.runtime.ApexPages.getMessages().size());
        assertEquals("boom", alloyx.runtime.ApexPages.getMessages().get(0).getSummary());
        alloyx.runtime.ApexPages.clearMessages();
    }

    @Test
    void apexPagesCurrentPage_returnsPageReferenceWithEmptyParams() {
        alloyx.runtime.PageReference ref = alloyx.runtime.ApexPages.currentPage();
        assertTrue(ref != null);
        assertTrue(ref.getParameters().isEmpty());
    }

    @Test
    void standardControllerCtorStores_getRecordRoundTrips_otherOpsNotLocal() {
        alloyx.runtime.SObject rec = new alloyx.runtime.SObject("Account");
        alloyx.runtime.ApexPages.StandardController sc =
            new alloyx.runtime.ApexPages.StandardController(rec);
        assertEquals(rec, sc.getRecord());
        assertThrows(UnsupportedOperationException.class, sc::save);
    }

    // --- Item 5: System async/job/context -------------------------------------------------------

    @Test
    void contextProbesAreHonestlyFalse() {
        // locally we are never inside a future/batch/queueable/scheduled context.
        assertFalse(alloyx.runtime.System.isFuture());
        assertFalse(alloyx.runtime.System.isBatch());
        assertFalse(alloyx.runtime.System.isQueueable());
        assertFalse(alloyx.runtime.System.isScheduled());
    }

    @Test
    void scheduleEnqueueAbort_notLocal() {
        assertThrows(UnsupportedOperationException.class,
            () -> alloyx.runtime.System.schedule("job", "0 0 1 * * ?", new Object()));
        assertThrows(UnsupportedOperationException.class,
            () -> alloyx.runtime.System.enqueueJob(new Object()));
        assertThrows(UnsupportedOperationException.class,
            () -> alloyx.runtime.System.abortJob("707000000000000"));
    }

    @Test
    void currentPageReference_isNullLocally() {
        assertNull(alloyx.runtime.System.currentPageReference());
    }
}
