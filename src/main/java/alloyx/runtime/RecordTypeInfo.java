// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex {@code Schema.RecordTypeInfo} — one record type of an sObject's describe.
 *
 * <p>Record-type metadata (ids, names, labels, availability per profile) is ORG metadata that the
 * synced schema does not store. So an entry's accessors degrade clearly ({@link Unsupported#notLocal})
 * rather than fabricating a record-type id. The type surface is recognized so a
 * {@code Schema.RecordTypeInfo} variable and {@code getRecordTypeId()}/{@code getName()} reads
 * type-check instead of collapsing onto the dynamic SObject.
 */
public final class RecordTypeInfo {
    private RecordTypeInfo() {
    }

    public String getRecordTypeId() {
        throw Unsupported.notLocal("Schema.RecordTypeInfo.getRecordTypeId()");
    }

    public String getName() {
        throw Unsupported.notLocal("Schema.RecordTypeInfo.getName()");
    }

    public String getDeveloperName() {
        throw Unsupported.notLocal("Schema.RecordTypeInfo.getDeveloperName()");
    }

    public boolean isActive() {
        throw Unsupported.notLocal("Schema.RecordTypeInfo.isActive()");
    }

    public boolean isAvailable() {
        throw Unsupported.notLocal("Schema.RecordTypeInfo.isAvailable()");
    }

    public boolean isDefaultRecordTypeMapping() {
        throw Unsupported.notLocal("Schema.RecordTypeInfo.isDefaultRecordTypeMapping()");
    }

    public boolean isMaster() {
        throw Unsupported.notLocal("Schema.RecordTypeInfo.isMaster()");
    }
}
