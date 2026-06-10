// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/** Raised when org-bound code runs without a configured gateway. */
public class OrgConnectionException extends RuntimeException {
    public OrgConnectionException(String message) {
        super(message);
    }
}
