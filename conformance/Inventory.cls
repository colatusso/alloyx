public class Inventory {
    private Map<String, Integer> stock;

    public Inventory() {
        this.stock = new Map<String, Integer>();
    }

    public void put(String item, Integer qty) {
        stock.put(item, qty);
    }

    public Integer get(String item) {
        return stock.get(item);
    }

    public Boolean has(String item) {
        return stock.containsKey(item);
    }

    public Integer count() {
        return stock.size();
    }
}
