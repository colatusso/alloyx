@isTest
private class CounterTest {
    @isTest
    static void testCounter() {
        Counter c = new Counter(10);
        c.increment();
        c.add(5);
        System.assertEquals(16, c.getCount());
    }
}
