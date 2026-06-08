package alloyx.runtime;

/** Default gateway: no org wired up. Every org-bound call fails with a clear hint. */
public final class UnconnectedGateway implements OrgGateway {
    private static final String MSG =
        "No org connection configured: this call needs Salesforce (SOQL/DML/sObject). "
        + "Set the org via alloyx.json or the --org flag.";

    @Override
    public List<SObject> query(String soql, java.util.Map<String, Object> binds) {
        throw new OrgConnectionException(MSG);
    }

    @Override
    public void insert(List<SObject> records) {
        throw new OrgConnectionException(MSG);
    }

    @Override
    public void update(List<SObject> records) {
        throw new OrgConnectionException(MSG);
    }

    @Override
    public void delete(List<SObject> records) {
        throw new OrgConnectionException(MSG);
    }
}
