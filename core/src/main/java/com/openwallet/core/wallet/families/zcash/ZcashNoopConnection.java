package com.openwallet.core.wallet.families.zcash;

import com.openwallet.core.network.AddressStatus;
import com.openwallet.core.network.interfaces.BlockchainConnection;
import com.openwallet.core.network.interfaces.ConnectionEventListener;
import com.openwallet.core.network.interfaces.TransactionEventListener;
import com.openwallet.core.wallet.AbstractAddress;

import org.bitcoinj.core.Sha256Hash;

import java.util.List;

import javax.annotation.Nullable;

/**
 * No-op BlockchainConnection stub for ZcashSdkFamily coins.
 *
 * The zcash-android-sdk manages its own lightwalletd gRPC connection lifecycle
 * through {@link ZcashBackendDelegate}. ServerClients still needs a
 * BlockchainConnection object to avoid NPEs in the dispatch loop, but all
 * real work is delegated to the SDK backend.
 */
public class ZcashNoopConnection implements BlockchainConnection<ZcashSdkTransaction> {

    @Override public void startAsync() { }
    @Override public void stopAsync() { }
    @Override public void resetConnection() { }
    @Override public boolean isActivelyConnected() { return false; }
    @Override public void ping(@Nullable String versionString) { }
    @Override public void addEventListener(ConnectionEventListener listener) { }

    @Override
    public void getBlock(int height, TransactionEventListener<ZcashSdkTransaction> listener) { }

    @Override
    public void subscribeToBlockchain(TransactionEventListener<ZcashSdkTransaction> listener) { }

    @Override
    public void subscribeToAddresses(List<AbstractAddress> addresses,
            TransactionEventListener<ZcashSdkTransaction> listener) { }

    @Override
    public void getHistoryTx(AddressStatus status,
            TransactionEventListener<ZcashSdkTransaction> listener) { }

    @Override
    public void getTransaction(Sha256Hash txHash,
            TransactionEventListener<ZcashSdkTransaction> listener) { }

    @Override
    public boolean broadcastTxSync(ZcashSdkTransaction tx) {
        return false;
    }

    @Override
    public void broadcastTx(ZcashSdkTransaction tx,
            @Nullable TransactionEventListener<ZcashSdkTransaction> listener) {
        // ZEC send not yet supported — silently no-op
    }
}
