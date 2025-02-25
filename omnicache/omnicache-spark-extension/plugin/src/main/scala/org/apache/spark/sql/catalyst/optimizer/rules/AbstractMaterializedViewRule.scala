/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.optimizer.rules

import com.google.common.collect._
import com.huawei.boostkit.spark.conf.OmniCachePluginConfig
import com.huawei.boostkit.spark.util._
import org.apache.calcite.util.graph.{DefaultEdge, Graphs}
import scala.collection.{mutable, JavaConverters}
import scala.util.control.Breaks

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.catalog.HiveTableRelation
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.Inner
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.execution.datasources.LogicalRelation

abstract class AbstractMaterializedViewRule(sparkSession: SparkSession)
    extends RewriteHelper {

  /**
   * try match the queryPlan and viewPlan ,then rewrite by viewPlan
   *
   * @param topProject queryTopProject
   * @param plan       queryPlan
   * @param usingMvs   usingMvs
   * @return performedPlan
   */
  def perform(topProject: Option[Project], plan: LogicalPlan,
      usingMvs: mutable.Set[String]): LogicalPlan = {
    var finalPlan = if (topProject.isEmpty) plan else topProject.get

    if (ViewMetadata.status == ViewMetadata.STATUS_LOADING) {
      return finalPlan
    }
    RewriteTime.withTimeStat("viewMetadata") {
      ViewMetadata.init(sparkSession)
    }
    // 1.check query sql is match current rule
    if (ViewMetadata.isEmpty || !isValidPlan(plan)) {
      return finalPlan
    }

    // 2.extract tablesInfo from queryPlan and replace the AttributeReference
    // in plan using tableAttr
    val (queryExpr, queryTables) = extractTables(finalPlan)

    // 3.use all tables to fetch views(may match) from ViewMetaData
    val candidateViewPlans = RewriteTime.withTimeStat("getApplicableMaterializations") {
      getApplicableMaterializations(queryTables.map(t => t.tableName))
          .filter(x => !OmniCachePluginConfig.isMVInUpdate(x._2))
    }
    if (candidateViewPlans.isEmpty) {
      return finalPlan
    }

    // continue for curPlanLoop,mappingLoop
    val curPlanLoop = new Breaks
    val mappingLoop = new Breaks

    // 4.iterate views,try match and rewrite
    for ((viewName, srcViewTablePlan, srcViewQueryPlan) <- candidateViewPlans) {
      curPlanLoop.breakable {
        // 4.1.check view query sql is match current rule
        if (!isValidPlan(srcViewQueryPlan)) {
          curPlanLoop.break()
        }

        OmniCachePluginConfig.getConf.setCurMatchMV(viewName)
        // 4.2.view plans
        var viewTablePlan = srcViewTablePlan
        var viewQueryPlan = srcViewQueryPlan
        var topViewProject: Option[Project] = None
        var viewQueryExpr: LogicalPlan = viewQueryPlan
        viewQueryPlan match {
          case p: Project =>
            topViewProject = Some(p)
            viewQueryPlan = p.child
            viewQueryExpr = p
          case _ =>
        }

        // 4.3.extract tablesInfo from viewPlan
        val viewTables = ViewMetadata.viewToContainsTables.get(viewName)

        // 4.4.compute the relation of viewTableInfo and queryTableInfo
        // 4.4.1.queryTableInfo containsAll viewTableInfo
        if (!viewTables.subsetOf(queryTables)) {
          curPlanLoop.break()
        }

        // 4.4.2.queryTableInfo!=viewTableInfo, need do join compensate
        val needCompensateTables = queryTables -- viewTables
        if (needCompensateTables.nonEmpty) {
          val newViewPlans = compensateViewPartial(viewTablePlan,
            viewQueryPlan, topViewProject, needCompensateTables)
          if (newViewPlans.isEmpty) {
            curPlanLoop.break()
          }
          val (newViewTablePlan, newViewQueryPlan, newTopViewProject) = newViewPlans.get
          viewTablePlan = newViewTablePlan
          viewQueryPlan = newViewQueryPlan
          viewQueryExpr = newViewQueryPlan
          topViewProject = newTopViewProject
        }

        // 4.5.extractPredictExpressions from viewQueryPlan and mappedQueryPlan
        val queryPredictExpression = RewriteTime.withTimeStat("extractPredictExpressions") {
          extractPredictExpressions(queryExpr, EMPTY_BIMAP)
        }

        val viewProjectList = extractTopProjectList(viewQueryExpr)
        val viewTableAttrs = viewTablePlan.output

        // 4.6.if a table emps used >=2 times in a sql (query and view)
        // we should try the combination,switch the seq
        // view:SELECT V1.locationid,V2.empname FROM emps V1 JOIN emps V2
        // ON V1.deptno='1' AND V2.deptno='2' AND V1.empname = V2.empname;
        // query:SELECT V2.locationid,V1.empname FROM emps V1 JOIN emps V2
        // ON V1.deptno='2' AND V2.deptno='1' AND V1.empname = V2.empname;
        val flatListMappings: Seq[BiMap[String, String]] = generateTableMappings(queryTables)

        flatListMappings.foreach { queryToViewTableMapping =>
          mappingLoop.breakable {
            val inverseTableMapping = queryToViewTableMapping.inverse()
            val viewPredictExpression = RewriteTime.withTimeStat("extractPredictExpressions") {
              extractPredictExpressions(viewQueryExpr,
                inverseTableMapping)
            }

            // 4.7.compute compensationPredicates between viewQueryPlan and queryPlan
            var newViewTablePlan = RewriteTime.withTimeStat("computeCompensationPredicates") {
              computeCompensationPredicates(viewTablePlan,
                queryPredictExpression, viewPredictExpression, inverseTableMapping,
                viewPredictExpression._1.getEquivalenceClassesMap,
                viewProjectList, viewTableAttrs)
            }
            // 4.8.compensationPredicates isEmpty, because view's row data cannot satisfy query
            if (newViewTablePlan.isEmpty) {
              mappingLoop.break()
            }

            // 4.9.use viewTablePlan(join compensated), query project,
            // compensationPredicts to rewrite final plan
            newViewTablePlan = RewriteTime.withTimeStat("rewriteView") {
              rewriteView(newViewTablePlan.get, viewQueryExpr,
                queryExpr, inverseTableMapping,
                queryPredictExpression._1.getEquivalenceClassesMap,
                viewProjectList, viewTableAttrs)
            }
            if (newViewTablePlan.isEmpty) {
              mappingLoop.break()
            }
            finalPlan = newViewTablePlan.get
            usingMvs += viewName
            return finalPlan
          }
        }
      }
    }
    finalPlan
  }

  /**
   * check plan if match current rule
   *
   * @param logicalPlan LogicalPlan
   * @return true:matched ; false:unMatched
   */
  def isValidPlan(logicalPlan: LogicalPlan): Boolean

  /**
   * basic check for all rule
   *
   * @param logicalPlan LogicalPlan
   * @return true:matched ; false:unMatched
   */
  def isValidLogicalPlan(logicalPlan: LogicalPlan): Boolean = {
    logicalPlan.foreach {
      case _: LogicalRelation =>
      case _: HiveTableRelation =>
      case _: Project =>
      case _: Filter =>
      case j: Join =>
        j.joinType match {
          case _: Inner.type =>
          case _ => return false
        }
      case _: SubqueryAlias =>
      case _ => return false
    }
    true
  }

  /**
   * use all tables to fetch views(may match) from ViewMetaData
   *
   * @param tableNames tableNames in query sql
   * @return Seq[(viewName, viewTablePlan, viewQueryPlan)]
   */
  def getApplicableMaterializations(tableNames: Set[String]): Seq[(String,
      LogicalPlan, LogicalPlan)] = {
    // viewName, viewTablePlan, viewQueryPlan
    var viewPlans = Seq.empty[(String, LogicalPlan, LogicalPlan)]
    val viewNames = mutable.Set.empty[String]
    // 1.topological iterate graph
    tableNames.foreach { tableName =>
      if (ViewMetadata.tableToViews.containsKey(tableName)) {
        viewNames ++= ViewMetadata.tableToViews.get(tableName)
      }
    }
    viewNames.foreach { viewName =>
      // 4.add plan info
      val viewQueryPlan = ViewMetadata.viewToViewQueryPlan.get(viewName)
      val viewTablePlan = ViewMetadata.viewToTablePlan.get(viewName)
      viewPlans +:= (viewName, viewTablePlan, viewQueryPlan)
    }
    viewPlans
  }

  /**
   * if the edge of (usedTable,view) in graph
   *
   * @param view        viewName
   * @param usedTables  usedTableNames
   * @param frozenGraph graph
   * @return true:exist ; false not exist
   */
  def usesTable(view: String, usedTables: Set[TableEqual],
      frozenGraph: Graphs.FrozenGraph[String, DefaultEdge]): Boolean = {
    usedTables.foreach { usedTable =>
      if (frozenGraph.getShortestPath(usedTable.tableName, view) != null) {
        return true
      }
    }
    false
  }

  /**
   * queryTableInfo!=viewTableInfo , need do join compensate
   *
   * @param viewTablePlan  viewTablePlan
   * @param viewQueryPlan  viewQueryPlan
   * @param topViewProject topViewProject
   * @param needTables     need join compensate tables
   * @return join compensated viewTablePlan
   */
  def compensateViewPartial(viewTablePlan: LogicalPlan,
      viewQueryPlan: LogicalPlan,
      topViewProject: Option[Project],
      needTables: Set[TableEqual]):
  Option[(LogicalPlan, LogicalPlan, Option[Project])] = None

  /**
   * We map every table in the query to a table with the same qualified
   * name (all query tables are contained in the view, thus this is equivalent
   * to mapping every table in the query to a view table).
   *
   * @param queryTables queryTables
   * @return
   */
  def generateTableMappings(queryTables: Set[TableEqual]): Seq[BiMap[String, String]] = {
    val multiMapTables: Multimap[String, String] = ArrayListMultimap.create()
    for (t1 <- queryTables) {
      for (t2 <- queryTables) {
        if (t1.tableName == t2.tableName) {
          multiMapTables.put(t1.qualifier, t2.qualifier)
        }
      }
    }
    var result: java.util.List[BiMap[String, String]] =
      ImmutableList.of[BiMap[String, String]](HashBiMap.create[String, String]())

    multiMapTables.asMap().forEach { (t1, t2s) =>
      if (t2s.size() == 1) {
        // Only one reference, we can just add it to every map
        val target = t2s.iterator().next()
        result.forEach { m =>
          m.put(t1, target)
        }
        // continue
      } else {
        // Multiple references: flatten
        val newResult: ImmutableList.Builder[BiMap[String, String]] = ImmutableList.builder()
        t2s.forEach { target =>
          result.forEach { m =>
            if (!m.containsValue(target)) {
              val newM = HashBiMap.create[String, String](m)
              newM.put(t1, target)
              newResult.add(newM)
            }
          }
        }
        result = newResult.build()
      }
    }
    JavaConverters.asScalaIteratorConverter(result.iterator()).asScala.toSeq
        .sortWith((map1, map2) => map1.toString < map2.toString)
  }

  /**
   * generate compensate EquivalenceClasses
   *
   * @param queryEC queryEC
   * @param viewEC  viewEC
   * @return compensate EquivalenceClasses expression
   */
  def generateEquivalenceClasses(queryEC: EquivalenceClasses,
      viewEC: EquivalenceClasses): Option[Expression] = {
    // 1.all empty,valid
    if (queryEC.getEquivalenceClassesMap.isEmpty && viewEC.getEquivalenceClassesMap.isEmpty) {
      return Some(Literal.TrueLiteral)
    }

    // 2.query is empty,invalid
    if (queryEC.getEquivalenceClassesMap.isEmpty && viewEC.getEquivalenceClassesMap.nonEmpty) {
      return None
    }

    // 3.extractPossibleMapping {queryEquivalenceClasses:[contained viewEquivalenceClasses]}
    val queryEquivalenceClasses = queryEC.getEquivalenceClasses
    val viewEquivalenceClasses = viewEC.getEquivalenceClasses
    val mappingOp: Option[Multimap[Int, Int]] = extractPossibleMapping(queryEquivalenceClasses,
      viewEquivalenceClasses)
    if (mappingOp.isEmpty) {
      return None
    }
    val mapping = mappingOp.get

    // 4.compute compensate EquivalenceClasses
    var compensationPredicate: Expression = Literal.TrueLiteral
    for (i <- queryEquivalenceClasses.indices) {
      val query = queryEquivalenceClasses(i)

      // 4.1.no mapping viewEC, add EqualTo according to queryEC
      if (!mapping.containsKey(i)) {
        val it = query.iterator
        val head = it.next().expression
        while (it.hasNext) {
          compensationPredicate = And(compensationPredicate,
            EqualTo(head, it.next().expression))
        }
        // 4.2.exist mapping viewEC,compute difference between queryEC and viewEC
        // add EqualTo according to difference and viewEC
      } else {
        mapping.get(i).forEach { j =>
          var difference = query
          val view = viewEquivalenceClasses(j)
          difference = difference -- view
          for (d <- difference) {
            compensationPredicate = And(compensationPredicate,
              EqualTo(d.expression, view.head.expression))
          }
        }
      }
    }

    Some(compensationPredicate)
  }

  /**
   * extractPossibleMapping {queryEquivalenceClasses:[contained viewEquivalenceClasses]}
   *
   * @param queryEquivalenceClasses queryEquivalenceClasses
   * @param viewEquivalenceClasses  viewEquivalenceClasses
   * @return {queryEquivalenceClasses:[contained viewEquivalenceClasses]}
   */
  def extractPossibleMapping(queryEquivalenceClasses: List[mutable.Set[ExpressionEqual]],
      viewEquivalenceClasses: List[mutable.Set[ExpressionEqual]]): Option[Multimap[Int, Int]] = {
    // extractPossibleMapping {queryEquivalenceClasses:[contained viewEquivalenceClasses]}
    // query:c1=c2=c3=c4  view:c1=c2 , c3=c4
    val mapping = ArrayListMultimap.create[Int, Int]()

    val breakLoop = new Breaks
    // 1.iterate viewEquivalenceClasses
    for (i <- viewEquivalenceClasses.indices) {
      val view = viewEquivalenceClasses(i)
      var foundQueryEquivalenceClasses = false

      breakLoop.breakable {
        // 2.iterate queryEquivalenceClasses
        for (j <- queryEquivalenceClasses.indices) {
          val query = queryEquivalenceClasses(j)
          // 3.query contains view,add mapping
          if (view.subsetOf(query)) {
            mapping.put(j, i)
            foundQueryEquivalenceClasses = true
            // break
            breakLoop.break()
          }
        }
      }

      // any viewEquivalenceClasses cannot find mapping queryEquivalenceClasses
      // this view is invalid
      if (!foundQueryEquivalenceClasses) {
        return None
      }
    }
    Some(mapping)
  }

  /**
   *
   * @param queryExpression queryExpression
   * @param viewExpression  viewExpression
   * @return compensate Expression
   */
  def splitFilter(queryExpression: Expression, viewExpression: Expression): Option[Expression] = {
    // 1.canonicalize expression,main for reorder
    val queryExpression2 = RewriteHelper.canonicalize(ExprSimplifier.simplify(queryExpression))
    val viewExpression2 = RewriteHelper.canonicalize(ExprSimplifier.simplify(viewExpression))

    // 2.or is residual predicts,this main deal residual predicts
    val z = splitOr(queryExpression2, viewExpression2)
    if (z.isDefined) {
      return z
    }

    // 3.isEquivalent after splitAnd
    if (isEquivalent(queryExpression2, viewExpression2)) {
      return Some(Literal.TrueLiteral)
    }

    // 4.viewExpression2 and not(queryExpression2)
    val x = andNot(viewExpression2, queryExpression2)
    // then check some absolutely invalid situation
    if (mayBeSatisfiable(x)) {
      // 4.1.queryExpression2 and viewExpression2
      val x2 = ExprOptUtil.composeConjunctions(
        Seq(queryExpression2, viewExpression2), nullOnEmpty = false)

      // 4.2.canonicalize
      val r = RewriteHelper.canonicalize(ExprSimplifier.simplify(x2))
      if (ExprOptUtil.isAlwaysFalse(r)) {
        return None
      }

      // 4.3.isEquivalent,remove views exists,return residue
      if (isEquivalent(queryExpression2, r)) {
        val conjs = ExprOptUtil.conjunctions(r).map(ExpressionEqual).toSet
        val views = ExprOptUtil.conjunctions(viewExpression2).map(ExpressionEqual).toSet
        val residue = (conjs -- views).map(_.expression).toSeq
        return Some(ExprOptUtil.composeConjunctions(residue, nullOnEmpty = false))
      }
    }
    None
  }

  /**
   * split expression by or,then compute compensation
   *
   * @param queryExpression queryExpression
   * @param viewExpression  viewExpression
   * @return compensation Expression
   */
  def splitOr(queryExpression: Expression, viewExpression: Expression): Option[Expression] = {
    val queries = ExprOptUtil.disjunctions(queryExpression)
    val views = ExprOptUtil.disjunctions(viewExpression)

    // 1.compute difference which queries residue
    val difference = queries.map(ExpressionEqual) -- views.map(ExpressionEqual)

    // 2.1.queries equal to views,just return true
    if (difference.isEmpty && queries.size == views.size) {
      Some(Literal.TrueLiteral)
      // 2.2.queries is subset of views,remain queries
    } else if (difference.isEmpty) {
      Some(queryExpression)
      // 2.3.other is invalid
    } else {
      None
    }
  }

  /**
   * split expression by and,then compare equals
   *
   * @param queryExpression queryExpression
   * @param viewExpression  viewExpression
   * @return isEquivalent:true;isNotEquivalent:false
   */
  def isEquivalent(queryExpression: Expression, viewExpression: Expression): Boolean = {
    // split expression by and,then compare equals
    val queries = ExprOptUtil.conjunctions(queryExpression).map(ExpressionEqual).toSet
    val views = ExprOptUtil.conjunctions(viewExpression).map(ExpressionEqual).toSet
    queries == views
  }

  /**
   * viewExpression and not (queryExpression)
   *
   * @param viewExpression  viewExpression
   * @param queryExpression queryExpression
   * @return andNot Expression
   */
  def andNot(viewExpression: Expression, queryExpression: Expression): Expression = {
    // check and filter if viewExpression is c1=Literal
    // and queryExpression is also c1=Literal
    viewExpression match {
      case e: EqualTo =>
        if (e.right.isInstanceOf[Literal]) {
          queryExpression match {
            case qe: EqualTo =>
              if (ExpressionEqual(e.left) == ExpressionEqual(qe.left)
                  && qe.right.isInstanceOf[Literal]) {
                // this is invalid not return false there,will check later
                return viewExpression
              }
            case _ =>
          }
        }
      case _ =>
    }
    And(viewExpression, Not(queryExpression))
  }

  /**
   * check some absolutely invalid situation
   *
   * @param expression expression
   * @return absolutely invalid situation:false; other:true
   */
  def mayBeSatisfiable(expression: Expression): Boolean = {
    // 1.split then divide into normal,not.
    // if expression is and,recursively split
    // if expression is not.or,split by or then divide into normal,not.
    // other into normal
    val normal = mutable.Buffer[Expression]()
    val not = mutable.Buffer[Expression]()
    ExprOptUtil.decomposeConjunctions(expression, normal, not)

    // 2.normal exists FalseLiteral is invalid
    normal.foreach {
      case Literal.FalseLiteral =>
        return false
      case _ =>
    }

    // 3.not exists TrueLiteral is invalid
    not.foreach {
      case Literal.TrueLiteral =>
        return false
      case _ =>
    }

    // 4.not is subset of normal,absolutely invalid
    val normalSet = normal.map(ExpressionEqual).toSet
    for (n <- not) {
      // not doesn't recursively split former, there recursively split by and
      val ns = ExprOptUtil.conjunctions(n).map(ExpressionEqual).toSet
      if (ns.subsetOf(normalSet)) {
        return false
      }
    }

    true
  }

  /**
   * compute compensationPredicates between viewQueryPlan and mappedQueryPlan
   *
   * @param viewTablePlan   viewTablePlan
   * @param queryPredict    queryPredict
   * @param viewPredict     viewPredict
   * @param tableMapping    tableMapping
   * @param columnMapping   columnMapping
   * @param viewProjectList viewProjectList
   * @param viewTableAttrs  viewTableAttrs
   * @return predictCompensationPlan
   */
  def computeCompensationPredicates(viewTablePlan: LogicalPlan,
      queryPredict: (EquivalenceClasses, Seq[ExpressionEqual],
          Seq[ExpressionEqual]),
      viewPredict: (EquivalenceClasses, Seq[ExpressionEqual],
          Seq[ExpressionEqual]),
      tableMapping: BiMap[String, String],
      columnMapping: Map[ExpressionEqual, mutable.Set[ExpressionEqual]],
      viewProjectList: Seq[Expression], viewTableAttrs: Seq[Attribute]):
  Option[LogicalPlan] = {
    val queryColumnMapping = queryPredict._1.getEquivalenceClassesMap

    // 1.compute equalColumnCompensation
    val compensationColumnsEquiPredicts = generateEquivalenceClasses(
      queryPredict._1, viewPredict._1)
    if (compensationColumnsEquiPredicts.isEmpty) {
      return None
    }

    // 2.compute rangeCompensation
    val queryRangePredicts = swapColumnReferences(
      queryPredict._2.map(_.expression), queryColumnMapping)
    val viewRangePredicts = swapTableColumnReferences(
      viewPredict._2.map(_.expression), tableMapping, queryColumnMapping)
    val compensationRangePredicts = splitFilter(
      mergeConjunctiveExpressions(queryRangePredicts),
      mergeConjunctiveExpressions(viewRangePredicts))
    if (compensationRangePredicts.isEmpty) {
      return None
    }

    // 3.compute residualCompensation
    val queryResidualPredicts = swapColumnReferences(
      queryPredict._3.map(_.expression), queryColumnMapping)
    val viewResidualPredicts = swapTableColumnReferences(
      viewPredict._3.map(_.expression), tableMapping, queryColumnMapping)
    val compensationResidualPredicts = splitFilter(
      mergeConjunctiveExpressions(queryResidualPredicts),
      mergeConjunctiveExpressions(viewResidualPredicts))
    if (compensationResidualPredicts.isEmpty) {
      return None
    }

    // 4.rewrite compensationColumnsEquiPredicts by viewTableAttrs
    val columnsEquiPredictsResult = rewriteExpressions(Seq(compensationColumnsEquiPredicts.get),
      swapTableColumn = false, tableMapping, columnMapping, viewProjectList, viewTableAttrs)
    if (columnsEquiPredictsResult.isEmpty) {
      return None
    }

    // 5.rewrite rangeCompensation,residualCompensation by viewTableAttrs
    val otherPredictsResult = rewriteExpressions(Seq(compensationRangePredicts.get,
      compensationResidualPredicts.get),
      swapTableColumn = true, tableMapping, queryColumnMapping, viewProjectList, viewTableAttrs)
    if (otherPredictsResult.isEmpty) {
      return None
    }

    // 6.compensate viewTablePlan
    Some(Filter(mergeConjunctiveExpressions(
      columnsEquiPredictsResult.get ++ otherPredictsResult.get), viewTablePlan))
  }

  /**
   * replace expression or attr by viewTableAttr
   *
   * @param exprsToRewrite  exprsToRewrite
   * @param swapTableColumn true:swapTableColumn;false:swapColumnTable
   * @param tableMapping    tableMapping
   * @param columnMapping   columnMapping
   * @param viewProjectList viewProjectList/viewAggExpression
   * @param viewTableAttrs  viewTableAttrs
   * @tparam T T <: Iterable[Expression]
   * @return rewritedExprs
   */
  def rewriteExpressions[T <: Iterable[Expression]](
      exprsToRewrite: T, swapTableColumn: Boolean,
      tableMapping: BiMap[String, String],
      columnMapping: Map[ExpressionEqual, mutable.Set[ExpressionEqual]],
      viewProjectList: Seq[Expression], viewTableAttrs: Seq[Attribute]): Option[T] = {

    // 1.swapReference for viewProjectList AttributeReference
    val swapProjectList = if (swapTableColumn) {
      swapTableColumnReferences(viewProjectList, tableMapping, columnMapping)
    } else {
      swapTableColumnReferences(viewProjectList, tableMapping, columnMapping)
    }
    val swapTableAttrs = swapTableReferences(viewTableAttrs, tableMapping)

    // 2.construct the map of viewQueryProjectExpression to ViewTableAttributeReference
    val viewProjectExprToTableAttr = swapProjectList.map(ExpressionEqual).zip(swapTableAttrs).toMap

    // 3.iterate exprsToRewrite and dfs mapping expression to ViewTableAttributeReference by map
    val result = exprsToRewrite.map { expr =>
      expr.transform {
        case e: NamedExpression =>
          val expressionEqual = ExpressionEqual(e)
          if (viewProjectExprToTableAttr.contains(expressionEqual)) {
            viewProjectExprToTableAttr(expressionEqual)
                .asInstanceOf[NamedExpression]
          } else {
            e
          }
        case e => e
      }
    }.asInstanceOf[T]

    // 4.iterate result and dfs check every AttributeReference in ViewTableAttributeReference
    val viewTableAttrsSet = swapTableAttrs.map(_.exprId).toSet
    result.foreach { expr =>
      expr.foreach {
        case attr: AttributeReference =>
          if (!viewTableAttrsSet.contains(attr.exprId)) {
            logBasedOnLevel(s"attr:%s cannot found in view:%s"
                .format(attr, OmniCachePluginConfig.getConf.curMatchMV))
            return None
          }
        case _ =>
      }
    }
    Some(result)
  }

  /**
   * if the rewrite expression exprId != origin expression exprId,
   * replace by Alias(rewrite expression,origin.name)(exprId=origin.exprId)
   *
   * @param newExpressions    newExpressions
   * @param originExpressions originExpressions
   * @return aliasExpressions
   */
  def aliasExpressions(newExpressions: Seq[NamedExpression],
      originExpressions: Seq[NamedExpression]): Seq[NamedExpression] = {
    val result = newExpressions.zip(originExpressions)
        .map { q =>
          val rewrited = q._1
          val origin = q._2
          if (rewrited.exprId == origin.exprId) {
            rewrited
          } else {
            Alias(rewrited, origin.name)(exprId = origin.exprId)
          }
        }
    result
  }

  /**
   * replace and alias expression or attr by viewTableAttr
   *
   * @param exprsToRewrite    exprsToRewrite
   * @param swapTableColumn   true:swapTableColumn;false:swapColumnTable
   * @param tableMapping      tableMapping
   * @param columnMapping     columnMapping
   * @param viewProjectList   viewProjectList/viewAggExpression
   * @param viewTableAttrs    viewTableAttrs
   * @param originExpressions originExpressions
   * @tparam T T <: Iterable[Expression]
   * @return rewrited and alias expression
   */
  def rewriteAndAliasExpressions[T <: Iterable[Expression]](
      exprsToRewrite: T, swapTableColumn: Boolean,
      tableMapping: BiMap[String, String],
      columnMapping: Map[ExpressionEqual, mutable.Set[ExpressionEqual]],
      viewProjectList: Seq[Expression], viewTableAttrs: Seq[Attribute],
      originExpressions: Seq[NamedExpression]): Option[T] = {
    val rewritedExpressions = rewriteExpressions(exprsToRewrite, swapTableColumn = true,
      tableMapping, columnMapping, viewProjectList, viewTableAttrs)
    if (rewritedExpressions.isEmpty) {
      return None
    }

    val aliasedExpressions = aliasExpressions(
      rewritedExpressions.get.map(_.asInstanceOf[NamedExpression]).toSeq, originExpressions)
    Some(aliasedExpressions.asInstanceOf[T])
  }

  /**
   * use viewTablePlan(join compensated) ,query project ,
   * compensationPredicts to rewrite final plan
   *
   * @param viewTablePlan   viewTablePlan(join compensated)
   * @param viewQueryPlan   viewQueryPlan
   * @param queryPlan       queryPlan
   * @param tableMapping    tableMapping
   * @param columnMapping   columnMapping
   * @param viewProjectList viewProjectList
   * @param viewTableAttrs  viewTableAttrs
   * @return final plan
   */
  def rewriteView(viewTablePlan: LogicalPlan, viewQueryPlan: LogicalPlan,
      queryPlan: LogicalPlan, tableMapping: BiMap[String, String],
      columnMapping: Map[ExpressionEqual, mutable.Set[ExpressionEqual]],
      viewProjectList: Seq[Expression], viewTableAttrs: Seq[Attribute]):
  Option[LogicalPlan]
}
