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
package org.apache.flink.table.planner.plan.rules.logical

import org.apache.flink.table.api._
import org.apache.flink.table.planner.plan.optimize.program.{BatchOptimizeContext, FlinkChainedProgram, FlinkHepRuleSetProgramBuilder, HEP_RULES_EXECUTION_TYPE}
import org.apache.flink.table.planner.utils.TableTestBase

import org.apache.calcite.plan.hep.HepMatchOrder
import org.apache.calcite.rel.rules.PruneEmptyRules
import org.apache.calcite.tools.RuleSets
import org.junit.jupiter.api.{BeforeEach, Test}

/**
 * Former test for [[FlinkLimit0RemoveRule]] which now replaced by Calcite's
 * [[PruneEmptyRules.SORT_FETCH_ZERO_INSTANCE]].
 */
class FlinkLimit0RemoveRuleTest extends TableTestBase {

  private val util = batchTestUtil()

  @BeforeEach
  def setup(): Unit = {
    val programs = new FlinkChainedProgram[BatchOptimizeContext]()
    programs.addLast(
      "rules",
      FlinkHepRuleSetProgramBuilder.newBuilder
        .setHepRulesExecutionType(HEP_RULES_EXECUTION_TYPE.RULE_SEQUENCE)
        .setHepMatchOrder(HepMatchOrder.BOTTOM_UP)
        .add(
          RuleSets.ofList(FlinkSubQueryRemoveRule.FILTER, PruneEmptyRules.SORT_FETCH_ZERO_INSTANCE))
        .build()
    )
    util.replaceBatchProgram(programs)

    util.addTableSource[(Int, Long, String)]("MyTable", 'a, 'b, 'c)
  }

  @Test
  def testSimpleLimitZero(): Unit = {
    util.verifyRelPlan("SELECT * FROM MyTable LIMIT 0")
  }

  @Test
  def testLimitZeroWithOrderBy(): Unit = {
    util.verifyRelPlan("SELECT * FROM MyTable ORDER BY a LIMIT 0")
  }

  @Test
  def testLimitZeroWithOffset(): Unit = {
    util.verifyRelPlan("SELECT * FROM MyTable ORDER BY a LIMIT 0 OFFSET 10")
  }

  @Test
  def testLimitZeroWithSelect(): Unit = {
    util.verifyRelPlan("SELECT * FROM (SELECT a FROM MyTable LIMIT 0)")
  }

  @Test
  def testLimitZeroWithIn(): Unit = {
    util.verifyRelPlan("SELECT * FROM MyTable WHERE a IN (SELECT a FROM MyTable LIMIT 0)")
  }

  @Test
  def testLimitZeroWithNotIn(): Unit = {
    util.verifyRelPlan("SELECT * FROM MyTable WHERE a NOT IN (SELECT a FROM MyTable LIMIT 0)")
  }

  @Test
  def testLimitZeroWithExists(): Unit = {
    util.verifyRelPlan("SELECT * FROM MyTable WHERE EXISTS (SELECT a FROM MyTable LIMIT 0)")
  }

  @Test
  def testLimitZeroWithNotExists(): Unit = {
    util.verifyRelPlan("SELECT * FROM MyTable WHERE NOT EXISTS (SELECT a FROM MyTable LIMIT 0)")
  }

  @Test
  def testLimitZeroWithJoin(): Unit = {
    util.verifyRelPlan("SELECT * FROM MyTable INNER JOIN (SELECT * FROM MyTable LIMIT 0) ON TRUE")
  }
}
