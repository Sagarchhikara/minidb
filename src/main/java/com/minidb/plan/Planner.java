package com.minidb.plan;

import com.minidb.record.Row;
import com.minidb.sql.Condition;
import com.minidb.sql.Predicates;
import com.minidb.sql.SelectStatement;
import com.minidb.sql.TokenType;
import com.minidb.table.Table;

import java.util.function.Predicate;

/**
 * AST -> physical plan.
 *
 * Rule-based, with exactly one rule: Filter(id = literal) over SeqScan collapses to an
 * IndexSeek. A cost model would need table statistics that nothing collects yet, so the
 * costs operators report are for EXPLAIN's benefit rather than for choosing between plans.
 *
 * useIndexes exists so the bad plan can be forced on purpose: the indexed-vs-scan
 * comparison is only meaningful if both plans can be built for the same query.
 */
public class Planner {

    private final Table table;
    private boolean useIndexes = true;

    public Planner(Table table) {
        this.table = table;
    }

    public Planner useIndexes(boolean enabled) {
        this.useIndexes = enabled;
        return this;
    }

    public boolean isUsingIndexes() {
        return useIndexes;
    }

    public Operator plan(SelectStatement select) {
        // 1. Logical shape: Scan -> Filter? -> Limit?
        Operator op = null;
        Condition where = select.where();

        // 2. The one rewrite rule, applied while the scan is still being chosen.
        if (useIndexes && isIndexableEquality(where)) {
            op = new IndexSeek(table.getBufferPool(), table.getIndex(), (Integer) where.literal());
            where = null; // the seek subsumes the predicate entirely
        } else {
            op = new SeqScan(table.getBufferPool());
        }

        if (where != null) {
            op = new Filter(op, Predicates.forCondition(where), describe(where));
        }
        if (select.limit() != null) {
            op = new Limit(op, select.limit());
        }
        return op;
    }

    /**
     * The rule's precondition: an equality on the indexed column against an int literal.
     * Only `id` is indexed, and only EQ is answerable by a point seek — a range would
     * need leaf-chain traversal that IndexSeek does not do.
     */
    private boolean isIndexableEquality(Condition c) {
        return c != null
                && c.column().equalsIgnoreCase("id")
                && c.op() == TokenType.EQ
                && c.literal() instanceof Integer;
    }

    private static String describe(Condition c) {
        Object lit = c.literal();
        String rendered = lit instanceof String s ? "'" + s + "'" : String.valueOf(lit);
        return c.column() + " " + symbol(c.op()) + " " + rendered;
    }

    private static String symbol(TokenType op) {
        return switch (op) {
            case EQ -> "=";
            case NEQ -> "!=";
            case LT -> "<";
            case GT -> ">";
            case LTE -> "<=";
            case GTE -> ">=";
            default -> op.toString();
        };
    }

    /** Recursive tree printer, root first, two spaces per level. */
    public static String explain(Operator root) {
        StringBuilder sb = new StringBuilder();
        explain(root, 0, sb);
        return sb.toString();
    }

    private static void explain(Operator op, int depth, StringBuilder sb) {
        sb.append("  ".repeat(depth))
                .append("-> ")
                .append(op.describe())
                .append("  (cost=")
                .append(String.format("%.1f", op.estimatedCost()))
                .append(")\n");
        for (Operator child : op.children()) {
            explain(child, depth + 1, sb);
        }
    }

    /** True if the plan tree contains a node of the given type. Used by plan-shape tests. */
    public static boolean containsNode(Operator root, Class<? extends Operator> type) {
        if (type.isInstance(root)) {
            return true;
        }
        for (Operator child : root.children()) {
            if (containsNode(child, type)) {
                return true;
            }
        }
        return false;
    }
}
