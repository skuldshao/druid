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

package org.apache.druid.query.aggregation.datasketches.theta.sql;

import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.datasketches.SketchQueryContext;
import org.apache.druid.query.aggregation.datasketches.theta.SketchAggregatorFactory;
import org.apache.druid.query.aggregation.datasketches.theta.SketchMergeAggregatorFactory;
import org.apache.druid.query.aggregation.datasketches.theta.SketchModule;
import org.apache.druid.query.dimension.DefaultDimensionSpec;
import org.apache.druid.query.dimension.DimensionSpec;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.ValueType;
import org.apache.druid.sql.calcite.aggregation.Aggregation;
import org.apache.druid.sql.calcite.aggregation.SqlAggregator;
import org.apache.druid.sql.calcite.expression.DruidExpression;
import org.apache.druid.sql.calcite.expression.Expressions;
import org.apache.druid.sql.calcite.planner.Calcites;
import org.apache.druid.sql.calcite.planner.PlannerConfig;
import org.apache.druid.sql.calcite.planner.PlannerContext;
import org.apache.druid.sql.calcite.rel.InputAccessor;
import org.apache.druid.sql.calcite.rel.VirtualColumnRegistry;

import javax.annotation.Nullable;
import java.util.List;

public abstract class ThetaSketchBaseSqlAggregator implements SqlAggregator
{
  private final boolean finalizeSketch;

  protected ThetaSketchBaseSqlAggregator(boolean finalizeSketch)
  {
    this.finalizeSketch = finalizeSketch;
  }

  @Nullable
  @Override
  public Aggregation toDruidAggregation(
      PlannerContext plannerContext,
      VirtualColumnRegistry virtualColumnRegistry,
      String name,
      AggregateCall aggregateCall,
      InputAccessor inputAccessor,
      List<Aggregation> existingAggregations,
      boolean finalizeAggregations
  )
  {
    // Don't use Aggregations.getArgumentsForSimpleAggregator, since it won't let us use direct column access
    // for string columns.
    final RexNode columnRexNode = inputAccessor.getField(aggregateCall.getArgList().get(0));

    final DruidExpression columnArg = Expressions.toDruidExpression(
        plannerContext,
        inputAccessor.getInputRowSignature(),
        columnRexNode
    );
    if (columnArg == null) {
      return null;
    }

    final int sketchSize;
    if (aggregateCall.getArgList().size() >= 2) {
      final RexNode sketchSizeArg = inputAccessor.getField(aggregateCall.getArgList().get(1));

      if (!sketchSizeArg.isA(SqlKind.LITERAL)) {
        // logK must be a literal in order to plan.
        return null;
      }

      sketchSize = ((Number) RexLiteral.value(sketchSizeArg)).intValue();
    } else {
      sketchSize = SketchAggregatorFactory.DEFAULT_MAX_SKETCH_SIZE;
    }

    final AggregatorFactory aggregatorFactory;
    final String aggregatorName = finalizeAggregations ? Calcites.makePrefixedName(name, "a") : name;

    if (columnArg.isDirectColumnAccess()
        && inputAccessor.getInputRowSignature()
                        .getColumnType(columnArg.getDirectColumn())
                        .map(type -> (
                            SketchModule.THETA_SKETCH_TYPE.equals(type) ||
                            SketchModule.MERGE_TYPE.equals(type) ||
                            SketchModule.BUILD_TYPE.equals(type)
                        ))
                        .orElse(false)) {
      aggregatorFactory = new SketchMergeAggregatorFactory(
          aggregatorName,
          columnArg.getDirectColumn(),
          sketchSize,
          finalizeSketch || SketchQueryContext.isFinalizeOuterSketches(plannerContext),
          null,
          null
      );
    } else {
      final RelDataType dataType = columnRexNode.getType();
      final ColumnType inputType = Calcites.getColumnTypeForRelDataType(dataType);
      if (inputType == null) {
        throw new ISE(
            "Cannot translate sqlTypeName[%s] to Druid type for field[%s]",
            dataType.getSqlTypeName(),
            aggregatorName
        );
      }

      if (inputType.is(ValueType.COMPLEX)) {
        if (!isValidComplexInputType(inputType)) {
          plannerContext.setPlanningError(
              "Using APPROX_COUNT_DISTINCT() or enabling approximation with COUNT(DISTINCT) is not supported for"
              + " column type [%s]. You can disable approximation by setting [%s: false] in the query context.",
              columnArg.getDruidType(),
              PlannerConfig.CTX_KEY_USE_APPROXIMATE_COUNT_DISTINCT
          );
          return null;
        }
      }

      final DimensionSpec dimensionSpec;

      if (columnArg.isDirectColumnAccess()) {
        dimensionSpec = columnArg.getSimpleExtraction().toDimensionSpec(null, inputType);
      } else {
        String virtualColumnName = virtualColumnRegistry.getOrCreateVirtualColumnForExpression(
            columnArg,
            dataType
        );
        dimensionSpec = new DefaultDimensionSpec(virtualColumnName, null, inputType);
      }

      aggregatorFactory = new SketchMergeAggregatorFactory(
          aggregatorName,
          dimensionSpec.getDimension(),
          sketchSize,
          finalizeSketch || SketchQueryContext.isFinalizeOuterSketches(plannerContext),
          null,
          null
      );
    }

    return toAggregation(
        name,
        finalizeAggregations,
        aggregatorFactory
    );
  }

  protected abstract Aggregation toAggregation(
      String name,
      boolean finalizeAggregations,
      AggregatorFactory aggregatorFactory
  );

  private boolean isValidComplexInputType(ColumnType columnType)
  {
    return SketchModule.THETA_SKETCH_TYPE.equals(columnType) ||
           SketchModule.THETA_SKETCH.equals(columnType.getComplexTypeName()) ||
           SketchModule.THETA_SKETCH_BUILD_AGG.equals(columnType.getComplexTypeName()) ||
           SketchModule.THETA_SKETCH_MERGE_AGG.equals(columnType.getComplexTypeName());
  }

}
