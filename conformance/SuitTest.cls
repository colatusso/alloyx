@isTest
private class SuitTest {
    @isTest
    static void testEnum() {
        Suit s = Suit.HEARTS;
        System.assertEquals(Suit.HEARTS, s);
        System.assert(s == Suit.HEARTS);
        System.assert(Suit.HEARTS != Suit.SPADES);
    }
}
