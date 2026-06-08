public class AccountDemo {
    public static void run() {
        List<Account> accs = [SELECT Id, Name FROM Account LIMIT 5];
        System.debug('Found ' + accs.size() + ' accounts');
    }
}
