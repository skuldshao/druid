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

package org.apache.druid.segment;

import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.segment.join.table.IndexedTable;
import org.apache.druid.timeline.SegmentId;
import org.easymock.EasyMock;
import org.joda.time.Days;
import org.joda.time.Interval;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.Closeable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 */
public class ReferenceCountingSegmentTest
{
  private ReferenceCountedSegmentProvider segment;
  private ExecutorService exec;

  private final SegmentId segmentId = SegmentId.dummy("test_segment");
  private final Interval dataInterval = new Interval(DateTimes.nowUtc().minus(Days.days(1)), DateTimes.nowUtc());
  private QueryableIndex index;
  private CursorFactory cursorFactory;
  private IndexedTable indexedTable;
  private int underlyingSegmentClosedCount;

  @Before
  public void setUp()
  {
    underlyingSegmentClosedCount = 0;
    index = EasyMock.createNiceMock(QueryableIndex.class);
    cursorFactory = EasyMock.createNiceMock(CursorFactory.class);
    indexedTable = EasyMock.createNiceMock(IndexedTable.class);

    segment = ReferenceCountedSegmentProvider.wrapRootGenerationSegment(
        new Segment()
        {
          @Override
          public SegmentId getId()
          {
            return segmentId;
          }

          @Override
          public Interval getDataInterval()
          {
            return dataInterval;
          }

          @Override
          public <T> T as(Class<T> clazz)
          {
            if (clazz.equals(QueryableIndex.class)) {
              return (T) index;
            } else if (clazz.equals(CursorFactory.class)) {
              return (T) cursorFactory;
            } else if (clazz.equals(IndexedTable.class)) {
              return (T) indexedTable;
            }
            return null;
          }

          @Override
          public void close()
          {
            underlyingSegmentClosedCount++;
          }
        }
    );

    exec = Executors.newSingleThreadExecutor();
  }

  @Test
  public void testMultipleClose() throws Exception
  {
    Assert.assertEquals(0, underlyingSegmentClosedCount);
    Assert.assertFalse(segment.isClosed());
    Assert.assertTrue(segment.increment());
    Assert.assertEquals(1, segment.getNumReferences());

    Closeable closeable = segment.decrementOnceCloseable();
    Assert.assertEquals(0, underlyingSegmentClosedCount);
    closeable.close();
    Assert.assertEquals(0, underlyingSegmentClosedCount);
    closeable.close();
    Assert.assertEquals(0, underlyingSegmentClosedCount);
    exec.submit(
        () -> {
          try {
            closeable.close();
          }
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
    ).get();
    Assert.assertEquals(0, segment.getNumReferences());
    Assert.assertEquals(0, underlyingSegmentClosedCount);
    Assert.assertFalse(segment.isClosed());

    // close for reals
    segment.close();
    Assert.assertTrue(segment.isClosed());
    Assert.assertEquals(1, underlyingSegmentClosedCount);
    // ... but make sure it only happens once
    segment.close();
    Assert.assertEquals(1, underlyingSegmentClosedCount);
    exec.submit(
        () -> {
          try {
            segment.close();
          }
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
    ).get();

    Assert.assertEquals(0, segment.getNumReferences());
    Assert.assertTrue(segment.isClosed());
    Assert.assertEquals(1, underlyingSegmentClosedCount);

    segment.increment();
    segment.increment();
    segment.increment();
    Assert.assertEquals(0, segment.getNumReferences());
    Assert.assertEquals(1, underlyingSegmentClosedCount);
    segment.close();
    Assert.assertEquals(0, segment.getNumReferences());
    Assert.assertEquals(1, underlyingSegmentClosedCount);
  }
}
