/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;

import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import freenet.crypt.RandomSource;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.node.NodeStarter.TestNodeParameters;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger.LogLevel;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.PooledExecutor;

/**
 * A base class for WoT unit tests.
 * As opposed to regular WoT unit tests based upon {@link AbstractJUnit4BaseTest}, this test runs
 * the unit tests inside one or multiple full Freenet nodes:
 * WoT is loaded as a regular plugin instead of executing the tests directly without Freenet.
 * 
 * This has the advantage of allowing more complex tests:
 * - The {@link PluginRespirator} is available.
 * - FCP can be used.
 * - Real network traffic can happen if more than one node is used.
 *   ATTENTION: This class' {@link #setUpNode()} stops all of WoT's networking threads to ensure
 *   tests don't have to deal with concurrency. To issue network traffic you have to manually call
 *   their functions for uploading/downloading stuff.
 * 
 * The price is that it is much more heavy to initialize and thus has a higher execution time.
 * Thus, please only use it as a base class if what {@link AbstractJUnit4BaseTest} provides is not
 * sufficient.
 * 
 * FIXME: This is at progress of being adapted from previously only being intended to run a single
 * node to supporting multiple nodes. See the Git history. */
@Ignore("Is ignored so it can be abstract. Self-tests are at class AbstractMultiNodeTestSelfTest.")
public abstract class AbstractMultiNodeTest
        extends AbstractJUnit4BaseTest {
    
    /** Needed for calling {@link NodeStarter#globalTestInit(File, boolean, LogLevel, String,
     *  boolean, RandomSource) only once per VM as it requires that. */
    private static boolean sGlobalTestInitDone = false;

    private Node[] mNodes;


    /**
     * Implementing child classes shall make this return the desired amount of nodes which
     * AbstractMultiNodeTest will create at startup and load the WoT plugin into. */
    public abstract int getNodeCount();

    @Before public final void setUpNodes()
            throws NodeInitException, InvalidThresholdException, IOException {
        
        mNodes = new Node[getNodeCount()];
        
        for(int i = 0; i < mNodes.length; ++i)
        	mNodes[i] = setUpNode();
    }

    private Node setUpNode()
            throws NodeInitException, InvalidThresholdException, IOException {
        
        File nodeFolder = mTempFolder.newFolder();

        // TODO: As of 2014-09-30, TestNodeParameters does not provide any defaults, so we have to
        // set all of its values to something reasonable. Please check back whether it supports
        // defaults in the future and use them.
        // The current parameters are basically set to disable anything which can be disabled
        // so the test runs as fast as possible, even if it might break stuff.
        // The exception is FCP since WOT has FCP tests.
        TestNodeParameters params = new TestNodeParameters();
        params.port = mRandom.nextInt((65535 - 1024) + 1) + 1024;
        params.opennetPort = mRandom.nextInt((65535 - 1024) + 1) + 1024;
        params.baseDirectory = nodeFolder;
        params.disableProbabilisticHTLs = true;
        params.maxHTL = 18;
        params.dropProb = 0;
        params.random = mRandom;
        params.executor = new PooledExecutor();
        params.threadLimit = 256;
        params.storeSize = 16 * 1024 * 1024;
        params.ramStore = true;
        params.enableSwapping = false;
        params.enableARKs = false;
        params.enableULPRs = false;
        params.enablePerNodeFailureTables = false;
        params.enableSwapQueueing = false;
        params.enablePacketCoalescing = false;
        params.outputBandwidthLimit = 0;
        params.enableFOAF = false;
        params.connectToSeednodes = false;
        params.longPingTimes = true;
        params.useSlashdotCache = false;
        params.ipAddressOverride = null;
        params.enableFCP = true;
        params.enablePlugins = true;

        if(!sGlobalTestInitDone) {
            // NodeStarter.createTestNode() will throw if we do not do this before
            NodeStarter.globalTestInit(nodeFolder, false, LogLevel.WARNING, "", true, mRandom);
            sGlobalTestInitDone = true;
        }

        Node node = NodeStarter.createTestNode(params);
        node.start(!params.enableSwapping);

        String wotFilename = System.getProperty("WOT_test_jar");
        
        assertNotNull("Please specify the name of the WOT unit test JAR to the JVM via "
            + "'java -DWOT_test_jar=...'",  wotFilename);
        
        PluginInfoWrapper wotWrapper = 
            node.getPluginManager().startPluginFile(wotFilename, false);
        
        WebOfTrust wot = (WebOfTrust) wotWrapper.getPlugin();
        
        // Prevent unit tests from having to do thread synchronization by terminating all WOT
        // subsystems which run their own thread.
        wot.getIntroductionClient().terminate();
        wot.getIntroductionServer().terminate();
        wot.getIdentityInserter().terminate();
        wot.getIdentityFetcher().stop();
        wot.getSubscriptionManager().stop();
        
        return node;
    }

    public Node getNode() {
        if(mNodes.length > 1)
            throw new UnsupportedOperationException("Running more than one Node!");
        
        return mNodes[0];
    }

    /**
     * {@link AbstractJUnit4BaseTest#testDatabaseIntegrityAfterTermination()} is based on this,
     * please apply changes there as well. */
    @After
    @Override
    public final void testDatabaseIntegrityAfterTermination() {
        for(Node node : mNodes) {
        // We cannot use Node.exit() because it would terminate the whole JVM.
        // TODO: Code quality: Once fred supports shutting down a Node without killing the JVM,
        // use that instead of only unloading WoT. https://bugs.freenetproject.org/view.php?id=6683
        /* node.exit("JUnit tearDown()"); */
        
        WebOfTrust wot = getWebOfTrust(node);
        File database = wot.getDatabaseFile();
        node.getPluginManager().killPlugin(wot, Long.MAX_VALUE);
       
        // The following commented-out assert would yield a false failure:
        // - setUpNode() already called terminate() upon various subsystems of WoT.
        // - When killPlugin() calls WebOfTrust.terminate(), that function will try to terminate()
        //   those subsystems again. This will fail because they are terminated already.
        // - WebOfTrust.terminate() will mark termination as failed due to subsystem termination
        //   failure. Thus, isTerminated() will return false.
        // The compensation for having this assert commented out is the function testTerminate() at
        // AbstractMultiNodeTestSelfTest.
        // TODO: Code quality: It would nevertheless be a good idea to find a way to enable this
        // assert since testTerminate() does not cause load upon the subsystems of WoT. This
        // function here however is an @After test, so it will be run after the child test classes'
        // tests, which can cause sophisticated load. An alternate solution would be to find a way
        // to make testTerminate() cause the subsystem threads to all run, in parallel of
        // terminate(). 
        /* assertTrue(wot.isTerminated()); */
        
        wot = null;
        
        WebOfTrust reopened = new WebOfTrust(database.toString());
        assertTrue(reopened.verifyDatabaseIntegrity());
        assertTrue(reopened.verifyAndCorrectStoredScores());
        reopened.terminate();
        assertTrue(reopened.isTerminated());
        }
    }

    @Override
    protected final WebOfTrust getWebOfTrust() {
        if(mNodes.length > 1)
            throw new UnsupportedOperationException("Running more than one WebOfTrust!");
        
        return getWebOfTrust(mNodes[0]);
    }

    protected static final WebOfTrust getWebOfTrust(Node node) {
        WebOfTrust wot = (WebOfTrust) node.getPluginManager()
            .getPluginInfoByClassName(WebOfTrust.class.getName()).getPlugin();
        assertNotNull(wot);
        return wot;
    }

    /**
     * {@link AbstractMultiNodeTest} loads WOT as a real plugin just as if it was running in
     * a regular node. This will cause WOT to create the seed identities.<br>
     * If you need to do a test upon a really empty database, use this function to delete them.
     * 
     * @throws UnknownIdentityException
     *             If the seeds did not exist. This is usually an error, don't catch it, let it hit
     *             JUnit.
     * @throws MalformedURLException
     *             Upon internal failure. Don't catch this, let it hit JUnit.
     */
    protected final void deleteSeedIdentities()
            throws UnknownIdentityException, MalformedURLException {
        WebOfTrust wot = getWebOfTrust();
        
        // Properly ordered combination of locks needed for wot.beginTrustListImport(),
        // wot.deleteWithoutCommit(Identity) and Persistent.checkedCommit().
        // We normally don't synchronize in unit tests but this is a base class for all WOT unit
        // tests so side effects of not locking cannot be known here.
        // Calling this now already so our assert..() are guaranteed to be coherent as well.
        // Also, taking all those locks at once for proper anti-deadlock order.
        synchronized(wot) {
        synchronized(wot.getIntroductionPuzzleStore()) {
        synchronized(wot.getIdentityFetcher()) {
        synchronized(wot.getSubscriptionManager()) {
        synchronized(Persistent.transactionLock(wot.getDatabase()))  {

        assertEquals(WebOfTrust.SEED_IDENTITIES.length, wot.getAllIdentities().size());
        
        // The function for deleting identities deleteWithoutCommit() is mostly a debug function
        // and thus shouldn't be used upon complex databases. See its JavaDoc.
        assertEquals(
              "This function might have side effects upon databases which contain more than"
            + " just the seed identities, so please do not use it upon such databases.",
            0, wot.getAllTrusts().size() + wot.getAllScores().size());
        
        wot.beginTrustListImport();
        for(String seedURI : WebOfTrust.SEED_IDENTITIES) {
            wot.deleteWithoutCommit(wot.getIdentityByURI(new FreenetURI(seedURI)));
        }
        wot.finishTrustListImport();
        Persistent.checkedCommit(wot.getDatabase(), wot);
        
        assertEquals(0, wot.getAllIdentities().size());

        }}}}}
    }
}
