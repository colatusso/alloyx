public class AccountService {
    public static Integer countByName(String name) {
        List<Account> accs = [SELECT Id, Name FROM Account WHERE Name = :name];
        return accs.size();
    }

    public static Account build(String name) {
        Account a = new Account(Name = name, Industry = 'Tech');
        return a;
    }

    public static void touch(Account a) {
        insert a;
    }
}
