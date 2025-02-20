/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.plan.rules.physical.batch

import org.apache.flink.table.api.{TableConfig, TableException, ValidationException}
import org.apache.flink.table.api.config.OptimizerConfigOptions
import org.apache.flink.table.planner.hint.{FlinkHints, JoinStrategy}
import org.apache.flink.table.planner.plan.nodes.FlinkConventions
import org.apache.flink.table.planner.plan.nodes.physical.batch.BatchPhysicalLocalHashAggregate
import org.apache.flink.table.planner.plan.rules.physical.batch.BatchPhysicalJoinRuleBase.SEMI_JOIN_BUILD_DISTINCT_NDV_RATIO
import org.apache.flink.table.planner.plan.utils.{JoinUtil, OperatorType}
import org.apache.flink.table.planner.utils.TableConfigUtils.isOperatorDisabled

import org.apache.calcite.plan.RelOptRule
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.{Join, JoinRelType}
import org.apache.calcite.util.ImmutableBitSet

import scala.collection.JavaConversions._

trait BatchPhysicalJoinRuleBase {

  protected def canUseJoinStrategy(
      join: Join,
      tableConfig: TableConfig,
      joinStrategy: JoinStrategy): Boolean = {
    val firstValidJoinHint = getFirstValidJoinHint(join, tableConfig)
    if (firstValidJoinHint.nonEmpty) {
      // if there are join hints, the first hint must be this one, otherwise it is invalid
      firstValidJoinHint.get.equals(joinStrategy)
    } else {
      // if there are no join hints, treat as non-join-hints
      val (isValid, _) =
        checkJoinStrategyValid(join, tableConfig, joinStrategy, withHint = false)
      isValid
    }
  }

  def addLocalDistinctAgg(node: RelNode, distinctKeys: Seq[Int]): RelNode = {
    val localRequiredTraitSet = node.getTraitSet.replace(FlinkConventions.BATCH_PHYSICAL)
    val newInput = RelOptRule.convert(node, localRequiredTraitSet)
    val providedTraitSet = localRequiredTraitSet

    new BatchPhysicalLocalHashAggregate(
      node.getCluster,
      providedTraitSet,
      newInput,
      node.getRowType, // output row type
      node.getRowType, // input row type
      distinctKeys.toArray,
      Array.empty,
      supportAdaptiveLocalHashAgg = false,
      Seq())
  }

  def chooseSemiBuildDistinct(buildRel: RelNode, distinctKeys: Seq[Int]): Boolean = {
    val mq = buildRel.getCluster.getMetadataQuery
    val inputRows = mq.getRowCount(buildRel)
    val ndvOfGroupKey = mq.getDistinctRowCount(buildRel, ImmutableBitSet.of(distinctKeys: _*), null)
    if (ndvOfGroupKey == null) {
      false
    } else {
      ndvOfGroupKey / inputRows < SEMI_JOIN_BUILD_DISTINCT_NDV_RATIO
    }
  }

  protected def getFirstValidJoinHint(
      join: Join,
      tableConfig: TableConfig): Option[JoinStrategy] = {
    val allHints = join.getHints

    allHints.forEach(
      relHint => {
        if (JoinStrategy.isJoinStrategy(relHint.hintName)) {
          val joinStrategy = JoinStrategy.valueOf(relHint.hintName)
          val (isValid, _) =
            checkJoinStrategyValid(join, tableConfig, joinStrategy, withHint = true)
          if (isValid) {
            return Some(joinStrategy)
          }
        }
      })

    None
  }

  /**
   * Check whether the join strategy is valid.
   *
   * @param join
   *   the join node
   * @param tableConfig
   *   the table config
   * @param triedJoinStrategy
   *   the join strategy checked
   * @param withHint
   *   whether this check is called with hint
   * @return
   *   an Tuple2 instance. The first element of tuple is true if join is valid, false else. The
   *   second element of tuple is true if left side used as build side, false else.
   */
  def checkJoinStrategyValid(
      join: Join,
      tableConfig: TableConfig,
      triedJoinStrategy: JoinStrategy,
      withHint: Boolean): (Boolean, Boolean) = {

    // TODO currently join hint is not supported with semi/anti join
    if (withHint && !join.getJoinType.projectsRight()) {
      return (false, false)
    }

    triedJoinStrategy match {
      case JoinStrategy.BROADCAST =>
        checkBroadcast(join, tableConfig, withHint)

      case JoinStrategy.SHUFFLE_HASH =>
        checkShuffleHash(join, tableConfig, withHint)

      case JoinStrategy.SHUFFLE_MERGE =>
        // for SortMergeJoin, there is no diff between with hint or without hint
        // the second arg should be ignored
        (checkSortMergeJoin(join, tableConfig), false)

      case JoinStrategy.NEST_LOOP =>
        checkNestLoopJoin(join, tableConfig, withHint)

      case JoinStrategy.LOOKUP =>
        (false, false)

      case _ =>
        throw new ValidationException("Unknown join strategy : " + triedJoinStrategy)
    }
  }

  private def isEquivJoin(join: Join): Boolean = {
    val joinInfo = join.analyzeCondition
    !joinInfo.pairs().isEmpty
  }

  /**
   * Decides whether the join can convert to BroadcastHashJoin.
   *
   * @param join
   *   the join node
   * @return
   *   an Tuple2 instance. The first element of tuple is true if join can convert to broadcast hash
   *   join, false else. The second element of tuple is true if left side used as broadcast side,
   *   false else.
   */
  protected def checkBroadcast(
      join: Join,
      tableConfig: TableConfig,
      withBroadcastHint: Boolean): (Boolean, Boolean) = {

    if (!isEquivJoin(join) || isOperatorDisabled(tableConfig, OperatorType.BroadcastHashJoin)) {
      return (false, false)
    }

    // if it is with hint, try best to use it and only check the join type
    if (withBroadcastHint) {
      // BROADCAST use first arg as the broadcast side
      val isLeftToBroadcastInHint =
        getFirstArgInJoinHint(join, JoinStrategy.BROADCAST.getJoinHintName)
          .equals(FlinkHints.LEFT_INPUT)

      join.getJoinType match {
        // if left join, must broadcast right side
        case JoinRelType.LEFT => (!isLeftToBroadcastInHint, false)
        // if right join, must broadcast left side
        case JoinRelType.RIGHT => (isLeftToBroadcastInHint, true)
        case JoinRelType.FULL => (false, false)
        case JoinRelType.INNER =>
          (true, isLeftToBroadcastInHint)
        case JoinRelType.SEMI | JoinRelType.ANTI =>
          // TODO currently join hint is not supported with semi/anti join
          (false, false)
      }
    } else {
      if (
        tableConfig.get(OptimizerConfigOptions.TABLE_OPTIMIZER_ADAPTIVE_BROADCAST_JOIN_STRATEGY)
          == OptimizerConfigOptions.AdaptiveBroadcastJoinStrategy.RUNTIME_ONLY
      ) {
        return (false, false)
      }

      val leftSize = JoinUtil.binaryRowRelNodeSize(join.getLeft)
      val rightSize = JoinUtil.binaryRowRelNodeSize(join.getRight)

      // if it is not with hint, just check size of left and right side by statistic and config
      // if leftSize or rightSize is unknown, cannot use broadcast
      if (leftSize == null || rightSize == null) {
        return (false, false)
      }

      val threshold =
        tableConfig.get(OptimizerConfigOptions.TABLE_OPTIMIZER_BROADCAST_JOIN_THRESHOLD)

      val rightSizeSmallerThanThreshold = rightSize <= threshold
      val leftSizeSmallerThanThreshold = leftSize <= threshold
      val leftSmallerThanRight = leftSize < rightSize

      join.getJoinType match {
        case JoinRelType.LEFT => (rightSizeSmallerThanThreshold, false)
        case JoinRelType.RIGHT => (leftSizeSmallerThanThreshold, true)
        case JoinRelType.FULL => (false, false)
        case JoinRelType.INNER =>
          (
            leftSizeSmallerThanThreshold
              || rightSizeSmallerThanThreshold,
            leftSmallerThanRight)
        // left side cannot be used as build side in SEMI/ANTI join.
        case JoinRelType.SEMI | JoinRelType.ANTI =>
          (rightSizeSmallerThanThreshold, false)
      }
    }
  }

  protected def checkShuffleHash(
      join: Join,
      tableConfig: TableConfig,
      withShuffleHashHint: Boolean): (Boolean, Boolean) = {
    if (!isEquivJoin(join) || isOperatorDisabled(tableConfig, OperatorType.ShuffleHashJoin)) {
      return (false, false)
    }

    if (withShuffleHashHint) {
      val isLeftToBuild = getFirstArgInJoinHint(join, JoinStrategy.SHUFFLE_HASH.getJoinHintName)
        .equals(FlinkHints.LEFT_INPUT)
      (true, isLeftToBuild)
    } else {
      val leftSize = JoinUtil.binaryRowRelNodeSize(join.getLeft)
      val rightSize = JoinUtil.binaryRowRelNodeSize(join.getRight)
      val leftIsBuild = if (leftSize == null || rightSize == null || leftSize == rightSize) {
        // use left to build hash table if leftSize or rightSize is unknown or equal size.
        // choose right to build if join is SEMI/ANTI.
        join.getJoinType.projectsRight
      } else {
        leftSize < rightSize
      }
      (true, leftIsBuild)

    }
  }

  // the sort merge join doesn't distinct the build side
  protected def checkSortMergeJoin(join: Join, tableConfig: TableConfig): Boolean = {
    if (!isEquivJoin(join) || isOperatorDisabled(tableConfig, OperatorType.SortMergeJoin)) {
      false
    } else {
      true
    }
  }

  protected def checkNestLoopJoin(
      join: Join,
      tableConfig: TableConfig,
      withNestLoopHint: Boolean): (Boolean, Boolean) = {

    if (isOperatorDisabled(tableConfig, OperatorType.NestedLoopJoin)) {
      return (false, false)
    }

    val isLeftToBuild = if (withNestLoopHint) {
      getFirstArgInJoinHint(join, JoinStrategy.NEST_LOOP.getJoinHintName)
        .equals(FlinkHints.LEFT_INPUT)
    } else {
      join.getJoinType match {
        case JoinRelType.LEFT => false
        case JoinRelType.RIGHT => true
        case JoinRelType.INNER | JoinRelType.FULL =>
          val leftSize = JoinUtil.binaryRowRelNodeSize(join.getLeft)
          val rightSize = JoinUtil.binaryRowRelNodeSize(join.getRight)
          // use left as build size if leftSize or rightSize is unknown.
          if (leftSize == null || rightSize == null) {
            true
          } else {
            leftSize <= rightSize
          }
        case JoinRelType.SEMI | JoinRelType.ANTI => false
      }

    }

    // all join can use NEST LOOP JOIN
    (true, isLeftToBuild)

  }

  private def getFirstArgInJoinHint(join: Join, joinHintName: String): String = {
    join.getHints.forEach(
      hint => {
        if (hint.hintName.equals(joinHintName)) {
          return hint.listOptions.get(0)
        }
      })

    // can not happen
    throw new TableException(
      String.format(
        "Fail to find the join hint `%s` among `%s`",
        joinHintName,
        join.getHints
          .map(hint => hint.hintName)
          .mkString(",")
      ))
  }
}
object BatchPhysicalJoinRuleBase {

  // In order to reduce the amount of data on semi/anti join's
  // build side, we will add distinct node before semi/anti join when
  // the semi-side or semi/anti join can distinct a lot of data in advance.
  val SEMI_JOIN_BUILD_DISTINCT_NDV_RATIO = 0.8
}
