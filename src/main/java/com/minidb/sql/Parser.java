package com.minidb.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser over a token list. Two statements only: INSERT and
 * a SELECT with at most one WHERE comparison.
 */
public class Parser {
    private final List<Token> tokens;
    private int i = 0;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Statement parse(String sql) {
        return new Parser(Lexer.tokenize(sql)).parseStatement();
    }

    private Token peek() {
        return tokens.get(i);
    }

    private Token advance() {
        return tokens.get(i++);
    }

    private Token expect(TokenType type) {
        Token t = peek();
        if (t.type() != type) {
            throw new ParseException("expected " + type + " but got '" + t.text() + "'", t.pos());
        }
        return advance();
    }

    private Statement parseStatement() {
        Statement s = switch (peek().type()) {
            case SELECT -> parseSelect();
            case INSERT -> parseInsert();
            default -> throw new ParseException(
                    "expected SELECT or INSERT but got '" + peek().text() + "'", peek().pos());
        };
        expect(TokenType.EOF); // reject trailing garbage after a valid statement
        return s;
    }

    private Statement parseInsert() {
        expect(TokenType.INSERT);
        expect(TokenType.INTO);
        String table = expect(TokenType.IDENTIFIER).text();
        expect(TokenType.VALUES);
        expect(TokenType.LPAREN);

        List<Object> values = new ArrayList<>();
        values.add(parseLiteral());
        while (peek().type() == TokenType.COMMA) {
            advance();
            values.add(parseLiteral());
        }
        expect(TokenType.RPAREN);

        return new InsertStatement(table, values);
    }

    private Statement parseSelect() {
        expect(TokenType.SELECT);

        List<String> columns = new ArrayList<>();
        if (peek().type() == TokenType.STAR) {
            advance();
            columns.add("*");
        } else {
            columns.add(expect(TokenType.IDENTIFIER).text());
            while (peek().type() == TokenType.COMMA) {
                advance();
                columns.add(expect(TokenType.IDENTIFIER).text());
            }
        }

        expect(TokenType.FROM);
        String table = expect(TokenType.IDENTIFIER).text();

        Condition where = null;
        if (peek().type() == TokenType.WHERE) {
            advance();
            where = parseCondition();
        }

        Integer limit = null;
        if (peek().type() == TokenType.LIMIT) {
            Token limitToken = advance();
            Object n = parseLiteral();
            if (!(n instanceof Integer count)) {
                throw new ParseException("LIMIT expects a number but got '" + n + "'", limitToken.pos());
            }
            if (count < 0) {
                throw new ParseException("LIMIT must not be negative but got " + count, limitToken.pos());
            }
            limit = count;
        }

        return new SelectStatement(table, columns, where, limit);
    }

    private Condition parseCondition() {
        String column = expect(TokenType.IDENTIFIER).text();

        Token opToken = advance();
        TokenType op = opToken.type();
        if (op != TokenType.EQ && op != TokenType.LT && op != TokenType.GT
                && op != TokenType.LTE && op != TokenType.GTE && op != TokenType.NEQ) {
            throw new ParseException(
                    "expected a comparison operator but got '" + opToken.text() + "'", opToken.pos());
        }

        Object literal = parseLiteral();
        return new Condition(column, op, literal);
    }

    private Object parseLiteral() {
        Token t = peek();
        if (t.type() == TokenType.NUMBER) {
            advance();
            try {
                return Integer.parseInt(t.text());
            } catch (NumberFormatException e) {
                throw new ParseException("number '" + t.text() + "' does not fit in a 32-bit int", t.pos());
            }
        }
        if (t.type() == TokenType.STRING) {
            advance();
            return t.text();
        }
        throw new ParseException("expected a literal but got '" + t.text() + "'", t.pos());
    }
}
