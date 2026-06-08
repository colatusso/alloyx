/**
 * In-memory warehouse — a fuller AlloyX showcase, run as an INSTANCE method:
 *
 *   allx run examples/inventory/Warehouse.cls --method Warehouse.run
 *
 * (the CLI news up Warehouse via its no-arg constructor, then calls run()).
 *
 * Exercises: instance state, a fluent builder (return this), Map/List literals,
 * the Decimal & Date runtime, exceptions (throw / try-catch / custom
 * StockException), cross-class calls, and control flow.
 */
public class Warehouse {
    private Map<String, Product> products;
    private List<String> shipped;
    private Date opened;

    public Warehouse() {
        this.products = new Map<String, Product>();
        this.shipped = new List<String>();
        this.opened = Date.valueOf('2024-01-01');
    }

    // fluent: returns this so stock(...).stock(...) chains
    public Warehouse stock(String sku, String name, Decimal price, Integer qty) {
        products.put(sku, new Product(sku, name, price, qty));
        return this;
    }

    public void ship(String sku, Integer qty) {
        Product p = products.get(sku);
        if (p == null) {
            throw new StockException('unknown sku: ' + sku);
        }
        if (p.qty < qty) {
            throw new StockException('not enough ' + sku + ' (have ' + p.qty + ', need ' + qty + ')');
        }
        p.qty = p.qty - qty;
        shipped.add(qty + 'x ' + sku);
    }

    public Decimal totalValue() {
        Decimal total = Decimal.valueOf('0');
        for (String sku : products.keySet()) {
            total = total.add(products.get(sku).lineValue());
        }
        return total.setScale(2);
    }

    public List<String> reorderList(Integer threshold) {
        List<String> low = new List<String>();
        for (String sku : products.keySet()) {
            Product p = products.get(sku);
            if (p.qty <= threshold) {
                low.add(p.sku);
            }
        }
        return low;
    }

    // instance entry point — run with: --method Warehouse.run
    public void run() {
        stock('WIDGET', 'Widget', Decimal.valueOf('9.90'), 100)
            .stock('GADGET', 'Gadget', Decimal.valueOf('24.50'), 4)
            .stock('GIZMO', 'Gizmo', Decimal.valueOf('5.00'), 0);

        Integer age = opened.daysBetween(Date.today());
        System.debug('warehouse opened ' + opened + ' (' + age + ' days ago)');
        System.debug('inventory value: ' + totalValue());

        List<String> orders = new List<String>{ 'WIDGET', 'GADGET', 'GIZMO' };
        List<Integer> qtys = new List<Integer>{ 10, 6, 1 };
        for (Integer i = 0; i < orders.size(); i++) {
            try {
                ship(orders.get(i), qtys.get(i));
                System.debug('shipped ' + qtys.get(i) + 'x ' + orders.get(i));
            } catch (StockException e) {
                System.debug('SKIP: ' + e.getMessage());
            }
        }

        System.debug('shipped lines: ' + shipped.size());
        System.debug('value after shipping: ' + totalValue());
        System.debug('reorder (qty <= 5): ' + reorderList(5));
    }
}
