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

package org.apache.druid.frame.segment;

import org.apache.druid.frame.Frame;
import org.apache.druid.frame.read.FrameReader;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.query.rowsandcols.concrete.ColumnBasedFrameRowsAndColumns;
import org.apache.druid.segment.CloseableShapeshifter;
import org.apache.druid.segment.CursorFactory;
import org.apache.druid.segment.Segment;
import org.apache.druid.timeline.SegmentId;
import org.joda.time.Interval;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A {@link Segment} implementation based on a single {@link Frame}.
 *
 * This class is used for both columnar and row-based frames.
 */
public class FrameSegment implements Segment
{
  private final Frame frame;
  private final FrameReader frameReader;

  public FrameSegment(Frame frame, FrameReader frameReader)
  {
    this.frame = frame;
    this.frameReader = frameReader;
  }

  @Override
  public SegmentId getId()
  {
    return null;
  }

  @Override
  public Interval getDataInterval()
  {
    return Intervals.ETERNITY;
  }

  @Override
  public void close()
  {
    // Nothing to close.
  }

  @SuppressWarnings("unchecked")
  @Nullable
  @Override
  public <T> T as(@Nonnull Class<T> clazz)
  {
    if (CloseableShapeshifter.class.equals(clazz)) {
      return (T) new ColumnBasedFrameRowsAndColumns(frame, frameReader.signature());
    } else if (CursorFactory.class.equals(clazz)) {
      return (T) frameReader.makeCursorFactory(frame);
    }
    return null;
  }
}
