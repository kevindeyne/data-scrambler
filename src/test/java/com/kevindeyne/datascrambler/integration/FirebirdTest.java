package com.kevindeyne.datascrambler.integration;

import com.kevindeyne.datascrambler.helper.SupportedDBType;
import org.jooq.SQLDialect;
import org.junit.Rule;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.utility.DockerImageName;

import org.firebirdsql.testcontainers.FirebirdContainer;

public class FirebirdTest extends AbstractDBIntegrationTest {

    @Rule
    public FirebirdContainer firebird = new FirebirdContainer(DockerImageName.parse("jacobalberty/firebird"));

    @Override
    protected JdbcDatabaseContainer getDB() {
        return firebird;
    }

    @Override
    protected SQLDialect getDialect() {
        return SQLDialect.FIREBIRD;
    }

    @Override
    protected SupportedDBType getDBType() {
        return SupportedDBType.FIREBIRD;
    }

}
