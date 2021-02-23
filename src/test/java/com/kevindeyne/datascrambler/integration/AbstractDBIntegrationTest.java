package com.kevindeyne.datascrambler.integration;

import com.kevindeyne.datascrambler.dao.SourceConnectionDao;
import com.kevindeyne.datascrambler.domain.Config;
import com.kevindeyne.datascrambler.domain.distributionmodel.*;
import com.kevindeyne.datascrambler.exceptions.ConnectionFailureException;
import com.kevindeyne.datascrambler.helper.ApplyContext;
import com.kevindeyne.datascrambler.helper.SupportedDBType;
import com.kevindeyne.datascrambler.service.CharacteristicService;
import com.kevindeyne.datascrambler.service.DistributionModelService;
import com.kevindeyne.datascrambler.service.GenerationService;
import org.jooq.DSLContext;
import org.jooq.Name;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.StringUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.testcontainers.containers.JdbcDatabaseContainer;

import java.sql.*;
import java.util.*;

public abstract class AbstractDBIntegrationTest {

    private static final String PERSON = "PERSON";
    private static final String TEST_123 = "TEST_123";
    private static final String AN_EMPTY_TABLE = "AN_EMPTY_TABLE";

    protected DistributionModelService distributionModelService;
    protected SourceConnectionDao sourceConnectionDao;
    protected GenerationService generationService;
    private Config config;

    @Before
    public void init() {
        distributionModelService = new DistributionModelService(new CharacteristicService());
        sourceConnectionDao = new SourceConnectionDao(getDB().getJdbcUrl(), getDB().getUsername(), getDB().getPassword(), getDialect());
        generationService = new GenerationService(distributionModelService);
        config = new Config(getDB().getUsername(), getDB().getPassword(), getDB().getFirstMappedPort(), getDB().getHost(), getDB().getDatabaseName(), getDBType());
    }

    protected abstract JdbcDatabaseContainer getDB();
    protected abstract SQLDialect getDialect();
    protected abstract SupportedDBType getDBType();

    @Test
    @Ignore
    public void testConnectionToContainer() throws SQLException {
        Assert.assertTrue(sourceConnectionDao.testConnection());
    }

    @Test
    public void testBuild() throws Exception {
        createTable(TEST_123);
        createTable(PERSON);
        createTable(AN_EMPTY_TABLE);

        insertRandomData(5, PERSON);

        DistributionModel model = distributionModelService.create(sourceConnectionDao, sourceConnectionDao.determineSchemaDynamically());
        Assert.assertNotNull(model);

        Assert.assertEquals(3, model.getTables().size());
        Assert.assertEquals(0, findTableInModel(model, "an_empty_table").getTotalCount());
        final TableData person = findTableInModel(model, PERSON);
        Assert.assertEquals(5, person.getTotalCount());
        Assert.assertEquals(3, person.getFieldData().size());


        for(FieldData data : person.getFieldData()) {
            Assert.assertNotNull(data.getFieldName());
            Assert.assertNotNull(data.getGenerator());
            Assert.assertEquals(1, data.getValueDistribution().getPercentages().size());
            Assert.assertNotNull(data.getValueDistribution().getPercentages().get(20.0D)); //since random and 5 records (=100%), each record represents a diff value, so 20% distribution of values - each record is diff
            System.out.println(data.getFieldName() + ": " + data.getValueDistribution().getPercentages().get(20D) );
        }

        for(TableData table : model.getTables()) {
            System.out.println(table);
        }

        System.out.println("");
        System.out.println(model);
    }

    @Test
    public void testGeneration() throws ConnectionFailureException, SQLException {
        DistributionModel model = new DistributionModel();
        TableData tableData = new TableData(PERSON);
        tableData.setTotalCount(10);
        tableData.setOrderOfExecution(1);

        final ValueDistribution valueDistribution = new ValueDistribution();
        valueDistribution.setPercentages(Collections.singletonMap(20D, new ValueDistribution.MutableInt(5)));

        FieldData pk = new FieldData("id");
        pk.setPrimaryKey(true);
        pk.setValueDistribution(valueDistribution);
        pk.setGenerator(new Generator(0, 10, Integer.class.getName(), "integer", false));
        pk.setCharacteristics(Collections.singletonList(Characteristics.CAN_BE_POSITIVE_NUMBER.getShortcutValue()));

        FieldData value = new FieldData("value");
        value.setValueDistribution(valueDistribution);
        value.setGenerator(new Generator(255, 0, String.class.getName(), "varchar", true));

        tableData.setFieldData(Arrays.asList(pk, value));
        model.setTables(Collections.singletonList(tableData));
        generationService.generateFromModel(model, config, new ApplyContext(2, true));

        List<Map<Integer, String>> records = retrieveAllRecords(PERSON);
        Assert.assertEquals(20, records.size());
    }

    protected List<Map<Integer, String>> retrieveAllRecords(String tableName) throws SQLException {
        List<Map<Integer, String>> records = new ArrayList<>();
        Connection connection = DriverManager.getConnection(getDB().getJdbcUrl(), getDB().getUsername(), getDB().getPassword());
        Statement stmt = connection.createStatement();

        DSLContext dsl = DSL.using(connection);
        final Name qualifiedName = findQualifiedName(tableName, dsl);

        ResultSet resultSet = dsl.select().from(qualifiedName).fetchResultSet();
        while (resultSet.next()) {
            records.add(Collections.singletonMap(resultSet.getInt("id"), resultSet.getString("value")));
        }
        stmt.close();
        connection.close();
        return records;
    }

    private Name findQualifiedName(String tableName, DSLContext dsl) {
        return dsl.meta().getTables().parallelStream()
                .filter(t -> t.getName().equalsIgnoreCase(tableName))
                .findFirst().get().getQualifiedName();
    }

    private TableData findTableInModel(DistributionModel model, String searchForTable) {
        return model.getTables().stream()
                .filter(t -> t.getTableName().equalsIgnoreCase(searchForTable)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(searchForTable + " was not found in model"));
    }

    private void createTable(String tableName) throws Exception {
        Connection connection = DriverManager.getConnection(getDB().getJdbcUrl(), getDB().getUsername(), getDB().getPassword());
        Statement stmt = connection.createStatement();
        stmt.executeUpdate("CREATE TABLE " + tableName + " (id int not null primary key, value_1 varchar(255), value_2 INT)");
        stmt.close();
        connection.close();
    }

    private void insertRandomData(int rowsOfData, String tableName) throws Exception {
        Connection connection = DriverManager.getConnection(getDB().getJdbcUrl(), getDB().getUsername(), getDB().getPassword());
        Statement stmt = connection.createStatement();
        for (int i = 0; i < rowsOfData; i++) {
            stmt.executeUpdate("INSERT INTO " + tableName + " VALUES (" + (i+1) + ", '" + randomString(255) + "', " + new Random().nextInt(99999) + ");");
        }
        stmt.close();
        connection.close();
    }

    protected String randomString(int maxLength) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append(UUID.randomUUID().toString());
        }
        return StringUtils.abbreviate(sb.toString(), maxLength);
    }

}
