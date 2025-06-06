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

package org.apache.druid.segment.join;

import com.google.common.base.Preconditions;
import org.apache.druid.java.util.common.Cacheable;
import org.apache.druid.math.expr.Expr;
import org.apache.druid.math.expr.ExprEval;
import org.apache.druid.math.expr.ExprMacroTable;
import org.apache.druid.math.expr.Exprs;
import org.apache.druid.math.expr.InputBindings;
import org.apache.druid.math.expr.Parser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Represents analysis of a join condition.
 *
 * Each condition is decomposed into "equiConditions" and "nonEquiConditions".
 *
 * 1) The equiConditions are of the form ExpressionOfLeft = ColumnFromRight. The right-hand part cannot be an expression
 * because we use this analysis to determine if we can perform the join using hashtables built off right-hand-side
 * columns.
 *
 * 2) The nonEquiConditions are other conditions that should also be ANDed together
 *
 * All of these conditions are ANDed together to get the overall condition.
 */
public class JoinConditionAnalysis implements Cacheable
{
  private final String originalExpression;
  private final String rightPrefix;
  private final List<Equality> equiConditions;
  private final List<Expr> nonEquiConditions;
  private final boolean isAlwaysFalse;
  private final boolean isAlwaysTrue;
  private final boolean canHashJoin;
  private final Set<String> rightKeyColumns;
  private final Set<String> requiredColumns;

  private JoinConditionAnalysis(
      final String originalExpression,
      final String rightPrefix,
      final List<Equality> equiConditions,
      final List<Expr> nonEquiConditions
  )
  {
    this.originalExpression = Preconditions.checkNotNull(originalExpression, "originalExpression");
    this.rightPrefix = Preconditions.checkNotNull(rightPrefix, "rightPrefix");
    this.equiConditions = Collections.unmodifiableList(equiConditions);
    this.nonEquiConditions = Collections.unmodifiableList(nonEquiConditions);

    rightKeyColumns = new HashSet<>();
    requiredColumns = new HashSet<>();

    for (Equality equality : equiConditions) {
      final Set<String> requiredLeft = equality.getLeftExpr().analyzeInputs().getRequiredBindings();
      requiredColumns.addAll(requiredLeft);

      rightKeyColumns.add(equality.getRightColumn());
      requiredColumns.add(rightPrefix + equality.getRightColumn());
    }

    boolean alwaysFalse = false;
    boolean alwaysTrue = equiConditions.isEmpty();
    boolean hashJoin = true;

    for (Expr expr : nonEquiConditions) {
      hashJoin = hashJoin && expr.isLiteral();
      if (expr.isLiteral()) {
        if (hashJoin) {
          // we only need to check if allTrue or allFalse if all conditions are literals
          final ExprEval<?> eval = expr.eval(InputBindings.nilBindings());
          alwaysTrue = alwaysTrue && eval.asBoolean();
          // we don't need to check for null here because it is ok to consider UNKNOWN as false because
          // UNKNOWN AND <anything> is UNKNOWN, and there is no possibility this condition can be wrapped in a NOT, so
          // it is effectively FALSE and we can still short-circuit
          alwaysFalse = alwaysFalse || !eval.asBoolean();
        }
      } else {
        requiredColumns.addAll(expr.analyzeInputs().getRequiredBindings());
      }
    }

    isAlwaysTrue = hashJoin && alwaysTrue;
    isAlwaysFalse = hashJoin && alwaysFalse;
    canHashJoin = hashJoin;
  }

  /**
   * Analyze a join condition.
   *
   * @param condition   the condition expression
   * @param rightPrefix prefix for the right-hand side of the join; will be used to determine which identifiers in
   *                    the condition come from the right-hand side and which come from the left-hand side
   * @param macroTable  macro table for parsing the condition expression
   */
  public static JoinConditionAnalysis forExpression(
      final String condition,
      final String rightPrefix,
      final ExprMacroTable macroTable
  )
  {
    return forExpression(condition, Parser.parse(condition, macroTable), rightPrefix);
  }

  /**
   * Analyze a join condition from a pre-parsed expression.
   *
   * @param condition     the condition expression
   * @param conditionExpr the parsed condition expression. Must match "condition".
   * @param rightPrefix   prefix for the right-hand side of the join; will be used to determine which identifiers in
   *                      the condition come from the right-hand side and which come from the left-hand side
   */
  public static JoinConditionAnalysis forExpression(
      final String condition,
      final Expr conditionExpr,
      final String rightPrefix
  )
  {
    final List<Equality> equiConditions = new ArrayList<>();
    final List<Expr> nonEquiConditions = new ArrayList<>();

    final List<Expr> exprs = Exprs.decomposeAnd(conditionExpr);
    for (Expr childExpr : exprs) {
      final Optional<Equality> maybeEquality = Exprs.decomposeEquals(childExpr, rightPrefix);

      if (!maybeEquality.isPresent()) {
        nonEquiConditions.add(childExpr);
      } else {
        equiConditions.add(maybeEquality.get());
      }
    }

    return new JoinConditionAnalysis(condition, rightPrefix, equiConditions, nonEquiConditions);
  }

  /**
   * Return the condition expression.
   */
  public String getOriginalExpression()
  {
    return originalExpression;
  }

  /**
   * Return a list of equi-conditions (see class-level javadoc).
   */
  public List<Equality> getEquiConditions()
  {
    return equiConditions;
  }

  /**
   * Return a list of non-equi-conditions (see class-level javadoc).
   */
  public List<Expr> getNonEquiConditions()
  {
    return nonEquiConditions;
  }

  /**
   * Return whether this condition is a constant that is always false.
   */
  public boolean isAlwaysFalse()
  {
    return isAlwaysFalse;
  }

  /**
   * Return whether this condition is a constant that is always true.
   */
  public boolean isAlwaysTrue()
  {
    return isAlwaysTrue;
  }

  /**
   * Returns whether this condition can be satisfied using a hashtable made from the right-hand side.
   */
  public boolean canHashJoin()
  {
    return canHashJoin;
  }

  /**
   * Returns the distinct column keys from the RHS required to evaluate the equi conditions.
   */
  public Set<String> getRightEquiConditionKeys()
  {
    return rightKeyColumns;
  }

  /**
   * Returns the set of column names required by this join condition. Columns from the right-hand side are returned
   * with their prefixes included.
   */
  public Set<String> getRequiredColumns()
  {
    return requiredColumns;
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
    JoinConditionAnalysis that = (JoinConditionAnalysis) o;
    return Objects.equals(originalExpression, that.originalExpression) &&
           Objects.equals(rightPrefix, that.rightPrefix);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(originalExpression, rightPrefix);
  }

  @Override
  public String toString()
  {
    return originalExpression;
  }

  @Override
  public byte[] getCacheKey()
  {
    return originalExpression.getBytes(StandardCharsets.UTF_8);
  }
}
