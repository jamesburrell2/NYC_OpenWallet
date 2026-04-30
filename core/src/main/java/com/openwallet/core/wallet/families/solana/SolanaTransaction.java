package com.openwallet.core.wallet.families.solana;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.Value;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.messages.TxMessage;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.AbstractTransaction;
import com.openwallet.core.wallet.AbstractWallet;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.TransactionConfidence;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Solana transaction — wraps a Solana transaction signature, from/to, value, and fees.
 */
public class SolanaTransaction implements AbstractTransaction, Serializable {
    private static final long serialVersionUID = 1L;

    private final CoinType type;
    private final String signature; // base58-encoded transaction signature
    private final String from;
    private final String to;
    private final long lamports; // value in lamports (1 SOL = 1e9 lamports)
    private final long feeLamports;
    private final long timestamp;
    private final int slot;
    private TransactionConfidence.ConfidenceType confidence;

    public SolanaTransaction(CoinType type, String signature, String from, String to,
                             long lamports, long feeLamports, long timestamp, int slot) {
        this.type = type;
        this.signature = signature;
        this.from = from;
        this.to = to;
        this.lamports = lamports;
        this.feeLamports = feeLamports;
        this.timestamp = timestamp;
        this.slot = slot;
        this.confidence = slot > 0 ? TransactionConfidence.ConfidenceType.BUILDING
                : TransactionConfidence.ConfidenceType.PENDING;
    }

    /**
     * Sentinel constructor for delivering a balance update from the server client.
     */
    public SolanaTransaction(CoinType type, long balanceLamports) {
        this.type = type;
        this.signature = null;
        this.from = null;
        this.to = null;
        this.lamports = balanceLamports;
        this.feeLamports = 0;
        this.timestamp = System.currentTimeMillis();
        this.slot = -1;
        this.confidence = TransactionConfidence.ConfidenceType.UNKNOWN;
    }

    /** Returns true if this object is a balance-update sentinel, not a real transaction. */
    public boolean isBalanceSentinel() {
        return signature == null;
    }

    @Override
    public CoinType getType() {
        return type;
    }

    @Override
    public Sha256Hash getHash() {
        if (signature == null) return null;
        // Hash the signature string to get a 32-byte Sha256Hash
        byte[] hashBytes = Sha256Hash.create(signature.getBytes()).getBytes();
        return new Sha256Hash(hashBytes);
    }

    @Override
    public String getHashAsString() {
        return signature;
    }

    @Override
    public byte[] getHashBytes() {
        Sha256Hash hash = getHash();
        return hash != null ? hash.getBytes() : null;
    }

    @Override
    public TransactionConfidence.ConfidenceType getConfidenceType() {
        return confidence;
    }

    @Override
    public void setConfidenceType(TransactionConfidence.ConfidenceType type) {
        this.confidence = type;
    }

    @Override
    public int getAppearedAtChainHeight() {
        return slot;
    }

    @Override
    public void setAppearedAtChainHeight(int height) { }

    @Override
    public TransactionConfidence.Source getSource() {
        return TransactionConfidence.Source.NETWORK;
    }

    @Override
    public void setSource(TransactionConfidence.Source source) { }

    @Override
    public int getDepthInBlocks() {
        return -1;
    }

    @Override
    public void setDepthInBlocks(int depth) { }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public void setTimestamp(long timestamp) { }

    @Override
    public Value getValue(AbstractWallet wallet) {
        try {
            AbstractAddress walletAddr = wallet.getReceiveAddress();
            if (walletAddr != null && walletAddr.toString().equals(from)) {
                return type.value(-lamports);
            }
        } catch (Exception e) {
            // Fall through
        }
        return type.value(lamports);
    }

    @Override
    public Value getFee() {
        return type.value(feeLamports);
    }

    @Nullable
    @Override
    public TxMessage getMessage() {
        return null;
    }

    @Override
    public List<AbstractAddress> getReceivedFrom() {
        if (from == null) return Collections.emptyList();
        try {
            return Collections.singletonList(SolanaAddress.from(type, from));
        } catch (AddressMalformedException e) {
            return Collections.emptyList();
        }
    }

    @Override
    public List<AbstractOutput> getSentTo() {
        if (to == null) return Collections.emptyList();
        try {
            AbstractAddress toAddr = SolanaAddress.from(type, to);
            return Collections.singletonList(new AbstractOutput(toAddr, type.value(lamports)));
        } catch (AddressMalformedException e) {
            return Collections.emptyList();
        }
    }

    @Override
    public boolean isGenerated() {
        return false;
    }

    @Override
    public boolean isTrimmed() {
        return false;
    }
}
