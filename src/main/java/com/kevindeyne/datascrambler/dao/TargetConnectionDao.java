package com.kevindeyne.datascrambler.dao;

import com.kevindeyne.datascrambler.domain.distributionmodel.*;
import com.kevindeyne.datascrambler.mapping.DataTypeMapping;
import com.kevindeyne.datascrambler.service.GenerationHelperService;
import com.kevindeyne.datascrambler.service.PKDistributionService;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Data;
import me.tongfei.progressbar.ProgressBar;
import org.jooq.*;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.*;

@Data
public class TargetConnectionDao {
    private final String url;
    private final String username;
    private final String password;
    private final GenerationHelperService generationHelperService;
    private final PKDistributionService pkDistributionService;

    public TargetConnectionDao(String url, String username, String password, GenerationHelperService generationHelperService, PKDistributionService pkDistributionService) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.generationHelperService = generationHelperService;
        this.pkDistributionService = pkDistributionService;
    }

    public boolean testConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(url, username, password);
        DSLContext dsl = using(new DefaultConfiguration().derive(connection));
        if(dsl.meta().getTables().isEmpty()) {
            Integer[] result = dsl.selectOne().fetch().intoArray(0, Integer.class);
            return result.length == 1 && result[0] == 1;
        } else {
            Integer[] result = dsl.selectCount().from(dsl.meta().getTables().get(0)).fetch().intoArray(0, Integer.class);
            return result.length == 1 && result[0] != null;
        }

    }

    public HikariDataSource toDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(this.url);
        ds.setPassword(this.password);
        ds.setUsername(this.username);
        return ds;
    }

    public List<Table<?>> getAllTables(DataSource dataSource) {
        DSLContext dsl = using(new DefaultConfiguration().derive(dataSource));
        return dsl.meta().getTables();
    }

    public void truncate(DSLContext dsl, String tableName) {
        try {
            dsl.truncate(DSL.table(tableName)).cascade().execute();
        } catch (Throwable e) {
            //TODO see https://github.com/jOOQ/jOOQ/issues/7367 ; not yet supported
            dsl.truncate(dsl.meta().getTables().stream().filter(t -> t.getName().equalsIgnoreCase(tableName)).findFirst().get()).execute();
        }
    }

    public void createTable(DSLContext dsl, TableData table) {
        List<Field<?>> primaryKeys = new ArrayList<>();
        CreateTableColumnStep createStep = null;
        final Table<Record> tableRef = table(quotedName(table.getTableName()));
        try {
            createStep = dsl.createTable(tableRef); //cannot be a final variable, so no try-resources - manually close
            for (FieldData fieldData : table.getFieldData()) {
                final Generator generator = fieldData.getGenerator();
                DataType<?> dataType = DataTypeMapping.findByKey(generator.getDataTypeKey()).getDataType();
                dataType = dataType.nullable(generator.isNullable());
                dataType = dataType.precision((generator.getPrecision() > Short.MAX_VALUE) ? Short.MAX_VALUE : generator.getPrecision());
                dataType = dataType.length((generator.getLength() > Short.MAX_VALUE) ? Short.MAX_VALUE : generator.getLength());

                final Name fieldName = quotedName(fieldData.getFieldName());
                final Field<?> field = field(fieldName, dataType);
                createStep = createStep.column(field);

                if (fieldData.isPrimaryKey()) primaryKeys.add(field(fieldName));
            }
            createStep.execute();
        } finally {
            if (createStep != null) createStep.close();
        }

        if (!primaryKeys.isEmpty()) {
            dsl.alterTable(tableRef).add(constraint().primaryKey(primaryKeys.toArray(new Field<?>[0]))).execute();
        }

        table.getFieldData().forEach(fieldData -> {
            final ForeignKeyData fk = fieldData.getForeignKeyData();
            if(fk != null) {
                dsl.alterTable(tableRef)
                        .add(foreignKey(field(quotedName(fieldData.getFieldName()))).references(quotedName(fk.getTable()), quotedName(fk.getKey())))
                        .execute();
            }
        });
    }

    public void validateTable(DSLContext dsl, TableData table) {
        //TODO https://github.com/kevindeyne/vardogr/issues/1
    }

    public void createIndexes(DSLContext dsl, TableData table) {
        List<String> alreadyCreated = dsl.meta().getIndexes().stream().map(Named::getName).collect(Collectors.toList());
        table.getIndexData().parallelStream().forEach(i -> {
                if(!alreadyCreated.contains(i.getName())){
                    dsl.createIndex(i.getName()).on(table.getTableName(), i.getFields()).execute();
                }
        });
    }

    public void pushData(DSLContext dsl, TableData table, boolean clean) {
        List<Field<?>> fields = new ArrayList<>();

        long total = table.getTotalCount();
        if(!clean) total -= dsl.selectCount().from(table(quotedName(table.getTableName()))).fetchOne(0, long.class);
        if(total < 0) total = 0;

        Map<String, Long> skipList = new HashMap<>();
        Map<String, Object> skipListData = new HashMap<>();

        Map<String, Map<Double, ValueDistribution.MutableInt>> percentagesHandled = new HashMap<>();

        prefetchFKValues(dsl, table, 0);

        List<Map<String, Object>> pkData = pkDistributionService.generatePrimaryKey(table);

        if(total != 0) {
            try (ProgressBar pb = new ProgressBar("Generating data for " + table.getTableName(), total)) {
                //TODO this code could use some refactoring for clarity -- too much visually going on in one method
                for (long i = 0; i < total; i++) {
                    List<Object> data = new LinkedList<>();
                    for (FieldData field : table.getFieldData()) {
                        final String fieldName = field.getFieldName();
                        if(field.isPrimaryKey()) {
                            if(field.getForeignKeyData() != null) {
                                final Object pkId = pkData.get((int) i).get(fieldName);
                                data.add(pkId);
                            } else {
                                checkPrefetch(dsl, table, field);
                                data.add(generateNewDataField(field));
                            }
                        } else {
                            Long skipListValue = skipList.get(fieldName);
                            if (skipListValue == null) {
                                Double percentage = determineActivePercentage(percentagesHandled, field);

                                long skipTo = calculateSkipTo(total, i, percentage);
                                skipList.put(fieldName, skipTo);
                                Object gen;
                                short maxLoop = 1000;
                                do {
                                    checkPrefetch(dsl, table, field);
                                    gen = generateNewDataField(field);
                                    if(maxLoop-- < 0) break;
                                } while (skipListData.containsValue(gen));

                                skipListData.put(fieldName, gen);
                                skipListValue = skipTo;
                            }

                            data.add(skipListData.get(fieldName));

                            if (i + 1 == skipListValue) {
                                skipList.put(fieldName, null);
                                skipListData.put(fieldName, null);
                            }
                        }
                    }

                    try {
                        dsl.insertInto(table(quotedName(table.getTableName())), fields)
                                .values(data)
                                .execute();
                    } catch (DataAccessException e) {
                        /*if (e.getMessage().contains("duplicate") || e.getMessage().contains(" violates foreign key")) {
                            //System.err.println(e.getMessage() + " for table: " + table.getTableName());
                            pb.step();
                            continue;
                        }*/

                        throw e;
                    }
                    pb.step();
                }
            }
        }
        pkData = null;
    }

    private void checkPrefetch(DSLContext dsl, TableData table, FieldData field) {
        if (exceedPrefetchedData(field)) prefetchFKValues(dsl, table, field.getForeignKeyData().getOffset() + 1000);
    }

    private boolean exceedPrefetchedData(FieldData field) {
        if(field == null || field.getForeignKeyData() == null || field.getForeignKeyData().getPossibleValues() == null) return false;
        return field.getForeignKeyData().getPossibleValues().size() <= field.getForeignKeyData().getOffset();
    }

    private void prefetchFKValues(DSLContext dsl, TableData table, int offset) {
        for (FieldData field : table.getFieldData()) {
            if (field.getForeignKeyData() != null) {
                ForeignKeyData fk = field.getForeignKeyData();
                final Name tableName = quotedName(fk.getTable());
                final Name fieldName = quotedName(fk.getKey());
                final Set<Object> results = dsl.selectDistinct().from(table(tableName)).offset(offset).limit(1000).fetchSet(field(fieldName), Object.class);
                field.getForeignKeyData().setPossibleValues(results);
                field.getForeignKeyData().setOffset(0);
            }
        }
    }

    private long calculateSkipTo(long total, long i, Double percentage) {
        long skipTo = Math.round(i + (((double) total) / 100 * percentage));
        if (skipTo > total) skipTo = total;
        return skipTo;
    }

    private Double determineActivePercentage(Map<String, Map<Double, ValueDistribution.MutableInt>> percentagesHandledPerField, FieldData field) {
        percentagesHandledPerField.computeIfAbsent(field.getFieldName(), k -> new HashMap<>());
        Map<Double, ValueDistribution.MutableInt> percentagesHandled = percentagesHandledPerField.get(field.getFieldName());
        final Map<Double, ValueDistribution.MutableInt> percentages = field.getValueDistribution().getPercentages();
        Double percentage = null;
        for (Double percentageToPossiblyHandle : percentages.keySet()) {
            if (!percentagesHandled.containsKey(percentageToPossiblyHandle)) {
                percentagesHandled.put(percentageToPossiblyHandle, new ValueDistribution.MutableInt());
                percentage = percentageToPossiblyHandle;
                break;
            } else if (percentagesHandled.get(percentageToPossiblyHandle).get() < percentages.get(percentageToPossiblyHandle).get()) {
                percentagesHandled.put(percentageToPossiblyHandle, percentagesHandled.get(percentageToPossiblyHandle).increment());
                percentage = percentageToPossiblyHandle;
                break;
            }
        }
        if (null == percentage) { //this is, essentially, a rounding error. If percentages don't count up to 100% but go to 99.999%, that 0.0001% needs to be accounted for
            return 0.0001D; //could probably introduce a percentage cleanup round after determining them too; but this will do for now. This happens because we round during persistence of model
        }
        return percentage;
    }

    private Object generateNewDataField(FieldData field) {
        final ForeignKeyData fk = field.getForeignKeyData();
        if(null != fk) {
            if(fk.getPossibleValues().size() <= field.getOffset()) {
                field.setOffset(0);
                return generateNewDataField(field);
            }
            final Object result = fk.getPossibleValues().toArray()[field.getOffset()];
            field.setOffset(field.getOffset()+1);
            return result;
        } else {
            final Generator g = field.getGenerator();
            return generationHelperService.generate(g.getOriginalType(), g.getLength(), field);
        }
    }
}
