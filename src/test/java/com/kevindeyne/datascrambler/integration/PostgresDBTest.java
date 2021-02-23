package com.kevindeyne.datascrambler.integration;

import com.kevindeyne.datascrambler.exceptions.ConnectionFailureException;
import com.kevindeyne.datascrambler.helper.SupportedDBType;
import org.jooq.SQLDialect;
import org.junit.Rule;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;

public class PostgresDBTest extends AbstractDBIntegrationTest {

    @Rule
    public PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres"));

    @Override
    protected JdbcDatabaseContainer getDB() {
        return postgres;
    }

    @Override
    protected SQLDialect getDialect() {
        return SQLDialect.POSTGRES;
    }

    @Override
    protected SupportedDBType getDBType() {
        return SupportedDBType.POSTGRESQL;
    }

    @Override
    public void testGeneration() throws ConnectionFailureException, SQLException {
        super.testGeneration();
    }
}
