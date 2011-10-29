/**
 * Copyright (C) 2011 Akiban Technologies Inc.
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

package com.akiban.qp.operator;

import com.akiban.qp.row.Row;
import com.akiban.qp.row.ValuesRow;
import com.akiban.qp.rowtype.RowType;
import com.akiban.qp.rowtype.UserTableRowType;
import com.akiban.qp.rowtype.ValuesRowType;
import com.akiban.server.types.AkType;
import com.akiban.util.ArgumentValidation;

import java.util.Set;

class Count_TableStatus extends Operator
{
    // Object interface

    @Override
    public String toString()
    {
        return String.format("%s(%s)", getClass().getSimpleName(), tableType);
    }

    // Operator interface

    @Override
    protected Cursor cursor(StoreAdapter adapter)
    {
        return new Execution(adapter);
    }

    @Override
    public RowType rowType()
    {
        return resultType;
    }

    @Override
    public void findDerivedTypes(Set<RowType> derivedTypes)
    {
        derivedTypes.add(resultType);
    }

    // Count_TableStatus interface

    public Count_TableStatus(RowType tableType)
    {
        ArgumentValidation.notNull("tableType", tableType);
        ArgumentValidation.isTrue("tableType instanceof UserTableRowType",
                                  tableType instanceof UserTableRowType);
        this.tableType = tableType;
        this.resultType = tableType.schema().newValuesType(AkType.LONG);
    }

    // Object state

    private final RowType tableType;
    private final ValuesRowType resultType;

    // Inner classes

    private class Execution implements Cursor
    {
        // Cursor interface

        @Override
        public void open(Bindings bindings)
        {
            pending = true;
        }

        @Override
        public Row next()
        {
            adapter.checkQueryCancelation();
            if (pending) {
                long rowCount = adapter.rowCount(tableType);
                pending = false;
                return new ValuesRow(resultType, new Object[] { rowCount });
            }
            else {
                return null;
            }
        }

        @Override
        public void close()
        {
            pending = false;
        }

        // Execution interface

        Execution(StoreAdapter adapter)
        {
            this.adapter = adapter;
        }

        // Object state

        private final StoreAdapter adapter;
        private boolean pending;
    }
}
