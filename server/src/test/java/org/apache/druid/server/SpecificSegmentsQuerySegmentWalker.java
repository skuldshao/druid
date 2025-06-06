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

package org.apache.druid.server;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.Closeables;
import com.google.inject.Injector;
import org.apache.druid.error.DruidException;
import org.apache.druid.query.DataSource;
import org.apache.druid.query.FrameBasedInlineDataSource;
import org.apache.druid.query.InlineDataSource;
import org.apache.druid.query.LookupDataSource;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryRunnerFactoryConglomerate;
import org.apache.druid.query.QuerySegmentWalker;
import org.apache.druid.query.SegmentDescriptor;
import org.apache.druid.query.lookup.LookupExtractorFactoryContainer;
import org.apache.druid.query.lookup.LookupExtractorFactoryContainerProvider;
import org.apache.druid.segment.CompleteSegment;
import org.apache.druid.segment.FrameBasedInlineSegmentWrangler;
import org.apache.druid.segment.IncrementalIndexSegment;
import org.apache.druid.segment.InlineSegmentWrangler;
import org.apache.druid.segment.LookupSegmentWrangler;
import org.apache.druid.segment.MapSegmentWrangler;
import org.apache.druid.segment.QueryableIndex;
import org.apache.druid.segment.QueryableIndexSegment;
import org.apache.druid.segment.ReferenceCountedSegmentProvider;
import org.apache.druid.segment.Segment;
import org.apache.druid.segment.SegmentWrangler;
import org.apache.druid.segment.incremental.IncrementalIndex;
import org.apache.druid.segment.join.JoinableFactory;
import org.apache.druid.segment.join.JoinableFactoryWrapper;
import org.apache.druid.server.initialization.ServerConfig;
import org.apache.druid.server.metrics.NoopServiceEmitter;
import org.apache.druid.sql.calcite.util.datasets.TestDataSet;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.SegmentId;
import org.apache.druid.timeline.VersionedIntervalTimeline;
import org.apache.druid.timeline.partition.LinearShardSpec;
import org.joda.time.Interval;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A self-contained class that executes queries similarly to the normal Druid query stack.
 *
 * {@link ClientQuerySegmentWalker}, the same class that Brokers use as the entry point for their query stack, is
 * used directly. It, and the sub-walkers it needs, are created by {@link QueryStackTests}.
 */
public class SpecificSegmentsQuerySegmentWalker implements QuerySegmentWalker, Closeable
{
  private final QuerySegmentWalker walker;
  private final Map<String, VersionedIntervalTimeline<String, ReferenceCountedSegmentProvider>> timelines;
  private final List<CompleteSegment> segments = new ArrayList<>();
  private static final LookupExtractorFactoryContainerProvider LOOKUP_EXTRACTOR_FACTORY_CONTAINER_PROVIDER =
      new LookupExtractorFactoryContainerProvider()
      {
        @Override
        public Set<String> getAllLookupNames()
        {
          return Collections.emptySet();
        }

        @Override
        public Optional<LookupExtractorFactoryContainer> get(String lookupName)
        {
          return Optional.empty();
        }

        @Override
        public String getCanonicalLookupName(String lookupName)
        {
          return lookupName;
        }
      };

  public static SpecificSegmentsQuerySegmentWalker createWalker(
      final QueryRunnerFactoryConglomerate conglomerate)
  {
    return createWalker(QueryStackTests.injectorWithLookup(), conglomerate);
  }

  /**
   * Create an instance using the provided query runner factory conglomerate and lookup provider.
   * If a JoinableFactory is provided, it will be used instead of the default. If a scheduler is included,
   * the runner will schedule queries according to the scheduling config.
   */
  public static SpecificSegmentsQuerySegmentWalker createWalker(
      final Injector injector,
      final QueryRunnerFactoryConglomerate conglomerate,
      final SegmentWrangler segmentWrangler,
      final JoinableFactoryWrapper joinableFactoryWrapper,
      final QueryScheduler scheduler
  )
  {
    Map<String, VersionedIntervalTimeline<String, ReferenceCountedSegmentProvider>> timelines = new HashMap<>();
    NoopServiceEmitter emitter = new NoopServiceEmitter();
    ServerConfig serverConfig = new ServerConfig();

    return new SpecificSegmentsQuerySegmentWalker(
        timelines,
        QueryStackTests.createClientQuerySegmentWalker(
            injector,
            QueryStackTests.createClusterQuerySegmentWalker(
                timelines,
                conglomerate,
                scheduler,
                injector
            ),
            QueryStackTests.createLocalQuerySegmentWalker(
                conglomerate,
                segmentWrangler,
                joinableFactoryWrapper,
                scheduler,
                emitter
            ),
            conglomerate,
            joinableFactoryWrapper.getJoinableFactory(),
            serverConfig,
            emitter
        )
    );
  }

  /**
   * Create an instance without any lookups and with a default {@link JoinableFactory} that handles only inline
   * datasources.
   */
  public static SpecificSegmentsQuerySegmentWalker createWalker(
      final Injector injector,
      final QueryRunnerFactoryConglomerate conglomerate
  )
  {
    return createWalker(
        injector,
        conglomerate,
        new MapSegmentWrangler(
            ImmutableMap.<Class<? extends DataSource>, SegmentWrangler>builder()
                        .put(InlineDataSource.class, new InlineSegmentWrangler())
                        .put(FrameBasedInlineDataSource.class, new FrameBasedInlineSegmentWrangler())
                        .put(
                            LookupDataSource.class,
                            new LookupSegmentWrangler(LOOKUP_EXTRACTOR_FACTORY_CONTAINER_PROVIDER)
                        )
                        .build()
        ),
        new JoinableFactoryWrapper(QueryStackTests.makeJoinableFactoryForLookup(LOOKUP_EXTRACTOR_FACTORY_CONTAINER_PROVIDER)),
        QueryStackTests.DEFAULT_NOOP_SCHEDULER
    );
  }

  public SpecificSegmentsQuerySegmentWalker(
      Map<String, VersionedIntervalTimeline<String, ReferenceCountedSegmentProvider>> timelines,
      QuerySegmentWalker walker)
  {
    this.timelines = timelines;
    this.walker = walker;
  }

  public SpecificSegmentsQuerySegmentWalker add(final DataSegment descriptor, final Segment segment)
  {
    return add(new CompleteSegment(descriptor, segment));
  }

  public SpecificSegmentsQuerySegmentWalker add(CompleteSegment completeSegment)
  {
    DataSegment descriptor = completeSegment.getDataSegment();
    Segment segment = completeSegment.getSegment();

    final ReferenceCountedSegmentProvider referenceCountingSegment =
        ReferenceCountedSegmentProvider.wrapSegment(
            segment,
            descriptor.getShardSpec()
        );
    final VersionedIntervalTimeline<String, ReferenceCountedSegmentProvider> timeline = timelines.computeIfAbsent(
        descriptor.getDataSource(),
        datasource -> new VersionedIntervalTimeline<>(Ordering.natural())
    );
    timeline.add(
        descriptor.getInterval(),
        descriptor.getVersion(),
        descriptor.getShardSpec().createChunk(referenceCountingSegment)
    );
    segments.add(completeSegment);
    return this;
  }

  public SpecificSegmentsQuerySegmentWalker add(final DataSegment descriptor, final QueryableIndex index)
  {
    return add(descriptor, new QueryableIndexSegment(index, descriptor.getId()));
  }

  public SpecificSegmentsQuerySegmentWalker add(final DataSegment descriptor, final IncrementalIndex index)
  {
    return add(descriptor, new IncrementalIndexSegment(index, descriptor.getId()));
  }

  public List<DataSegment> getSegments()
  {
    return Lists.transform(segments, CompleteSegment::getDataSegment);
  }

  @Override
  public <T> QueryRunner<T> getQueryRunnerForIntervals(final Query<T> query, final Iterable<Interval> intervals)
  {
    return walker.getQueryRunnerForIntervals(query, intervals);
  }

  @Override
  public <T> QueryRunner<T> getQueryRunnerForSegments(final Query<T> query, final Iterable<SegmentDescriptor> specs)
  {
    return walker.getQueryRunnerForSegments(query, specs);
  }

  @Override
  public void close() throws IOException
  {
    for (Closeable closeable : segments) {
      Closeables.close(closeable, true);
    }
  }

  public SpecificSegmentsQuerySegmentWalker add(TestDataSet dataset, File tmpDir)
  {
    QueryableIndex indexNumericDims = dataset.makeIndex(tmpDir);
    return add(
        DataSegment.builder()
            .dataSource(dataset.getName())
            .interval(indexNumericDims.getDataInterval())
            .version("1")
            .shardSpec(new LinearShardSpec(0))
            .size(0)
            .build(),
        indexNumericDims
    );
  }

  public CompleteSegment getSegment(SegmentId segmentId)
  {
    List<CompleteSegment> matches = new ArrayList<>(1);
    for (CompleteSegment s : segments) {
      SegmentId id = s.getDataSegment().getId();
      if (id.equals(segmentId)) {
        matches.add(s);
      }
    }
    if (matches.size() != 1) {
      throw DruidException.defensive(
          "SegmentId [%s] has unexpected number of matches! [%s]",
          segmentId,
          matches
      );
    }
    return matches.get(0);
  }
}
