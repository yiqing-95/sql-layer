package com.akiban.ais.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class PrimaryKey implements Serializable
{
    public List<Column> getColumns()
    {
        return columns;
    }

    public Index getIndex()
    {
        return index;
    }

    public PrimaryKey()
    {
        // GWT: needs default constructor
    }

    public PrimaryKey(Index index)
    {
        this.index = index;
        this.columns = new ArrayList<Column>();
        for (IndexColumn indexColumn : index.getColumns()) {
            this.columns.add(indexColumn.getColumn());
        }
    }

    private Index index;
    private List<Column> columns;
}
