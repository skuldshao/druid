/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment.realtime.appenderator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.JSONParseSpec;
import org.apache.druid.data.input.impl.MapInputRowParser;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.indexer.granularity.UniformGranularitySpec;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.java.util.common.FileUtils;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.java.util.emitter.core.NoopEmitter;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.query.aggregation.CountAggregatorFactory;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.segment.IndexIO;
import org.apache.druid.segment.IndexMerger;
import org.apache.druid.segment.IndexMergerV9;
import org.apache.druid.segment.IndexSpec;
import org.apache.druid.segment.column.ColumnConfig;
import org.apache.druid.segment.incremental.ParseExceptionHandler;
import org.apache.druid.segment.incremental.RowIngestionMeters;
import org.apache.druid.segment.incremental.SimpleRowIngestionMeters;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.segment.indexing.TuningConfig;
import org.apache.druid.segment.loading.DataSegmentPusher;
import org.apache.druid.segment.metadata.CentralizedDatasourceSchemaConfig;
import org.apache.druid.segment.realtime.SegmentGenerationMetrics;
import org.apache.druid.segment.writeout.OffHeapMemorySegmentWriteOutMediumFactory;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.partition.LinearShardSpec;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class BatchAppenderatorTester implements AutoCloseable
{
  public static final String DATASOURCE = "foo";

  private final DataSchema schema;
  private final AppenderatorConfig tuningConfig;
  private final SegmentGenerationMetrics metrics;
  private final ObjectMapper objectMapper;
  private final Appenderator appenderator;
  private final ServiceEmitter emitter;

  private final List<DataSegment> pushedSegments = new CopyOnWriteArrayList<>();

  public BatchAppenderatorTester(
      final int maxRowsInMemory
  )
  {
    this(maxRowsInMemory, -1, null, false);
  }

  public BatchAppenderatorTester(
      final int maxRowsInMemory,
      final boolean enablePushFailure
  )
  {
    this(maxRowsInMemory, -1, null, enablePushFailure);
  }

  public BatchAppenderatorTester(
      final int maxRowsInMemory,
      final long maxSizeInBytes,
      final boolean enablePushFailure
  )
  {
    this(maxRowsInMemory, maxSizeInBytes, null, enablePushFailure);
  }

  public BatchAppenderatorTester(
      final int maxRowsInMemory,
      final long maxSizeInBytes,
      final File basePersistDirectory,
      final boolean enablePushFailure
  )
  {
    this(
        maxRowsInMemory,
        maxSizeInBytes,
        basePersistDirectory,
        enablePushFailure,
        new SimpleRowIngestionMeters(),
        false
    );
  }

  public BatchAppenderatorTester(
      final int maxRowsInMemory,
      final long maxSizeInBytes,
      @Nullable final File basePersistDirectory,
      final boolean enablePushFailure,
      final RowIngestionMeters rowIngestionMeters
  )
  {
    this(maxRowsInMemory, maxSizeInBytes, basePersistDirectory, enablePushFailure, rowIngestionMeters,
         false
    );
  }
  
  public BatchAppenderatorTester(
      final int maxRowsInMemory,
      final long maxSizeInBytes,
      @Nullable final File basePersistDirectory,
      final boolean enablePushFailure,
      final RowIngestionMeters rowIngestionMeters,
      final boolean skipBytesInMemoryOverheadCheck
  )
  {
    objectMapper = new DefaultObjectMapper();
    objectMapper.registerSubtypes(LinearShardSpec.class);

    final Map<String, Object> parserMap = objectMapper.convertValue(
        new MapInputRowParser(
            new JSONParseSpec(
                new TimestampSpec("ts", "auto", null),
                DimensionsSpec.EMPTY,
                null,
                null,
                null
            )
        ),
        Map.class
    );

    schema = DataSchema.builder()
                       .withDataSource(DATASOURCE)
                       .withAggregators(
                           new CountAggregatorFactory("count"),
                           new LongSumAggregatorFactory("met", "met")
                       )
                       .withGranularity(
                           new UniformGranularitySpec(Granularities.MINUTE, Granularities.NONE, null)
                       )
                       .withParserMap(parserMap)
                       .withObjectMapper(objectMapper)
                       .build();

    tuningConfig = new TestAppenderatorConfig(
        TuningConfig.DEFAULT_APPENDABLE_INDEX,
        maxRowsInMemory,
        maxSizeInBytes == 0L ? getDefaultMaxBytesInMemory() : maxSizeInBytes,
        skipBytesInMemoryOverheadCheck,
        IndexSpec.DEFAULT,
        0,
        false,
        0L,
        OffHeapMemorySegmentWriteOutMediumFactory.instance(),
        IndexMerger.UNLIMITED_MAX_COLUMNS_TO_MERGE,
        basePersistDirectory == null ? createNewBasePersistDirectory() : basePersistDirectory
    );
    metrics = new SegmentGenerationMetrics();

    IndexIO indexIO = new IndexIO(objectMapper, ColumnConfig.DEFAULT);
    IndexMergerV9 indexMerger = new IndexMergerV9(
        objectMapper,
        indexIO,
        OffHeapMemorySegmentWriteOutMediumFactory.instance()
    );

    emitter = new ServiceEmitter(
        "test",
        "test",
        new NoopEmitter()
    );
    emitter.start();
    EmittingLogger.registerEmitter(emitter);
    DataSegmentPusher dataSegmentPusher = new DataSegmentPusher()
    {
      private boolean mustFail = true;

      @Deprecated
      @Override
      public String getPathForHadoop(String dataSource)
      {
        return getPathForHadoop();
      }

      @Override
      public String getPathForHadoop()
      {
        throw new UnsupportedOperationException();
      }

      @Override
      public DataSegment push(File file, DataSegment segment, boolean useUniquePath) throws IOException
      {
        if (enablePushFailure && mustFail) {
          mustFail = false;
          throw new IOException("Push failure test");
        } else if (enablePushFailure) {
          mustFail = true;
        }
        pushedSegments.add(segment);
        return segment;
      }

      @Override
      public Map<String, Object> makeLoadSpec(URI uri)
      {
        throw new UnsupportedOperationException();
      }
    };
    appenderator = Appenderators.createBatch(
        schema.getDataSource(),
        schema,
        tuningConfig,
        metrics,
        dataSegmentPusher,
        objectMapper,
        indexIO,
        indexMerger,
        rowIngestionMeters,
        new ParseExceptionHandler(rowIngestionMeters, false, Integer.MAX_VALUE, 0),
        CentralizedDatasourceSchemaConfig.create()
    );
  }

  private long getDefaultMaxBytesInMemory()
  {
    return (Runtime.getRuntime().totalMemory()) / 3;
  }

  public DataSchema getSchema()
  {
    return schema;
  }

  public AppenderatorConfig getTuningConfig()
  {
    return tuningConfig;
  }

  public SegmentGenerationMetrics getMetrics()
  {
    return metrics;
  }

  public ObjectMapper getObjectMapper()
  {
    return objectMapper;
  }

  public Appenderator getAppenderator()
  {
    return appenderator;
  }

  public List<DataSegment> getPushedSegments()
  {
    return pushedSegments;
  }

  @Override
  public void close() throws Exception
  {
    appenderator.close();
    emitter.close();
    FileUtils.deleteDirectory(tuningConfig.getBasePersistDirectory());
  }

  private static File createNewBasePersistDirectory()
  {
    return FileUtils.createTempDir("druid-batch-persist");
  }
}
