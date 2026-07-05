package com.openwallet.core.wallet;

import com.openwallet.core.coins.BitcoinMain;
import com.openwallet.core.coins.CoinType;
import com.openwallet.core.network.AddressStatus;
import com.openwallet.core.network.BlockHeader;
import com.openwallet.core.network.ServerClient.HistoryTx;
import com.openwallet.core.network.ServerClient.UnspentTx;
import com.openwallet.core.network.interfaces.ConnectionEventListener;
import com.openwallet.core.network.interfaces.TransactionEventListener;
import com.openwallet.core.wallet.families.bitcoin.BitBlockchainConnection;
import com.openwallet.core.wallet.families.bitcoin.BitTransaction;
import com.openwallet.core.wallet.families.bitcoin.BitTransactionEventListener;
import com.google.common.collect.ImmutableList;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.wallet.DeterministicSeed;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.openwallet.core.Preconditions.checkNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the reconnect-driven re-sync loop.
 *
 * <p>Before the fix, {@code onConnection()} wiped every committed
 * {@code addressesStatus} entry whenever the in-memory UTXO set was empty
 * (the normal state during the initial sync of a multi-tx account). On a
 * flaky link every reconnect therefore made all addresses appear "changed"
 * and re-fetched their history. The fix keeps committed statuses across
 * reconnects and, when the UTXO set is empty, reconciles UTXOs for the
 * already-committed addresses instead of restarting discovery.
 */
public class ReconnectResyncTest {
    static final CoinType BTC = BitcoinMain.get();
    static final List<String> MNEMONIC = ImmutableList.of("citizen", "fever",
            "scale", "nurse", "brief", "round", "ski", "fiction", "car",
            "fitness", "pluck", "act");

    DeterministicSeed seed = new DeterministicSeed(MNEMONIC, null, "", 0);
    DeterministicKey masterKey =
            HDKeyDerivation.createMasterPrivateKey(checkNotNull(seed.getSeedBytes()));
    DeterministicHierarchy hierarchy = new DeterministicHierarchy(masterKey);

    WalletPocketHD pocket;

    @Before
    public void setup() {
        BriefLogFormatter.init();
        DeterministicKey rootKey = hierarchy.get(BTC.getBip84Path(7), false, true);
        pocket = new WalletPocketHD(rootKey, BTC, null, null);
        pocket.keys.setLookaheadSize(5);
    }

    @Test
    public void reconnectWithUnchangedStatusDoesNotRefetchHistory() {
        RecordingConnection conn = new RecordingConnection();

        // Initial sync: commit a status for the receive address with an
        // *empty* UTXO/history set, so the status commits while the in-memory
        // UTXO set stays empty (the real initial-sync condition that used to
        // trigger the wipe).
        pocket.onConnection(conn);
        AbstractAddress a = pocket.getReceiveAddress();
        pocket.onAddressStatusUpdate(new AddressStatus(a, "status-hash-1"));
        int historyCallsAfterFirstSync = conn.historyCalls;
        assertTrue("status should have committed", pocket.getAddressStatus(a) != null);

        // Reconnect; the server reports the SAME status for the same address.
        pocket.onConnection(conn);
        pocket.onAddressStatusUpdate(new AddressStatus(a, "status-hash-1"));

        // No new history fetch for the unchanged, already-committed address.
        assertEquals(historyCallsAfterFirstSync, conn.historyCalls);
    }

    @Test
    public void reconnectWithEmptyUtxoSetReconcilesUnspent() {
        RecordingConnection conn = new RecordingConnection();

        pocket.onConnection(conn);
        AbstractAddress a = pocket.getReceiveAddress();
        pocket.onAddressStatusUpdate(new AddressStatus(a, "status-hash-1"));

        // Committed status but empty UTXO set: a reconnect must re-request
        // unspent outputs for the committed address so the balance is not
        // stuck at zero (the bug the old status-wipe worked around).
        conn.unspentCalls = 0;
        pocket.onConnection(conn);
        assertTrue("must re-request unspent for committed addresses",
                conn.unspentCalls > 0);
    }

    /**
     * A {@link BitBlockchainConnection} that records the calls we assert on and
     * delivers empty unspent/history replies (so statuses commit with an empty
     * UTXO set). Address subscription is recorded but does NOT auto-deliver
     * statuses, so tests drive {@code onAddressStatusUpdate} explicitly.
     */
    static class RecordingConnection implements BitBlockchainConnection {
        int historyCalls = 0;
        int unspentCalls = 0;
        int subscribeCalls = 0;

        @Override
        public void getUnspentTx(AddressStatus status, BitTransactionEventListener listener) {
            unspentCalls++;
            listener.onUnspentTransactionUpdate(status, new ArrayList<UnspentTx>());
        }

        @Override
        public void getHistoryTx(AddressStatus status, TransactionEventListener<BitTransaction> listener) {
            historyCalls++;
            listener.onTransactionHistory(status, new ArrayList<HistoryTx>());
        }

        @Override
        public void subscribeToAddresses(List<AbstractAddress> addresses,
                                         TransactionEventListener<BitTransaction> listener) {
            subscribeCalls++;
        }

        @Override
        public void subscribeToBlockchain(TransactionEventListener<BitTransaction> listener) { }

        @Override
        public void getBlock(int height, TransactionEventListener<BitTransaction> listener) { }

        @Override
        public void getTransaction(Sha256Hash txHash, TransactionEventListener<BitTransaction> listener) { }

        @Override
        public void broadcastTx(BitTransaction tx, TransactionEventListener<BitTransaction> listener) { }

        @Override
        public boolean broadcastTxSync(BitTransaction tx) { return false; }

        @Override
        public void ping(String versionString) { }

        @Override
        public void addEventListener(ConnectionEventListener listener) { }

        @Override
        public void resetConnection() { }

        @Override
        public void stopAsync() { }

        @Override
        public boolean isActivelyConnected() { return false; }

        @Override
        public void startAsync() { }
    }
}
