/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.rdb.sharding.parsing;

import com.dangdang.ddframe.rdb.sharding.constant.AggregationType;
import com.dangdang.ddframe.rdb.sharding.constant.OrderType;
import com.dangdang.ddframe.rdb.sharding.constant.ShardingOperator;
import com.dangdang.ddframe.rdb.sharding.parsing.jaxb.Assert;
import com.dangdang.ddframe.rdb.sharding.parsing.jaxb.Asserts;
import com.dangdang.ddframe.rdb.sharding.parsing.jaxb.Value;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.Column;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.GroupBy;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.OrderBy;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.condition.Condition;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.condition.Conditions;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.limit.Limit;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.selectitem.AggregationSelectItem;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.context.table.Table;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.expression.SQLExpression;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.expression.SQLNumberExpression;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.expression.SQLPlaceholderExpression;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.expression.SQLTextExpression;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.statement.SQLStatement;
import com.dangdang.ddframe.rdb.sharding.parsing.parser.statement.select.SelectStatement;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import lombok.AccessLevel;
import lombok.Getter;
import org.mockito.internal.matchers.apachecommons.ReflectionEquals;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import java.io.File;
import java.util.*;

import static org.junit.Assert.assertTrue;

public abstract class AbstractBaseParseTest {
    
    @Getter(AccessLevel.PROTECTED)
    private final String sql;
    
    private final String expectedSQL;
    
    private final Iterator<Table> expectedTables;
    
    private final Conditions expectedConditions;
    
    private final Iterator<OrderBy> orderByList;
    
    private final Iterator<GroupBy> groupByList;
    
    private final Iterator<AggregationSelectItem> aggregationColumns;
    
    private final Limit limit;
    
    protected AbstractBaseParseTest(final String testCaseName, final String sql, final String expectedSQL,
                                    final Collection<Table> expectedTables, final Conditions expectedConditions, final SQLStatement expectedSQLStatement) {
        this.sql = sql;
        this.expectedSQL = expectedSQL;
        this.expectedTables = expectedTables.iterator();
        this.expectedConditions = expectedConditions;
        this.orderByList = expectedSQLStatement.getOrderByList().iterator();
        this.groupByList = expectedSQLStatement.getGroupByList().iterator();
        this.aggregationColumns = expectedSQLStatement.getAggregationSelectItems().iterator();
        this.limit = expectedSQLStatement.getLimit();
    }
    
    protected static Collection<Object[]> dataParameters(final String path) {
        Collection<Object[]> result = new ArrayList<>();
        for (File each : new File(AbstractBaseParseTest.class.getClassLoader().getResource(path).getPath()).listFiles()) {
            result.addAll(dataParameters(each));
        }
        return result;
    }
    
    private static Collection<Object[]> dataParameters(final File file) {
        Asserts asserts = loadAsserts(file);
        Object[][] result = new Object[asserts.getAsserts().size()][6];
        for (int i = 0; i < asserts.getAsserts().size(); i++) {
            result[i] = getDataParameter(asserts.getAsserts().get(i));
        }
        return Arrays.asList(result);
    }
    
    private static Asserts loadAsserts(final File file) {
        try {
            return (Asserts) JAXBContext.newInstance(Asserts.class).createUnmarshaller().unmarshal(file);
        } catch (final JAXBException ex) {
            throw new RuntimeException(ex);
        }
    }
    
    private static Object[] getDataParameter(final Assert assertObj) {
        final Object[] result = new Object[6];
        result[0] = assertObj.getId();
        result[1] = assertObj.getSql();
        result[2] = assertObj.getExpectedSQL();
        result[3] = Lists.transform(assertObj.getTables(), new Function<com.dangdang.ddframe.rdb.sharding.parsing.jaxb.Table, Table>() {
            
            @Override
            public Table apply(final com.dangdang.ddframe.rdb.sharding.parsing.jaxb.Table input) {
                return new Table(input.getName(), Optional.fromNullable(input.getAlias()));
            }
        });
        if (null == assertObj.getConditions()) {
            result[4] = new Conditions();
        } else {
            Conditions conditions = new Conditions();
            for (com.dangdang.ddframe.rdb.sharding.parsing.jaxb.Condition each : assertObj.getConditions().getConditions()) {
                List<SQLExpression> sqlExpressions = new LinkedList<>();
                for (Value value : each.getValues()) {
                    Comparable<?> valueWithType = value.getValueWithType();
                    if (valueWithType instanceof Number) {
                        sqlExpressions.add(new SQLNumberExpression((Number) valueWithType));
                    } else {
                        sqlExpressions.add(new SQLTextExpression(valueWithType.toString()));
                    }
                }
                if (null != each.getValueIndices()) {
                    for (int index : each.getValueIndices()) {
                        sqlExpressions.add(new SQLPlaceholderExpression(index));
                    }
                }
                Condition condition;
                switch (ShardingOperator.valueOf(each.getOperator().toUpperCase())) {
                    case EQUAL:
                        condition = new Condition(new Column(each.getColumnName(), each.getTableName()), sqlExpressions.get(0));
                        break;
                    case BETWEEN:
                        condition = new Condition(new Column(each.getColumnName(), each.getTableName()), sqlExpressions.get(0), sqlExpressions.get(1));
                        break;
                    case IN:
                        condition = new Condition(new Column(each.getColumnName(), each.getTableName()), sqlExpressions);
                        break;
                    default:
                        throw new UnsupportedOperationException();
                }
                conditions.add(condition);
            }
            result[4] = conditions;
        }
        SQLStatement selectStatement = new SelectStatement();
        if (null != assertObj.getOrderByColumns()) {
            selectStatement.getOrderByList().addAll(Lists.transform(assertObj.getOrderByColumns(), new Function<com.dangdang.ddframe.rdb.sharding.parsing.jaxb.OrderByColumn, OrderBy>() {
                
                @Override
                public OrderBy apply(final com.dangdang.ddframe.rdb.sharding.parsing.jaxb.OrderByColumn input) {
                    return Strings.isNullOrEmpty(input.getName()) ? new OrderBy(input.getIndex(), OrderType.valueOf(input.getOrderByType().toUpperCase())) 
                            : new OrderBy(input.getOwner(), input.getName(), OrderType.valueOf(input.getOrderByType().toUpperCase()), Optional.fromNullable(input.getAlias()));
                }
            }));
        }
        if (null != assertObj.getGroupByColumns()) {
            selectStatement.getGroupByList().addAll(Lists.transform(assertObj.getGroupByColumns(), new Function<com.dangdang.ddframe.rdb.sharding.parsing.jaxb.GroupByColumn, GroupBy>() {
                
                @Override
                public GroupBy apply(final com.dangdang.ddframe.rdb.sharding.parsing.jaxb.GroupByColumn input) {
                    return new GroupBy(
                            Optional.fromNullable(input.getOwner()), input.getName(), OrderType.valueOf(input.getOrderByType().toUpperCase()), Optional.fromNullable(input.getAlias()));
                }
            }));
        }
        if (null != assertObj.getAggregationColumns()) {
            selectStatement.getAggregationSelectItems().addAll(Lists.transform(assertObj.getAggregationColumns(), 
                    new Function<com.dangdang.ddframe.rdb.sharding.parsing.jaxb.AggregationColumn, AggregationSelectItem>() {
                        
                        @Override
                        public AggregationSelectItem apply(final com.dangdang.ddframe.rdb.sharding.parsing.jaxb.AggregationColumn input) {
                            AggregationSelectItem result = new AggregationSelectItem(input.getExpression(), Optional.fromNullable(input.getAlias()), -1, 
                                    AggregationType.valueOf(input.getAggregationType().toUpperCase()));
                            if (null != input.getIndex()) {
                                result.setColumnIndex(input.getIndex());
                            }
                            for (com.dangdang.ddframe.rdb.sharding.parsing.jaxb.AggregationColumn each : input.getDerivedColumns()) {
                                result.getDerivedAggregationSelectItems().add(new AggregationSelectItem(each.getExpression(), Optional.fromNullable(each.getAlias()), -1, 
                                        AggregationType.valueOf(each.getAggregationType().toUpperCase())));
                            }
                            return result;
                        }
                }));
        }
        if (null != assertObj.getLimit()) {
            selectStatement.setLimit(new Limit(
                    assertObj.getLimit().getOffset(), assertObj.getLimit().getRowCount(), assertObj.getLimit().getOffsetParameterIndex(), assertObj.getLimit().getRowCountParameterIndex()));
        }
        result[5] = selectStatement;
        return result;
    }
    
    protected final void assertSQLStatement(final SQLStatement actual) {
        assertConditionContexts(actual);
    }
    
    private void assertConditionContexts(final SQLStatement actual) {
        assertTrue(new ReflectionEquals(expectedConditions).matches(actual.getConditions()));
    }
}
