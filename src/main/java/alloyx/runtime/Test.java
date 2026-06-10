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
}
