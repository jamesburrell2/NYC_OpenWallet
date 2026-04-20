package com.openwallet.core.wallet.families.evm;

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
 * EVM transaction — wraps an Ethereum-style transaction with hash, value, gas fees,
 * from/to addresses, and confirmation tracking.
 */
public class EvmTransaction implements AbstractTransaction, Serializable {
    private static final long serialVersionUID = 1L;

    private final CoinType type;
    private final String txHash;
    private final String from;
    private final String to;
    private final long valueSatoshis; // value in smallest unit (wei for ETH)
    private final long feeSatoshis;
    private final long timestamp;
    private final int blockHeight;
    private TransactionConfidence.ConfidenceType confidence;

    public EvmTransaction(CoinType type, String txHash, String from, String to,
                          long valueSatoshis, long feeSatoshis, long timestamp,
                          int blockHeight) {
        this.type = type;
        this.txHash = txHash;
        this.from = from;
        this.to = to;
        this.valueSatoshis = valueSatoshis;
        this.feeSatoshis = feeSatoshis;
        this.timestamp = timestamp;
        this.blockHeight = blockHeight;
        this.confidence = blockHeight > 0 ? TransactionConfidence.ConfidenceType.BUILDING
                : TransactionConfidence.ConfidenceType.PENDING;
    }

    @Override
    public CoinType getType() {
        return type;
    }

    @Override
    public Sha256Hash getHash() {
        if (txHash == null) return null;
        String hex = txHash.startsWith("0x") ? txHash.substring(2) : txHash;
        while (hex.length() < 64) hex = "0" + hex;
        if (hex.length() > 64) hex = hex.substring(0, 64);
        return new Sha256Hash(hex);
    }

    @Override
    public String getHashAsString() {
        return txHash;
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
        return blockHeight;
    }

    @Override
    public void setAppearedAtChainHeight(int height) {
        // Immutable in this implementation
    }

    @Override
    public TransactionConfidence.Source getSource() {
        return TransactionConfidence.Source.NETWORK;
    }

    @Override
    public void setSource(TransactionConfidence.Source source) { }

    @Override
    public int getDepthInBlocks() {
        return -1; // Must be set from outside with chain height
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
        // Determine if the wallet sent or received this transaction
        try {
            AbstractAddress walletAddr = wallet.getReceiveAddress();
            if (walletAddr != null && walletAddr.toString().equalsIgnoreCase(from)) {
                return type.value(-valueSatoshis);
            }
        } catch (Exception e) {
            // Fall through
        }
        return type.value(valueSatoshis);
    }

    @Override
    public Value getFee() {
        return type.value(feeSatoshis);
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
            return Collections.singletonList(EvmAddress.from(type, from));
        } catch (AddressMalformedException e) {
            return Collections.emptyList();
        }
    }

    @Override
    public List<AbstractOutput> getSentTo() {
        if (to == null) return Collections.emptyList();
        try {
            AbstractAddress toAddr = EvmAddress.from(type, to);
            return Collections.singletonList(new AbstractOutput(toAddr, type.value(valueSatoshis)));
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
