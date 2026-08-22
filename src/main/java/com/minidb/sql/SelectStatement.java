package com.minidb.sql;

import java.util.List;

/** where and limit are null when the corresponding clause is absent. */
public record SelectStatement(String table, List<String> columns, Condition where, Integer limit)
        implements Statement {

    /** Convenience for the common no-LIMIT case. */
    public SelectStatement(String table, List<String> columns, Condition where) {
        this(table, columns, where, null);
    }
}
