/*
 * Copyright 2016-2018 shardingsphere.io.
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

package io.shardingsphere.shardingjdbc.executor;

import io.shardingsphere.core.constant.ConnectionMode;
import io.shardingsphere.core.constant.SQLType;
import io.shardingsphere.core.event.ShardingEventType;
import io.shardingsphere.core.executor.ShardingExecuteGroup;
import io.shardingsphere.core.executor.StatementExecuteUnit;
import io.shardingsphere.core.routing.BatchRouteUnit;
import io.shardingsphere.core.routing.RouteUnit;
import io.shardingsphere.core.routing.SQLUnit;
import io.shardingsphere.shardingjdbc.executor.BatchPreparedStatementExecutor;
import org.junit.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class BatchPreparedStatementExecutorTest extends AbstractBaseExecutorTest {
    
    private static final String SQL = "DELETE FROM table_x WHERE id=?";
    
    private BatchPreparedStatementExecutor actual;
    
    @Override
    public void setUp() throws SQLException, ReflectiveOperationException {
        super.setUp();
        actual = new BatchPreparedStatementExecutor(1, 1, 1, false, getConnection());
    }
    
    private void setSQLType(final SQLType sqlType) throws ReflectiveOperationException {
        Field field = BatchPreparedStatementExecutor.class.getSuperclass().getDeclaredField("sqlType");
        field.setAccessible(true);
        field.set(actual, sqlType);
    }
    
    private void setExecuteGroups(final List<PreparedStatement> preparedStatements) throws ReflectiveOperationException {
        Collection<ShardingExecuteGroup<StatementExecuteUnit>> executeGroups = new LinkedList<>();
        List<StatementExecuteUnit> preparedStatementExecuteUnits = new LinkedList<>();
        executeGroups.add(new ShardingExecuteGroup<>(preparedStatementExecuteUnits));
        Collection<BatchRouteUnit> routeUnits = new LinkedList<>();
        for (PreparedStatement each : preparedStatements) {
            List<List<Object>> parameterSets = new LinkedList<>();
            parameterSets.add(Collections.singletonList((Object) 1));
            RouteUnit routeUnit = new RouteUnit("ds_0", new SQLUnit(SQL, parameterSets));
            BatchRouteUnit batchRouteUnit = new BatchRouteUnit(routeUnit);
            batchRouteUnit.mapAddBatchCount(0);
            batchRouteUnit.mapAddBatchCount(1);
            routeUnits.add(batchRouteUnit);
            preparedStatementExecuteUnits.add(new StatementExecuteUnit(routeUnit, each, ConnectionMode.MEMORY_STRICTLY));
        }
        setFields(executeGroups, routeUnits);
    }
    
    private void setFields(
            final Collection<ShardingExecuteGroup<StatementExecuteUnit>> executeGroups, final Collection<BatchRouteUnit> routeUnits) throws NoSuchFieldException, IllegalAccessException {
        Field field = BatchPreparedStatementExecutor.class.getSuperclass().getDeclaredField("executeGroups");
        field.setAccessible(true);
        field.set(actual, executeGroups);
        field = BatchPreparedStatementExecutor.class.getDeclaredField("routeUnits");
        field.setAccessible(true);
        field.set(actual, routeUnits);
        field = BatchPreparedStatementExecutor.class.getDeclaredField("batchCount");
        field.setAccessible(true);
        field.set(actual, 2);
    }
    
    @SuppressWarnings("unchecked")
    @Test
    public void assertNoPreparedStatement() throws SQLException, ReflectiveOperationException {
        PreparedStatement preparedStatement = getPreparedStatement();
        when(preparedStatement.executeBatch()).thenReturn(new int[] {0, 0});
        setSQLType(SQLType.DQL);
        setExecuteGroups(Collections.singletonList(preparedStatement));
        assertThat(actual.executeBatch(), is(new int[] {0, 0}));
    }
    
    @Test
    public void assertExecuteBatchForSinglePreparedStatementSuccess() throws SQLException, ReflectiveOperationException {
        PreparedStatement preparedStatement = getPreparedStatement();
        when(preparedStatement.executeBatch()).thenReturn(new int[] {10, 20});
        setSQLType(SQLType.DQL);
        setExecuteGroups(Collections.singletonList(preparedStatement));
        assertThat(actual.executeBatch(), is(new int[] {10, 20}));
        verify(preparedStatement).executeBatch();
        verify(getEventCaller(), times(2)).verifyDataSource("ds_0");
        verify(getEventCaller(), times(2)).verifySQL(SQL);
        verify(getEventCaller(), times(2)).verifyParameters(Collections.singletonList((Object) 1));
        verify(getEventCaller(), times(1)).verifyEventExecutionType(ShardingEventType.BEFORE_EXECUTE);
        verify(getEventCaller(), times(1)).verifyEventExecutionType(ShardingEventType.EXECUTE_SUCCESS);
        verify(getEventCaller(), times(0)).verifyException(null);
    }
    
    private PreparedStatement getPreparedStatement() throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData databaseMetaData = mock(DatabaseMetaData.class);
        when(databaseMetaData.getURL()).thenReturn("jdbc:h2:mem:ds_master;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false;MODE=MYSQL");
        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(statement.getConnection()).thenReturn(connection);
        return statement;
    }
    
    @Test
    public void assertExecuteBatchForMultiplePreparedStatementsSuccess() throws SQLException, ReflectiveOperationException {
        PreparedStatement preparedStatement1 = getPreparedStatement();
        PreparedStatement preparedStatement2 = getPreparedStatement();
        when(preparedStatement1.executeBatch()).thenReturn(new int[] {10, 20});
        when(preparedStatement2.executeBatch()).thenReturn(new int[] {20, 40});
        setSQLType(SQLType.DQL);
        setExecuteGroups(Arrays.asList(preparedStatement1, preparedStatement2));
        assertThat(actual.executeBatch(), is(new int[] {30, 60}));
        verify(preparedStatement1).executeBatch();
        verify(preparedStatement2).executeBatch();
        verify(getEventCaller(), times(4)).verifyDataSource("ds_0");
        verify(getEventCaller(), times(4)).verifySQL(SQL);
        verify(getEventCaller(), times(4)).verifyParameters(Collections.singletonList((Object) 1));
        verify(getEventCaller(), times(2)).verifyEventExecutionType(ShardingEventType.BEFORE_EXECUTE);
        verify(getEventCaller(), times(2)).verifyEventExecutionType(ShardingEventType.EXECUTE_SUCCESS);
        verify(getEventCaller(), times(0)).verifyException(null);
    }
    
    @Test
    public void assertExecuteBatchForSinglePreparedStatementFailure() throws SQLException, ReflectiveOperationException {
        PreparedStatement preparedStatement = getPreparedStatement();
        SQLException exp = new SQLException();
        when(preparedStatement.executeBatch()).thenThrow(exp);
        setSQLType(SQLType.DQL);
        setExecuteGroups(Collections.singletonList(preparedStatement));
        assertThat(actual.executeBatch(), is(new int[] {0, 0}));
        verify(preparedStatement).executeBatch();
        verify(getEventCaller(), times(2)).verifyDataSource("ds_0");
        verify(getEventCaller(), times(2)).verifySQL(SQL);
        verify(getEventCaller(), times(2)).verifyParameters(Collections.singletonList((Object) 1));
        verify(getEventCaller(), times(1)).verifyEventExecutionType(ShardingEventType.BEFORE_EXECUTE);
        verify(getEventCaller(), times(1)).verifyException(exp);
    }
    
    @Test
    public void assertExecuteBatchForMultiplePreparedStatementsFailure() throws SQLException, ReflectiveOperationException {
        PreparedStatement preparedStatement1 = getPreparedStatement();
        PreparedStatement preparedStatement2 = getPreparedStatement();
        SQLException exp = new SQLException();
        when(preparedStatement1.executeBatch()).thenThrow(exp);
        when(preparedStatement2.executeBatch()).thenThrow(exp);
        setSQLType(SQLType.DQL);
        setExecuteGroups(Arrays.asList(preparedStatement1, preparedStatement2));
        assertThat(actual.executeBatch(), is(new int[] {0, 0}));
        verify(preparedStatement1).executeBatch();
        verify(preparedStatement2).executeBatch();
        verify(getEventCaller(), times(4)).verifyDataSource("ds_0");
        verify(getEventCaller(), times(4)).verifySQL(SQL);
        verify(getEventCaller(), times(4)).verifyParameters(Collections.singletonList((Object) 1));
        verify(getEventCaller(), times(2)).verifyEventExecutionType(ShardingEventType.BEFORE_EXECUTE);
        verify(getEventCaller(), times(2)).verifyException(exp);
    }
}
