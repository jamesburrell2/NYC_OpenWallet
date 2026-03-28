package com.openwallet.core.coins;


import com.openwallet.core.coins.families.Families;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.messages.MessageFactory;
import com.openwallet.core.util.MonetaryFormat;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.families.bitcoin.BitAddress;
import com.openwallet.core.wallet.families.bitcoin.SegwitAddress;
import com.openwallet.core.wallet.families.bitcoin.TaprootAddress;
import com.google.common.base.Charsets;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Utils;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.HDUtils;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author John L. Jegutanis
 */
abstract public class CoinType extends NetworkParameters implements ValueType, Serializable {
    private static final long serialVersionUID = 1L;

    private static final String BIP_44_KEY_PATH = "44H/%dH/%dH";

    protected String name;
    protected String symbol;
    protected String uriScheme;
    protected Integer bip44Index;
    protected Integer unitExponent;
    protected String addressPrefix;
    protected Value feeValue;
    protected Value minNonDust;
    protected Value softDustLimit;
    protected SoftDustPolicy softDustPolicy;
    protected FeePolicy feePolicy = FeePolicy.FEE_PER_KB;
    protected byte[] signedMessageHeader;
    protected Set<AddressType> supportedAddressTypes =
            Collections.unmodifiableSet(EnumSet.of(AddressType.LEGACY));
    protected String bech32Hrp = null;

    private transient MonetaryFormat friendlyFormat;
    private transient MonetaryFormat plainFormat;
    private transient Value oneCoin;

    private static FeeProvider feeProvider = null;

    @Override
    public String getName() {
        return checkNotNull(name, "A coin failed to set a name");
    }

    public boolean isTestnet() {
        return id.endsWith("test");
    }

    @Override
    public String getSymbol() {
        return checkNotNull(symbol, "A coin failed to set a symbol");
    }

    public String getUriScheme() {
        return checkNotNull(uriScheme, "A coin failed to set a URI scheme");
    }

    public int getBip44Index() {
        return checkNotNull(bip44Index, "A coin failed to set a BIP 44 index");
    }

    @Override
    public int getUnitExponent() {
        return checkNotNull(unitExponent, "A coin failed to set a unit exponent");
    }

    public Value getFeeValue() {
        if (feeProvider != null) {
            return feeProvider.getFeeValue(this);
        } else {
            return getDefaultFeeValue();
        }
    }

    public Value getDefaultFeeValue() {
        return checkNotNull(feeValue, "A coin failed to set a fee value");
    }

    @Override
    public Value getMinNonDust() {
        return checkNotNull(minNonDust, "A coin failed to set a minimum amount to be considered not dust");
    }

    public Value getSoftDustLimit() {
        return checkNotNull(softDustLimit, "A coin failed to set a soft dust limit");
    }

    public SoftDustPolicy getSoftDustPolicy() {
        return checkNotNull(softDustPolicy, "A coin failed to set a soft dust policy");
    }

    public FeePolicy getFeePolicy() {
        return checkNotNull(feePolicy, "A coin failed to set a fee policy");
    }

    public byte[] getSignedMessageHeader() {
        return checkNotNull(signedMessageHeader, "A coin failed to set signed message header bytes");
    }

    public boolean canSignVerifyMessages() {
        return signedMessageHeader != null;
    }

    public boolean canHandleMessages() {
        return getMessagesFactory() != null;
    }

    public Set<AddressType> getSupportedAddressTypes() { return supportedAddressTypes; }

    public String getBech32Hrp() { return bech32Hrp; }

    @Nullable
    public MessageFactory getMessagesFactory() {
        return null;
    }

    protected static byte[] toBytes(String str) {
        return str.getBytes(Charsets.UTF_8);
    }

    public List<ChildNumber> getBip44Path(int account) {
        String path = String.format(BIP_44_KEY_PATH, bip44Index, account);
        return HDUtils.parsePath(path);
    }

    /**
        Return an address prefix like NXT- or BURST-, otherwise and empty string
     */
    public String getAddressPrefix() {
        return checkNotNull(addressPrefix, "A coin failed to set the address prefix");
    }

    public abstract AbstractAddress newAddress(String addressStr) throws AddressMalformedException;

    /**
     * Returns a 1 coin of this type with the correct amount of units (satoshis)
     * Use {@link com.openwallet.core.coins.CoinType:oneCoin}
     */
    @Deprecated
    public Coin getOneCoin() {
        BigInteger units = BigInteger.TEN.pow(getUnitExponent());
        return Coin.valueOf(units.longValue());
    }

    @Override
    public Value oneCoin() {
        if (oneCoin == null) {
            BigInteger units = BigInteger.TEN.pow(getUnitExponent());
            oneCoin = Value.valueOf(this, units.longValue());
        }
        return oneCoin;
    }

    @Override
    public Value value(String string) {
        return Value.parse(this, string);
    }

    @Override
    public Value value(Coin coin) {
        return Value.valueOf(this, coin);
    }

    @Override
    public Value value(long units) {
        return Value.valueOf(this, units);
    }

    @Override
    public String getPaymentProtocolId() {
        throw new RuntimeException("Method not implemented");
    }

    @Override
    public String toString() {
        return "Coin{" +
                "name='" + name + '\'' +
                ", symbol='" + symbol + '\'' +
                ", bip44Index=" + bip44Index +
                '}';
    }

    @Override
    public MonetaryFormat getMonetaryFormat() {
        if (friendlyFormat == null) {
            friendlyFormat = new MonetaryFormat()
                    .shift(0).minDecimals(2).code(0, symbol).postfixCode();
            switch (unitExponent) {
                case 8:
                    friendlyFormat = friendlyFormat.optionalDecimals(2, 2, 2);
                    break;
                case 6:
                    friendlyFormat = friendlyFormat.optionalDecimals(2, 2);
                    break;
                case 4:
                    friendlyFormat = friendlyFormat.optionalDecimals(2);
                    break;
                default:
                    friendlyFormat = friendlyFormat.minDecimals(unitExponent);
            }
        }
        return friendlyFormat;
    }

    @Override
    public MonetaryFormat getPlainFormat() {
        if (plainFormat == null) {
            plainFormat = new MonetaryFormat().shift(0)
                    .minDecimals(0).repeatOptionalDecimals(1, unitExponent).noCode();
        }
        return plainFormat;
    }

    @Override
    public boolean equals(ValueType obj) {
        return super.equals(obj);
    }

    public static void setFeeProvider(FeeProvider feeProvider) {
        CoinType.feeProvider = feeProvider;
    }

    public interface FeeProvider {
        Value getFeeValue(CoinType type);
    }

    /**
     * Create an address for this coin type from a public key.
     * Default: P2PKH BitAddress. Override in ZcashFamily to return ZcashAddress.
     * Throws RuntimeException (not checked) — WrongNetworkException cannot fire
     * when deriving from a key's own hash160.
     */
    public AbstractAddress addressFromKey(ECKey key) {
        try {
            return BitAddress.from(this, key.getPubKeyHash());
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive address for " + getName(), e);
        }
    }

    public AbstractAddress addressFromKey(ECKey key, AddressType type) {
        switch (type) {
            case LEGACY:
                return addressFromKey(key);
            case COMPATIBLE: {
                // P2SH-P2WPKH: redeemScript = OP_0 <20-byte-key-hash>
                byte[] pubKeyHash = key.getPubKeyHash();
                byte[] redeemScript = new byte[22];
                redeemScript[0] = 0x00; // OP_0
                redeemScript[1] = 0x14; // PUSH 20
                System.arraycopy(pubKeyHash, 0, redeemScript, 2, 20);
                byte[] scriptHash = Utils.sha256hash160(redeemScript);
                try {
                    return BitAddress.from(this, p2shHeader, scriptHash);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to derive P2SH address for " + getName(), e);
                }
            }
            case NATIVE_SEGWIT:
                return SegwitAddress.fromKey(this, key);
            case TAPROOT:
                return TaprootAddress.fromKey(this, key);
            default:
                throw new IllegalArgumentException("Unknown address type: " + type);
        }
    }
}
