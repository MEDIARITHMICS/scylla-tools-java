/*
 * Copyright 2016 Cloudius Systems
 *
 * Modified by Cloudius Systems
 */

/*
 * This file is part of Scylla.
 *
 * Scylla is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Scylla is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Scylla.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.scylladb.tools;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.cassandra.db.ClusteringBound.inclusiveEndOf;
import static org.apache.cassandra.db.ClusteringBound.inclusiveStartOf;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.db.ClusteringBound;
import org.apache.cassandra.db.ClusteringPrefix;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.DeletionTime;
import org.apache.cassandra.db.LivenessInfo;
import org.apache.cassandra.db.context.CounterContext;
import org.apache.cassandra.db.marshal.AbstractCompositeType;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.CollectionType;
import org.apache.cassandra.db.marshal.Int32Type;
import org.apache.cassandra.db.marshal.LongType;
import org.apache.cassandra.db.marshal.TupleType;
import org.apache.cassandra.db.rows.Cell;
import org.apache.cassandra.db.rows.ColumnData;
import org.apache.cassandra.db.rows.ComplexColumnData;
import org.apache.cassandra.db.rows.RangeTombstoneBoundMarker;
import org.apache.cassandra.db.rows.RangeTombstoneBoundaryMarker;
import org.apache.cassandra.db.rows.RangeTombstoneMarker;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.Row.Deletion;
import org.apache.cassandra.db.rows.Unfiltered;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.io.sstable.ISSTableScanner;
import org.apache.cassandra.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;

/**
 * Basic sstable -> CQL statements translator.
 * 
 * Goes through a table, in token range order if possible, and tries to generate
 * CQL delete/update statements to match the perceived sstable atom cells read.
 * 
 * This works fairly ok for most normal types, as well as frozen collections.
 * However, it breaks down completely for non-frozen lists.
 * 
 * In cassandra, a list is stored as actually a "map" of time UUID -> value.
 * Since maps in turn are just prefixed thrift column names (the key is part of
 * column name), UUID sorting makes sure the list remains in order. However,
 * actually determining what is index 0, 1, 2 etc cannot be done until the whole
 * list is materialized (reading backwards etc yadayada). Since we a.) Read
 * forwards b.) Might not be reading all relevant sstables we have no idea what
 * is actually in the CQL list. Thus we cannot generate any of the valid
 * expressions to manipulate the list in question.
 * 
 * As a "workaround", the code will instead generate map expressions, using the
 * actual time UUID keys for all list ops. This is of course bogus, and indeed
 * will result in wild errors from for example Origin getting any such
 * statements.
 * 
 * Compact storage column families are not handled yet.
 * 
 */
public class SSTableToCQL {
    public static final String TIMESTAMP_VAR_NAME = "timestamp";
    public static final String TTL_VAR_NAME = "ttl";

    public static String cqlEscape(String id) {
        return "\"" + id + "\"";
    }

    public static class Options {
        public boolean setAllColumns;
        public ColumnNamesMapping columnNamesMapping = new ColumnNamesMapping(emptyMap());
        public boolean ignoreDroppedCounterData;
    }

    public static class Statistics {
        public long partitionsProcessed;
        public long rowsProcessed;
        public long rowsDeleted;
        public long partitionsDeletes;
        public long statementsGenerated;
        public long localCountersSkipped;
        public long remoteCountersSkipped;

        public void add(Statistics s) {
            partitionsProcessed += s.partitionsProcessed;
            rowsProcessed += s.rowsProcessed;
            rowsDeleted += s.rowsDeleted;
            partitionsDeletes += s.partitionsDeletes;
            statementsGenerated += s.statementsGenerated;
            localCountersSkipped += s.localCountersSkipped;
            remoteCountersSkipped += s.remoteCountersSkipped;
        }
    }
    
    /**
     * SSTable row worker.
     *
     * @author calle
     *
     */
    private static class RowBuilder {
        /** Interface for partial generating CQL statements */
        private static interface ColumnOp {
            boolean canDoInsert();

            String apply(ColumnDefinition c, Map<String, Object> params);
        }

        private class DeleteSetEntry implements ColumnOp {
            private final Object key;

            public DeleteSetEntry(Object key) {
                this.key = key;
            }

            @Override
            public String apply(ColumnDefinition c, Map<String, Object> params) {
                String name = columnName(c);
                String varName = varName(c);
                params.put(name, Collections.singleton(key));
                return " = " + cqlEscape(name) + " - :" + varName;
            }

            @Override
            public boolean canDoInsert() {
                return false;
            }
        }

        // CQL operations
        private static enum Op {
            NONE, UPDATE, DELETE, INSERT
        }

        private class SetColumn implements ColumnOp {
            private final Object value;

            public SetColumn(Object value) {
                this.value = value;
            }

            @Override
            public String apply(ColumnDefinition c, Map<String, Object> params) {
                String name = varName(c);
                params.put(name, value);
                return " = :" + name;
            }

            @Override
            public boolean canDoInsert() {
                return true;
            }
        }

        private final SetColumn SET_NULL = new SetColumn(null) {
            @Override
            public boolean canDoInsert() {
                return false;
            }
        };

        private class SetMapEntry implements ColumnOp {
            private final Object key;

            private final Object value;

            public SetMapEntry(Object key, Object value) {
                this.key = key;
                this.value = value;
            }

            @Override
            public String apply(ColumnDefinition c, Map<String, Object> params) {
                String name = varName(c);
                String keyName = name + "_k";
                String varName = name + "_v";
                params.put(keyName, key);
                params.put(varName, value);
                return "[:" + keyName + "] = :" + varName;
            }

            @Override
            public boolean canDoInsert() {
                return false;
            }
        }

        private class SetListEntry implements ColumnOp {
            private final Object key;
            private final Object value;

            public SetListEntry(Object key, Object value) {
                this.key = key;
                this.value = value;
            }

            @Override
            public String apply(ColumnDefinition c, Map<String, Object> params) {
                String name = varName(c);
                String keyName = name + "_k";
                String varName = name + "_v";
                params.put(keyName, key);
                params.put(varName, value);
                return "[SCYLLA_TIMEUUID_LIST_INDEX(:" + keyName + ")] = :" + varName;
            }

            @Override
            public boolean canDoInsert() {
                return false;
            }
        }

        private class SetSetEntry implements ColumnOp {
            private final Object key;

            public SetSetEntry(Object key) {
                this.key = key;
            }

            @Override
            public String apply(ColumnDefinition c, Map<String, Object> params) {
                String name = columnName(c);
                String varName = varName(c);
                params.put(varName, Collections.singleton(key));
                return " = " + cqlEscape(name) + " + :" + varName;
            }

            @Override
            public boolean canDoInsert() {
                return false;
            }
        }

        private class SetCounterEntry implements ColumnOp {
            @SuppressWarnings("unused")
            private final AbstractType<?> type;
            private final ByteBuffer value;

            SetCounterEntry(AbstractType<?> type, ByteBuffer value) {
                this.type = type;
                this.value = value;
            }

            @Override
            public boolean canDoInsert() {
                return false;
            }

            @Override
            public String apply(ColumnDefinition c, Map<String, Object> params) {
                CounterContext.ContextState state = CounterContext.ContextState.wrap(value);
                String varName = varName(c);
                List<ByteBuffer> list = new ArrayList<>();
                while (state.hasRemaining()) {
                    if (state.isGlobal()) {
                        int type = 'g';
                        list.add(TupleType.buildValue(new ByteBuffer[] { Int32Type.instance.getSerializer().serialize(type),
                                state.getCounterId().bytes(),                            
                                LongType.instance.getSerializer().serialize(state.getClock()),
                                LongType.instance.getSerializer().serialize(state.getCount())
    
                        }));
                    } else if (!options.ignoreDroppedCounterData) {
                        throw new RuntimeException(
                                (state.isLocal() ? "Local" : "Remote") + " counter shard found. Data loss may occur");
                    } else if (state.isLocal()) {
                        ++stats.localCountersSkipped;
                    } else if (state.isRemote()) {
                        ++stats.remoteCountersSkipped;
                    }

                    state.moveToNext();
                }
                
                params.put(varName, list);
                return " = SCYLLA_COUNTER_SHARD_LIST(:" + varName + ")";
            }
        }

        private static final long invalidTimestamp = LivenessInfo.NO_TIMESTAMP;
        private static final int invalidTTL = LivenessInfo.NO_TTL;

        private final Client client;
        private final Options options;
        private final Statistics stats = new Statistics();
        
        Op op;
        CFMetaData cfMetaData;
        DecoratedKey key;
        Row row;
        boolean rowDelete;
        boolean setAllColumns;
        boolean allowTTL = true;
        long timestamp;
        int ttl;
        Multimap<ColumnDefinition, ColumnOp> values = MultimapBuilder.treeKeys().arrayListValues(1).build();

        ClusteringWhere where;

        enum Comp {
            Equal("="),
            GreaterEqual(">="),
            Greater(">"),
            LessEqual("<="),
            Less("<"),        
            ;
            
            private String s;
            private Comp(String s) {
                this.s = s;
            }
            public String toString() {
                return s;
            }            
        }
        // sorted atoms?

        public RowBuilder(Client client, Options options) {
            this.client = client;
            this.options = options;
        }


        private String columnName(ColumnDefinition c) {
            return options.columnNamesMapping.getName(c);
        }

        public Statistics getStatistics() {
            return stats;
        }
        
        private static class ClusteringWhere {
            private final List<ColumnDefinition> columnDefs;
            private final List<Object> lowBounds;
            private final List<Object> highBounds;
            private Comp low, hi;

            public ClusteringWhere(List<ColumnDefinition> clusteringColumns, ClusteringBound start, ClusteringBound end) {
                this.columnDefs = clusteringColumns;
                this.lowBounds = new ArrayList<>(columnDefs.size());
                this.highBounds = new ArrayList<>(columnDefs.size());

                ClusteringPrefix spfx = start.clustering();
                ClusteringPrefix epfx = end.clustering();

                for (int i = 0; i < columnDefs.size(); i++) {
                    ColumnDefinition column = columnDefs.get(i);
                    if (i < spfx.size()) {
                        lowBounds.add(column.cellValueType().compose(spfx.get(i)));
                    }
                    if (i < epfx.size()) {
                        highBounds.add(column.cellValueType().compose(epfx.get(i)));
                    }
                }
                low = start.isInclusive() ? Comp.GreaterEqual : Comp.Greater;
                hi = end.isInclusive() ? Comp.LessEqual : Comp.Less;

                if (start.isInclusive() && end.isInclusive() && lowBounds.equals(highBounds)) {
                    low = hi = Comp.Equal;
                }
            }

            public Comp getLow() {
                return low;
            }

            public Comp getHi() {
                return hi;
            }

            public List<ColumnDefinition> getLowColumnDefs() {
                return columnDefs.subList(0, lowBounds.size());
            }

            public List<ColumnDefinition> getHighColumnDefs() {
                return columnDefs.subList(0, highBounds.size());
            }

            public List<Object> getHighBounds() {
                return highBounds;
            }

            public List<Object> getLowBounds() {
                return lowBounds;
            }

            public boolean isEmpty() {
                return lowBounds.isEmpty() && highBounds.isEmpty();
            }
        }

        /**
         * Figure out the "WHERE" clauses (except for PK) for a column name
         *
         * @param composite
         *            Thrift/cassandra name composite
         * @param timestamp
         * @param ttl
         */
        private void setWhere(ClusteringBound start, ClusteringBound end) {
            assert where == null;
            where = new ClusteringWhere(cfMetaData.clusteringColumns(), start, end);
        }

        // Begin a new partition (cassandra "Row")
        private void begin(DecoratedKey key, CFMetaData cfMetaData) {
            this.key = key;
            this.cfMetaData = cfMetaData;
            clear();
            ++stats.partitionsProcessed;
        }

        private void beginRow(Row row) {
            where = null;
            this.row = row;
            ++stats.rowsProcessed;
        }
        private void endRow() {
            this.row = null;
            this.rowDelete = false;            
        }
        
        private void clear() {
            op = Op.NONE;
            values.clear();
            where = null;
            timestamp = invalidTimestamp;
            ttl = invalidTTL;
        }

        // Delete the whole cql row
        void deleteCqlRow(ClusteringBound start, ClusteringBound end, long timestamp) {
            if (!values.isEmpty()) {
                finish();
            }            
            setOp(Op.DELETE, timestamp, invalidTTL);                        
            setWhere(start, end);
            finish();
            ++stats.rowsDeleted;
        }

        // Delete the whole partition
        private void deletePartition(DecoratedKey key, DeletionTime topLevelDeletion) {
            setOp(Op.DELETE, topLevelDeletion.markedForDeleteAt(), invalidTTL);
            finish();
            ++stats.partitionsDeletes;
        };

        // Genenerate the CQL query for this CQL row
        private void finish() {
            // Nothing?
            if (op == Op.NONE) {
                clear();
                return;
            }


            checkRowClustering();

            Map<String, Object> params = new HashMap<>();
            StringBuilder buf = new StringBuilder();

            buf.append(op.toString());
            
            if (op == Op.UPDATE) {
                writeColumnFamily(buf);
                // Timestamps can be sent using statement options.
                // TTL cannot. But just to be extra funny, at least
                // origin does not seem to respect the timestamp
                // in statement, so we'll add them to the CQL string as well.
                writeUsingTimestamp(buf, params);
                writeUsingTTL(buf, params);
                buf.append(" SET ");
            }

            if (op == Op.INSERT) {
                buf.append(" INTO");
                writeColumnFamily(buf);
            }

            int i = 0;
            for (Map.Entry<ColumnDefinition, ColumnOp> e : values.entries()) {
                ColumnDefinition c = e.getKey();
                ColumnOp o = e.getValue();
                String s = o != null ? o.apply(c, params) : null;

                if (op != Op.INSERT) {
                    if (i++ > 0) {
                        buf.append(", ");
                    }
                    ensureWhitespace(buf);
                    writeColumnName(buf, c);
                    if (s != null) {
                        buf.append(s);
                    }
                }
            }

            if (op == Op.DELETE) {
                buf.append(" FROM");
                writeColumnFamily(buf);
                writeUsingTimestamp(buf, params);
            }

            List<ColumnDefinition> pk = cfMetaData.partitionKeyColumns();
            AbstractType<?> type = cfMetaData.getKeyValidator();
            ByteBuffer bufs[];
            if (type instanceof AbstractCompositeType) {
                bufs = ((AbstractCompositeType) type).split(key.getKey());
            } else {
                bufs = new ByteBuffer[] { key.getKey() };
            }

            int k = 0;
            for (ColumnDefinition c : pk) {
                params.put(varName(c), c.type.compose(bufs[k++]));
            }

            if (op == Op.INSERT) {
                assert where == null || where.getLow() == Comp.Equal;

                if (where != null) {
                    Iterator<Object> li = where.getLowBounds().iterator();
                    for (ColumnDefinition c : where.getLowColumnDefs()) {
                        params.put(varName(c), li.next());
                    }
                }

                if (setAllColumns) {
                    appendColumns(buf, cfMetaData.allColumns());
                } else {
                    appendColumns(buf, values.keySet(), pk, where != null ? where.getLowColumnDefs() : emptyList());
                }
                writeUsingTimestamp(buf, params);
                writeUsingTTL(buf, params);
            } else {
                buf.append(" WHERE ");

                i = 0;

                for (ColumnDefinition c : pk) {
                    if (i++ > 0) {
                        buf.append(" AND ");
                    }
                    String var = varName(c);
                    writeColumnName(buf, c);
                    buf.append(' ');
                    buf.append(Comp.Equal.toString());
                    buf.append(" :");
                    buf.append(var);
                }

                if (where != null && !where.isEmpty()) {
                    int instanceNumber = 0;
                    for (Pair<Pair<List<ColumnDefinition>, List<Object>>, Comp> p : Arrays.asList(
                            Pair.create(Pair.create(where.getLowColumnDefs(), where.getLowBounds()), where.getLow()),
                            Pair.create(Pair.create(where.getHighColumnDefs(), where.getHighBounds()),
                                    where.getHi()))) {
                        if (p.left.left.isEmpty()) {
                            continue;
                        }

                        buf.append(" AND (");

                        i = 0;
                        for (ColumnDefinition c : p.left.left) {
                            if (i > 0) {
                                buf.append(',');
                            }
                            writeColumnName(buf, c);
                            ++i;
                        }

                        buf.append(") ").append(p.right.toString()).append(' ');
                        if (p.right != Comp.Equal) {
                            buf.append("SCYLLA_CLUSTERING_BOUND ");
                        }
                        buf.append('(');

                        i = 0;
                        for (ColumnDefinition c : p.left.left) {
                            if (i > 0) {
                                buf.append(',');
                            }
                            String var = varName(c, instanceNumber);
                            params.put(var, p.left.right.get(i));
                            buf.append(':').append(var);
                            ++i;
                        }

                        buf.append(")");

                        ++instanceNumber;
                        if (p.right == Comp.Equal) {
                            break;
                        }
                    }
                }
            }
            buf.append(';');

            try {
                makeStatement(key, timestamp, buf.toString(), params);
            } finally {
                clear();
            }
        }

        @SafeVarargs
        private final void appendColumns(StringBuilder buf, Collection<ColumnDefinition> ... columns) {
            int i = 0;
            buf.append('(');
            for (Collection<ColumnDefinition> cc : columns) {
                for (ColumnDefinition c : cc) {
                    if (i++ > 0) {
                        buf.append(',');
                    }
                    writeColumnName(buf, c);
                }
            }
            buf.append(") values (");
            i = 0;
            for (Collection<ColumnDefinition> cc : columns) {
                for (ColumnDefinition c : cc) {
                    if (i++ > 0) {
                        buf.append(',');
                    }
                    buf.append(':');
                    buf.append(varName(c));
                }
            }
            buf.append(')');
        }

        private final Map<Pair<ColumnDefinition, Integer>, String> variableNames = new HashMap<>();
        
        private String varName(ColumnDefinition c, int instanceNumber) {
            Pair<ColumnDefinition, Integer> entryPair = Pair.create(c, instanceNumber);
            String name = variableNames.get(entryPair);
            if (name == null) {
                name = "v" + variableNames.size();
                variableNames.put(entryPair, name);
            }
            return name;
        }

        private String varName(ColumnDefinition c) {
            return varName(c, 0);
        }

        private void writeUsingTTL(StringBuilder buf, Map<String, Object> params) {
            if (ttl != invalidTTL || (setAllColumns && allowTTL)) {
                ensureWhitespace(buf);

                int adjustedTTL = ttl;

                // if the row is in fact not actually live, i.e. already explicitly 
                // marked dead by ttl == expired, set it to a minimal value 
                // so we can hope it expires again as soon as possible. 
                if (adjustedTTL == LivenessInfo.EXPIRED_LIVENESS_TTL) {
                    adjustedTTL = 1;
                }
                
                if (timestamp == invalidTimestamp) {
                    buf.append("USING ");
                } else {
                    buf.append("AND ");
                    
                    long exp = SECONDS.convert(timestamp, MICROSECONDS) + ttl; 
                    long now = SECONDS.convert(System.currentTimeMillis(), MILLISECONDS); 

                    if (exp < now) {
                        adjustedTTL = 1; // 0 -> no ttl. 1 should disappear fast enoug
                    } else {
                        adjustedTTL = (int)Math.min(adjustedTTL, exp - now);
                    }                    
                }
                buf.append(" TTL :" + TTL_VAR_NAME);

                if (ttl != invalidTTL) {
                    params.put(TTL_VAR_NAME, adjustedTTL);
                }
            }
        }

        private void ensureWhitespace(StringBuilder buf) {
            if (buf.length() > 0 && !Character.isWhitespace(buf.charAt(buf.length() - 1))) {
                buf.append(' ');
            }
        }

        private void writeUsingTimestamp(StringBuilder buf, Map<String, Object> params) {
            if (cfMetaData.isCounter()) {
                return;
            }
            if (timestamp != invalidTimestamp || setAllColumns) {
                ensureWhitespace(buf);
                buf.append("USING TIMESTAMP :" + TIMESTAMP_VAR_NAME);
            } 
            if (timestamp != invalidTimestamp) {
                params.put(TIMESTAMP_VAR_NAME, timestamp);
            }
        }

        // Dispatch the CQL
        private void makeStatement(DecoratedKey key, long timestamp, String what, Map<String, Object> objects) {
            client.processStatment(key, timestamp, what, objects, cfMetaData.isCounter());
            ++stats.statementsGenerated;
        }

        private void process(Row row) {
            beginRow(row);

            try {
                LivenessInfo liveInfo = row.primaryKeyLivenessInfo();
                Deletion d = row.deletion();

                updateTimestamp(liveInfo.timestamp());
                updateTTL(liveInfo.ttl());

                for (ColumnData cd : row) {
                    if (cd.column().isSimple()) {
                        process((Cell) cd, liveInfo, null);
                    } else {
                        ComplexColumnData complexData = (ComplexColumnData) cd;

                        for (Cell cell : complexData) {
                            process(cell, liveInfo, null);
                        }
                    }
                }

                if (!d.isLive() && d.deletes(liveInfo)) {
                    rowDelete = true;
                }

                if (rowDelete) {
                    setOp(Op.DELETE, d.time().markedForDeleteAt(), invalidTTL);
                }
                if (row.size() == 0 && !rowDelete && !row.isStatic()) {
                    op = Op.INSERT;
                }

                finish();
            } finally {
                endRow();
            }
        }
        
        private void checkRowClustering() {
            if (row == null) {
                return;
            }
            if (!row.isStatic()) {
                ClusteringBound b = inclusiveStartOf(row.clustering().clustering());
                ClusteringBound e = inclusiveEndOf(row.clustering().clustering());
                setWhere(b, e);

                if (rowDelete && !tombstoneMarkers.isEmpty()) {
                    RangeTombstoneMarker last = tombstoneMarkers.getLast();
                    ClusteringBound start = last.openBound(false);
                    // If we're doing a cql row delete while processing a ranged
                    // tombstone
                    // chain, we're probably dealing with (old, horrble)
                    // sstables with
                    // overlapping tombstones. Since then this row delete was
                    // also a link
                    // in that tombstone chain, add a marker to the current list
                    // being processed.
                    if (start != null && this.cfMetaData.comparator.compare(start, b) < 0) {
                        tombstoneMarkers.add(new RangeTombstoneBoundMarker(e, row.deletion().time()));
                    }
                }

            }            
        }
        // process an actual cell (data or tombstone)
        private void process(Cell cell, LivenessInfo liveInfo, DeletionTime d) {
            ColumnDefinition c = cell.column();

            this.allowTTL = true;
            
            if (logger.isTraceEnabled()) {
                logger.trace("Processing {}", c.name);
            }
            
            AbstractType<?> type = c.type;
            ColumnOp cop = null;

            boolean live = !cell.isTombstone() && (d == null || d.isLive());
            
            try {
                if (cell.path() != null && cell.path().size() > 0) {
                    CollectionType<?> ctype = (CollectionType<?>) type;
                    
                    Object key = ctype.nameComparator().compose(cell.path().get(0));
                    Object val = live ? cell.column().cellValueType().compose(cell.value()) : null;

                    finish();
                    
                    switch (ctype.kind) {
                    case MAP:
                        cop = new SetMapEntry(key, val);
                        break;
                    case LIST:
                        cop = new SetListEntry(key, val);
                        break;
                    case SET:
                        cop = cell.isTombstone() ?  new DeleteSetEntry(key) : new SetSetEntry(key);
                        break;
                    }
                } else if (live && type.isCounter()) {
                    finish();
                    cop = new SetCounterEntry(type, cell.value());
                    this.allowTTL = false;                   
                } else if (live) {
                    cop = new SetColumn(type.compose(cell.value()));
                } else {
                    cop = SET_NULL;
                }

            } catch (Exception e) {
                logger.error("Could not compose value for " + c.name, e);
                throw e;
            }

            updateColumn(c, cop, cell.timestamp(), cell.ttl());
        }
        
        // Process an SSTable row (partial partition)
        private void process(UnfilteredRowIterator rows) {
            CFMetaData cfMetaData = rows.metadata();
            DeletionTime deletionTime = rows.partitionLevelDeletion();
            DecoratedKey key = rows.partitionKey();

            begin(key, cfMetaData);

            if (!deletionTime.isLive()) {
                deletePartition(key, deletionTime);
                return;
            }

            Row sr = rows.staticRow();
            if (sr != null) {
                process(sr);
            }
            
            while (rows.hasNext()) {
                Unfiltered f = rows.next();
                switch (f.kind()) {
                case RANGE_TOMBSTONE_MARKER:
                    if (f instanceof RangeTombstoneBoundMarker) {
                        process((RangeTombstoneBoundMarker) f);
                    } else if (f instanceof RangeTombstoneBoundaryMarker) {
                        process((RangeTombstoneBoundaryMarker) f);
                    } else {
                        throw new UnsupportedOperationException("Encountered unsupported range tombstone marker: " + f);
                    }
                    break;
                case ROW:
                    process((Row)f);
                    break;
                default:
                    break;                
                }
            }
        }

        private Deque<RangeTombstoneMarker> tombstoneMarkers = new ArrayDeque<>();

        private void process(RangeTombstoneBoundMarker tombstone) {
            ClusteringBound start = tombstone.openBound(false);
            ClusteringBound end = tombstone.closeBound(false);
            if (end != null) {
                processRangeTombstoneEndBound(end, tombstone);
            } else if (start != null) {
                processRangeTombstoneStartBound(start, tombstone);
            }
        }

        private void process(RangeTombstoneBoundaryMarker tombstone) {
            ClusteringBound start = tombstone.openBound(false);
            ClusteringBound end = tombstone.closeBound(false);

            assert start != null && end != null;
            processRangeTombstoneEndBound(end, tombstone);
            processRangeTombstoneStartBound(start, tombstone);
        }

        private void processRangeTombstoneStartBound(ClusteringBound start, RangeTombstoneMarker tombstone) {
            if (!tombstoneMarkers.isEmpty()) {
                RangeTombstoneMarker last = tombstoneMarkers.getLast();
                ClusteringBound stop = last.closeBound(false);

                if (stop == null) {
                    throw new IllegalStateException("Unexpected tombstone: " + tombstone);
                }
                if (this.cfMetaData.comparator.compare(start, stop) != 0) {
                    deleteCqlRow(tombstoneMarkers.getFirst().openBound(false), stop,
                            tombstoneMarkers.getFirst().openDeletionTime(false).markedForDeleteAt());
                    tombstoneMarkers.clear();
                }
            }
            tombstoneMarkers.add(tombstone);
        }

        private void processRangeTombstoneEndBound(ClusteringBound end, RangeTombstoneMarker tombstone) {
            if (tombstoneMarkers.isEmpty()) {
                throw new IllegalStateException("Unexpected tombstone: " + tombstone);
            }

            ClusteringBound last = tombstoneMarkers.getLast().closeBound(false);

            // This can happen if we're adding a tombstone marker but had a
            // row delete in between. In that case (overlapping tombstones),
            // we should (I hope) assume that he was really the high
            // watermark for the chain, and should also be followed by a new
            // tombstone range.
            if (last != null && this.cfMetaData.comparator.compare(end, last) < 0) {
                return;
            }

            ClusteringBound start = tombstoneMarkers.getFirst().openBound(false);
            assert start != null;
            deleteCqlRow(start, end, tombstoneMarkers.getFirst().openDeletionTime(false).markedForDeleteAt());
            tombstoneMarkers.clear();
        }

        // update the CQL operation. If we change, we need
        // to send the old query.
        private void setOp(Op op, long timestamp, int ttl) {
            if (this.op != op) {
                finish();
                assert this.op == Op.NONE;
            }
            updateTimestamp(timestamp);
            updateTTL(ttl);
            this.op = op;
        }

        private boolean canDoInsert() {
            if (this.op == Op.UPDATE) {
                return false;
            }
            if (this.row != null && this.row.primaryKeyLivenessInfo().timestamp() != timestamp) {
                return false;
            }
            return true;
        }

        // add a column value to update. If we already have one for this column,
        // flush. (Should never happen though, as long as CQL row detection is
        // valid)
        private void updateColumn(ColumnDefinition c, ColumnOp object, long timestamp, int ttl) {
            if (object != null && object.canDoInsert() && canDoInsert()) {
                setOp(Op.INSERT, timestamp, ttl);
            } else {
                setOp(Op.UPDATE, timestamp, ttl);
            }
            values.put(c, object);
        }

        // Since each CQL query can only have a single
        // timestamp, we must send old query once we
        // set a new timestamp
        private void updateTimestamp(long timestamp) {
            if (this.timestamp != invalidTimestamp && this.timestamp != timestamp) {
                finish();
            }
            this.timestamp = timestamp;
        }

        private void updateTTL(int ttl) {
            if (this.ttl != invalidTTL && this.ttl != ttl) {
                finish();
            }
            this.ttl = ttl;
        }

        private void writeColumnName(StringBuilder buf, ColumnDefinition c) {
            buf.append(columnName(c)); // already escaped!
        }
        private void writeColumnFamily(StringBuilder buf) {
            buf.append(" ");
            buf.append(cqlEscape(cfMetaData.ksName));
            buf.append(".");
            buf.append(cqlEscape(cfMetaData.cfName));
            buf.append(" ");
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(SSTableToCQL.class);

    private final SStableScannerSource source;
    
    public SSTableToCQL(SStableScannerSource source) {
        this.source = source;
    }

    /** 
     * Performs the transformation of the SSTable to CQL statements. 
     * This can be called exactly once. 
     * @param client
     */
    public Statistics run(Client client, Options options) {
        ISSTableScanner scanner = source.scanner();
        try {
            RowBuilder builder = new RowBuilder(client, options);
            logger.info("Processing {}", scanner.getBackingFiles());
            while (scanner.hasNext()) {
                UnfilteredRowIterator ri = scanner.next();
                builder.process(ri);
            }
            return builder.getStatistics();
        } finally {
            scanner.close();
        }
    }
}
