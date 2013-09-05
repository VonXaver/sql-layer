/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.sql.optimizer.rule;

import com.foundationdb.sql.optimizer.plan.*;

import com.foundationdb.ais.model.Column;
import com.foundationdb.ais.model.IndexColumn;
import com.foundationdb.ais.model.Join;
import com.foundationdb.ais.model.JoinColumn;

import com.foundationdb.sql.optimizer.plan.BaseUpdateStatement.StatementType;
import com.foundationdb.sql.optimizer.plan.UpdateStatement.UpdateColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** Identify queries that are susceptible to the Halloween problem.
 * <ul>
 * <li>Updating a primary or grouping foreign key, 
 * which can change hkeys and so group navigation.</li>
 * <li>Updating a field of an index that is scanned.</li>
 * <li>A <em>second</em> access to the target table.</li>
 * </ul>
 */
public class HalloweenRecognizer extends BaseRule
{
    private static final Logger logger = LoggerFactory.getLogger(HalloweenRecognizer.class);

    @Override
    protected Logger getLogger() {
        return logger;
    }

    @Override
    public void apply(PlanContext plan) {
        if (plan.getPlan() instanceof DMLStatement) {
            DMLStatement stmt = (DMLStatement)plan.getPlan();
            TableNode targetTable = stmt.getTargetTable();
            boolean bufferRequired = false;
            Set<Column> updateColumns = new HashSet<>();

            if (stmt.getType() == BaseUpdateStatement.StatementType.UPDATE) {
                UpdateStatement updateStmt = findUpdateStmt(stmt);
                
                update: { 
                    for (UpdateStatement.UpdateColumn updateColumn : updateStmt.getUpdateColumns()) {
                        updateColumns.add(updateColumn.getColumn());
                    }
                    for (Column pkColumn : targetTable.getTable().getPrimaryKeyIncludingInternal().getColumns()) {
                        if (updateColumns.contains(pkColumn)) {
                            bufferRequired = true;
                            break update;
                        }
                    }
                    Join parentJoin = targetTable.getTable().getParentJoin();
                    if (parentJoin != null) {
                        for (JoinColumn joinColumn : parentJoin.getJoinColumns()) {
                            if (updateColumns.contains(joinColumn.getChild())) {
                                bufferRequired = true;
                                break update;
                            }
                        }
                    }
                }

                if(bufferRequired) {
                    DMLStatement newDMLStmt = transformUpdate(stmt, updateStmt);
                    plan.setPlan(newDMLStmt);
                }
            }

            if (!bufferRequired) {
                Checker checker = new Checker(stmt, updateColumns);
                checker.check(stmt.getQuery());
                if(checker.newDMLStmt != null) {
                    plan.setPlan(checker.newDMLStmt);
                }
            }
        }
    }

    private static UpdateStatement findUpdateStmt(DMLStatement stmt) {
        BasePlanWithInput node = stmt;
        do {
            node = (BasePlanWithInput) node.getInput();
        } while (node != null && !(node instanceof UpdateStatement));
        assert node != null;
        return (UpdateStatement)node;
    }

    private static List<ExpressionNode> updateListToProjectList(UpdateStatement update, TableSource tableSource) {
        List<ExpressionNode> projects = new ArrayList<>();
        for(Column c : update.getTargetTable().getTable().getColumns()) {
            projects.add(new ColumnExpression(tableSource, c));
        }
        for(UpdateColumn updateCol : update.getUpdateColumns()) {
            projects.set(updateCol.getColumn().getPosition(), updateCol.getExpression());
        }
        return projects;
    }

    /**
     * Transform
     * <pre>Out() <- Update(col=5) <- In()</pre>
     * to
     * <pre>Out() <- Insert() <- Project(col=5) <- Buffer() <- Delete() <- In()</pre>
     */
    private static DMLStatement transformUpdate(DMLStatement dml, UpdateStatement update) {
        assert dml.getOutput() == null;

        TableSource tableSource = dml.getSelectTable();
        PlanNode scanSource = update.getInput();
        PlanWithInput updateDest = update.getOutput();

        InsertStatement insert = new InsertStatement(
            new Project(
                new Buffer(
                    new DeleteStatement(scanSource, update.getTargetTable(), tableSource)
                ),
                updateListToProjectList(update, tableSource)
            ),
            update.getTargetTable(),
            tableSource.getTable().getTable().getColumns(),
            tableSource
        );

        scanSource.setOutput(insert);
        updateDest.replaceInput(update, insert);

        return new DMLStatement(insert, StatementType.INSERT, dml.getSelectTable(), dml.getTargetTable(),
                                dml.getResultField(), dml.getReturningTable(), dml.getColumnEquivalencies());
    }

    /**
     * Transform
     * <pre>Out() <- IndexScan()</pre>
     * to
     * <pre>Out() <- Buffer() <- IndexScan()</pre>
     */
    private static void injectBufferNode(PlanNode node) {
        PlanWithInput origDest = node.getOutput();
        PlanWithInput newInput = new Buffer(node);
        origDest.replaceInput(node, newInput);
    }

    static class Checker implements PlanVisitor, ExpressionVisitor {
        private final DMLStatement dmlStmt;
        private final TableNode targetTable;
        private final Set<Column> updateColumns;
        private int targetMaxUses;
        private boolean bufferRequired;
        private DMLStatement newDMLStmt;

        public Checker(DMLStatement dmlStmt, Set<Column> updateColumns) {
            assert updateColumns != null;
            this.dmlStmt = dmlStmt;
            this.targetTable = dmlStmt.getTargetTable();
            this.targetMaxUses = (dmlStmt.getType() == StatementType.INSERT) ? 0 : 1;
            this.updateColumns = updateColumns;
        }

        public boolean check(PlanNode root) {
            bufferRequired = false;
            root.accept(this);
            return bufferRequired;
        }

        private void indexScan(IndexScan scan) {
            if (scan instanceof SingleIndexScan) {
                SingleIndexScan single = (SingleIndexScan)scan;
                if (single.isCovering()) { // Non-covering loads via XxxLookup.
                    for (TableSource table : single.getTables()) {
                        if (table.getTable() == targetTable) {
                            targetMaxUses--;
                            if (targetMaxUses < 0) {
                                bufferRequired = true;
                            }
                            break;
                        }
                    }
                }
                if (!updateColumns.isEmpty()) {
                    for (IndexColumn indexColumn : single.getIndex().getAllColumns()) {
                        if (updateColumns.contains(indexColumn.getColumn())) {
                            bufferRequired = true;
                            break;
                        }
                    }
                }

                if(bufferRequired) {
                    switch(dmlStmt.getType()) {
                        case INSERT:
                        case DELETE:
                            injectBufferNode(single);
                            break;
                        case UPDATE:
                            if(single.getIndex().isUnique()) {
                                newDMLStmt = transformUpdate(dmlStmt, findUpdateStmt(dmlStmt));
                            } else {
                                injectBufferNode(single);
                            }
                        break;
                        default:
                            assert false : dmlStmt.getType();
                    }
                }
            }
            else if (scan instanceof MultiIndexIntersectScan) {
                MultiIndexIntersectScan multi = (MultiIndexIntersectScan)scan;
                indexScan(multi.getOutputIndexScan());
                indexScan(multi.getSelectorIndexScan());
            }
        }

        @Override
        public boolean visitEnter(PlanNode n) {
            return visit(n);
        }

        @Override
        public boolean visitLeave(PlanNode n) {
            return !bufferRequired;
        }

        @Override
        public boolean visit(PlanNode n) {
            if (n instanceof IndexScan) {
                indexScan((IndexScan)n);
            }
            else if (n instanceof TableLoader) {
                for (TableSource table : ((TableLoader)n).getTables()) {
                    if (table.getTable() == targetTable) {
                        targetMaxUses--;
                        if (targetMaxUses < 0) {
                            bufferRequired = true;
                            injectBufferNode(n);
                            break;
                        }
                    }
                }
            }
            return !bufferRequired;
        }

        @Override
        public boolean visitEnter(ExpressionNode n) {
            return visit(n);
        }

        @Override
        public boolean visitLeave(ExpressionNode n) {
            return !bufferRequired;
        }

        @Override
        public boolean visit(ExpressionNode n) {
            return !bufferRequired;
        }
    }
}
