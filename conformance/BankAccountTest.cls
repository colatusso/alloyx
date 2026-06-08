@isTest
private class BankAccountTest {
    @isTest
    static void testWithdrawOk() {
        BankAccount acc = new BankAccount(100);
        acc.withdraw(30);
        System.assertEquals(70, acc.getBalance());
    }

    @isTest
    static void testWithdrawThrows() {
        BankAccount acc = new BankAccount(50);
        Boolean caught = false;
        try {
            acc.withdraw(100);
        } catch (InsufficientFundsException e) {
            caught = true;
        } finally {
            acc.getBalance();
        }
        System.assert(caught);
    }
}
