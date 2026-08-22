package com.minidb.sql;

import com.minidb.plan.Operator;
import com.minidb.plan.Planner;
import com.minidb.record.Row;
import com.minidb.table.Table;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * AST -> plan -> rows.
 *
 * As of Stage 8 this holds no query strategy of its own: the hardcoded
 * "if (WHERE id = X) use the index else scan" reflex moved into Planner, where the
 * choice is an object that can be inspected, forced, and benchmarked. What is left
 * here is statement dispatch and draining the iterator tree.
 */
public class Executor {

    private static final String TABLE_NAME = "users";

    private final Table table;
    private final Planner planner;

    public Executor(Table table) {
        this.table = table;
        this.planner = new Planner(table);
    }

    public Planner getPlanner() {
        return planner;
    }

    public List<Row> execute(Statement stmt) throws IOException {
        if (stmt instanceof InsertStatement ins) {
            checkTable(ins.table());
            table.insert(rowFrom(ins.values()));
            return List.of();
        }
        if (stmt instanceof SelectStatement sel) {
            checkTable(sel.table());
            return drain(planner.plan(sel));
        }
        throw new IllegalStateException("unhandled statement type: " + stmt);
    }

    /** Runs a plan to completion. close() in a finally, so an exception mid-scan still unpins. */
    public static List<Row> drain(Operator plan) throws IOException {
        List<Row> rows = new ArrayList<>();
        plan.open();
        try {
            Row row;
            while ((row = plan.next()) != null) {
                rows.add(row);
            }
        } finally {
            plan.close();
        }
        return rows;
    }

    /** Returns the plan EXPLAIN text for a SELECT, without running it. */
    public String explain(SelectStatement sel) {
        checkTable(sel.table());
        return Planner.explain(planner.plan(sel));
    }

    /** Formats a row for display, projecting down to the requested columns. */
    public static String project(Row row, List<String> columns) {
        if (columns.size() == 1 && columns.get(0).equals("*")) {
            return row.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(columns.get(i)).append('=').append(fieldValue(row, columns.get(i)));
        }
        return sb.toString();
    }

    private void checkTable(String name) {
        if (!TABLE_NAME.equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("unknown table '" + name + "', only '" + TABLE_NAME + "' exists");
        }
    }

    private Row rowFrom(List<Object> values) {
        if (values.size() != 3) {
            throw new IllegalArgumentException("expected 3 values (id, name, age) but got " + values.size());
        }
        if (!(values.get(0) instanceof Integer id)) {
            throw new IllegalArgumentException("id must be a number, got " + values.get(0));
        }
        if (!(values.get(1) instanceof String name)) {
            throw new IllegalArgumentException("name must be a string, got " + values.get(1));
        }
        if (!(values.get(2) instanceof Integer age)) {
            throw new IllegalArgumentException("age must be a number, got " + values.get(2));
        }
        return new Row(id, name, age);
    }

    private static Object fieldValue(Row row, String column) {
        return switch (column.toLowerCase()) {
            case "id" -> row.getId();
            case "name" -> row.getName();
            case "age" -> row.getAge();
            default -> throw new IllegalArgumentException("unknown column '" + column + "'");
        };
    }
}
