package com.minidb.sql;

import java.util.List;

public record InsertStatement(String table, List<Object> values) implements Statement {
}
