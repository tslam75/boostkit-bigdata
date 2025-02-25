/*
 * Copyright (C) 2018-2021. Huawei Technologies Co., Ltd. All rights reserved.
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
 */
package io.prestosql.plugin.hive;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.plugin.hive.omnidata.OmniDataNodeManager;
import io.prestosql.plugin.hive.omnidata.OmniDataNodeStatus;
import io.prestosql.spi.HostAddress;
import io.prestosql.spi.connector.QualifiedObjectName;
import io.prestosql.spi.function.BuiltInFunctionHandle;
import io.prestosql.spi.function.FunctionKind;
import io.prestosql.spi.function.Signature;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.ValueSet;
import io.prestosql.spi.relation.CallExpression;
import io.prestosql.spi.relation.InputReferenceExpression;
import io.prestosql.spi.relation.VariableReferenceExpression;
import io.prestosql.spi.type.TypeSignature;
import org.apache.hadoop.fs.Path;
import org.eclipse.jetty.util.URIUtil;
import org.testng.annotations.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Properties;

import static io.prestosql.plugin.hive.HiveBucketing.BucketingVersion.BUCKETING_V1;
import static io.prestosql.plugin.hive.HivePageSourceProvider.modifyDomain;
import static io.prestosql.plugin.hive.HiveTestUtils.TYPE_MANAGER;
import static io.prestosql.plugin.hive.HiveTestUtils.createTestHdfsEnvironment;
import static io.prestosql.plugin.hive.HiveTestUtils.getDefaultHiveDataStreamFactories;
import static io.prestosql.plugin.hive.HiveTestUtils.getDefaultHiveRecordCursorProvider;
import static io.prestosql.plugin.hive.HiveTestUtils.getDefaultHiveSelectiveFactories;
import static io.prestosql.plugin.hive.HiveTestUtils.getNoOpIndexCache;
import static io.prestosql.plugin.hive.HiveType.HIVE_LONG;
import static io.prestosql.plugin.hive.HiveType.HIVE_STRING;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.SmallintType.SMALLINT;
import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;

public class TestHivePageSourceProvider
{
    @Test
    public void testEncodePath()
    {
        URI splitUri0 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405#12345#abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405%2312345%23abcdefgh", splitUri0.getRawPath());
        URI splitUri1 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405%12345%abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405%2512345%25abcdefgh", splitUri1.getRawPath());
        URI splitUri2 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405?12345?abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405%3F12345%3Fabcdefgh", splitUri2.getRawPath());
        URI splitUri3 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405 12345 abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405%2012345%20abcdefgh", splitUri3.getRawPath());
        URI splitUri4 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405^12345^abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405%5E12345%5Eabcdefgh", splitUri4.getRawPath());
        URI splitUri5 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405>12345>abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405%3E12345%3Eabcdefgh", splitUri5.getRawPath());
        URI splitUri6 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405+12345+abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405+12345+abcdefgh", splitUri6.getRawPath());
        URI splitUri7 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405-12345-abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405-12345-abcdefgh", splitUri7.getRawPath());
        URI splitUri8 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405*12345*abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405*12345*abcdefgh", splitUri8.getRawPath());
        URI splitUri9 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405<12345<abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405%3C12345%3Cabcdefgh", splitUri9.getRawPath());
        URI splitUri10 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405_12345_abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405_12345_abcdefgh", splitUri10.getRawPath());
        URI splitUri11 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405=12345=abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405=12345=abcdefgh", splitUri11.getRawPath());
        URI splitUri12 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405@12345@abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405@12345@abcdefgh", splitUri12.getRawPath());
        URI splitUri13 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405$12345$abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405$12345$abcdefgh", splitUri13.getRawPath());
        URI splitUri14 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405&12345&abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405&12345&abcdefgh", splitUri14.getRawPath());
        URI splitUri15 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405!12345!abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405!12345!abcdefgh", splitUri15.getRawPath());
        URI splitUri16 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405[12345[abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405%5B12345%5Babcdefgh", splitUri16.getRawPath());
        URI splitUri17 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405]12345]abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405%5D12345%5Dabcdefgh", splitUri17.getRawPath());
        URI splitUri18 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405{12345{abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405%7B12345%7Babcdefgh", splitUri18.getRawPath());
        URI splitUri19 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405}12345}abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405%7D12345%7Dabcdefgh", splitUri19.getRawPath());
        URI splitUri20 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405\"12345\"abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405%2212345%22abcdefgh", splitUri20.getRawPath());
        URI splitUri21 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405|12345|abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405%7C12345%7Cabcdefgh", splitUri21.getRawPath());
        URI splitUri22 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405\\12345\\abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405%5C12345%5Cabcdefgh", splitUri22.getRawPath());
        URI splitUri23 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405:12345:abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405:12345:abcdefgh", splitUri23.getRawPath());
        URI splitUri24 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405;12345;abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405%3B12345%3Babcdefgh", splitUri24.getRawPath());
        URI splitUri25 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405(12345(abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405(12345(abcdefgh", splitUri25.getRawPath());
        URI splitUri26 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405)12345)abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405)12345)abcdefgh", splitUri26.getRawPath());
        URI splitUri27 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405'12345'abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405%2712345%27abcdefgh", splitUri27.getRawPath());
        URI splitUri28 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405,12345,abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405,12345,abcdefgh", splitUri28.getRawPath());
        URI splitUri29 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405.12345.abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405.12345.abcdefgh", splitUri29.getRawPath());
        URI splitUri30 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405/12345/abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405/12345/abcdefgh", splitUri30.getRawPath());
        URI splitUri31 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405~12345~abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405~12345~abcdefgh", splitUri31.getRawPath());
        URI splitUri32 = URI.create(URIUtil.encodePath("hdfs://localhost:9000/user/hive/warehouse/part=part_1/20200405`12345`abcdefgh"));
        assertEquals("/user/hive/warehouse/part=part_1/20200405%6012345%60abcdefgh", splitUri32.getRawPath());
    }

    @Test
    public void testModifyDomainGreaterThanOrEqual()
    {
        Collection<Object> valueSet = new HashSet<>();
        valueSet.add(Long.valueOf(40));
        VariableReferenceExpression argument1 = new VariableReferenceExpression("arg_1", BIGINT);
        VariableReferenceExpression argument2 = new VariableReferenceExpression("arg_2", BIGINT);
        QualifiedObjectName objectName = new QualifiedObjectName("presto", "default", "$operator$greater_than_or_equal");

        BuiltInFunctionHandle functionHandle = new BuiltInFunctionHandle(new Signature(objectName, FunctionKind.SCALAR, ImmutableList.of(), ImmutableList.of(), new TypeSignature("boolean"), ImmutableList.of(new TypeSignature("bigint"), new TypeSignature("bigint")), false));
        CallExpression filter = new CallExpression("GREATER_THAN_OR_EQUAL", functionHandle, BOOLEAN, ImmutableList.of(argument1, argument2));
        Domain domain = Domain.create(ValueSet.copyOf(BIGINT, valueSet), false);
        domain = modifyDomain(domain, Optional.of(filter));
        assertEquals(domain.getValues().getRanges().getSpan().getHigh().getValueBlock(), Optional.empty());
        assertEquals(domain.getValues().getRanges().getSpan().getLow().getValue(), Long.valueOf(40));
    }

    @Test
    public void testModifyDomainGreaterThan()
    {
        Collection<Object> valueSet = new HashSet<>();
        valueSet.add(Long.valueOf(40));
        VariableReferenceExpression argument1 = new VariableReferenceExpression("arg_1", BIGINT);
        VariableReferenceExpression argument2 = new VariableReferenceExpression("arg_2", BIGINT);
        QualifiedObjectName objectName = new QualifiedObjectName("presto", "default", "$operator$greater_than");

        BuiltInFunctionHandle functionHandle = new BuiltInFunctionHandle(new Signature(objectName, FunctionKind.SCALAR, ImmutableList.of(), ImmutableList.of(), new TypeSignature("boolean"), ImmutableList.of(new TypeSignature("bigint"), new TypeSignature("bigint")), false));
        CallExpression filter = new CallExpression("GREATER_THAN", functionHandle, BOOLEAN, ImmutableList.of(argument1, argument2));
        Domain domain = Domain.create(ValueSet.copyOf(BIGINT, valueSet), false);
        domain = modifyDomain(domain, Optional.of(filter));
        assertEquals(domain.getValues().getRanges().getSpan().getHigh().getValueBlock(), Optional.empty());
        assertEquals(domain.getValues().getRanges().getSpan().getLow().getValue(), Long.valueOf(40));
    }

    @Test
    public void testModifyDomainLessThanOrEqual()
    {
        Collection<Object> valueSet = new HashSet<>();
        valueSet.add(Long.valueOf(40));
        VariableReferenceExpression argument1 = new VariableReferenceExpression("arg_1", BIGINT);
        VariableReferenceExpression argument2 = new VariableReferenceExpression("arg_2", BIGINT);
        QualifiedObjectName objectName = new QualifiedObjectName("presto", "default", "$operator$less_than_or_equal");

        BuiltInFunctionHandle functionHandle = new BuiltInFunctionHandle(new Signature(objectName, FunctionKind.SCALAR, ImmutableList.of(), ImmutableList.of(), new TypeSignature("boolean"), ImmutableList.of(new TypeSignature("bigint"), new TypeSignature("bigint")), false));
        CallExpression filter = new CallExpression("LESS_THAN", functionHandle, BOOLEAN, ImmutableList.of(argument1, argument2));
        Domain domain = Domain.create(ValueSet.copyOf(BIGINT, valueSet), false);
        domain = modifyDomain(domain, Optional.of(filter));
        assertEquals(domain.getValues().getRanges().getSpan().getHigh().getValue(), Long.valueOf(40));
        assertEquals(domain.getValues().getRanges().getSpan().getLow().getValueBlock(), Optional.empty());
    }

    @Test
    public void testModifyDomainLessThan()
    {
        Collection<Object> valueSet = new HashSet<>();
        valueSet.add(Long.valueOf(40));
        VariableReferenceExpression argument1 = new VariableReferenceExpression("arg_1", BIGINT);
        VariableReferenceExpression argument2 = new VariableReferenceExpression("arg_2", BIGINT);
        QualifiedObjectName objectName = new QualifiedObjectName("presto", "default", "$operator$less_than");

        BuiltInFunctionHandle functionHandle = new BuiltInFunctionHandle(new Signature(objectName, FunctionKind.SCALAR, ImmutableList.of(), ImmutableList.of(), new TypeSignature("boolean"), ImmutableList.of(new TypeSignature("bigint"), new TypeSignature("bigint")), false));
        CallExpression filter = new CallExpression("LESS_THAN_OR_EQUAL", functionHandle, BOOLEAN, ImmutableList.of(argument1, argument2));
        Domain domain = Domain.create(ValueSet.copyOf(BIGINT, valueSet), false);
        domain = modifyDomain(domain, Optional.of(filter));
        assertEquals(domain.getValues().getRanges().getSpan().getHigh().getValue(), Long.valueOf(40));
        assertEquals(domain.getValues().getRanges().getSpan().getLow().getValueBlock(), Optional.empty());
    }

    @Test
    public void testGetSplitOmniDataAddr() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException
    {
        Properties schema = new Properties();
        schema.setProperty("foo", "bar");
        schema.setProperty("bar", "baz");

        ImmutableList<HivePartitionKey> partitionKeys = ImmutableList.of(new HivePartitionKey("a", "apple"), new HivePartitionKey("b", "42"));
        ImmutableList<HostAddress> addresses = ImmutableList.of(HostAddress.fromParts("127.0.0.1", 44), HostAddress.fromParts("127.0.0.1", 45));

        DeleteDeltaLocations.Builder deleteDeltaLocationsBuilder = DeleteDeltaLocations.builder(new Path("file:///data/fullacid"));
        deleteDeltaLocationsBuilder.addDeleteDelta(new Path("file:///data/fullacid/delete_delta_0000004_0000004_0000"), 4L, 4L, 0);
        deleteDeltaLocationsBuilder.addDeleteDelta(new Path("file:///data/fullacid/delete_delta_0000007_0000007_0000"), 7L, 7L, 0);
        DeleteDeltaLocations deleteDeltaLocations = deleteDeltaLocationsBuilder.build().get();

        Map<String, String> customSplitInfo = ImmutableMap.of("key", "value");

        HiveSplit hiveSplit = new HiveSplit(
                "db",
                "table",
                "partitionId",
                "path",
                42,
                87,
                88,
                0,
                schema,
                partitionKeys,
                addresses,
                OptionalInt.empty(),
                true,
                ImmutableMap.of(1, HIVE_STRING),
                Optional.of(new HiveSplit.BucketConversion(
                        BUCKETING_V1,
                        32,
                        16,
                        ImmutableList.of(new HiveColumnHandle("col", HIVE_LONG, BIGINT.getTypeSignature(), 5, HiveColumnHandle.ColumnType.REGULAR, Optional.of("comment"))))),
                false,
                Optional.of(deleteDeltaLocations),
                Optional.empty(),
                false,
                customSplitInfo);

        HiveOffloadExpression hiveOffloadExpression =
                new HiveOffloadExpression(Collections.emptySet(), new InputReferenceExpression(0, SMALLINT), Optional.empty(), OptionalLong.of(5), Collections.emptyMap());

        Method method = HivePageSourceProvider.class.getDeclaredMethod("getSplitOmniDataAddr", HiveOffloadExpression.class, HiveSplit.class);
        method.setAccessible(true);

        HiveConfig config = new HiveConfig();
        OmniDataNodeManager omniDataNodeManager = new OmniDataNodeManager();
        HivePageSourceProvider hivePageSourceProvider = new HivePageSourceProvider(omniDataNodeManager, config, createTestHdfsEnvironment(config), getDefaultHiveRecordCursorProvider(config), getDefaultHiveDataStreamFactories(config), TYPE_MANAGER, getNoOpIndexCache(), getDefaultHiveSelectiveFactories(config));
        Object actual = method.invoke(hivePageSourceProvider, hiveOffloadExpression, hiveSplit);
        assertEquals(actual, Optional.empty());

        omniDataNodeManager.setAllNodesForTest("127.0.0.1", mock(OmniDataNodeStatus.class));
        hivePageSourceProvider = new HivePageSourceProvider(omniDataNodeManager, config, createTestHdfsEnvironment(config), getDefaultHiveRecordCursorProvider(config), getDefaultHiveDataStreamFactories(config), TYPE_MANAGER, getNoOpIndexCache(), getDefaultHiveSelectiveFactories(config));
        actual = method.invoke(hivePageSourceProvider, hiveOffloadExpression, hiveSplit);
        assertEquals(actual.toString(), "Optional[null,null]");
    }
}
