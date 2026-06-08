public class Calc {
    public static Integer sumTo(Integer n) {
        Integer total = 0;
        for (Integer i = 1; i <= n; i++) {
            total += i;
        }
        return total;
    }

    public static Integer sumList(List<Integer> xs) {
        Integer total = 0;
        for (Integer x : xs) {
            total += x;
        }
        return total;
    }

    public static String classify(Integer n) {
        return n >= 0 ? 'non-negative' : 'negative';
    }

    public static Integer firstEven(List<Integer> xs) {
        for (Integer x : xs) {
            if (Math.mod(x, 2) == 0) {
                return x;
            }
        }
        return -1;
    }
}
