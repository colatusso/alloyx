package alloyx.runtime;

/**
 * Apex {@code Schema.DescribeSObjectResult} — describe metadata for one sObject.
 * The API name is carried directly; field metadata is pulled from the SAME real
 * org describe the typer already uses ({@link OrgGateway#describe}). Nothing here
 * is hardcoded — it reflects the connected org.
 */
public final class DescribeSObjectResult {
    private final String name;

    public DescribeSObjectResult(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String getLocalName() {
        return name;
    }

    /** Field/relationship API name -> Apex type, from the live/cached org describe. */
    public java.util.Map<String, String> getFieldsMap() {
        return Database.gateway().describe(name);
    }
}
