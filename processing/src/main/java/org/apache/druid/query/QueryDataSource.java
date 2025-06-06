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

package org.apache.druid.query;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.base.Preconditions;
import org.apache.druid.error.DruidException;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.query.union.UnionQuery;
import org.apache.druid.segment.SegmentMapFunction;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@JsonTypeName("query")
public class QueryDataSource implements DataSource
{
  @JsonProperty
  private final Query<?> query;

  @JsonCreator
  public QueryDataSource(@JsonProperty("query") Query query)
  {
    this.query = Preconditions.checkNotNull(query, "'query' must be nonnull");
  }

  @Override
  public Set<String> getTableNames()
  {
    Set<String> names = new HashSet<>();
    for (DataSource ds : getQueryDataSources()) {
      names.addAll(ds.getTableNames());
    }
    return names;
  }

  @JsonProperty
  public Query getQuery()
  {
    return query;
  }

  @Override
  public List<DataSource> getChildren()
  {
    return getQueryDataSources();
  }

  private List<DataSource> getQueryDataSources()
  {
    if (query instanceof UnionQuery) {
      return ((UnionQuery) query).getDataSources();
    }
    return Collections.singletonList(query.getDataSource());
  }

  @Override
  public DataSource withChildren(List<DataSource> children)
  {
    if (query instanceof UnionQuery) {
      return new QueryDataSource(((UnionQuery) query).withDataSources(children));
    } else {
      if (children.size() != 1) {
        throw new IAE("Must have exactly one child");
      }
      return new QueryDataSource(query.withDataSource(children.get(0)));
    }
  }

  @Override
  public boolean isCacheable(boolean isBroker)
  {
    return false;
  }

  @Override
  public boolean isGlobal()
  {
    return false;
  }

  @Override
  public boolean isProcessable()
  {
    return false;
  }

  @Override
  public SegmentMapFunction createSegmentMapFunction(Query query)
  {
    throw DruidException.defensive("Creating a segmentMapFunction for a QueryDataSource is not supported [%s].", query);
  }

  @Override
  public byte[] getCacheKey()
  {
    return null;
  }

  @Override
  public String toString()
  {
    return query.toString();
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    QueryDataSource that = (QueryDataSource) o;

    if (!query.equals(that.query)) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode()
  {
    return query.hashCode();
  }
}
