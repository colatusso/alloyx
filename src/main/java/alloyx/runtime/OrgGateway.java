// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * The middleware contract. Everything that needs the org (SOQL/DML) goes through
 * a gateway. The default is {@link UnconnectedGateway}; the real one is
 * {@link SalesforceGateway}; tests inject a fake. All classes in a run share one
 * gateway, so they all hit the same org.
 */
public interface OrgGateway {
    List<SObject> query(String soql, java.util.Map<String, Object> binds);

    void insert(List<SObject> records);

    void update(List<SObject> records);

    void delete(List<SObject> records);

    /** Local approximation: real upsert splits by Id into insert/update. */
    default void upsert(List<SObject> records) {
        update(records);
    }

    /** Field/relationship API name -> Apex type, for an sObject. Throws if no org. */
    default java.util.Map<String, String> describe(String sobjectType) {
        throw new OrgConnectionException("schema describe needs an org — run with --org <alias>");
    }

    /** Every real sObject API name in the org (global describe). Throws if no org. */
    default java.util.Set<String> globalSObjects() {
        throw new OrgConnectionException("global describe needs an org — run with --org <alias>");
    }
}
