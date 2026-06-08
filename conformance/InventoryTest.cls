@isTest
private class InventoryTest {
    @isTest
    static void testInventory() {
        Inventory inv = new Inventory();
        inv.put('apple', 3);
        inv.put('pear', 5);
        System.assertEquals(3, inv.get('apple'));
        System.assert(inv.has('pear'));
        System.assertEquals(2, inv.count());
        System.assertEquals(null, inv.get('missing'));
    }

    @isTest
    static void testSet() {
        Set<Integer> s = new Set<Integer>();
        s.add(1);
        s.add(1);
        s.add(2);
        System.assertEquals(2, s.size());
        System.assert(s.contains(2));
    }
}
