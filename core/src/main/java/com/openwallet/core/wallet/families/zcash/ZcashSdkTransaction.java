package com.openwallet.core.wallet.families.zcash;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.Value;
import com.openwallet.core.messages.TxMessage;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.AbstractTransaction;
import com.openwallet.core.wallet.AbstractWallet;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.core.TransactionConfidence.Source;
import org.bitcoinj.core.Utils;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Zcash SDK transaction — minimal stub populated from zcash-android-sdk data.
 */
public class ZcashSdkTransaction implements AbstractTransaction, Serializable {
    private static final long serialVersionUID = 1L;

    private final CoinType type;
    @Nullable private final String txId;
    private final long valueZatoshi;
    private final long feeSatoshis;
    private long timestamp;
    private final int blockHeight;
    private final boolean isIncoming;
    @Nullable private final String toAddress;

    private ConfidenceType confidenceType;
    private Source source = Source.UNKNOWN;
    private int depthInBlocks;

    public ZcashSdkTransaction(CoinType type, String txId, long valueZatoshi,
                                long feeSatoshis, long timestamp, int blockHeight,
                                boolean isIncoming, @Nullable String toAddress) {
        this.type = type;
        this.txId = txId;
        this.valueZatoshi = valueZatoshi;
        this.feeSatoshis = feeSatoshis;
        this.timestamp = timestamp;
        this.blockHeight = blockHeight;
        this.isIncoming = isIncoming;
        this.toAddress = toAddress;
        this.confidenceType = blockHeight > 0 ? ConfidenceType.BUILDING : ConfidenceType.PENDING;
    }

    /** Sentinel used to deliver a balance update (no real txid). */
    public ZcashSdkTransaction(CoinType type, long balanceZatoshi) {
        this.type = type;
        this.txId = null;
        this.valueZatoshi = balanceZatoshi;
        this.feeSatoshis = 0;
        this.timestamp = System.currentTimeMillis() / 1000L; // getTimestamp() convention: epoch SECONDS
        this.blockHeight = -1;
        this.isIncoming = false;
        this.toAddress = null;
        this.confidenceType = ConfidenceType.PENDING;
    }

    public boolean isBalanceSentinel() { return txId == null; }

    // ---- Field accessors (needed by ZcashSdkWallet for send + history) ------

    /** Raw amount in zatoshi (always positive; see {@link #getValue} for sign). */
    public long getValueZatoshi() { return valueZatoshi; }

    /** Destination address for outgoing transactions; null for incoming. */
    @Nullable public String getToAddressStr() { return toAddress; }

    /** True if this is an incoming receive. */
    public boolean isIncoming() { return isIncoming; }

    // ---- AbstractTransaction ------------------------------------------------

    @Override public CoinType getType() { return type; }

    @Override
    public Sha256Hash getHash() {
        if (txId == null) return Sha256Hash.ZERO_HASH;
        try {
            // txId is a 64-char lower-case hex string; SHA256 it to produce a stable Sha256Hash
            return Sha256Hash.create(Utils.HEX.decode(txId));
        } catch (Exception e) {
            return Sha256Hash.ZERO_HASH;
        }
    }

    @Override public String getHashAsString() { return txId != null ? txId : ""; }

    @Override public byte[] getHashBytes() { return getHash().getBytes(); }

    @Override public ConfidenceType getConfidenceType() { return confidenceType; }
    @Override public void setConfidenceType(ConfidenceType type) { this.confidenceType = type; }

    @Override public int getAppearedAtChainHeight() { return blockHeight; }
    @Override public void setAppearedAtChainHeight(int h) { /* immutable */ }

    @Override public Source getSource() { return source; }
    @Override public void setSource(Source source) { this.source = source; }

    @Override public int getDepthInBlocks() { return depthInBlocks; }
    @Override public void setDepthInBlocks(int d) { this.depthInBlocks = d; }

    @Override public long getTimestamp() { return timestamp; }
    @Override public void setTimestamp(long ts) { this.timestamp = ts; }

    @Override
    public Value getValue(AbstractWallet wallet) {
        // Positive = received, negative = sent (mirroring the convention in other wallets)
        return type.value(isIncoming ? valueZatoshi : -valueZatoshi);
    }

    @Override @Nullable public Value getFee() { return feeSatoshis > 0 ? type.value(feeSatoshis) : null; }

    @Override @Nullable public TxMessage getMessage() { return null; }

    @Override
    public List<AbstractAddress> getReceivedFrom() { return Collections.emptyList(); }

    @Override
    public List<AbstractOutput> getSentTo() {
        if (toAddress == null) return Collections.emptyList();
        ZcashSdkAddress addr = new ZcashSdkAddress(type, toAddress);
        AbstractOutput out = new AbstractOutput(addr, type.value(valueZatoshi));
        return Collections.singletonList(out);
    }

    @Override public boolean isGenerated() { return false; }
    @Override public boolean isTrimmed() { return false; }

    @Override public String toString() { return "ZcashSdkTransaction{txId=" + txId + "}"; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ZcashSdkTransaction)) return false;
        ZcashSdkTransaction that = (ZcashSdkTransaction) o;
        return txId != null ? txId.equals(that.txId) : that.txId == null;
    }

    @Override public int hashCode() { return txId != null ? txId.hashCode() : 0; }
}
