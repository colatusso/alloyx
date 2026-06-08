public class Counter {
    private Integer count;

    public Counter(Integer start) {
        this.count = start;
    }

    public void increment() {
        count = count + 1;
    }

    public void add(Integer n) {
        this.count = this.count + n;
    }

    public Integer getCount() {
        return count;
    }
}
