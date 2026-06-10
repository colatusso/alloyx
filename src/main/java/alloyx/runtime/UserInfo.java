// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Rafael Colatusso
package alloyx.runtime;

/**
 * Apex's {@code UserInfo} namespace.
 *
 * <p><b>LOCAL STUBS — NOT REAL ORG DATA.</b> AlloyX runs locally with no
 * logged-in Salesforce user and no connected org, so every method here returns a
 * fixed placeholder value. The Ids are syntactically valid 18-char Salesforce Ids
 * (correct key prefix per object type) but reference nothing real. Do not rely on
 * these values for anything beyond keeping transpiled Apex compiling and running.
 *
 * <p>Named exactly "UserInfo" so transpiled code reads like Apex
 * ({@code UserInfo.getUserId()} verbatim). All methods are {@code public static}.
 */
public class UserInfo {

    /** Local stub: fake 18-char User Id (key prefix 005). Not a real user. */
    public static String getUserId() {
        return "005000000000000AAA";
    }

    /** Local stub username. */
    public static String getUserName() {
        return "local.user@alloyx.dev";
    }

    /** Local stub full name. */
    public static String getName() {
        return "Local User";
    }

    /** Local stub first name. */
    public static String getFirstName() {
        return "Local";
    }

    /** Local stub last name. */
    public static String getLastName() {
        return "User";
    }

    /** Local stub email. */
    public static String getUserEmail() {
        return "local.user@alloyx.dev";
    }

    /** Local stub: fake 18-char Organization Id (key prefix 00D). */
    public static String getOrganizationId() {
        return "00D000000000000EAA";
    }

    /** Local stub organization name. */
    public static String getOrganizationName() {
        return "AlloyX Local";
    }

    /** Local stub: fake 18-char Profile Id (key prefix 00e). */
    public static String getProfileId() {
        return "00e000000000000AAA";
    }

    /** Local stub locale (e.g. used by Apex for formatting). */
    public static String getLocale() {
        return "en_US";
    }

    /** Local stub language. */
    public static String getLanguage() {
        return "en_US";
    }

    /** Local stub user type. */
    public static String getUserType() {
        return "Standard";
    }

    /**
     * Local stub session Id. No session exists locally, so this is empty rather
     * than a fake token (matching Apex behavior when there is no active session).
     */
    public static String getSessionId() {
        return "";
    }

    /** Local stub: the local "org" is single-currency. */
    public static Boolean isMultiCurrencyOrganization() {
        return false;
    }
}
