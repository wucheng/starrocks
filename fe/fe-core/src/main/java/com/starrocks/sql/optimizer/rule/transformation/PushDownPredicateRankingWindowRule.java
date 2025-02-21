// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package com.starrocks.sql.optimizer.rule.transformation;

import com.google.common.collect.Lists;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.operator.Operator;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.SortPhase;
import com.starrocks.sql.optimizer.operator.TopNType;
import com.starrocks.sql.optimizer.operator.logical.LogicalFilterOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalTopNOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalWindowOperator;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rule.RuleType;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/*
 * For ranking window functions, such as row_number, rank, dense_rank, if there exists rank related predicate
 * then we can add a TopN to filter data in order to reduce the amount of data to be exchanged and sorted
 * E.g.
 *      select * from (
 *          select *, rank() over (order by v2) as rk from t0
 *      ) sub_t0
 *      where rk < 4;
 * Before:
 *       Filter
 *         |
 *       Window
 *
 * After:
 *       Filter
 *         |
 *       Window
 *         |
 *       TopN
 */
public class PushDownPredicateRankingWindowRule extends TransformationRule {

    public PushDownPredicateRankingWindowRule() {
        super(RuleType.TF_PUSH_DOWN_PREDICATE_RANKING_WINDOW,
                Pattern.create(OperatorType.LOGICAL_FILTER).
                        addChildren(Pattern.create(OperatorType.LOGICAL_WINDOW, OperatorType.PATTERN_LEAF)));
    }

    @Override
    public boolean check(OptExpression input, OptimizerContext context) {
        // This rule introduce a new version of TopNOperator, i.e. PartitionTopNOperator
        // which only supported in pipeline engine, so we cannot apply this rule in non-pipeline engine
        if (!context.getSessionVariable().isEnablePipelineEngine()) {
            return false;
        }

        OptExpression childExpr = input.inputAt(0);
        LogicalWindowOperator windowOperator = childExpr.getOp().cast();

        if (windowOperator.getWindowCall().size() != 1) {
            return false;
        }

        ColumnRefOperator windowCol = Lists.newArrayList(windowOperator.getWindowCall().keySet()).get(0);
        CallOperator callOperator = windowOperator.getWindowCall().get(windowCol);

        // TODO(hcf) we support dense_rank later
        if (!FunctionSet.ROW_NUMBER.equals(callOperator.getFnName()) &&
                !FunctionSet.RANK.equals(callOperator.getFnName())) {
            return false;
        }

        if (!childExpr.getInputs().isEmpty() && childExpr.inputAt(0).getOp() instanceof LogicalWindowOperator) {
            OptExpression grandChildExpr = childExpr.inputAt(0);
            LogicalWindowOperator nextWindowOperator = grandChildExpr.getOp().cast();
            // There might be a negative gain if we add a partitionTopN between two window operators that
            // share the same sort group
            return !Objects.equals(windowOperator.getEnforceSortColumns(), nextWindowOperator.getEnforceSortColumns());
        }

        return true;
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        LogicalFilterOperator filterOperator = input.getOp().cast();
        List<ScalarOperator> filters = Utils.extractConjuncts(filterOperator.getPredicate());
        OptExpression childExpr = input.inputAt(0);
        LogicalWindowOperator windowOperator = childExpr.getOp().cast();

        ColumnRefOperator windowCol = Lists.newArrayList(windowOperator.getWindowCall().keySet()).get(0);
        CallOperator callOperator = windowOperator.getWindowCall().get(windowCol);

        List<BinaryPredicateOperator> lessPredicates =
                filters.stream().filter(op -> op instanceof BinaryPredicateOperator)
                        .map(ScalarOperator::<BinaryPredicateOperator>cast)
                        .filter(op -> Objects.equals(BinaryPredicateOperator.BinaryType.LE, op.getBinaryType()) ||
                                Objects.equals(BinaryPredicateOperator.BinaryType.LT, op.getBinaryType()) ||
                                Objects.equals(BinaryPredicateOperator.BinaryType.EQ, op.getBinaryType()))
                        .filter(op -> Objects.equals(windowCol, op.getChild(0)))
                        .filter(op -> op.getChild(1) instanceof ConstantOperator)
                        .collect(Collectors.toList());

        if (lessPredicates.size() != 1) {
            return Collections.emptyList();
        }

        BinaryPredicateOperator lessPredicate = lessPredicates.get(0);
        ConstantOperator rightChild = lessPredicate.getChild(1).cast();
        long limitValue = rightChild.getBigint();

        List<ColumnRefOperator> partitionByColumns = windowOperator.getPartitionExpressions().stream()
                .map(ScalarOperator::<ColumnRefOperator>cast)
                .collect(Collectors.toList());

        // TODO(hcf) we will support multi-partition later
        if (partitionByColumns.size() > 1) {
            return Collections.emptyList();
        }

        TopNType topNType = TopNType.parse(callOperator.getFnName());

        // If partition by columns is not empty, then we cannot derive sort property from the SortNode
        // OutputPropertyDeriver will generate PhysicalPropertySet.EMPTY if sortPhase is SortPhase.PARTIAL
        final SortPhase sortPhase = partitionByColumns.isEmpty() ? SortPhase.FINAL : SortPhase.PARTIAL;
        final long limit = partitionByColumns.isEmpty() ? limitValue : Operator.DEFAULT_LIMIT;
        final long partitionLimit = partitionByColumns.isEmpty() ? Operator.DEFAULT_LIMIT : limitValue;
        OptExpression newTopNOptExp = OptExpression.create(new LogicalTopNOperator.Builder()
                .setPartitionByColumns(partitionByColumns)
                .setPartitionLimit(partitionLimit)
                .setOrderByElements(windowOperator.getEnforceSortColumns())
                .setLimit(limit)
                .setTopNType(topNType)
                .setSortPhase(sortPhase)
                .build(), childExpr.getInputs());

        OptExpression newWindowOptExp = OptExpression.create(windowOperator, newTopNOptExp);
        return Collections.singletonList(OptExpression.create(filterOperator, newWindowOptExp));
    }
}
