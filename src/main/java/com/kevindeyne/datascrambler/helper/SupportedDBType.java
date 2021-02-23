package com.kevindeyne.datascrambler.helper;

import org.jooq.SQLDialect;

import java.util.ArrayList;
import java.util.List;

public enum SupportedDBType {

    MYSQL("jdbc:mysql://host:port/dbname", SQLDialect.MYSQL),
    POSTGRESQL("jdbc:postgresql://host:port/dbname", SQLDialect.POSTGRES),
    MARIADB("jdbc:mariadb://host:port/dbname", SQLDialect.MARIADB),
    FIREBIRD("jdbc:firebirdsql://host:port/dbname", SQLDialect.FIREBIRD);

    private String placeholder;
    private SQLDialect sqlDialect;

    SupportedDBType(String placeholder, SQLDialect sqlDialect) {
        this.placeholder = placeholder;
        this.sqlDialect = sqlDialect;
    }

    public static List<String> all() {
        List<String> all = new ArrayList<>();
        for (SupportedDBType value : values()) all.add(value.name());
        return all;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public SQLDialect getSQLDialect() {
        return sqlDialect;
    }
}
