package com.minidb.sql;

import java.util.List;

/** where is null when there is no WHERE clause. */
public record SelectStatement(String table, List<String> columns, Condition where) implements Statement {
}
