/**
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
package org.apache.sentry.hdfs;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStore;
import org.apache.hadoop.hive.metastore.HiveMetaStore.HMSHandler;
import org.apache.hadoop.hive.metastore.IHMSHandler;
import org.apache.hadoop.hive.metastore.MetaStorePreEventListener;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.sentry.hdfs.ServiceConstants.ServerConfig;
import org.apache.sentry.hdfs.service.thrift.TPathChanges;
import org.apache.sentry.provider.db.SentryMetastoreListenerPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

/**
 * Plugin implementation of {@link SentryMetastoreListenerPlugin} that hooks
 * into the sites in the {@link MetaStorePreEventListener} that deal with
 * creation/updation and deletion for paths.
 */
public class MetastorePlugin extends SentryMetastoreListenerPlugin {

  private static final Logger LOGGER = LoggerFactory.getLogger(MetastorePlugin.class);

  private final Configuration conf;
  private SentryHDFSServiceClient sentryClient;
  private UpdateableAuthzPaths authzPaths;
  private Lock notificiationLock;

  //Initialized to some value > 1 so that the first update notification
 // will trigger a full Image fetch
  private final AtomicLong seqNum = new AtomicLong(5);
  private volatile long lastSentSeqNum = -1;
  private final ExecutorService threadPool;

  static class ProxyHMSHandler extends HMSHandler {
	public ProxyHMSHandler(String name, HiveConf conf) throws MetaException {
		super(name, conf);
	}
	@Override
	public String startFunction(String function, String extraLogInfo) {
		return function;
	}
  }

  public MetastorePlugin(Configuration conf) {
    this.notificiationLock = new ReentrantLock();
    this.conf = new HiveConf((HiveConf)conf);
    this.conf.unset(HiveConf.ConfVars.METASTORE_PRE_EVENT_LISTENERS.varname);
    this.conf.unset(HiveConf.ConfVars.METASTORE_EVENT_LISTENERS.varname);
    this.conf.unset(HiveConf.ConfVars.METASTORE_END_FUNCTION_LISTENERS.varname);
    this.conf.unset(HiveConf.ConfVars.METASTOREURIS.varname);
    try {
      this.authzPaths = createInitialUpdate(new ProxyHMSHandler("sentry.hdfs", (HiveConf)this.conf));
    } catch (Exception e1) {
      LOGGER.error("Could not create Initial AuthzPaths or HMSHandler !!", e1);
      throw new RuntimeException(e1);
    }
    try {
      sentryClient = new SentryHDFSServiceClient(conf);
    } catch (Exception e) {
      sentryClient = null;
      LOGGER.error("Could not connect to Sentry HDFS Service !!", e);
    }
    ScheduledExecutorService threadPool = Executors.newScheduledThreadPool(1);
    threadPool.scheduleWithFixedDelay(new Runnable() {
      @Override
      public void run() {
        notificiationLock.lock();
        try {
          long lastSeenHMSPathSeqNum =
              MetastorePlugin.this.getClient().getLastSeenHMSPathSeqNum();
          if (lastSeenHMSPathSeqNum != lastSentSeqNum) {
            LOGGER.warn("Sentry not in sync with HMS [" + lastSeenHMSPathSeqNum + ", " + lastSentSeqNum + "]");
            PathsUpdate fullImageUpdate =
                MetastorePlugin.this.authzPaths.createFullImageUpdate(
                    lastSentSeqNum);
            LOGGER.warn("Sentry not in sync with HMS !!");
            notifySentryNoLock(fullImageUpdate, false);
          }
        } catch (Exception e) {
          sentryClient = null;
          LOGGER.error("Error talking to Sentry HDFS Service !!", e);
        } finally {
          notificiationLock.unlock();
        }
      }
    }, this.conf.getLong(ServerConfig.SENTRY_HDFS_INIT_UPDATE_RETRY_DELAY_MS,
        ServerConfig.SENTRY_HDFS_INIT_UPDATE_RETRY_DELAY_DEFAULT), 1000,
        TimeUnit.MILLISECONDS);
    this.threadPool = threadPool;
  }

  private UpdateableAuthzPaths createInitialUpdate(IHMSHandler hmsHandler) throws Exception {
    UpdateableAuthzPaths authzPaths = new UpdateableAuthzPaths(new String[] {"/"});
    PathsUpdate tempUpdate = new PathsUpdate(-1, false);
    List<String> allDbStr = hmsHandler.get_all_databases();
    for (String dbName : allDbStr) {
      Database db = hmsHandler.get_database(dbName);
      tempUpdate.newPathChange(db.getName()).addToAddPaths(
          PathsUpdate.cleanPath(db.getLocationUri()));
      List<String> allTblStr = hmsHandler.get_all_tables(db.getName());
      for (String tblName : allTblStr) {
        Table tbl = hmsHandler.get_table(db.getName(), tblName);
        TPathChanges tblPathChange = tempUpdate.newPathChange(tbl
            .getDbName() + "." + tbl.getTableName());
        List<Partition> tblParts =
            hmsHandler.get_partitions(db.getName(), tbl.getTableName(), (short) -1);
        tblPathChange.addToAddPaths(PathsUpdate.cleanPath(tbl.getSd()
            .getLocation() == null ? db.getLocationUri() : tbl
            .getSd().getLocation()));
        for (Partition part : tblParts) {
          tblPathChange.addToAddPaths(PathsUpdate.cleanPath(part.getSd()
              .getLocation()));
        }
      }
    }
    authzPaths.updatePartial(Lists.newArrayList(tempUpdate),
        new ReentrantReadWriteLock());
    return authzPaths;
  }

  @Override
  public void addPath(String authzObj, String path) {
    LOGGER.debug("#### HMS Path Update ["
        + "OP : addPath, "
        + "authzObj : " + authzObj + ", "
        + "path : " + path + "]");
    PathsUpdate update = createHMSUpdate();
    update.newPathChange(authzObj).addToAddPaths(PathsUpdate.cleanPath(path));
    notifySentry(update, true);
  }

  @Override
  public void removeAllPaths(String authzObj) {
    LOGGER.debug("#### HMS Path Update ["
        + "OP : removeAllPaths, "
        + "authzObj : " + authzObj + "]");
    PathsUpdate update = createHMSUpdate();
    update.newPathChange(authzObj).addToDelPaths(Lists.newArrayList(PathsUpdate.ALL_PATHS));
    notifySentry(update, true);
  }

  @Override
  public void removePath(String authzObj, String path) {
    if ("*".equals(path)) {
      removeAllPaths(authzObj);
    } else {
      LOGGER.debug("#### HMS Path Update ["
          + "OP : removePath, "
          + "authzObj : " + authzObj + ", "
          + "path : " + path + "]");
      PathsUpdate update = createHMSUpdate();
      update.newPathChange(authzObj).addToDelPaths(PathsUpdate.cleanPath(path));
      notifySentry(update, true);
    }
  }

  @Override
  public void renameAuthzObject(String oldName, String oldPath, String newName,
      String newPath) {
    PathsUpdate update = createHMSUpdate();
    LOGGER.debug("#### HMS Path Update ["
        + "OP : renameAuthzObject, "
        + "oldName : " + oldName + ","
        + "newPath : " + oldPath + ","
        + "newName : " + newName + ","
        + "newPath : " + newPath + "]");
    update.newPathChange(newName).addToAddPaths(PathsUpdate.cleanPath(newPath));
    update.newPathChange(oldName).addToDelPaths(PathsUpdate.cleanPath(oldPath));
    notifySentry(update, true);
  }

  private SentryHDFSServiceClient getClient() {
    if (sentryClient == null) {
      try {
        sentryClient = new SentryHDFSServiceClient(conf);
      } catch (IOException e) {
        sentryClient = null;
        LOGGER.error("Could not connect to Sentry HDFS Service !!", e);
      }
    }
    return sentryClient;
  }

  private PathsUpdate createHMSUpdate() {
    PathsUpdate update = new PathsUpdate(seqNum.incrementAndGet(), false);
    LOGGER.debug("#### HMS Path Update SeqNum : [" + seqNum.get() + "]");
    return update;
  }

  private void notifySentryNoLock(PathsUpdate update, boolean applyLocal) {
    if (applyLocal) {
      authzPaths.updatePartial(Lists.newArrayList(update), new ReentrantReadWriteLock());
    }
    try {
      getClient().notifyHMSUpdate(update);
    } catch (Exception e) {
      LOGGER.error("Could not send update to Sentry HDFS Service !!", e);
    } finally {
      lastSentSeqNum = update.getSeqNum();
      LOGGER.debug("#### HMS Path Last update sent : [" + lastSentSeqNum + "]");
    }
  }

  private void notifySentry(PathsUpdate update, boolean applyLocal) {
    notificiationLock.lock();
    try {
      notifySentryNoLock(update, applyLocal);
    } finally {
      notificiationLock.unlock();
    }
  }

}
