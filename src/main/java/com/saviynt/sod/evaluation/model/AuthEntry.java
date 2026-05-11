package com.saviynt.sod.evaluation.model;

/**
 * A single auth object/field/value entry on a SAP role.
 * Loaded from ENTITLEMENT_OBJECTS table.
 */
public record AuthEntry(
        long roleKey,       // ENTITLEMENT_VALUEKEY (the role that has this auth)
        long objectKey,     // OBJECTKEY
        long fieldKey,      // FIELD_KEY
        String minValue,    // MINVALUE
        String maxValue     // MXVALUE
) {}
