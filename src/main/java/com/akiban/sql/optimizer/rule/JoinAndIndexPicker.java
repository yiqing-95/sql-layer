/**
 * Copyright (C) 2012 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.sql.optimizer.rule;

import com.akiban.sql.optimizer.rule.join_enum.*;
import com.akiban.sql.optimizer.rule.join_enum.DPhyp.JoinOperator;

import com.akiban.sql.optimizer.plan.*;
import com.akiban.sql.optimizer.plan.Sort.OrderByExpression;
import com.akiban.sql.optimizer.plan.JoinNode.JoinType;

import com.akiban.server.error.AkibanInternalException;
import com.akiban.server.error.UnsupportedSQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** Pick joins and indexes. 
 * This the the core of actual query optimization.
 */
public class JoinAndIndexPicker extends BaseRule
{
    private static final Logger logger = LoggerFactory.getLogger(JoinAndIndexPicker.class);

    @Override
    protected Logger getLogger() {
        return logger;
    }

    @Override
    public void apply(PlanContext planContext) {
        BaseQuery query = (BaseQuery)planContext.getPlan();
        List<Picker> pickers = 
          new JoinsFinder(((SchemaRulesContext)planContext.getRulesContext())
                          .getCostEstimator()).find(query);
        for (Picker picker : pickers) {
            picker.apply();
        }
    }

    static class Picker {
        Map<SubquerySource,Picker> subpickers;
        CostEstimator costEstimator;
        Joinable joinable;
        BaseQuery query;
        QueryIndexGoal queryGoal;

        public Picker(Joinable joinable, BaseQuery query,
                      CostEstimator costEstimator, 
                      Map<SubquerySource,Picker> subpickers) {
            this.subpickers = subpickers;
            this.costEstimator = costEstimator;
            this.joinable = joinable;
            this.query = query;
        }

        public void apply() {
            queryGoal = determineQueryIndexGoal(joinable);
            if (joinable instanceof TableGroupJoinTree) {
                // Single group.
                pickIndex((TableGroupJoinTree)joinable);
            }
            else if (joinable instanceof JoinNode) {
                // General joins.
                pickJoinsAndIndexes((JoinNode)joinable);
            }
            else if (joinable instanceof SubquerySource) {
                // Single subquery // view. Just do its insides.
                subpicker((SubquerySource)joinable).apply();
            }
            // TODO: Any other degenerate cases?
        }

        protected QueryIndexGoal determineQueryIndexGoal(PlanNode input) {
            ConditionList whereConditions = null;
            Sort ordering = null;
            AggregateSource grouping = null;
            Project projectDistinct = null;
            input = input.getOutput();
            if (input instanceof Select) {
                ConditionList conds = ((Select)input).getConditions();
                if (!conds.isEmpty()) {
                    whereConditions = conds;
                }
            }
            input = input.getOutput();
            if (input instanceof Sort) {
                ordering = (Sort)input;
            }
            else if (input instanceof AggregateSource) {
                grouping = (AggregateSource)input;
                if (!grouping.hasGroupBy())
                    grouping = null;
                input = input.getOutput();
                if (input instanceof Select)
                    input = input.getOutput();
                if (input instanceof Sort) {
                    // Needs to be possible to satisfy both.
                    ordering = (Sort)input;
                    if (grouping != null) {
                        List<ExpressionNode> groupBy = grouping.getGroupBy();
                        for (OrderByExpression orderBy : ordering.getOrderBy()) {
                            ExpressionNode orderByExpr = orderBy.getExpression();
                            if (!((orderByExpr.isColumn() &&
                                   (((ColumnExpression)orderByExpr).getTable() == grouping)) ||
                                  groupBy.contains(orderByExpr))) {
                                ordering = null;
                                break;
                            }
                        }
                    }
                }
            }
            else if (input instanceof Project) {
                Project project = (Project)input;
                input = project.getOutput();
                if (input instanceof Distinct)
                    projectDistinct = project;
                else if (input instanceof Sort)
                    ordering = (Sort)input;
            }
            return new QueryIndexGoal(query, costEstimator, whereConditions, 
                                      grouping, ordering, projectDistinct);
        }

        // Only a single group of tables. Don't need to run general
        // join algorithm and can shortcut some of the setup for this
        // group.
        protected void pickIndex(TableGroupJoinTree tables) {
            GroupIndexGoal groupGoal = new GroupIndexGoal(queryGoal, tables);
            groupGoal.updateRequiredColumns(); // No more joins / bound tables.
            PlanNode scan = groupGoal.pickBestScan();
            joinable.getOutput().replaceInput(tables, toTableJoins(tables, scan));
        }

        // General joins: run enumerator.
        protected void pickJoinsAndIndexes(JoinNode joins) {
            Plan rootPlan = new JoinEnumerator(this).run(joins, queryGoal.getWhereConditions()).bestPlan();
            installPlan(rootPlan);
        }

        // Put the chosen plan in place.
        public void installPlan(Plan rootPlan) {
            joinable.getOutput().replaceInput(joinable, rootPlan.install());
        }

        // Get the handler for the given subquery so that it can be done in context.
        public Picker subpicker(SubquerySource subquery) {
            Picker subpicker = subpickers.get(subquery);
            assert (subpicker != null);
            return subpicker;
        }

        // Subquery but as part of a larger plan tree. Return best
        // plan to be installed with it.
        public Plan subqueryPlan(Set<ColumnSource> subqueryBoundTables,
                                 Collection<JoinOperator> subqueryJoins) {
            if (queryGoal == null)
                queryGoal = determineQueryIndexGoal(joinable);
            if (joinable instanceof TableGroupJoinTree) {
                TableGroupJoinTree tables = (TableGroupJoinTree)joinable;
                GroupIndexGoal groupGoal = new GroupIndexGoal(queryGoal, tables);
                groupGoal.setBoundTables(subqueryBoundTables);
                groupGoal.setJoinConditions(subqueryJoins);
                groupGoal.updateRequiredColumns();
                PlanNode scan = groupGoal.pickBestScan();
                CostEstimate costEstimate = groupGoal.costEstimateScan(scan);
                return new GroupPlan(tables, JoinableBitSet.of(0), scan, costEstimate);
            }
            if (joinable instanceof JoinNode) {
                return new JoinEnumerator(this, subqueryBoundTables, subqueryJoins).run((JoinNode)joinable, queryGoal.getWhereConditions()).bestPlan();
            }
            if (joinable instanceof SubquerySource) {
                return subpicker((SubquerySource)joinable).subqueryPlan(subqueryBoundTables, subqueryJoins);
            }
            throw new AkibanInternalException("Unknown join element: " + joinable);
        }

    }

    static abstract class Plan implements Comparable<Plan> {
        CostEstimate costEstimate;

        protected Plan(CostEstimate costEstimate) {
            this.costEstimate = costEstimate;
        }

        public int compareTo(Plan other) {
            return costEstimate.compareTo(other.costEstimate);
        }

        public abstract Joinable install();

        public void addDistinct() {
            throw new UnsupportedOperationException();
        }
    }

    static abstract class PlanClass {
        JoinEnumerator enumerator;        
        long bitset;

        protected PlanClass(JoinEnumerator enumerator, long bitset) {
            this.enumerator = enumerator;
            this.bitset = bitset;
        }

        public abstract Plan bestPlan();

        public Plan bestNestedPlan(PlanClass outerPlan, Collection<JoinOperator> joins) {
            return bestPlan();  // By default, it doesn't matter.
        }
    }
    
    static class GroupPlan extends Plan {
        TableGroupJoinTree tables;
        long outerTables;
        PlanNode scan;

        public GroupPlan(TableGroupJoinTree tables, 
                         long outerTables, PlanNode scan, 
                         CostEstimate costEstimate) {
            super(costEstimate);
            this.tables = tables;
            this.outerTables = outerTables;
            this.scan = scan;
        }

        @Override
        public String toString() {
            return scan.toString();
        }

        @Override
        public Joinable install() {
            return toTableJoins(tables, scan);
        }
    }

    // Temporarily convert join tree back to older table joins so that
    // following rules can run unchanged.
    // TODO: Remove when start doing more intelligent branch joining.
    protected static TableJoins toTableJoins(TableGroupJoinTree tables, PlanNode scan) {
        Joinable joinable = null;
        for (TableGroupJoinTree.TableGroupJoinNode table : tables) {
            if (joinable == null)
                joinable = table.getTable();
            else {
                JoinNode join = new JoinNode(joinable, 
                                             table.getTable(),
                                             table.getParentJoinType());
                join.setJoinConditions(table.getJoinConditions());
            }
        }
        TableJoins joins = new TableJoins(joinable, tables.getGroup());
        joins.setScan(scan);
        return joins;
    }

    static class GroupPlanClass extends PlanClass {
        GroupIndexGoal groupGoal;
        Collection<GroupPlan> bestPlans = new ArrayList<GroupPlan>();

        public GroupPlanClass(JoinEnumerator enumerator, long bitset, 
                              GroupIndexGoal groupGoal) {
            super(enumerator, bitset);
            this.groupGoal = groupGoal;
        }

        @Override
        public Plan bestPlan() {
            return bestPlan(JoinableBitSet.empty(), Collections.<JoinOperator>emptyList());
        }

        @Override
        public Plan bestNestedPlan(PlanClass outerPlan, Collection<JoinOperator> joins) {
            return bestPlan(outerPlan.bitset, joins);
        }

        protected GroupPlan bestPlan(long outerTables, Collection<JoinOperator> joins) {
            for (GroupPlan groupPlan : bestPlans) {
                if (groupPlan.outerTables == outerTables) {
                    return groupPlan;
                }
            }
            groupGoal.setBoundTables(enumerator.boundTables(outerTables));
            groupGoal.setJoinConditions(joins);
            groupGoal.updateRequiredColumns();
            PlanNode scan = groupGoal.pickBestScan();
            CostEstimate costEstimate = groupGoal.costEstimateScan(scan);
            GroupPlan groupPlan = new GroupPlan(groupGoal.getTables(),
                                                outerTables, scan, 
                                                costEstimate);
            bestPlans.add(groupPlan);
            return groupPlan;
        }
    }

    static class SubqueryPlan extends Plan {
        SubquerySource subquery;
        Picker picker;
        long outerTables;
        Plan rootPlan;

        public SubqueryPlan(SubquerySource subquery, Picker picker,
                            long outerTables, Plan rootPlan, 
                            CostEstimate costEstimate) {
            super(costEstimate);
            this.subquery = subquery;
            this.picker = picker;
            this.outerTables = outerTables;
            this.rootPlan = rootPlan;
        }

        @Override
        public String toString() {
            return rootPlan.toString();
        }


        @Override
        public Joinable install() {
            picker.installPlan(rootPlan);
            return subquery;
        }        

        @Override
        public void addDistinct() {
            Subquery output = subquery.getSubquery();
            PlanNode input = output.getInput();
            output.replaceInput(input, new Distinct(input));
        }
    }

    static class SubqueryPlanClass extends PlanClass {
        SubquerySource subquery;
        Picker picker;
        Collection<SubqueryPlan> bestPlans = new ArrayList<SubqueryPlan>();

        public SubqueryPlanClass(JoinEnumerator enumerator, long bitset, 
                                 SubquerySource subquery, Picker picker) {
            super(enumerator, bitset);
            this.subquery = subquery;
            this.picker = picker;
        }

        @Override
        public Plan bestPlan() {
            return bestPlan(JoinableBitSet.empty(), Collections.<JoinOperator>emptyList());
        }

        @Override
        public Plan bestNestedPlan(PlanClass outerPlan, Collection<JoinOperator> joins) {
            return bestPlan(outerPlan.bitset, joins);
        }

        protected SubqueryPlan bestPlan(long outerTables, Collection<JoinOperator> joins) {
            for (SubqueryPlan subqueryPlan : bestPlans) {
                if (subqueryPlan.outerTables == outerTables) {
                    return subqueryPlan;
                }
            }
            Plan rootPlan = picker.subqueryPlan(enumerator.boundTables(outerTables), joins);
            CostEstimate costEstimate = rootPlan.costEstimate;
            SubqueryPlan subqueryPlan = new SubqueryPlan(subquery, picker,
                                                         outerTables, rootPlan, 
                                                         costEstimate);
            bestPlans.add(subqueryPlan);
            return subqueryPlan;
        }
    }

    static class ValuesPlan extends Plan {
        ExpressionsSource values;
        
        public ValuesPlan(ExpressionsSource values, CostEstimate costEstimate) {
            super(costEstimate);
            this.values = values;
        }

        @Override
        public String toString() {
            return values.getName();
        }


        @Override
        public Joinable install() {
            return values;
        }

        @Override
        public void addDistinct() {
            values.setDistinctState(ExpressionsSource.DistinctState.NEED_DISTINCT);
        }
    }

    static class ValuesPlanClass extends PlanClass {
        ValuesPlan plan;

        public ValuesPlanClass(JoinEnumerator enumerator, long bitset, 
                               ExpressionsSource values) {
            super(enumerator, bitset);
            this.plan = new ValuesPlan(values, new CostEstimate(values.getExpressions().size(), 0));
        }

        @Override
        public Plan bestPlan() {
            return plan;
        }
    }

    static class JoinPlan extends Plan {
        Plan left, right;
        JoinType joinType;
        JoinNode.Implementation joinImplementation;
        Collection<JoinOperator> joins;
        boolean needDistinct;
        
        public JoinPlan(Plan left, Plan right, 
                        JoinType joinType, JoinNode.Implementation joinImplementation,
                        Collection<JoinOperator> joins, CostEstimate costEstimate) {
            super(costEstimate);
            this.left = left;
            this.right = right;
            switch (joinType) {
            case SEMI_INNER_ALREADY_DISTINCT:
            case SEMI_INNER_IF_DISTINCT:
                this.joinType = JoinType.SEMI;
                break;
            case INNER_NEED_DISTINCT:
                this.joinType = JoinType.INNER;
                needDistinct = true;
                break;
            default:
                this.joinType = joinType;
            }
            this.joinImplementation = joinImplementation;
            this.joins = joins;
        }

        @Override
        public String toString() {
            return "(" + left + ") " +
                joinType + "/" + joinImplementation +
                " (" + right + ")";
        }

        @Override
        public Joinable install() {
            if (needDistinct)
                left.addDistinct();
            Joinable leftJoinable = left.install();
            Joinable rightJoinable = right.install();
            JoinNode join = null;
            ConditionList joinConditions = null;
            boolean newJoinConditions = false;
            for (JoinOperator joinOp : joins) {
                if ((join == null) &&
                    (joinOp.getJoin() != null)) {
                    join = joinOp.getJoin();
                }
                if (joinConditions == null) {
                    joinConditions = joinOp.getJoinConditions();
                }
                else { 
                    if (!newJoinConditions) {
                        joinConditions = new ConditionList(joinConditions);
                        newJoinConditions = true;
                    }
                    joinConditions.addAll(joinOp.getJoinConditions());
                }
            }
            if (join == null) {
                join = new JoinNode(leftJoinable, rightJoinable, joinType);
            }
            else {
                join.setLeft(leftJoinable);
                join.setRight(rightJoinable);
                join.setJoinType(joinType);
            }
            join.setJoinConditions(joinConditions);
            join.setImplementation(joinImplementation);
            return join;
        }
    }

    static class JoinPlanClass extends PlanClass {
        JoinPlan bestPlan;      // TODO: Later have separate sorted, etc.

        public JoinPlanClass(JoinEnumerator enumerator, long bitset) {
            super(enumerator, bitset);
        }

        @Override
        public Plan bestPlan() {
            return bestPlan;
        }
    }

    static class JoinEnumerator extends DPhyp<PlanClass> {
        private Picker picker;
        private Set<ColumnSource> subqueryBoundTables;
        private Collection<JoinOperator> subqueryJoins;

        public JoinEnumerator(Picker picker) {
            this.picker = picker;
        }

        public JoinEnumerator(Picker picker, Set<ColumnSource> subqueryBoundTables, Collection<JoinOperator> subqueryJoins) {
            this.picker = picker;
            this.subqueryBoundTables = subqueryBoundTables;
            this.subqueryJoins = subqueryJoins;
        }

        @Override
        public PlanClass evaluateTable(long s, Joinable joinable) {
            // Seed with the right plan class to hold state / alternatives.
            if (joinable instanceof TableGroupJoinTree) {
                GroupIndexGoal groupGoal = new GroupIndexGoal(picker.queryGoal, 
                                                              (TableGroupJoinTree)joinable);
                return new GroupPlanClass(this, s, groupGoal);
            }
            if (joinable instanceof SubquerySource) {
                SubquerySource subquery = (SubquerySource)joinable;
                Picker subpicker = picker.subpicker(subquery);
                return new SubqueryPlanClass(this, s, subquery, subpicker);
            }
            if (joinable instanceof ExpressionsSource) {
                return new ValuesPlanClass(this, s, (ExpressionsSource)joinable);
            }
            throw new AkibanInternalException("Unknown join element: " + joinable);
        }

        @Override
        public PlanClass evaluateJoin(long leftBitset, PlanClass left, 
                                      long rightBitset, PlanClass right, 
                                      long bitset, PlanClass existing,
                                      JoinType joinType, Collection<JoinOperator> joins) {
            JoinPlanClass planClass = (JoinPlanClass)existing;
            if (planClass == null)
                planClass = new JoinPlanClass(this, bitset);
            if (subqueryJoins != null) {
                // "Push down" joins into the subquery. Since these
                // are joins to the dervived table, they still need to
                // be recognized to match an indexable column.
                Collection<JoinOperator> allJoins = new ArrayList<JoinOperator>(subqueryJoins);
                allJoins.addAll(joins);
                joins = allJoins;
            }
            // TODO: Divvy up sorting. Consider group joins. Consider merge joins.
            Plan leftPlan = left.bestPlan();
            Plan rightPlan = right.bestNestedPlan(left, joins);
            CostEstimate costEstimate = leftPlan.costEstimate.nest(rightPlan.costEstimate);
            JoinPlan joinPlan = new JoinPlan(leftPlan, rightPlan,
                                             joinType, JoinNode.Implementation.NESTED_LOOPS,
                                             joins, costEstimate);
            if (planClass.bestPlan == null) {
                logger.debug("Selecting {}, {}", joinPlan, costEstimate);
                planClass.bestPlan = joinPlan;
            }
            else if (planClass.bestPlan.compareTo(joinPlan) > 0) {
                logger.debug("Preferring {}, {}", joinPlan, costEstimate);
                planClass.bestPlan = joinPlan;
            }
            else {
                logger.debug("Rejecting {}, {}", joinPlan, costEstimate);
            }
            return planClass;
        }

        /** Get the tables that correspond to the given bitset, plus
         * any that are bound outside the subquery, either
         * syntactically or via joins to it.
         */
        public Set<ColumnSource> boundTables(long tables) {
            if (JoinableBitSet.isEmpty(tables) &&
                (subqueryBoundTables == null))
                return picker.queryGoal.getQuery().getOuterTables();
            Set<ColumnSource> boundTables = new HashSet<ColumnSource>();
            boundTables.addAll(picker.queryGoal.getQuery().getOuterTables());
            if (subqueryBoundTables != null)
                boundTables.addAll(subqueryBoundTables);
            if (!JoinableBitSet.isEmpty(tables)) {
                for (int i = 0; i < 64; i++) {
                    if (JoinableBitSet.overlaps(tables, JoinableBitSet.of(i))) {
                        Joinable table = getTable(i);
                        if (table instanceof TableGroupJoinTree) {
                            for (TableGroupJoinTree.TableGroupJoinNode gtable : (TableGroupJoinTree)table) {
                                boundTables.add(gtable.getTable());
                            }
                        }
                        else {
                            boundTables.add((ColumnSource)table);
                        }
                    }
                }
            }
            return boundTables;
        }
    }
    
    // Purpose is twofold: 
    // Find top-level joins and note what query they come from; 
    // Annotate subqueries with their outer table references.
    // Top-level queries and those used in expressions are returned directly.
    // Derived tables are deferred, since they need to be planned in
    // the context of various join orders to allow for join predicated
    // to be pushed "inside." So they are stored in a Map accessible
    // to other Pickers.
    static class JoinsFinder implements PlanVisitor, ExpressionVisitor {
        List<Picker> result;
        Map<SubquerySource,Picker> subpickers;
        BaseQuery rootQuery;
        Deque<SubqueryState> subqueries = new ArrayDeque<SubqueryState>();
        CostEstimator costEstimator;

        public JoinsFinder(CostEstimator costEstimator) {
            this.costEstimator = costEstimator;
        }

        public List<Picker> find(BaseQuery query) {
            result = new ArrayList<Picker>();
            subpickers = new HashMap<SubquerySource,Picker>();
            rootQuery = query;
            query.accept(this);
            result.removeAll(subpickers.values()); // Do these in context.
            return result;
        }

        @Override
        public boolean visitEnter(PlanNode n) {
            if (n instanceof Subquery) {
                subqueries.push(new SubqueryState((Subquery)n));
                return true;
            }
            return visit(n);
        }

        @Override
        public boolean visitLeave(PlanNode n) {
            if (n instanceof Subquery) {
                SubqueryState s = subqueries.pop();
                s.subquery.setOuterTables(s.getTablesReferencedButNotDefined());
            }
            return true;
        }

        @Override
        public boolean visit(PlanNode n) {
            if (!subqueries.isEmpty() &&
                (n instanceof ColumnSource)) {
                boolean added = subqueries.peek().tablesDefined.add((ColumnSource)n);
                assert added : "Table defined more than once";
            }
            if ((n instanceof Joinable) && !(n instanceof TableSource)) {
                Joinable j = (Joinable)n;
                while (j.getOutput() instanceof Joinable)
                    j = (Joinable)j.getOutput();
                BaseQuery query = rootQuery;
                SubquerySource subquerySource = null;
                if (!subqueries.isEmpty()) {
                    query = subqueries.peek().subquery;
                    if (query.getOutput() instanceof SubquerySource)
                        subquerySource = (SubquerySource)query.getOutput();
                }
                for (Picker picker : result) {
                    if (picker.joinable == j)
                        // Already have another set of joins to same root join.
                        return true;
                }
                Picker picker = new Picker(j, query, costEstimator, subpickers);
                result.add(picker);
                if (subquerySource != null)
                    subpickers.put(subquerySource, picker);
            }
            return true;
        }

        @Override
        public boolean visitEnter(ExpressionNode n) {
            return visit(n);
        }

        @Override
        public boolean visitLeave(ExpressionNode n) {
            return true;
        }

        @Override
        public boolean visit(ExpressionNode n) {
            if (!subqueries.isEmpty() &&
                (n instanceof ColumnExpression)) {
                subqueries.peek().tablesReferenced.add(((ColumnExpression)n).getTable());
            }
            return true;
        }
    }

    static class SubqueryState {
        Subquery subquery;
        Set<ColumnSource> tablesReferenced = new HashSet<ColumnSource>();
        Set<ColumnSource> tablesDefined = new HashSet<ColumnSource>();

        public SubqueryState(Subquery subquery) {
            this.subquery = subquery;
        }

        public Set<ColumnSource> getTablesReferencedButNotDefined() {
            tablesReferenced.removeAll(tablesDefined);
            return tablesReferenced;
        }
    }

}
