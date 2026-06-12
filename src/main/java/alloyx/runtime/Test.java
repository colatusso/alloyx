// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex's Test namespace. Recognized so production code that guards on the test context
 * (Test.isRunningTest()) type-checks and runs. There is no Apex test engine locally, so
 * isRunningTest() is honestly false; the mocking, fixture and governor entry points aren't
 * modeled yet and fail clearly if called.
 */
public final class Test {
    private Test() {
    }

    /** No Apex unit test runs locally, so this is honestly false. */
    public static boolean isRunningTest() {
        return false;
    }

    public static void startTest() {
        throw Unsupported.notLocal("Test.startTest()");
    }

    public static void stopTest() {
        throw Unsupported.notLocal("Test.stopTest()");
    }

    public static void setMock(Object apiType, Object impl) {
        throw Unsupported.notLocal("Test.setMock()");
    }

    public static void setFixedSearchResults(Object ids) {
        throw Unsupported.notLocal("Test.setFixedSearchResults()");
    }

    public static Object loadData(Object sObjectType, String resourceName) {
        throw Unsupported.notLocal("Test.loadData()");
    }

    public static Object createStub(Object stubbedType, Object stubProvider) {
        throw Unsupported.notLocal("Test.createStub()");
    }

    /**
     * Apex {@code Test.getStandardPricebookId()}.
     *
     * <p>DEGRADATION: there is no org locally, so there's no real standard Pricebook2 record to read
     * an Id from. Returns a STABLE, clearly-synthetic 18-char Id so fixture code that needs a standard
     * pricebook Id (to set on a line item, to compare, to store) can proceed deterministically. The
     * value is a fake — the {@code 01s} key prefix is Pricebook2's, the rest is an obvious literal
     * body (no real org would mint it) — not a lookup; do not treat it as addressing a live record.
     */
    public static String getStandardPricebookId() {
        // 18 chars: 01s (Pricebook2 key prefix) + a deterministic, obviously-synthetic body.
        return "01s000000000000AAA";
    }

    /**
     * Apex {@code Test.setCurrentPage(PageReference)}: seed the Visualforce page context a test runs
     * under. Wired to the same process-static state {@link ApexPages#currentPage()} reads, so a
     * controller that calls {@code ApexPages.currentPage()} sees exactly the page set here. Pure
     * local state (no org), so it runs for real.
     */
    public static void setCurrentPage(PageReference page) {
        ApexPages.setCurrentPage(page);
    }

    /**
     * Apex {@code Test.setCreatedDate(Id, Datetime)}: override a record's CreatedDate in a test.
     *
     * <p>DEGRADATION: CreatedDate is an audit field the platform stamps and only a test context with
     * a live org transaction can rewrite — there's no record store locally to mutate — so this fails
     * clearly rather than silently no-op'ing (which would hide that the override didn't take).
     */
    public static void setCreatedDate(String recordId, Datetime createdDatetime) {
        throw Unsupported.notLocal("Test.setCreatedDate()");
    }
}
