package com.jobfinder.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Per-request record of executed statements, exposed to the browser as the X-SQL-Log header. */
public final class SqlLog {

    private static final ThreadLocal<List<String>> ENTRIES = ThreadLocal.withInitial(ArrayList::new);
    private static final Pattern STATEMENT =
            Pattern.compile("^(select|insert\\s+into|update|delete\\s+from)\\s+(\\w+)");
    private static final Pattern WHERE = Pattern.compile("where\\s+([\\w.]+)\\s*=");
    private static final Pattern SET = Pattern.compile("set\\s+([\\w.]+)\\s*=");

    private SqlLog() {
    }

    public static void reset() {
        ENTRIES.remove();
    }

    public static List<String> entries() {
        return ENTRIES.get();
    }

    static void record(String query, Object[] params) {
        ENTRIES.get().add(summarize(query, params));
    }

    static String summarize(String query, Object[] params) {
        String flat = String.join(" ", query.strip().split("\\s+")).toLowerCase(Locale.ROOT);
        Matcher statement = STATEMENT.matcher(flat);
        if (!statement.find()) {
            String stripped = query.strip();
            return stripped.substring(0, Math.min(80, stripped.length()));
        }
        String operation = statement.group(1).split("\\s+")[0].toUpperCase(Locale.ROOT);
        String table = statement.group(2);
        boolean update = operation.equals("UPDATE");
        Matcher clause = (update ? SET : WHERE).matcher(flat);
        String hint = clause.find() ? (update ? " SET " : " WHERE ") + clause.group(1) : "";
        String paramHint = "";
        if (params.length > 0) {
            String first = String.valueOf(params[0]);
            if (first.length() <= 40) {
                paramHint = "  →  " + first;
            }
        }
        return operation + " " + table + hint + paramHint;
    }
}
