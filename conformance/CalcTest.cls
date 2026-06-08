@isTest
private class CalcTest {
    @isTest
    static void testSumTo() {
        System.assertEquals(15, Calc.sumTo(5));
        System.assertEquals(0, Calc.sumTo(0));
    }

    @isTest
    static void testSumList() {
        List<Integer> xs = new List<Integer>{ 10, 20, 30 };
        System.assertEquals(60, Calc.sumList(xs));
    }

    @isTest
    static void testTernary() {
        System.assertEquals('non-negative', Calc.classify(3));
        System.assertEquals('negative', Calc.classify(-1));
    }

    @isTest
    static void testFirstEven() {
        System.assertEquals(4, Calc.firstEven(new List<Integer>{ 1, 3, 4, 6 }));
    }
}
