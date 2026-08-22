package com.minidb.sql;

import com.minidb.record.Row;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Condition (AST) -> Predicate<Row>.
 *
 * Extracted from Executor so the planner and the differential-test oracle build their
 * predicates from the same code; two implementations of "what does age > 60 mean" would
 * make a differential test compare a bug against itself.
 *
 * The schema (id, name, age) is hardcoded here the same way it is in Row — there is
 * nowhere else to put it until there is a catalog.
 */
public final class Predicates {

    private Predicates() {
    }

    public static Predicate<Row> forCondition(Condition condition) {
        return switch (condition.column().toLowerCase()) {
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
}
