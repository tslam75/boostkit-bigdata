/*
 * Copyright (C) 2020-2022. Huawei Technologies Co., Ltd. All rights reserved.
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

package nova.hetu.olk.operator.benchmark;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.operator.OperatorFactory;
import io.prestosql.spi.Page;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.spi.type.Type;
import nova.hetu.olk.operator.BuildOffHeapOmniOperator;
import nova.hetu.olk.operator.benchmark.AbstractOperatorBenchmarkContext.AbstractOmniOperatorBenchmarkContext;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.DateType.DATE;
import static io.prestosql.spi.type.DecimalType.createDecimalType;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static io.prestosql.spi.type.VarcharType.createVarcharType;

@State(Scope.Thread)
@Fork(1)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkBuildOffHeapOmniOperator
{
    public static final int TOTAL_PAGES = 100;
    public static final int ROWS_PER_PAGE = 10000;

    private static final Map<String, ImmutableList<Type>> INPUT_TYPES = ImmutableMap
            .<String, ImmutableList<Type>>builder().put("boolean", ImmutableList.of(BOOLEAN))
            .put("long", ImmutableList.of(BIGINT)).put("int", ImmutableList.of(INTEGER))
            .put("decimal", ImmutableList.of(createDecimalType())).put("date", ImmutableList.of(DATE))
            .put("double", ImmutableList.of(DOUBLE)).put("varchar", ImmutableList.of(createVarcharType(50)))
            .build();

    @State(Scope.Thread)
    public static class BenchmarkContext
            extends AbstractOmniOperatorBenchmarkContext
    {
        @Param({"int", "long", "boolean", "double", "decimal", "date", "varchar"})
        String testGroup = "int";

        @Param({"false", "true"})
        boolean dictionaryBlocks;

        @Override
        protected List<Page> buildPages()
        {
            return buildPages(INPUT_TYPES.get(testGroup), TOTAL_PAGES, ROWS_PER_PAGE, dictionaryBlocks);
        }

        @Override
        protected OperatorFactory createOperatorFactory()
        {
            return new BuildOffHeapOmniOperator.BuildOffHeapOmniOperatorFactory(0, new PlanNodeId("test"),
                    INPUT_TYPES.get(testGroup));
        }

        @Override
        protected List<Page> forkPages(List<Page> pages)
        {
            List<Page> slicedPages = new ArrayList<>(pages.size());
            for (Page page : pages) {
                slicedPages.add(page.getRegion(0, page.getPositionCount()));
            }
            return slicedPages;
        }
    }

    @Benchmark
    public List<Page> buildOffHeap(BenchmarkContext context)
    {
        return context.doDefaultBenchMark();
    }

    public static void main(String[] args) throws RunnerException
    {
        Options options = new OptionsBuilder().verbosity(VerboseMode.NORMAL)
                .include(".*" + BenchmarkBuildOffHeapOmniOperator.class.getSimpleName() + ".*").build();
        new Runner(options).run();
    }
}
