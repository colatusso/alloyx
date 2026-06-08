public class PrimeBenchmark {
    // Heavy CPU example: counts primes below 'limit' by trial division (O(n*sqrt(n))).
    // In a Salesforce org this blows the Apex CPU time governor limit; here it just runs.

    public static Boolean isPrime(Integer n) {
        if (n < 2) {
            return false;
        }
        Integer d = 2;
        while (d * d <= n) {
            if (Math.mod(n, d) == 0) {
                return false;
            }
            d++;
        }
        return true;
    }

    public static Integer countPrimes(Integer limit) {
        Integer count = 0;
        Integer n = 2;
        while (n < limit) {
            if (isPrime(n)) {
                count++;
            }
            n++;
        }
        return count;
    }

    public static void run() {
        // ~60s on a laptop and ~6x over the Apex 10s CPU governor limit.
        // Drop to 1000000 for a sub-second run.
        Integer limit = 100000000;
        Integer total = countPrimes(limit);
        System.debug('primes below ' + limit + ': ' + total);
    }
}
