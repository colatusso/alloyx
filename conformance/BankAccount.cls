public class BankAccount {
    private Integer balance;

    public BankAccount(Integer initial) {
        this.balance = initial;
    }

    public void withdraw(Integer amount) {
        if (amount > balance) {
            throw new InsufficientFundsException('Not enough funds');
        }
        balance = balance - amount;
    }

    public Integer getBalance() {
        return balance;
    }
}
