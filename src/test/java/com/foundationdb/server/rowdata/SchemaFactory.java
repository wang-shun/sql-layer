/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
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

package com.foundationdb.server.rowdata;

import com.foundationdb.ais.model.AISMerge;
import com.foundationdb.ais.model.AkibanInformationSchema;
import com.foundationdb.ais.model.DefaultNameGenerator;
import com.foundationdb.ais.model.Group;
import com.foundationdb.ais.model.Index;
import com.foundationdb.ais.model.Routine;
import com.foundationdb.ais.model.Sequence;
import com.foundationdb.ais.model.Table;
import com.foundationdb.ais.model.UserTable;
import com.foundationdb.ais.model.View;
import com.foundationdb.server.MemoryOnlyTableStatusCache;
import com.foundationdb.server.api.DDLFunctions;
import com.foundationdb.server.api.ddl.DDLFunctionsMockBase;
import com.foundationdb.server.service.routines.MockRoutineLoader;
import com.foundationdb.server.service.session.Session;
import com.foundationdb.sql.StandardException;
import com.foundationdb.sql.aisddl.IndexDDL;
import com.foundationdb.sql.aisddl.RoutineDDL;
import com.foundationdb.sql.aisddl.SequenceDDL;
import com.foundationdb.sql.aisddl.TableDDL;
import com.foundationdb.sql.aisddl.ViewDDL;
import com.foundationdb.sql.optimizer.AISBinderContext;
import com.foundationdb.sql.parser.CreateAliasNode;
import com.foundationdb.sql.parser.CreateIndexNode;
import com.foundationdb.sql.parser.CreateTableNode;
import com.foundationdb.sql.parser.CreateViewNode;
import com.foundationdb.sql.parser.CreateSequenceNode;
import com.foundationdb.sql.parser.SQLParser;
import com.foundationdb.sql.parser.StatementNode;
import com.foundationdb.util.Strings;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SchemaFactory {
    private final static String DEFAULT_DEFAULT_SCHEMA = "test";
    private final String defaultSchema;

    public SchemaFactory() {
        this(DEFAULT_DEFAULT_SCHEMA);
    }
    
    public SchemaFactory(String defaultSchema) {
        this.defaultSchema = defaultSchema;
    }

    public AkibanInformationSchema aisWithRowDefs(String... ddl) {
        AkibanInformationSchema ais = ais(ddl);
        buildRowDefs(ais);
        return ais;
    }

    public AkibanInformationSchema ais(String... ddl)
    {
        return ais(new AkibanInformationSchema(), ddl);
    }
    
    public static AkibanInformationSchema loadAIS(File fromFile, String defaultSchema) {
        try {
            List<String> ddl = Strings.dumpFile(fromFile);
            SchemaFactory schemaFactory = new SchemaFactory(defaultSchema);
            return schemaFactory.ais(ddl.toArray(new String[ddl.size()]));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    public AkibanInformationSchema ais(AkibanInformationSchema baseAIS, String... ddl) {
        return ais(new CreateOnlyDDLMock(baseAIS), null, ddl);
    }

    public AkibanInformationSchema ais(DDLFunctions ddlFunctions, Session session, String... ddl) {
        StringBuilder buffer = new StringBuilder();
        for (String line : ddl) {
            buffer.append(line);
        }
        String fullDDL = buffer.toString();
        SQLParser parser = new SQLParser();
        List<StatementNode> nodes;
        try {
            nodes = parser.parseStatements(fullDDL);
        } catch(StandardException e) {
            throw new RuntimeException(e);
        }
        for(StatementNode stmt : nodes) {
            if (stmt instanceof CreateTableNode) {
                TableDDL.createTable(ddlFunctions , session , defaultSchema, (CreateTableNode) stmt, null);
            } else if (stmt instanceof CreateIndexNode) {
                IndexDDL.createIndex(ddlFunctions, session, defaultSchema, (CreateIndexNode) stmt);
            } else if (stmt instanceof CreateViewNode) {
                ViewDDL.createView(ddlFunctions, session, defaultSchema, (CreateViewNode) stmt,
                                   new AISBinderContext(ddlFunctions.getAIS(session), defaultSchema), null);
            } else if (stmt instanceof CreateSequenceNode) {
                SequenceDDL.createSequence(ddlFunctions, session, defaultSchema, (CreateSequenceNode)stmt);
            } else if (stmt instanceof CreateAliasNode) {
                RoutineDDL.createRoutine(ddlFunctions, new MockRoutineLoader(), session, defaultSchema, (CreateAliasNode)stmt);
            } else {
                throw new IllegalStateException("Unsupported StatementNode type: " + stmt);
            }
        }
        return ddlFunctions.getAIS(session);
    }

    public void buildRowDefs(AkibanInformationSchema ais) {
        RowDefCache rowDefCache = new FakeRowDefCache();
        // Note: Somewhat fragile. Fake doesn't need a Session for creating RowDefs.
        rowDefCache.setAIS(null, ais);
    }

    private static class FakeRowDefCache extends RowDefCache {
        public FakeRowDefCache() {
            super(new MemoryOnlyTableStatusCache());
        }

        @Override
        protected Map<Table,Integer> createOrdinalMap() {
            Map<Group,List<RowDef>> groupToRowDefs = getRowDefsByGroup();
            Map<Table,Integer> ordinalMap = new HashMap<>();
            for(List<RowDef> allRowDefs  : groupToRowDefs.values()) {
                int userTableOrdinal = 1;
                for(RowDef userRowDef : allRowDefs) {
                    int ordinal = userTableOrdinal++;
                    userRowDef.userTable().setOrdinal(ordinal);
                    ordinalMap.put(userRowDef.table(), ordinal);
                }
            }
            return ordinalMap;
        }
    }

    private static class CreateOnlyDDLMock extends DDLFunctionsMockBase {
        AkibanInformationSchema ais = new AkibanInformationSchema();

        public CreateOnlyDDLMock(AkibanInformationSchema ais) {
            this.ais = ais;
        }

        @Override
        public void createTable(Session session, UserTable newTable) {
            AISMerge merge = AISMerge.newForAddTable(new DefaultNameGenerator(ais), ais, newTable);
            merge.merge();
            ais = merge.getAIS();
        }

        @Override
        public void createView(Session session, View view) {
            ais = AISMerge.mergeView(ais, view);
        }

        @Override
        public AkibanInformationSchema getAIS(Session session) {
            return ais;
        }

        @Override
        public void createIndexes(Session session, Collection<? extends Index> indexesToAdd) {
            AISMerge merge = AISMerge.newForAddIndex(new DefaultNameGenerator(ais), ais);
            for(Index newIndex : indexesToAdd) {
                merge.mergeIndex(newIndex);
            }
            merge.merge();
            ais = merge.getAIS();
        }
        
        @Override
        public void createSequence(Session session, Sequence sequence) {
            AISMerge merge = AISMerge.newForOther(new DefaultNameGenerator(ais), ais);
            ais = merge.mergeSequence(sequence);
        }

        @Override
        public void createRoutine(Session session, Routine routine, boolean replaceExisting) {
            ais = AISMerge.mergeRoutine(ais, routine);
        }
    }
}