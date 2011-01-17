package com.akiban.cserver.itests.bugs.bug695544;

import com.akiban.ais.ddl.SchemaDef;
import com.akiban.cserver.InvalidOperationException;
import com.akiban.cserver.api.common.TableId;
import com.akiban.cserver.api.ddl.ParseException;
import com.akiban.cserver.api.dml.DuplicateKeyException;
import com.akiban.cserver.itests.ApiTestBase;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public final class ParsingUNIQUETest extends ApiTestBase {
    private final static String SCHEMA = "sc1";
    private final static String TABLE = "tb1";
    private TableId tableId;

    @After
    public void tearDown() {
        tableId = null;
    }

    @Test
    public void UNIQUE() throws InvalidOperationException {
        create( "id int key",
                "c1 int UNIQUE");
        testInserts();
        testIndex("c1");
    }

    @Test
    public void UNIQUE_KEY() throws InvalidOperationException {
        create( "id int key",
                "c1 int UNIQUE KEY");
        testInserts();
        testIndex("c1");
    }

    @Test
    public void UNIQUE_KEY_UNIQUE_UNIQUE() throws InvalidOperationException {
        create( "id int key",
                "c1 int UNIQUE KEY UNIQUE UNIQUE");
        testInserts();
        testIndex("c1");
    }

    @Test
    public void KEY_KEY_KEY_UNIQUE() throws InvalidOperationException {
        create( "id int",
                "c1 int KEY KEY KEY UNIQUE");
        testInserts();
        expectIndexes(tableId, "PRIMARY", "c1");
        expectIndexColumns(tableId, "PRIMARY", "c1");
        expectIndexColumns(tableId, "c1", "c1");
    }

    @Test
    public void UNIQUE_PRIMARY_KEY() throws InvalidOperationException {
        create("id int",
                "c1 int UNIQUE PRIMARY KEY");
        testInserts();
        expectIndexes(tableId, "PRIMARY");
        expectIndexColumns(tableId, "PRIMARY", "c1");
    }

    @Test(expected=ParseException.class)
    public void fail_PRIMARY() throws InvalidOperationException {
        create("id int primary");
    }

    @Test(expected=ParseException.class)
    public void fail_PRIMARY_UNIQUE_KEY() throws InvalidOperationException {
        create("id int primary unique key");
    }

    @Test(expected=ParseException.class)
    public void fail_twoColsWithKEY() throws InvalidOperationException {
        create("id1 int key, id2 int key");
    }

    @Test
    public void constraintUNIQUE() throws InvalidOperationException {
        create( "id int key",
                "c1 int",
                "UNIQUE (c1)");
        testInserts();
        testIndex("c1");
    }

    @Test
    public void fullerConstraintUNIQUE_INDEX() throws InvalidOperationException {
        create( "id int key",
                "c1 int",
                "CONSTRAINT my_uniqueness_constraint UNIQUE INDEX my_uniqueness_index (c1)");
        testInserts();
        testIndex("my_uniqueness_index");
    }

    @Test
    public void fullerConstraintUNIQUE_KEY() throws InvalidOperationException {
        create( "id int key",
                "c1 int",
                "CONSTRAINT my_uniqueness_constraint UNIQUE KEY my_uniqueness_index (c1)");
        testInserts();
        testIndex("my_uniqueness_index");
    }

    @Test
    public void fullerConstraintUNIQUE() throws InvalidOperationException {
        create( "id int key",
                "c1 int",
                "CONSTRAINT my_uniqueness_constraint UNIQUE my_uniqueness_index (c1)");
        testInserts();
        testIndex("my_uniqueness_index");
    }

    private void create(String... definitions) throws InvalidOperationException {
        tableId = createTable(SCHEMA, TABLE, definitions);
    }

    private void testInserts() throws InvalidOperationException {
        writeRows( createNewRow(tableId, 10, 11) );

        DuplicateKeyException expected = null;
        try {
            writeRows( createNewRow(tableId, 20, 11) );
        } catch (DuplicateKeyException e) {
            expected = e;
        }
        assertNotNull("expected a DuplicateKeyException", expected);

        expectFullRows(tableId, createNewRow(tableId, 10L, 11L));
    }

    private void testIndex(String indexName) {
        expectIndexes(tableId, "PRIMARY", indexName);
        expectIndexColumns(tableId, "PRIMARY", "id");
        expectIndexColumns(tableId, indexName, "c1");
    }
}
