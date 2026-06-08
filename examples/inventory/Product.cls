/**
 * A product line in the warehouse. Plain value class: fields + constructor.
 */
public class Product {
    public String sku;
    public String name;
    public Decimal price;
    public Integer qty;

    public Product(String sku, String name, Decimal price, Integer qty) {
        this.sku = sku;
        this.name = name;
        this.price = price;
        this.qty = qty;
    }

    // line value = price * qty (Decimal arithmetic via methods, not the + operator)
    public Decimal lineValue() {
        return price.multiply(Decimal.valueOf(qty));
    }
}
