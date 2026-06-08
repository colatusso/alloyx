public class AccountServiceDemo {
    public static void run() {
        Integer total = AccountService.countByName('Acme');
        System.debug('Accounts named Acme: ' + total);
    }
}
