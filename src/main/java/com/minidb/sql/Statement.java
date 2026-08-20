package com.minidb.sql;

public sealed interface Statement permits InsertStatement, SelectStatement {
}
