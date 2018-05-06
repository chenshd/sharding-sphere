/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingjdbc.dbtest.asserts;

import com.google.common.base.Strings;
import io.shardingjdbc.core.api.MasterSlaveDataSourceFactory;
import io.shardingjdbc.core.api.ShardingDataSourceFactory;
import io.shardingjdbc.core.constant.DatabaseType;
import io.shardingjdbc.dbtest.common.DatabaseUtil;
import io.shardingjdbc.dbtest.config.AnalyzeDataset;
import io.shardingjdbc.dbtest.config.bean.AssertDDLDefinition;
import io.shardingjdbc.dbtest.config.bean.AssertDMLDefinition;
import io.shardingjdbc.dbtest.config.bean.AssertDQLDefinition;
import io.shardingjdbc.dbtest.config.bean.AssertDefinition;
import io.shardingjdbc.dbtest.config.bean.AssertSubDefinition;
import io.shardingjdbc.dbtest.config.bean.ColumnDefinition;
import io.shardingjdbc.dbtest.config.bean.DatasetDatabase;
import io.shardingjdbc.dbtest.config.bean.DatasetDefinition;
import io.shardingjdbc.dbtest.config.bean.ParameterDefinition;
import io.shardingjdbc.dbtest.env.DatabaseTypeEnvironment;
import io.shardingjdbc.dbtest.env.EnvironmentPath;
import io.shardingjdbc.dbtest.env.datasource.DataSourceUtil;
import io.shardingjdbc.dbtest.env.schema.SchemaEnvironmentManager;
import io.shardingjdbc.dbtest.exception.DbTestException;
import io.shardingjdbc.test.sql.SQLCasesLoader;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.junit.Assert;
import org.xml.sax.SAXException;

import javax.sql.DataSource;
import javax.xml.bind.JAXBException;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AssertEngine {
    
    /**
     * Run assert.
     * 
     * @param assertDefinition assert definition
     * @param path Check the use case storage path
     * @param shardingRuleType sharding rule type
     * @param databaseTypeEnvironment database type environment
     */
    public static void runAssert(final AssertDefinition assertDefinition, final String path, final String shardingRuleType, final DatabaseTypeEnvironment databaseTypeEnvironment) throws JAXBException, ParserConfigurationException, IOException, XPathExpressionException, SQLException, SAXException, ParseException {
        String dataInitializationPath = EnvironmentPath.getDataInitializeResourceFile(shardingRuleType);
        File fileDirDatabase = new File(dataInitializationPath);
        if (fileDirDatabase.exists()) {
            File[] fileDatabases = fileDirDatabase.listFiles();
            List<String> dataSourceNames = new LinkedList<>();
            for (File fileDatabase : fileDatabases) {
                String dataSourceName = fileDatabase.getName();
                dataSourceName = dataSourceName.substring(0, dataSourceName.indexOf("."));
                dataSourceNames.add(dataSourceName);
            }
            String rootPath = path.substring(0, path.lastIndexOf(File.separator) + 1);
            runAssert(assertDefinition, shardingRuleType, rootPath, dataInitializationPath, dataSourceNames, databaseTypeEnvironment);
        }
    }
    
    private static void runAssert(final AssertDefinition assertDefinition, final String shardingRuleType, final String rootPath, final String initDataPath, final List<String> dataSourceNames, final DatabaseTypeEnvironment databaseTypeEnvironment) throws IOException, SQLException, SAXException, ParserConfigurationException, XPathExpressionException, ParseException, JAXBException {
        if (!databaseTypeEnvironment.isEnabled()) {
            return;
        }
        Map<String, DataSource> dataSourceMap = createDataSourceMap(dataSourceNames, databaseTypeEnvironment.getDatabaseType());
        DataSource dataSource = createDataSource(shardingRuleType, dataSourceMap);
        if (assertDefinition instanceof AssertDQLDefinition) {
            dqlRun((AssertDQLDefinition) assertDefinition, shardingRuleType, databaseTypeEnvironment.getDatabaseType(), initDataPath, rootPath, dataSource, dataSourceMap, dataSourceNames);
        } else if (assertDefinition instanceof AssertDMLDefinition) {
            dmlRun((AssertDMLDefinition) assertDefinition, shardingRuleType, databaseTypeEnvironment.getDatabaseType(), initDataPath, rootPath, dataSource, dataSourceMap, dataSourceNames);
        } else if (assertDefinition instanceof AssertDDLDefinition) {
            ddlRun((AssertDDLDefinition) assertDefinition, databaseTypeEnvironment.getDatabaseType(), shardingRuleType, rootPath, dataSource);
        }
    }
    
    private static Map<String, DataSource> createDataSourceMap(final List<String> dataSourceNames, final DatabaseType each) {
        Map<String, DataSource> dataSourceMap = new HashMap<>(dataSourceNames.size(), 1);
        for (String db : dataSourceNames) {
            dataSourceMap.put(db, DataSourceUtil.createDataSource(each, db));
        }
        return dataSourceMap;
    }
    
    private static DataSource createDataSource(final String shardingRuleType, final Map<String, DataSource> dataSourceMap) throws SQLException, IOException {
        return "masterslaveonly".equals(shardingRuleType)
                        ? MasterSlaveDataSourceFactory.createDataSource(dataSourceMap, new File(EnvironmentPath.getShardingRuleResourceFile(shardingRuleType)))
                        : ShardingDataSourceFactory.createDataSource(dataSourceMap, new File(EnvironmentPath.getShardingRuleResourceFile(shardingRuleType)));
    }
    
    private static void ddlRun(final AssertDDLDefinition ddlDefinition, final DatabaseType databaseType, final String shardingRuleType, final String rootPath, final DataSource dataSource) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException, JAXBException {
        String rootSQL = ddlDefinition.getSql();
        rootSQL = SQLCasesLoader.getInstance().getSupportedSQL(rootSQL);
        String expectedDataFile = rootPath + "asserts/ddl/" + shardingRuleType + "/" + ddlDefinition.getExpectedDataFile();
        if (!new File(expectedDataFile).exists()) {
            expectedDataFile = rootPath + "asserts/ddl/" + ddlDefinition.getExpectedDataFile();
        }
        if (ddlDefinition.getParameter().getValues().isEmpty() && ddlDefinition.getParameter().getValueReplaces().isEmpty()) {
            List<AssertSubDefinition> subAsserts = ddlDefinition.getSubAsserts();
            if (subAsserts.isEmpty()) {
                doUpdateUseStatementToExecuteUpdateDDL(shardingRuleType, databaseType, expectedDataFile, dataSource, ddlDefinition, rootSQL);
                doUpdateUseStatementToExecuteDDL(shardingRuleType, databaseType, expectedDataFile, dataSource, ddlDefinition, rootSQL);
                doUpdateUsePreparedStatementToExecuteUpdateDDL(shardingRuleType, databaseType, expectedDataFile, dataSource, ddlDefinition, rootSQL);
                doUpdateUsePreparedStatementToExecuteDDL(shardingRuleType, databaseType, expectedDataFile, dataSource, ddlDefinition, rootSQL);
            } else {
                ddlSubRun(shardingRuleType, databaseType, rootPath, dataSource, ddlDefinition, rootSQL, expectedDataFile, subAsserts);
            }
        } else {
            doUpdateUseStatementToExecuteUpdateDDL(shardingRuleType, databaseType, expectedDataFile, dataSource, ddlDefinition, rootSQL);
            doUpdateUseStatementToExecuteDDL(shardingRuleType, databaseType, expectedDataFile, dataSource, ddlDefinition, rootSQL);
            doUpdateUsePreparedStatementToExecuteUpdateDDL(shardingRuleType, databaseType, expectedDataFile, dataSource, ddlDefinition, rootSQL);
            doUpdateUsePreparedStatementToExecuteDDL(shardingRuleType, databaseType, expectedDataFile, dataSource, ddlDefinition, rootSQL);
            List<AssertSubDefinition> subAsserts = ddlDefinition.getSubAsserts();
            if (!subAsserts.isEmpty()) {
                ddlSubRun(shardingRuleType, databaseType, rootPath, dataSource, ddlDefinition, rootSQL, expectedDataFile, subAsserts);
            }
        }
    }
    
    private static void ddlSubRun(final String shardingRuleType, final DatabaseType databaseType, final String rootPath, final DataSource dataSource, final AssertDDLDefinition anAssert, final String rootsql, final String expectedDataFile, final List<AssertSubDefinition> subAsserts) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException, JAXBException {
        for (AssertSubDefinition subAssert : subAsserts) {
            List<DatabaseType> databaseSubTypes = getDatabaseTypes(subAssert.getDatabaseConfig());
            
            if (!databaseSubTypes.contains(databaseType)) {
                break;
            }
            String baseConfig = subAssert.getShardingRuleType();
            if (StringUtils.isNotBlank(baseConfig)) {
                String[] baseConfigs = StringUtils.split(baseConfig, ",");
                boolean flag = true;
                for (String config : baseConfigs) {
                    if (shardingRuleType.equals(config)) {
                        flag = false;
                    }
                }
                //Skip use cases that do not need to run
                if (flag) {
                    continue;
                }
            }
            String expectedDataFileSub = subAssert.getExpectedDataFile();
            ParameterDefinition parameter = subAssert.getParameter();
            String expectedDataFileTmp = expectedDataFile;
            if (StringUtils.isBlank(expectedDataFileSub)) {
                expectedDataFileSub = anAssert.getExpectedDataFile();
            } else {
                expectedDataFileTmp = rootPath + "asserts/ddl/" + shardingRuleType + "/" + expectedDataFileSub;
                if (!new File(expectedDataFileTmp).exists()) {
                    expectedDataFileTmp = rootPath + "asserts/ddl/" + expectedDataFileSub;
                }
            }
            if (parameter == null) {
                parameter = anAssert.getParameter();
            }
            AssertDDLDefinition anAssertSub = new AssertDDLDefinition(anAssert.getId(), anAssert.getInitSql(),
                    anAssert.getShardingRuleType(), anAssert.getDatabaseConfig(), anAssert.getCleanSql(), expectedDataFileSub,
                    anAssert.getSql(), anAssert.getTable(),
                    parameter, anAssert.getSubAsserts());
            doUpdateUseStatementToExecuteUpdateDDL(shardingRuleType, databaseType, expectedDataFileTmp, dataSource, anAssertSub, rootsql);
            doUpdateUseStatementToExecuteDDL(shardingRuleType, databaseType, expectedDataFileTmp, dataSource, anAssertSub, rootsql);
            doUpdateUsePreparedStatementToExecuteUpdateDDL(shardingRuleType, databaseType, expectedDataFileTmp, dataSource, anAssertSub, rootsql);
            doUpdateUsePreparedStatementToExecuteDDL(shardingRuleType, databaseType, expectedDataFileTmp, dataSource, anAssertSub, rootsql);
        }
    }
    
    private static void dmlRun(final AssertDMLDefinition dmlDefinition, final String shardingRuleType, final DatabaseType databaseType, final String initDataFile, final String rootPath, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final List<String> dbs) throws IOException, SAXException, ParserConfigurationException, XPathExpressionException, SQLException, ParseException {
        String rootSQL = dmlDefinition.getSql();
        rootSQL = SQLCasesLoader.getInstance().getSupportedSQL(rootSQL);
        Map<String, DatasetDefinition> mapDatasetDefinition = new HashMap<>();
        Map<String, String> sqls = new HashMap<>();
        getInitDatas(dbs, initDataFile, mapDatasetDefinition, sqls);
        if (mapDatasetDefinition.isEmpty()) {
            throw new DbTestException("Use cases cannot be parsed");
        }
        if (sqls.isEmpty()) {
            throw new DbTestException("The use case cannot initialize the data");
        }
        String expectedDataFile = rootPath + "asserts/dml/" + shardingRuleType + "/" + dmlDefinition.getExpectedDataFile();
        if (!new File(expectedDataFile).exists()) {
            expectedDataFile = rootPath + "asserts/dml/" + dmlDefinition.getExpectedDataFile();
        }
        int resultDoUpdateUseStatementToExecuteUpdate = 0;
        int resultDoUpdateUseStatementToExecute = 0;
        int resultDoUpdateUsePreparedStatementToExecuteUpdate = 0;
        int resultDoUpdateUsePreparedStatementToExecute = 0;
        if (dmlDefinition.getParameter().getValues().isEmpty() && dmlDefinition.getParameter().getValueReplaces().isEmpty()) {
            List<AssertSubDefinition> subAsserts = dmlDefinition.getSubAsserts();
            if (subAsserts.isEmpty()) {
                resultDoUpdateUseStatementToExecuteUpdate = resultDoUpdateUseStatementToExecuteUpdate + doUpdateUseStatementToExecuteUpdate(expectedDataFile, dataSource, dataSourceMaps, dmlDefinition, rootSQL, mapDatasetDefinition, sqls);
                resultDoUpdateUseStatementToExecute = resultDoUpdateUseStatementToExecute + doUpdateUseStatementToExecute(expectedDataFile, dataSource, dataSourceMaps, dmlDefinition, rootSQL, mapDatasetDefinition, sqls);
                resultDoUpdateUsePreparedStatementToExecuteUpdate = resultDoUpdateUsePreparedStatementToExecuteUpdate + doUpdateUsePreparedStatementToExecuteUpdate(expectedDataFile, dataSource, dataSourceMaps, dmlDefinition, rootSQL, mapDatasetDefinition, sqls);
                resultDoUpdateUsePreparedStatementToExecute = resultDoUpdateUsePreparedStatementToExecute + doUpdateUsePreparedStatementToExecute(expectedDataFile, dataSource, dataSourceMaps, dmlDefinition, rootSQL, mapDatasetDefinition, sqls);
            } else {
                for (AssertSubDefinition subAssert : subAsserts) {
                    List<DatabaseType> databaseSubTypes = getDatabaseTypes(subAssert.getDatabaseConfig());
                    if (!databaseSubTypes.contains(databaseType)) {
                        break;
                    }
                    String baseConfigSub = subAssert.getShardingRuleType();
                    if (StringUtils.isNotBlank(baseConfigSub)) {
                        String[] baseConfigs = StringUtils.split(baseConfigSub, ",");
                        boolean flag = true;
                        for (String config : baseConfigs) {
                            if (shardingRuleType.equals(config)) {
                                flag = false;
                            }
                        }
                        //Skip use cases that do not need to run
                        if (flag) {
                            continue;
                        }
                    }
                
                    String expectedDataFileSub = subAssert.getExpectedDataFile();
                    ParameterDefinition parameter = subAssert.getParameter();
                    ParameterDefinition expectedParameter = subAssert.getExpectedParameter();
                    String expectedDataFileTmp = expectedDataFile;
                    if (StringUtils.isBlank(expectedDataFileSub)) {
                        expectedDataFileSub = dmlDefinition.getExpectedDataFile();
                    } else {
                        expectedDataFileTmp = rootPath + "asserts/dml/" + shardingRuleType + "/" + expectedDataFileSub;
                        if (!new File(expectedDataFileTmp).exists()) {
                            expectedDataFileTmp = rootPath + "asserts/dml/" + expectedDataFileSub;
                        }
                    }
                    if (parameter == null) {
                        parameter = dmlDefinition.getParameter();
                    }
                    if (expectedParameter == null) {
                        expectedParameter = dmlDefinition.getParameter();
                    }
                    AssertDMLDefinition anAssertSub = new AssertDMLDefinition(dmlDefinition.getId(),
                            expectedDataFileSub, dmlDefinition.getShardingRuleType(), dmlDefinition.getDatabaseConfig(), subAssert.getExpectedUpdate(), dmlDefinition.getSql(),
                            dmlDefinition.getExpectedSql(), parameter, expectedParameter, dmlDefinition.getSubAsserts());
                    resultDoUpdateUseStatementToExecuteUpdate = resultDoUpdateUseStatementToExecuteUpdate + doUpdateUseStatementToExecuteUpdate(expectedDataFileTmp, dataSource, dataSourceMaps, anAssertSub, rootSQL, mapDatasetDefinition, sqls);
                    resultDoUpdateUseStatementToExecute = resultDoUpdateUseStatementToExecute + doUpdateUseStatementToExecute(expectedDataFileTmp, dataSource, dataSourceMaps, anAssertSub, rootSQL, mapDatasetDefinition, sqls);
                    resultDoUpdateUsePreparedStatementToExecuteUpdate = resultDoUpdateUsePreparedStatementToExecuteUpdate + doUpdateUsePreparedStatementToExecuteUpdate(expectedDataFileTmp, dataSource, dataSourceMaps, anAssertSub, rootSQL, mapDatasetDefinition, sqls);
                    resultDoUpdateUsePreparedStatementToExecute = resultDoUpdateUsePreparedStatementToExecute + doUpdateUsePreparedStatementToExecute(expectedDataFileTmp, dataSource, dataSourceMaps, anAssertSub, rootSQL, mapDatasetDefinition, sqls);
                }
            }
        } else {
            resultDoUpdateUseStatementToExecuteUpdate = resultDoUpdateUseStatementToExecuteUpdate + doUpdateUseStatementToExecuteUpdate(expectedDataFile, dataSource, dataSourceMaps, dmlDefinition, rootSQL, mapDatasetDefinition, sqls);
            resultDoUpdateUseStatementToExecute = resultDoUpdateUseStatementToExecute + doUpdateUseStatementToExecute(expectedDataFile, dataSource, dataSourceMaps, dmlDefinition, rootSQL, mapDatasetDefinition, sqls);
            resultDoUpdateUsePreparedStatementToExecuteUpdate = resultDoUpdateUsePreparedStatementToExecuteUpdate + doUpdateUsePreparedStatementToExecuteUpdate(expectedDataFile, dataSource, dataSourceMaps, dmlDefinition, rootSQL, mapDatasetDefinition, sqls);
            resultDoUpdateUsePreparedStatementToExecute = resultDoUpdateUsePreparedStatementToExecute + doUpdateUsePreparedStatementToExecute(expectedDataFile, dataSource, dataSourceMaps, dmlDefinition, rootSQL, mapDatasetDefinition, sqls);
            List<AssertSubDefinition> subAsserts = dmlDefinition.getSubAsserts();
            if (!subAsserts.isEmpty()) {
                for (AssertSubDefinition subAssert : subAsserts) {
                    List<DatabaseType> databaseSubTypes = getDatabaseTypes(subAssert.getDatabaseConfig());
                    if (!databaseSubTypes.contains(databaseType)) {
                        break;
                    }
                    String baseConfigSub = subAssert.getShardingRuleType();
                    if (StringUtils.isNotBlank(baseConfigSub)) {
                        String[] baseConfigs = StringUtils.split(baseConfigSub, ",");
                        boolean flag = true;
                        for (String config : baseConfigs) {
                            if (shardingRuleType.equals(config)) {
                                flag = false;
                            }
                        }
                        //Skip use cases that do not need to run
                        if (flag) {
                            continue;
                        }
                    }
                    String expectedDataFileSub = subAssert.getExpectedDataFile();
                    ParameterDefinition parameter = subAssert.getParameter();
                    ParameterDefinition expectedParameter = subAssert.getExpectedParameter();
                    String expectedDataFileTmp = expectedDataFile;
                    if (StringUtils.isBlank(expectedDataFileSub)) {
                        expectedDataFileSub = dmlDefinition.getExpectedDataFile();
                    } else {
                        expectedDataFileTmp = rootPath + "asserts/dml/" + shardingRuleType + "/" + expectedDataFileSub;
                        if (!new File(expectedDataFileTmp).exists()) {
                            expectedDataFileTmp = rootPath + "asserts/dml/" + expectedDataFileSub;
                        }
                    }
                    if (parameter == null) {
                        parameter = dmlDefinition.getParameter();
                    }
                    if (expectedParameter == null) {
                        expectedParameter = dmlDefinition.getParameter();
                    }
                    AssertDMLDefinition anAssertSub = new AssertDMLDefinition(dmlDefinition.getId(),
                            expectedDataFileSub, dmlDefinition.getShardingRuleType(), dmlDefinition.getDatabaseConfig(), subAssert.getExpectedUpdate(), dmlDefinition.getSql(),
                            dmlDefinition.getExpectedSql(), parameter, expectedParameter, dmlDefinition.getSubAsserts());
                    resultDoUpdateUseStatementToExecuteUpdate = resultDoUpdateUseStatementToExecuteUpdate + doUpdateUseStatementToExecuteUpdate(expectedDataFileTmp, dataSource, dataSourceMaps, anAssertSub, rootSQL, mapDatasetDefinition, sqls);
                    resultDoUpdateUseStatementToExecute = resultDoUpdateUseStatementToExecute + doUpdateUseStatementToExecute(expectedDataFileTmp, dataSource, dataSourceMaps, anAssertSub, rootSQL, mapDatasetDefinition, sqls);
                    resultDoUpdateUsePreparedStatementToExecuteUpdate = resultDoUpdateUsePreparedStatementToExecuteUpdate + doUpdateUsePreparedStatementToExecuteUpdate(expectedDataFileTmp, dataSource, dataSourceMaps, anAssertSub, rootSQL, mapDatasetDefinition, sqls);
                    resultDoUpdateUsePreparedStatementToExecute = resultDoUpdateUsePreparedStatementToExecute + doUpdateUsePreparedStatementToExecute(expectedDataFileTmp, dataSource, dataSourceMaps, anAssertSub, rootSQL, mapDatasetDefinition, sqls);
                }
            }
        }
        if (null != dmlDefinition.getExpectedUpdate()) {
            Assert.assertEquals("Update row number error UpdateUseStatementToExecuteUpdate", dmlDefinition.getExpectedUpdate().intValue(), resultDoUpdateUseStatementToExecuteUpdate);
            Assert.assertEquals("Update row number error UpdateUseStatementToExecute", dmlDefinition.getExpectedUpdate().intValue(), resultDoUpdateUseStatementToExecute);
            Assert.assertEquals("Update row number error UpdateUsePreparedStatementToExecuteUpdate", dmlDefinition.getExpectedUpdate().intValue(), resultDoUpdateUsePreparedStatementToExecuteUpdate);
            Assert.assertEquals("Update row number error UpdateUsePreparedStatementToExecute", dmlDefinition.getExpectedUpdate().intValue(), resultDoUpdateUsePreparedStatementToExecute);
        }
    }
    
    private static void dqlRun(final AssertDQLDefinition dqlDefinition, final String shardingRuleType, final DatabaseType databaseType, final String initDataFile, final String rootPath, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final List<String> dbs) throws IOException, SAXException, ParserConfigurationException, XPathExpressionException, SQLException, ParseException {
        String rootSQL = dqlDefinition.getSql();
        rootSQL = SQLCasesLoader.getInstance().getSupportedSQL(rootSQL);
        Map<String, DatasetDefinition> mapDatasetDefinition = new HashMap<>();
        Map<String, String> sqls = new HashMap<>();
        getInitDatas(dbs, initDataFile, mapDatasetDefinition, sqls);
        if (mapDatasetDefinition.isEmpty()) {
            throw new DbTestException("Use cases cannot be parsed");
        }
        if (sqls.isEmpty()) {
            throw new DbTestException("The use case cannot initialize the data");
        }
        try {
            initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
            String expectedDataFile = rootPath + "asserts/dql/" + shardingRuleType + "/" + dqlDefinition.getExpectedDataFile();
            if (!new File(expectedDataFile).exists()) {
                expectedDataFile = rootPath + "asserts/dql/" + dqlDefinition.getExpectedDataFile();
            }
            if (dqlDefinition.getParameter().getValues().isEmpty() && dqlDefinition.getParameter().getValueReplaces().isEmpty()) {
                List<AssertSubDefinition> subAsserts = dqlDefinition.getSubAsserts();
                if (subAsserts.isEmpty()) {
                    doSelectUsePreparedStatement(expectedDataFile, dataSource, dqlDefinition, rootSQL);
                    doSelectUsePreparedStatementToExecuteSelect(expectedDataFile, dataSource, dqlDefinition, rootSQL);
                    doSelectUseStatement(expectedDataFile, dataSource, dqlDefinition, rootSQL);
                    doSelectUseStatementToExecuteSelect(expectedDataFile, dataSource, dqlDefinition, rootSQL);
                } else {
                    dqlSubRun(databaseType, shardingRuleType, rootPath, dataSource, dqlDefinition, rootSQL, expectedDataFile, subAsserts);
                }
            } else {
                doSelectUsePreparedStatement(expectedDataFile, dataSource, dqlDefinition, rootSQL);
                doSelectUsePreparedStatementToExecuteSelect(expectedDataFile, dataSource, dqlDefinition, rootSQL);
                doSelectUseStatement(expectedDataFile, dataSource, dqlDefinition, rootSQL);
                doSelectUseStatementToExecuteSelect(expectedDataFile, dataSource, dqlDefinition, rootSQL);
                List<AssertSubDefinition> subAsserts = dqlDefinition.getSubAsserts();
                if (!subAsserts.isEmpty()) {
                    dqlSubRun(databaseType, shardingRuleType, rootPath, dataSource, dqlDefinition, rootSQL, expectedDataFile, subAsserts);
                }
            }
        } finally {
            clearTableData(dataSourceMaps, mapDatasetDefinition);
        }
    }
    
    private static void dqlSubRun(final DatabaseType databaseType, final String dbName, final String rootPath, final DataSource dataSource, final AssertDQLDefinition anAssert, final String rootSQL, final String expectedDataFile, final List<AssertSubDefinition> subAsserts) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        for (AssertSubDefinition subAssert : subAsserts) {
            List<DatabaseType> databaseSubTypes = getDatabaseTypes(subAssert.getDatabaseConfig());
            if (!databaseSubTypes.contains(databaseType)) {
                break;
            }
            String baseSubConfig = subAssert.getShardingRuleType();
            if (StringUtils.isNotBlank(baseSubConfig)) {
                String[] baseConfigs = StringUtils.split(baseSubConfig, ",");
                boolean flag = true;
                for (String config : baseConfigs) {
                    if (dbName.equals(config)) {
                        flag = false;
                    }
                }
                //Skip use cases that do not need to run
                if (flag) {
                    continue;
                }
            }
            String expectedDataFileSub = subAssert.getExpectedDataFile();
            ParameterDefinition parameter = subAssert.getParameter();
            String expectedDataFileTmp = expectedDataFile;
            if (StringUtils.isBlank(expectedDataFileSub)) {
                expectedDataFileSub = anAssert.getExpectedDataFile();
            } else {
                expectedDataFileTmp = rootPath + "asserts/dql/" + dbName + "/" + expectedDataFileSub;
                if (!new File(expectedDataFileTmp).exists()) {
                    expectedDataFileTmp = rootPath + "asserts/dql/" + expectedDataFileSub;
                }
            }
            if (parameter == null) {
                parameter = anAssert.getParameter();
            }
            AssertDQLDefinition anAssertSub = new AssertDQLDefinition(anAssert.getId(),
                    expectedDataFileSub, anAssert.getShardingRuleType(), anAssert.getDatabaseConfig(), anAssert.getSql(),
                    parameter, anAssert.getSubAsserts());
            doSelectUsePreparedStatement(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
            doSelectUsePreparedStatementToExecuteSelect(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
            doSelectUseStatement(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
            doSelectUseStatementToExecuteSelect(expectedDataFileTmp, dataSource, anAssertSub, rootSQL);
        }
    }
    
    private static int doUpdateUsePreparedStatementToExecute(final String expectedDataFile, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final AssertDMLDefinition anAssert, final String rootsql, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
            try (Connection con = dataSource.getConnection();) {
                int actual = DatabaseUtil.updateUsePreparedStatementToExecute(con, rootsql,
                        anAssert.getParameter());
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
                
                if (anAssert.getExpectedUpdate() != null) {
                    Assert.assertEquals("Update row number error", anAssert.getExpectedUpdate().intValue(), actual);
                }
                String checksql = anAssert.getExpectedSql();
                checksql = SQLCasesLoader.getInstance().getSupportedSQL(checksql);
                DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(con, checksql,
                        anAssert.getExpectedParameter());
                DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement);
                return actual;
            }
        } finally {
            clearTableData(dataSourceMaps, mapDatasetDefinition);
        }
    }
    
    private static void doUpdateUsePreparedStatementToExecuteDDL(final String shardingRuleType, final DatabaseType databaseType, final String expectedDataFile, final DataSource dataSource, final AssertDDLDefinition anAssert, final String rootsql) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException, JAXBException {
        try {
            try (Connection con = dataSource.getConnection()) {
                if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                    SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseType, anAssert.getCleanSql());
                }
                if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                    SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseType, anAssert.getInitSql());
                }
                DatabaseUtil.updateUsePreparedStatementToExecute(con, rootsql,
                        anAssert.getParameter());
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
                
                String table = anAssert.getTable();
                List<ColumnDefinition> columnDefinitions = DatabaseUtil.getColumnDefinitions(con, table);
                DatabaseUtil.assertConfigs(checkDataset, columnDefinitions, table);
            }
        } finally {
            if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseType, anAssert.getCleanSql());
            }
            if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseType, anAssert.getInitSql());
            }
        }
    }
    
    private static int doUpdateUsePreparedStatementToExecuteUpdate(final String expectedDataFile, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final AssertDMLDefinition anAssert, final String rootsql, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
            try (Connection con = dataSource.getConnection()) {
                int actual = DatabaseUtil.updateUsePreparedStatementToExecuteUpdate(con, rootsql,
                        anAssert.getParameter());
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
                
                if (anAssert.getExpectedUpdate() != null) {
                    Assert.assertEquals("Update row number error", anAssert.getExpectedUpdate().intValue(), actual);
                }
                String checkSQL = anAssert.getExpectedSql();
                checkSQL = SQLCasesLoader.getInstance().getSupportedSQL(checkSQL);
                DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(con, checkSQL,
                        anAssert.getExpectedParameter());
                DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement);
                
                return actual;
            }
        } finally {
            clearTableData(dataSourceMaps, mapDatasetDefinition);
        }
    }
    
    private static void doUpdateUsePreparedStatementToExecuteUpdateDDL(final String shardingRuleType, final DatabaseType databaseType, final String expectedDataFile, final DataSource dataSource, final AssertDDLDefinition anAssert, final String rootsql) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException, JAXBException {
        try {
            try (Connection con = dataSource.getConnection()) {
                if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                    SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseType, anAssert.getCleanSql());
                }
                if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                    SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseType, anAssert.getInitSql());
                }
                DatabaseUtil.updateUsePreparedStatementToExecuteUpdate(con, rootsql,
                        anAssert.getParameter());
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
                String table = anAssert.getTable();
                List<ColumnDefinition> columnDefinitions = DatabaseUtil.getColumnDefinitions(con, table);
                DatabaseUtil.assertConfigs(checkDataset, columnDefinitions, table);
            }
        } finally {
            if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseType, anAssert.getCleanSql());
            }
            if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseType, anAssert.getInitSql());
            }
        }
    }
    
    private static int doUpdateUseStatementToExecute(final String expectedDataFile, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final AssertDMLDefinition anAssert, final String rootsql, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
            try (Connection con = dataSource.getConnection();) {
                int actual = DatabaseUtil.updateUseStatementToExecute(con, rootsql, anAssert.getParameter());
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
                if (anAssert.getExpectedUpdate() != null) {
                    Assert.assertEquals("Update row number error", anAssert.getExpectedUpdate().intValue(), actual);
                }
                String checkSQL = anAssert.getExpectedSql();
                checkSQL = SQLCasesLoader.getInstance().getSupportedSQL(checkSQL);
                DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(con, checkSQL,
                        anAssert.getExpectedParameter());
                DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement);
                return actual;
            }
        } finally {
            clearTableData(dataSourceMaps, mapDatasetDefinition);
        }
    }
    
    private static void doUpdateUseStatementToExecuteDDL(final String shardingRuleType, final DatabaseType databaseType, final String expectedDataFile, final DataSource dataSource, final AssertDDLDefinition anAssert, final String rootsql) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException, JAXBException {
        try {
            try (Connection con = dataSource.getConnection()) {
                if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                    SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseType, anAssert.getCleanSql());
                }
                if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                    SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseType, anAssert.getInitSql());
                }
                DatabaseUtil.updateUseStatementToExecute(con, rootsql, anAssert.getParameter());
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
                String table = anAssert.getTable();
                List<ColumnDefinition> columnDefinitions = DatabaseUtil.getColumnDefinitions(con, table);
                DatabaseUtil.assertConfigs(checkDataset, columnDefinitions, table);
            }
        } finally {
            if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseType, anAssert.getCleanSql());
            }
            if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseType, anAssert.getInitSql());
            }
        }
    }
    
    private static int doUpdateUseStatementToExecuteUpdate(final String expectedDataFile, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final AssertDMLDefinition anAssert, final String rootsql, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
            try (Connection con = dataSource.getConnection();) {
                int actual = DatabaseUtil.updateUseStatementToExecuteUpdate(con, rootsql, anAssert.getParameter());
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
                if (null != anAssert.getExpectedUpdate()) {
                    Assert.assertEquals("Update row number error", anAssert.getExpectedUpdate().intValue(), actual);
                }
                String checksql = anAssert.getExpectedSql();
                checksql = SQLCasesLoader.getInstance().getSupportedSQL(checksql);
                DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(con, checksql,
                        anAssert.getExpectedParameter());
                DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement);
                return actual;
            }
        } finally {
            clearTableData(dataSourceMaps, mapDatasetDefinition);
        }
    }
    
    private static void doUpdateUseStatementToExecuteUpdateDDL(final String shardingRuleType, final DatabaseType databaseType, final String expectedDataFile, final DataSource dataSource, final AssertDDLDefinition anAssert, final String rootsql) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException, JAXBException {
        try {
            try (Connection con = dataSource.getConnection()) {
                if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                    SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseType, anAssert.getCleanSql());
                }
                if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                    SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseType, anAssert.getInitSql());
                }
                DatabaseUtil.updateUseStatementToExecuteUpdate(con, rootsql, anAssert.getParameter());
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
                
                String table = anAssert.getTable();
                List<ColumnDefinition> columnDefinitions = DatabaseUtil.getColumnDefinitions(con, table);
                DatabaseUtil.assertConfigs(checkDataset, columnDefinitions, table);
            }
        } finally {
            if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseType, anAssert.getCleanSql());
            }
            if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                SchemaEnvironmentManager.executeSQL(shardingRuleType, databaseType, anAssert.getInitSql());
            }
        }
    }
    
    private static void doSelectUseStatement(final String expectedDataFile, final DataSource dataSource, final AssertDQLDefinition anAssert, final String rootsql) throws SQLException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try (Connection con = dataSource.getConnection()) {
            DatasetDatabase ddStatement = DatabaseUtil.selectUseStatement(con, rootsql, anAssert.getParameter());
            DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
            DatabaseUtil.assertDatas(checkDataset, ddStatement);
        }
    }
    
    private static void doSelectUseStatementToExecuteSelect(final String expectedDataFile, final DataSource dataSource, final AssertDQLDefinition anAssert, final String rootsql) throws SQLException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try (Connection con = dataSource.getConnection();) {
            DatasetDatabase ddStatement = DatabaseUtil.selectUseStatementToExecuteSelect(con, rootsql, anAssert.getParameter());
            DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
            DatabaseUtil.assertDatas(checkDataset, ddStatement);
        }
    }
    
    private static void doSelectUsePreparedStatement(final String expectedDataFile, final DataSource dataSource, final AssertDQLDefinition anAssert, final String rootsql) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try (Connection con = dataSource.getConnection()) {
            DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(con, rootsql, anAssert.getParameter());
            DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
            DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement);
        }
    }
    
    private static void doSelectUsePreparedStatementToExecuteSelect(final String expectedDataFile, final DataSource dataSource, final AssertDQLDefinition anAssert, final String rootsql) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try (Connection con = dataSource.getConnection();) {
            DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatementToExecuteSelect(con, rootsql, anAssert.getParameter());
            DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
            DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement);
        }
    }
    
    private static void getInitDatas(final List<String> dbs, final String initDataFile, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls)
            throws IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        for (String each : dbs) {
            String tempPath = initDataFile + "/" + each + ".xml";
            File file = new File(tempPath);
            if (file.exists()) {
                DatasetDefinition datasetDefinition = AnalyzeDataset.analyze(file, null);
                mapDatasetDefinition.put(each, datasetDefinition);
                Map<String, List<Map<String, String>>> datas = datasetDefinition.getDatas();
                for (Map.Entry<String, List<Map<String, String>>> eachEntry : datas.entrySet()) {
                    String sql = DatabaseUtil.analyzeSql(eachEntry.getKey(), eachEntry.getValue().get(0));
                    sqls.put(eachEntry.getKey(), sql);
                }
            }
        }
    }
    
    private static void clearTableData(final Map<String, DataSource> dataSourceMaps, final Map<String, DatasetDefinition> mapDatasetDefinition) throws SQLException {
        for (Map.Entry<String, DataSource> eachEntry : dataSourceMaps.entrySet()) {
            DataSource dataSource1 = eachEntry.getValue();
            DatasetDefinition datasetDefinition = mapDatasetDefinition.get(eachEntry.getKey());
            Map<String, List<Map<String, String>>> datas = datasetDefinition.getDatas();
            for (Map.Entry<String, List<Map<String, String>>> eachListEntry : datas.entrySet()) {
                try (Connection conn = dataSource1.getConnection()) {
                    DatabaseUtil.cleanAllUsePreparedStatement(conn, eachListEntry.getKey());
                }
            }
        }
    }
    
    private static void initTableData(final Map<String, DataSource> dataSourceMaps, final Map<String, String> sqls, final Map<String, DatasetDefinition> mapDatasetDefinition) throws SQLException, ParseException {
        clearTableData(dataSourceMaps, mapDatasetDefinition);
        for (Map.Entry<String, DataSource> eachDataSourceEntry : dataSourceMaps.entrySet()) {
            DataSource dataSource1 = eachDataSourceEntry.getValue();
            DatasetDefinition datasetDefinition = mapDatasetDefinition.get(eachDataSourceEntry.getKey());
            Map<String, List<ColumnDefinition>> configs = datasetDefinition.getMetadatas();
            Map<String, List<Map<String, String>>> datas = datasetDefinition.getDatas();
            for (Map.Entry<String, List<Map<String, String>>> eachListEntry : datas.entrySet()) {
                try (Connection conn = dataSource1.getConnection()) {
                    DatabaseUtil.insertUsePreparedStatement(conn, sqls.get(eachListEntry.getKey()), datas.get(eachListEntry.getKey()), configs.get(eachListEntry.getKey()));
                }
            }
        }
    }
    
    private static List<DatabaseType> getDatabaseTypes(final String databaseTypes) {
        List<DatabaseType> result = new LinkedList<>();
        if (Strings.isNullOrEmpty(databaseTypes)) {
            return Arrays.asList(DatabaseType.values());
        }
        for (String eachType : databaseTypes.split(",")) {
            result.add(DatabaseType.valueOf(eachType));
        }
        return result;
    }
}
