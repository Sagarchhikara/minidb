package com.minidb.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * String -> tokens. The parser never touches raw characters; this is the only
 * place that does.
 */
public class Lexer {
    private static final Map<String, TokenType> KEYWORDS = Map.of(
            "SELECT", TokenType.SELECT,
            "INSERT", TokenType.INSERT,
            "INTO", TokenType.INTO,
            "VALUES", TokenType.VALUES,
            "FROM", TokenType.FROM,
            "WHERE", TokenType.WHERE
    );

    private Lexer() {
    }

    public static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = sql.length();

        while (i < n) {
            char c = sql.charAt(i);
            int start = i;

            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            if (Character.isLetter(c) || c == '_') {
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
                    i++;
                }
                String word = sql.substring(start, i);
                // Keywords are case-insensitive; identifiers keep their original text.
                TokenType keyword = KEYWORDS.get(word.toUpperCase(Locale.ROOT));
                tokens.add(new Token(keyword != null ? keyword : TokenType.IDENTIFIER, word, start));
                continue;
            }

            if (Character.isDigit(c)) {
                while (i < n && Character.isDigit(sql.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(TokenType.NUMBER, sql.substring(start, i), start));
                continue;
            }

            if (c == '\'') {
                i++;
                int contentStart = i;
                while (i < n && sql.charAt(i) != '\'') {
                    i++;
                }
                if (i >= n) {
                    throw new ParseException("unterminated string literal", start);
                }
                // Contents are never uppercased or otherwise normalized - 'Sagar' != 'sagar'.
                String content = sql.substring(contentStart, i);
                i++; // closing quote
                tokens.add(new Token(TokenType.STRING, content, start));
                continue;
            }

            switch (c) {
                case '*' -> { tokens.add(new Token(TokenType.STAR, "*", start)); i++; }
                case ',' -> { tokens.add(new Token(TokenType.COMMA, ",", start)); i++; }
                case '(' -> { tokens.add(new Token(TokenType.LPAREN, "(", start)); i++; }
                case ')' -> { tokens.add(new Token(TokenType.RPAREN, ")", start)); i++; }
                case '=' -> { tokens.add(new Token(TokenType.EQ, "=", start)); i++; }
                case '<' -> {
                    if (i + 1 < n && sql.charAt(i + 1) == '=') {
                        tokens.add(new Token(TokenType.LTE, "<=", start));
                        i += 2;
                    } else {
                        tokens.add(new Token(TokenType.LT, "<", start));
                        i++;
                    }
                }
                case '>' -> {
                    if (i + 1 < n && sql.charAt(i + 1) == '=') {
                        tokens.add(new Token(TokenType.GTE, ">=", start));
                        i += 2;
                    } else {
                        tokens.add(new Token(TokenType.GT, ">", start));
                        i++;
                    }
                }
                case '!' -> {
                    if (i + 1 < n && sql.charAt(i + 1) == '=') {
                        tokens.add(new Token(TokenType.NEQ, "!=", start));
                        i += 2;
                    } else {
                        throw new ParseException("unexpected character '!'", start);
                    }
                }
                default -> throw new ParseException("unexpected character '" + c + "'", start);
            }
        }

        tokens.add(new Token(TokenType.EOF, "", n));
        return tokens;
    }
}
