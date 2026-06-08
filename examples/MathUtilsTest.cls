@isTest
private class MathUtilsTest {
    @isTest
    static void testFactorial() {
        System.assertEquals(120, MathUtils.factorial(5));
        System.assertEquals(1, MathUtils.factorial(0));
        System.assertEquals(1, MathUtils.factorial(1));
    }

    @isTest
    static void testIsPositive() {
        System.assert(MathUtils.isPositive(3));
        System.assertEquals(false, MathUtils.isPositive(-2));
    }
}
