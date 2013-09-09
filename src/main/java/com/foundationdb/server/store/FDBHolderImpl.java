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
package com.foundationdb.server.store;

import com.foundationdb.Transaction;
import com.foundationdb.async.Function;
import com.foundationdb.server.service.Service;
import com.foundationdb.server.service.config.ConfigurationService;
import com.foundationdb.Database;
import com.foundationdb.FDB;
import com.foundationdb.tuple.Tuple;
import com.foundationdb.util.layers.Directory;
import com.foundationdb.util.layers.DirectorySubspace;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FDBHolderImpl implements FDBHolder, Service {
    private static final Logger LOG = LoggerFactory.getLogger(FDBHolderImpl.class.getName());

    private static final String CONFIG_API_VERSION = "fdbsql.fdb.api_version";
    private static final String CONFIG_CLUSTER_FILE = "fdbsql.fdb.cluster_file";
    private static final String CONFIG_TRACE_DIRECTORY = "fdbsql.fdb.trace_directory";
    private static final String CONFIG_ROOT_DIR = "fdbsql.fdb.root_directory";

    private final ConfigurationService configService;

    private FDB fdb;
    private Database db;
    private DirectorySubspace rootDirectory;

    @Inject
    public FDBHolderImpl(ConfigurationService configService) {
        this.configService = configService;
    }


    //
    // Service
    //

    @Override
    public void start() {
        // Just one FDB for whole JVM and its dispose doesn't do anything.
        if(fdb == null) {
            int apiVersion = Integer.parseInt(configService.getProperty(CONFIG_API_VERSION));
            LOG.info("Staring with API Version {}", apiVersion);
            fdb = FDB.selectAPIVersion(apiVersion);
        }
        String traceDirectory = configService.getProperty(CONFIG_TRACE_DIRECTORY);
        if (!traceDirectory.isEmpty()) {
            fdb.options().setTraceEnable(traceDirectory);
        }
        String clusterFile = configService.getProperty(CONFIG_CLUSTER_FILE);
        boolean isDefault = clusterFile.isEmpty();
        LOG.info("Opening cluster file {}", isDefault ? "DEFAULT" : clusterFile);
        db = isDefault ? fdb.open() : fdb.open(clusterFile);
        final String rootDirName = configService.getProperty(CONFIG_ROOT_DIR);
        rootDirectory = db.run(
            new Function<Transaction, DirectorySubspace>()
            {
                @Override
                public DirectorySubspace apply(Transaction tr) {
                    Directory dir = new Directory();
                    return dir.createOrOpen(tr, Tuple.from(rootDirName));
                }
            }
        );
    }

    @Override
    public void stop() {
        if(db != null)
            db.dispose();
        db = null;
    }

    @Override
    public void crash() {
        stop();
    }


    //
    // FDBHolder
    //

    @Override
    public FDB getFDB() {
        return fdb;
    }

    @Override
    public Database getDatabase() {
        return db;
    }

    @Override
    public DirectorySubspace getRootDirectory() {
        return rootDirectory;
    }
}
