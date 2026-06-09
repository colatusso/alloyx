package alloyx.runtime;

/**
 * Apex trigger context (`Trigger.new`, `Trigger.oldMap`, `Trigger.isInsert`, ...).
 * Recognized so trigger-handler code type-checks. There is no trigger running
 * locally, so the context fields are simply null/false — honest: the type is right,
 * and code that reads them outside a trigger gets the empty context, like Apex.
 *
 * `Trigger.new` is a Java keyword, so it is exposed as `newRecords` and the
 * transpiler maps `Trigger.new` onto it.
 */
public final class Trigger {
    private Trigger() {}

    public static List<SObject> newRecords;
    public static List<SObject> old;
    public static Map<String, SObject> newMap;
    public static Map<String, SObject> oldMap;
    public static Integer size;
    public static Boolean isExecuting;
    public static Boolean isInsert;
    public static Boolean isUpdate;
    public static Boolean isDelete;
    public static Boolean isUndelete;
    public static Boolean isBefore;
    public static Boolean isAfter;
}
