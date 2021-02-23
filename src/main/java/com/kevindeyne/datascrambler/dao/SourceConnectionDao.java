package com.kevindeyne.datascrambler.dao;

import com.kevindeyne.datascrambler.domain.distributionmodel.Generator;
import com.kevindeyne.datascrambler.domain.distributionmodel.ValueDistribution;
import com.kevindeyne.datascrambler.exceptions.ModelCreationException;
import com.kevindeyne.datascrambler.mapping.ColumnTypeMapping;
import com.kevindeyne.datascrambler.mapping.DataTypeMapping;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Data;
import org.jooq.*;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.SQLDataType;

import javax.sql.DataSource;
import java.sql.Statement;
import java.sql.*;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.*;

@Data
public class SourceConnectionDao {

    private final String url;
    private final String username;
    private final String password;
    private final SQLDialect sqlDialect;

    public SourceConnectionDao(String url, String username, String password, SQLDialect sqlDialect) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.sqlDialect = sqlDialect;
    }

    public boolean testConnection() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            DSLContext dsl = using(new DefaultConfiguration().derive(connection));
            Short[] result = dsl.selectOne().fetch().intoArray(0, Short.class);
            return result.length == 1 && result[0] == 1;
        }
    }

    public List<Table<?>> getAllTables(DataSource dataSource, String schemaName) {
        DSLContext dsl = using(new DefaultConfiguration().derive(dataSource));
        dsl.settings().setExecuteLogging(false);
        final Optional<Schema> optionalSchema = dsl.meta().getSchemas().stream()
                .filter(s -> s.getName().equals(schemaName))
                .findFirst();
        if (!optionalSchema.isPresent()) {
            Optional<Schema> suggestion = dsl.meta().getSchemas().stream().filter(s -> s.getName().startsWith(schemaName.substring(0, 1))).findFirst();
            if (!suggestion.isPresent()) suggestion = dsl.meta().getSchemas().stream().findFirst();
            if (suggestion.isPresent())
                throw new RuntimeException("Schema '" + schemaName + "' is invalid. Did you mean: '" + suggestion.get().getName() + "'?");
            throw new RuntimeException("Schema '" + schemaName + "' is invalid. No schemas found.");
        }
        final List<Table<?>> tables = dsl.meta(optionalSchema.get()).getTables();
        return tables.stream()
                .filter(t -> TableOptions.TableType.TABLE.equals(t.getOptions().type()) && t.fields().length > 0)
                .filter(t -> !(t.getName().startsWith("MON$") || t.getName().startsWith("RDB$") || t.getName().startsWith("SEC$")))
                .collect(Collectors.toList());

    }

    public Long count(String tableName, DSLContext dsl) {
        return dsl
                .selectCount()
                .from(table(quotedName(tableName)))
                .fetchOne(0, long.class);
    }

    public ValueDistribution determineDistribution(Table<?> table, Field<?> field, long totalCount, DSLContext dsl) {
        Field<Object> f = field(quotedName(field.getName()));
        Result<? extends Record2<?, Integer>> rawDistribution = dsl.select(f, DSL.count())
                .from(table(quotedName(table.getName())))
                .groupBy(f)
                .fetch();
        return new ValueDistribution()
                .from(rawDistribution, totalCount);
    }

    public HikariDataSource toDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(this.url);
        ds.setPassword(this.password);
        ds.setUsername(this.username);
        return ds;
    }

    public Generator manualDetermineGenerator(DSLContext dsl, String tableName, String fieldName) {
        final SelectLimitPercentStep<Record1<Object>> sql = dsl.select(field(quotedName(fieldName))).from(table(quotedName(tableName))).limit(1);
        try (Connection connection = DriverManager.getConnection(url, username, password);
            Statement st = connection.createStatement();
            ResultSet rs = st.executeQuery(sql.getSQL(ParamType.INLINED))) {
            ResultSetMetaData metaData = rs.getMetaData();
            Class<?> matchingJavaClass;
            String key;
            try {
                final DataTypeMapping typeMapping = DataTypeMapping.findByKey(metaData.getColumnTypeName(1));
                matchingJavaClass = typeMapping.getDataType().getType();
                key = typeMapping.getKey();
            } catch (IllegalArgumentException e) {
                try {
                    matchingJavaClass = ColumnTypeMapping.findByKey(metaData.getColumnType(1)).getType();
                    key = DataTypeMapping.findByKey(ColumnTypeMapping.findByKey(metaData.getColumnType(1)).name().toLowerCase()).getKey();
                } catch (IllegalArgumentException i) {
                    matchingJavaClass = SQLDataType.VARCHAR.getType();
                    key = DataTypeMapping.VARCHAR.getKey();
                }
            }
            final boolean nullable = ResultSetMetaData.columnNullable == metaData.isNullable(1);
            return new Generator(metaData.getColumnDisplaySize(1), metaData.getPrecision(1), matchingJavaClass.getName(), key, nullable);
        } catch (Exception e) {
            throw new RuntimeException("Error during manual determining of generator", e);
        }
    }

    public String determineSchemaDynamically() throws ModelCreationException {
        try (Connection connection = DriverManager.getConnection(url, username, password)){
            String schema = connection.getSchema();
            if(schema != null) return schema;
        } catch (Exception e) {
            throw new ModelCreationException(e.getMessage(), e);
        }
        return "";
    }
}
