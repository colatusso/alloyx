public class MathUtils {
    public static Integer factorial(Integer n) {
        Integer result = 1;
        Integer i = 2;
        while (i <= n) {
            result = result * i;
            i = i + 1;
        }
        return result;
    }

    public static Boolean isPositive(Integer n) {
        if (n > 0) {
            return true;
        } else {
            return false;
        }
    }
}
