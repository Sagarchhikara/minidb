package com.minidb.sql;

import com.minidb.index.Rid;
import com.minidb.record.Row;
import com.minidb.record.RowSerializer;
import com.minidb.storage.Page;
import com.minidb.table.Table;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Thin tree-walk from AST to the Table calls Stage 4 already proved. Not a
 * planner - a switch on statement type that reuses insert/scan/scanWhere
 * directly, with an index-accelerated fast path for WHERE id = X. The schema
 * (id, name, age) is hardcoded here the same way it is in Row; there is nowhere
 * else to put it until there is a catalog.
 */
public class Executor {
    private static final String TABLE_NAME = "users";

    private final Table table;

    public Executor(Table table) {
        this.table = table;
    }

    public List<Row> execute(Statement stmt) throws IOException {
        if (stmt instanceof InsertStatement ins) {
            checkTable(ins.table());
            table.insert(rowFrom(ins.values()));
            return List.of();
        }
        if (stmt instanceof SelectStatement sel) {
            checkTable(sel.table());
            if (sel.where() != null
                    && sel.where().column().equalsIgnoreCase("id")
                    && sel.where().op() == TokenType.EQ
                    && sel.where().literal() instanceof Integer targetId) {
                Rid rid = table.getIndex().search(targetId);
                if (rid == null) {
                    return List.of();
                }
                Page page = table.getDisk().readPage(rid.pageNum());
                Row row = RowSerializer.deserialize(page.getData(), rid.offset());
                return List.of(row);
            }
            return sel.where() == null ? table.scan() : table.scanWhere(predicateFrom(sel.where()));
        }
        throw new IllegalStateException("unhandled statement type: " + stmt);
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

    private Predicate<Row> predicateFrom(Condition condition) {
        String column = condition.column().toLowerCase();
        return switch (column) {
            case "id" -> intPredicate(Row::getId, condition.op(), requireInt(condition));
            case "age" -> intPredicate(Row::getAge, condition.op(), requireInt(condition));
            case "name" -> stringPredicate(Row::getName, condition.op(), requireString(condition));
            default -> throw new IllegalArgumentException("unknown column '" + condition.column() + "'");
        };
    }

    private static int requireInt(Condition condition) {
        if (condition.literal() instanceof Integer n) {
            return n;
        }
        throw new IllegalArgumentException(
                "column '" + condition.column() + "' compares to a number, got " + condition.literal());
    }

    private static String requireString(Condition condition) {
        if (condition.literal() instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(
                "column '" + condition.column() + "' compares to a string, got " + condition.literal());
    }

    private static Predicate<Row> intPredicate(ToIntFunction<Row> field, TokenType op, int value) {
        return switch (op) {
            case EQ -> r -> field.applyAsInt(r) == value;
            case NEQ -> r -> field.applyAsInt(r) != value;
            case LT -> r -> field.applyAsInt(r) < value;
            case GT -> r -> field.applyAsInt(r) > value;
            case LTE -> r -> field.applyAsInt(r) <= value;
            case GTE -> r -> field.applyAsInt(r) >= value;
            default -> throw new IllegalArgumentException("operator " + op + " is not valid for a numeric column");
        };
    }

    private static Predicate<Row> stringPredicate(Function<Row, String> field, TokenType op, String value) {
        return switch (op) {
            case EQ -> r -> field.apply(r).equals(value);
            case NEQ -> r -> !field.apply(r).equals(value);
            default -> throw new IllegalArgumentException(
                    "operator " + op + " is not valid for a text column (only = and != are)");
        };
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
