package com.openwallet.core.wallet.families.chia;

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
 * Chia transaction — wraps a Chia spend bundle with ID, from/to puzzle hashes, and fees.
 */
public class ChiaTransaction implements AbstractTransaction, Serializable {
    private static final long serialVersionUID = 1L;

    private final CoinType type;
    private final String spendBundleId;
    private final String from; // puzzle hash or bech32m address
    private final String to;
    private final long mojos; // 1 XCH = 1e12 mojos
    private final long feeMojos;
    private final long timestamp;
    private final int blockHeight;
    private TransactionConfidence.ConfidenceType confidence;

    public ChiaTransaction(CoinType type, String spendBundleId, String from, String to,
                           long mojos, long feeMojos, long timestamp, int blockHeight) {
        this.type = type;
        this.spendBundleId = spendBundleId;
        this.from = from;
        this.to = to;
        this.mojos = mojos;
        this.feeMojos = feeMojos;
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
        if (spendBundleId == null) return null;
        String hex = spendBundleId;
        while (hex.length() < 64) hex = "0" + hex;
        if (hex.length() > 64) hex = hex.substring(0, 64);
        return new Sha256Hash(hex);
    }

    @Override
    public String getHashAsString() {
        return spendBundleId;
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
                return type.value(-mojos);
            }
        } catch (Exception e) {
            // Fall through
        }
        return type.value(mojos);
    }

    @Override
    public Value getFee() {
        return type.value(feeMojos);
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
            return Collections.singletonList(ChiaAddress.from(type, from));
        } catch (AddressMalformedException e) {
            return Collections.emptyList();
        }
    }

    @Override
    public List<AbstractOutput> getSentTo() {
        if (to == null) return Collections.emptyList();
        try {
            AbstractAddress toAddr = ChiaAddress.from(type, to);
            return Collections.singletonList(new AbstractOutput(toAddr, type.value(mojos)));
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
