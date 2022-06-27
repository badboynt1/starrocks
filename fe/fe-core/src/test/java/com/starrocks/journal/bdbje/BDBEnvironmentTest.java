// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

package com.starrocks.journal.bdbje;

import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.LockMode;
import com.starrocks.common.util.NetUtils;
import com.starrocks.journal.JournalException;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BDBEnvironmentTest {
    private static final Logger LOG = LogManager.getLogger(BDBEnvironmentTest.class);
    private List<File> tmpDirs = new ArrayList<>();

    public File createTmpDir() throws Exception{
        File f = Files.createTempDirectory(Paths.get("."), "BDBEnvironmentTest").toFile();
        tmpDirs.add(f);
        return f;
    }

    @Before
    public void setup() throws Exception {
        BDBEnvironment.RETRY_TIME = 3;
        BDBEnvironment.SLEEP_INTERVAL_SEC = 0;
    }

    @After
    public void cleanup() throws Exception {
        for (File tmpDir: tmpDirs) {
            FileUtils.deleteDirectory(tmpDir);
        }
    }

    private String findUnbindHostPort() throws Exception {
        // try to find a port that is not bind
        String selfNodeHostPort = null;
        int seed = new Random().nextInt() % 1000;
        for (int port = 9000 + seed; port != 120000; port ++) {
            if(! NetUtils.isPortUsing("127.0.0.1", port)) {
                selfNodeHostPort = "127.0.0.1:" + String.valueOf(port);
                break;
            }
        }
        Assert.assertNotNull(selfNodeHostPort);
        return selfNodeHostPort;
    }

    private DatabaseEntry randomEntry() {
        byte[] array = new byte[16];
        new Random().nextBytes(array);
        return new DatabaseEntry(array);
    }


    @Test
    public void testSetupStandalone() throws Exception {
        String selfNodeHostPort = findUnbindHostPort();
        BDBEnvironment environment = new BDBEnvironment(
                createTmpDir(),
                "standalone",
                selfNodeHostPort,
                selfNodeHostPort,
                true);
        environment.setup();

        CloseSafeDatabase db = environment.openDatabase("testdb");
        DatabaseEntry key = randomEntry();
        DatabaseEntry value = randomEntry();
        db.put(null, key, value);

        DatabaseEntry newvalue = new DatabaseEntry();
        db.get(null, key, newvalue, LockMode.READ_COMMITTED);
        Assert.assertEquals(new String(value.getData()), new String(newvalue.getData()));
        environment.close();
    }

    // address already in use
    @Test(expected = JournalException.class)
    public void testSetupStandaloneMultitimes() throws Exception {
        String selfNodeHostPort = findUnbindHostPort();
        for (int i = 0; i < 2; i ++) {
            BDBEnvironment environment = new BDBEnvironment(
                    createTmpDir(),
                    "standalone",
                    selfNodeHostPort,
                    selfNodeHostPort,
                    true);
            environment.setup();
        }
        Assert.fail();
    }

    /**
     * used for cluster test from here, 1 master + 2 follower + 1 observer
     */
    private String masterNodeHostPort = null;
    private File masterPath = null;
    private BDBEnvironment masterEnvironment = null;
    private BDBEnvironment[] followerEnvironments = new BDBEnvironment[2];
    private String[] followerNodeHostPorts = new String[2];
    private File[] followerPaths = new File[2];

    private void initClusterMasterFollower() throws Exception {
        // setup master
        masterNodeHostPort = findUnbindHostPort();
        masterPath = createTmpDir();
        masterEnvironment = new BDBEnvironment(
                masterPath,
                "master",
                masterNodeHostPort,
                masterNodeHostPort,
                true);
        masterEnvironment.setup();
        Assert.assertEquals(0, masterEnvironment.getDatabaseNames().size());

        // set up 2 followers
        for (int i = 0; i < 2; i++) {
            followerNodeHostPorts[i] = findUnbindHostPort();
            followerPaths[i] = createTmpDir();
            BDBEnvironment followerEnvironment = new BDBEnvironment(
                    followerPaths[i],
                    String.format("follower%d", i),
                    followerNodeHostPorts[i],
                    masterNodeHostPort,
                    true);
            followerEnvironments[i] = followerEnvironment;
            followerEnvironment.setup();
            Assert.assertEquals(0, followerEnvironment.getDatabaseNames().size());
        }
    }

    @Test
    public void testNormalCluster() throws Exception {
        initClusterMasterFollower();

        // master write
        Long DB_INDEX_1 = 0L;
        String DB_NAME_1 = String.valueOf(DB_INDEX_1);
        CloseSafeDatabase masterDb = masterEnvironment.openDatabase(DB_NAME_1);
        Assert.assertEquals(1, masterEnvironment.getDatabaseNames().size());
        Assert.assertEquals(DB_INDEX_1, masterEnvironment.getDatabaseNames().get(0));
        DatabaseEntry key = randomEntry();
        DatabaseEntry value = randomEntry();
        masterDb.put(null, key, value);

        Thread.sleep(1000);

        // follower read
        for (BDBEnvironment followerEnvironment: followerEnvironments) {
            Assert.assertEquals(1, followerEnvironment.getDatabaseNames().size());
            Assert.assertEquals(DB_INDEX_1, followerEnvironment.getDatabaseNames().get(0));

            CloseSafeDatabase followerDb = followerEnvironment.openDatabase(DB_NAME_1);
            DatabaseEntry newvalue = new DatabaseEntry();
            followerDb.get(null, key, newvalue, LockMode.READ_COMMITTED);
            Assert.assertEquals(new String(value.getData()), new String(newvalue.getData()));
        }

        // add observer
        BDBEnvironment observerEnvironment = new BDBEnvironment(
                createTmpDir(),
                "observer",
                findUnbindHostPort(),
                masterNodeHostPort,
                false);
        observerEnvironment.setup();

        // observer read
        Assert.assertEquals(1, observerEnvironment.getDatabaseNames().size());
        Assert.assertEquals(DB_INDEX_1, observerEnvironment.getDatabaseNames().get(0));

        CloseSafeDatabase observerDb = observerEnvironment.openDatabase(DB_NAME_1);
        DatabaseEntry newvalue = new DatabaseEntry();
        observerDb.get(null, key, newvalue, LockMode.READ_COMMITTED);
        Assert.assertEquals(new String(value.getData()), new String(newvalue.getData()));

        // close
        masterEnvironment.close();
        for (BDBEnvironment followerEnvironment: followerEnvironments) {
            followerEnvironment.close();
        }
        observerEnvironment.close();
    }

    @Test
    public void testDeleteDb() throws Exception {
        initClusterMasterFollower();

        // open n dbs and each write 1 kv
        DatabaseEntry key = randomEntry();
        DatabaseEntry value = randomEntry();
        Long [] DB_INDEX_ARR = {0L, 1L, 2L, 9L, 10L};
        String [] DB_NAME_ARR = new String[DB_INDEX_ARR.length];
        for (int i = 0; i < DB_NAME_ARR.length; ++ i) {
            DB_NAME_ARR[i] = String.valueOf(DB_INDEX_ARR[i]);

            // master write
            CloseSafeDatabase masterDb = masterEnvironment.openDatabase(DB_NAME_ARR[i]);
            Assert.assertEquals(i + 1, masterEnvironment.getDatabaseNames().size());
            Assert.assertEquals(DB_INDEX_ARR[i], masterEnvironment.getDatabaseNames().get(i));
            masterDb.put(null, key, value);

            Thread.sleep(1000);

            // follower read
            for (BDBEnvironment followerEnvironment: followerEnvironments) {
                Assert.assertEquals(i + 1, followerEnvironment.getDatabaseNames().size());
                Assert.assertEquals(DB_INDEX_ARR[i], followerEnvironment.getDatabaseNames().get(i));

                CloseSafeDatabase followerDb = followerEnvironment.openDatabase(DB_NAME_ARR[i]);
                DatabaseEntry newvalue = new DatabaseEntry();
                followerDb.get(null, key, newvalue, LockMode.READ_COMMITTED);
                Assert.assertEquals(new String(value.getData()), new String(newvalue.getData()));
            }
        }

        // drop first 2 dbs
        masterEnvironment.removeDatabase(DB_NAME_ARR[0]);
        masterEnvironment.removeDatabase(DB_NAME_ARR[1]);

        // check dbnames
        List<Long> expectDbNames = new ArrayList<>();
        for (int i = 2;  i != DB_NAME_ARR.length; ++ i) {
            expectDbNames.add(DB_INDEX_ARR[i]);
        }
        Assert.assertEquals(expectDbNames, masterEnvironment.getDatabaseNames());
        for (BDBEnvironment followerEnvironment: followerEnvironments) {
            Assert.assertEquals(expectDbNames, followerEnvironment.getDatabaseNames());
        }

        // close
        masterEnvironment.close();
        for (BDBEnvironment followerEnvironment: followerEnvironments) {
            followerEnvironment.close();
        }
    }

    /**
     * see https://github.com/StarRocks/starrocks/issues/4977
     */
    @Test(expected = JournalException.class)
    public void testRollbackExceptionOnSetupCluster() throws Exception {
        initClusterMasterFollower();

        // master write db 0
        Long DB_INDEX_OLD = 0L;
        String DB_NAME_OLD = String.valueOf(DB_INDEX_OLD);
        CloseSafeDatabase masterDb = masterEnvironment.openDatabase(DB_NAME_OLD);
        DatabaseEntry key = randomEntry();
        DatabaseEntry value = randomEntry();
        masterDb.put(null, key, value);
        Assert.assertEquals(1, masterEnvironment.getDatabaseNames().size());
        Assert.assertEquals(DB_INDEX_OLD, masterEnvironment.getDatabaseNames().get(0));

        Thread.sleep(1000);

        // follower read db 0
        for (BDBEnvironment followerEnvironment: followerEnvironments) {
            CloseSafeDatabase followerDb = followerEnvironment.openDatabase(DB_NAME_OLD);
            DatabaseEntry newvalue = new DatabaseEntry();
            followerDb.get(null, key, newvalue, LockMode.READ_COMMITTED);
            Assert.assertEquals(new String(value.getData()), new String(newvalue.getData()));
            Assert.assertEquals(1, followerEnvironment.getDatabaseNames().size());
            Assert.assertEquals(DB_INDEX_OLD, followerEnvironment.getDatabaseNames().get(0));
        }

        // manually backup follower's meta dir
        for (File followerPath : followerPaths) {
            File dst = new File(followerPath.getAbsolutePath() + "_bk");
            LOG.info("backup {} to {}", followerPath, dst);
            FileUtils.copyDirectory(followerPath, dst);
        }

        // master write 100 lines in new db and quit
        Long DB_INDEX_NEW = 1L;
        String DB_NAME_NEW = String.valueOf(DB_INDEX_NEW);
        masterDb = masterEnvironment.openDatabase(DB_NAME_NEW);
        for (int i = 0; i < 100; i++) {
            masterDb.put(null, randomEntry(), randomEntry());
        }
        Assert.assertEquals(2, masterEnvironment.getDatabaseNames().size());
        Assert.assertEquals(DB_INDEX_OLD, masterEnvironment.getDatabaseNames().get(0));
        Assert.assertEquals(DB_INDEX_NEW, masterEnvironment.getDatabaseNames().get(1));

        // close all environment
        masterEnvironment.close();
        for (BDBEnvironment followerEnvironment: followerEnvironments) {
            followerEnvironment.close();
        }

        // restore follower's path
        for (File followerPath : followerPaths) {
            LOG.info("delete {} ", followerPath);
            FileUtils.deleteDirectory(followerPath);
            File src = new File(followerPath.getAbsolutePath() + "_bk");
            LOG.info("mv {} to {}", src, followerPath);
            FileUtils.moveDirectory(src, followerPath);
        }

        Thread.sleep(1000);

        // start follower
        for (int i = 0; i < 2; ++ i) {
            BDBEnvironment followerEnvironment = new BDBEnvironment(
                    followerPaths[i],
                    String.format("follower%d", i),
                    followerNodeHostPorts[i],
                    followerNodeHostPorts[0],
                    true);
            followerEnvironment.setup();
            Assert.assertEquals(1, followerEnvironment.getDatabaseNames().size());
            Assert.assertEquals(DB_INDEX_OLD, followerEnvironment.getDatabaseNames().get(0));
        }

        // start master will get rollback exception
        BDBEnvironment maserEnvironment = new BDBEnvironment(
                masterPath,
                "master",
                masterNodeHostPort,
                followerNodeHostPorts[0],
                true);
        Assert.assertTrue(true);
        maserEnvironment.setup();
        Assert.fail();
    }
}