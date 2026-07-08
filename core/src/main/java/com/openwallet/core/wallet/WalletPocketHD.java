/**
 * Copyright 2013 Google Inc.
 * Copyright 2014 Andreas Schildbach
 * Copyright 2014 John L. Jegutanis
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.openwallet.core.wallet;

import com.openwallet.core.coins.AddressType;
import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.Value;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.exceptions.Bip44KeyLookAheadExceededException;
import com.openwallet.core.protos.Protos;
import com.openwallet.core.util.KeyUtils;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.families.bitcoin.BitAddress;
import com.openwallet.core.wallet.families.bitcoin.BitSendRequest;
import com.openwallet.core.wallet.families.bitcoin.SegwitAddress;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.KeyCrypter;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.RedeemData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import static com.openwallet.core.Preconditions.checkArgument;
import static com.openwallet.core.Preconditions.checkNotNull;
import static com.openwallet.core.Preconditions.checkState;
import static com.openwallet.core.util.BitAddressUtils.getHash160;
import static com.openwallet.core.util.BitAddressUtils.isP2SHAddress;
import static org.bitcoinj.wallet.KeyChain.KeyPurpose.CHANGE;
import static org.bitcoinj.wallet.KeyChain.KeyPurpose.RECEIVE_FUNDS;
import static org.bitcoinj.wallet.KeyChain.KeyPurpose.REFUND;

/**
 * @author John L. Jegutanis
 *
 *
 */
public class WalletPocketHD extends BitWalletBase {
    private static final Logger log = LoggerFactory.getLogger(WalletPocketHD.class);

    // The default receive/change keychain. For a single-path pocket this is the only
    // keychain; for a bundled pocket it is the native-segwit (84') branch. Kept as a
    // field so the many existing single-path call sites remain valid.
    @VisibleForTesting
    protected SimpleHDKeyChain keys;

    // Ordered, purpose-tagged keychains. A normal pocket holds exactly one (== keys) and
    // behaves identically to before. A "bundled" pocket holds several — 44'->LEGACY,
    // 49'->COMPATIBLE, 84'->NATIVE_SEGWIT — under one account index, matching Coinomi's
    // per-account multi-branch layout so a single account shows the complete history.
    protected final List<SimpleHDKeyChain> keychains;
    protected final List<AddressType> purposes;
    // True when this pocket bundles more than one purpose keychain. Single-keychain pockets
    // keep their historical "emit every supported script type from the one key" behaviour,
    // so an already-saved single-path pocket deserializes and behaves identically.
    protected final boolean bundled;
    // Index into keychains of the default receive branch (native-segwit if present).
    private final int receiveKeychainIndex;
    // Index into keychains used for change. Change must be a legacy/P2SH BitAddress so it
    // flows through TransactionCreator's change-output path (which expects an
    // org.bitcoinj.core.Address); the legacy (44') branch is watched, spendable and castable.
    private final int changeKeychainIndex;

    public WalletPocketHD(DeterministicKey rootKey, CoinType coinType,
                          @Nullable KeyCrypter keyCrypter, @Nullable KeyParameter key) {
        this(new SimpleHDKeyChain(rootKey, keyCrypter, key), coinType);
    }

    WalletPocketHD(SimpleHDKeyChain keys, CoinType coinType) {
        this(KeyUtils.getPublicKeyId(coinType, keys.getRootKey().getPubKey()), keys, coinType);
    }

    WalletPocketHD(String id, SimpleHDKeyChain keys, CoinType coinType) {
        super(checkNotNull(coinType), id);
        this.keys = checkNotNull(keys);
        this.keychains = new ArrayList<>();
        this.keychains.add(keys);
        // Purpose is not consulted on the single-keychain (emit-all) path; LEGACY is a
        // harmless placeholder that matches the default receive-address encoding.
        this.purposes = ImmutableList.of(AddressType.LEGACY);
        this.bundled = false;
        this.receiveKeychainIndex = 0;
        this.changeKeychainIndex = 0;
    }

    /**
     * Build a bundled pocket that tracks several purpose keychains under one account index.
     * The purposes list is parallel to keychains (44'->LEGACY, 49'->COMPATIBLE,
     * 84'->NATIVE_SEGWIT). The native-segwit branch, if present, is the default receive
     * branch; otherwise the first keychain is used.
     */
    WalletPocketHD(List<SimpleHDKeyChain> keychains, List<AddressType> purposes,
                   CoinType coinType, @Nullable KeyCrypter keyCrypter, @Nullable KeyParameter key) {
        this(bundledId(coinType, keychains, purposes), keychains, purposes, coinType);
        // Encrypt in-place to mirror the single-path constructor, which encrypts its
        // keychain up front. Callers that create then separately encrypt() are unaffected
        // because the second encrypt is skipped once isEncrypted() is true.
        if (keyCrypter != null) {
            lock.lock();
            try {
                for (int i = 0; i < this.keychains.size(); i++) {
                    SimpleHDKeyChain kc = this.keychains.get(i);
                    if (!kc.isEncrypted()) {
                        this.keychains.set(i, kc.toEncrypted(keyCrypter, checkNotNull(key)));
                    }
                }
                this.keys = this.keychains.get(receiveKeychainIndex);
            } finally {
                lock.unlock();
            }
        }
    }

    WalletPocketHD(String id, List<SimpleHDKeyChain> keychains,
                   List<AddressType> purposes, CoinType coinType) {
        super(checkNotNull(coinType), id != null ? id : bundledId(coinType, keychains, purposes));
        checkArgument(!keychains.isEmpty(), "A pocket needs at least one keychain");
        checkArgument(keychains.size() == purposes.size(),
                "keychains and purposes must be parallel");
        this.keychains = new ArrayList<>(keychains);
        this.purposes = ImmutableList.copyOf(purposes);
        this.bundled = keychains.size() > 1;
        this.receiveKeychainIndex = bundled ? defaultReceiveIndex(purposes) : 0;
        this.changeKeychainIndex = bundled ? defaultChangeIndex(purposes, receiveKeychainIndex) : 0;
        this.keys = this.keychains.get(receiveKeychainIndex);
    }

    /** Prefer the native-segwit branch for receiving; fall back to the first keychain. */
    private static int defaultReceiveIndex(List<AddressType> purposes) {
        int idx = purposes.indexOf(AddressType.NATIVE_SEGWIT);
        return idx >= 0 ? idx : 0;
    }

    /** Prefer the legacy branch for change (a plain BitAddress); fall back to receive. */
    private static int defaultChangeIndex(List<AddressType> purposes, int receiveIndex) {
        int idx = purposes.indexOf(AddressType.LEGACY);
        return idx >= 0 ? idx : receiveIndex;
    }

    /** Keychain index to hand out addresses from for the given purpose. */
    private int keychainIndexForPurpose(SimpleHDKeyChain.KeyPurpose purpose) {
        if (!bundled) return 0;
        return purpose == CHANGE ? changeKeychainIndex : receiveKeychainIndex;
    }

    /** Deterministic id for a bundled pocket, derived from the default receive branch. */
    private static String bundledId(CoinType coinType, List<SimpleHDKeyChain> keychains,
                                    List<AddressType> purposes) {
        int idx = keychains.size() > 1 ? defaultReceiveIndex(purposes) : 0;
        return KeyUtils.getPublicKeyId(coinType, keychains.get(idx).getRootKey().getPubKey());
    }

    /** Ordered list of this pocket's purpose keychains (one for a single-path pocket). */
    public List<SimpleHDKeyChain> getKeychains() {
        lock.lock();
        try {
            return ImmutableList.copyOf(keychains);
        } finally {
            lock.unlock();
        }
    }

    /** Purpose (script type) of the keychain at the given index. */
    public AddressType getPurpose(int keychainIndex) {
        return purposes.get(keychainIndex);
    }

    public boolean isBundled() {
        return bundled;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Region vending transactions and other internal state
    //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Get the BIP44 index of this account
     */
    public int getAccountIndex() {
        lock.lock();
        try {
            return keys.getAccountIndex();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns this account's BIP32 derivation path formatted for display,
     * e.g. {@code m/84'/0'/7'}. Hardened levels are shown with a trailing
     * apostrophe.
     */
    public String getDerivationPath() {
        lock.lock();
        try {
            List<ChildNumber> path = keys.getRootKey().getPath();
            if (bundled && !path.isEmpty()) {
                // Show the shared account path with the set of bundled purposes, e.g.
                // m/{44',49',84'}/0'/7'.
                StringBuilder purposeSet = new StringBuilder("{");
                for (int i = 0; i < keychains.size(); i++) {
                    if (i > 0) purposeSet.append(',');
                    List<ChildNumber> p = keychains.get(i).getRootKey().getPath();
                    ChildNumber purpose = p.get(0);
                    purposeSet.append(purpose.num());
                    if (purpose.isHardened()) purposeSet.append('\'');
                }
                purposeSet.append('}');
                StringBuilder sb = new StringBuilder("m/").append(purposeSet);
                for (int i = 1; i < path.size(); i++) {
                    ChildNumber cn = path.get(i);
                    sb.append('/').append(cn.num());
                    if (cn.isHardened()) sb.append('\'');
                }
                return sb.toString();
            }
            StringBuilder sb = new StringBuilder("m");
            for (ChildNumber cn : path) {
                sb.append('/').append(cn.num());
                if (cn.isHardened()) sb.append('\'');
            }
            return sb.toString();
        } finally {
            lock.unlock();
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Serialization support
    //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    List<Protos.Key> serializeKeychainToProtobuf() {
        lock.lock();
        try {
            // Concatenate each keychain's key tree in order. Each keychain begins with its
            // own account-level root key (a distinct 44'/49'/84' path prefix), which lets
            // the loader split them back apart. For a single-path pocket this is exactly the
            // old single-keychain output, so the wallet-file format is unchanged.
            if (keychains.size() == 1) {
                return keys.toProtobuf();
            }
            ArrayList<Protos.Key> all = new ArrayList<>();
            for (SimpleHDKeyChain kc : keychains) {
                all.addAll(kc.toProtobuf());
            }
            return all;
        } finally {
            lock.unlock();
        }
    }

    @VisibleForTesting Protos.WalletPocket toProtobuf() {
        lock.lock();
        try {
            return WalletPocketProtobufSerializer.toProtobuf(this);
        } finally {
            lock.unlock();
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Encryption support
    //
    ////////////////////////////////////////////////////////////////////////////////////////////////


    @Override
    public boolean isEncryptable() {
        return true;
    }

    @Override
    public boolean isEncrypted() {
        lock.lock();
        try {
            return keys.isEncrypted();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the wallet pocket's KeyCrypter, or null if the wallet pocket is not encrypted.
     * (Used in encrypting/ decrypting an ECKey).
     */
    @Nullable
    @Override
    public KeyCrypter getKeyCrypter() {
        lock.lock();
        try {
            return keys.getKeyCrypter();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Encrypt the keys in the group using the KeyCrypter and the AES key. A good default KeyCrypter to use is
     * {@link org.bitcoinj.crypto.KeyCrypterScrypt}.
     *
     * @throws org.bitcoinj.crypto.KeyCrypterException Thrown if the wallet encryption fails for some reason,
     *         leaving the group unchanged.
     */
    @Override
    public void encrypt(KeyCrypter keyCrypter, KeyParameter aesKey) {
        checkNotNull(keyCrypter);
        checkNotNull(aesKey);

        lock.lock();
        try {
            for (int i = 0; i < keychains.size(); i++) {
                keychains.set(i, keychains.get(i).toEncrypted(keyCrypter, aesKey));
            }
            this.keys = keychains.get(receiveKeychainIndex);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Decrypt the keys in the group using the previously given key crypter and the AES key. A good default
     * KeyCrypter to use is {@link org.bitcoinj.crypto.KeyCrypterScrypt}.
     *
     * @throws org.bitcoinj.crypto.KeyCrypterException Thrown if the wallet decryption fails for some reason, leaving the group unchanged.
     */
    @Override
    public void decrypt(KeyParameter aesKey) {
        checkNotNull(aesKey);

        lock.lock();
        try {
            for (int i = 0; i < keychains.size(); i++) {
                keychains.set(i, keychains.get(i).toDecrypted(aesKey));
            }
            this.keys = keychains.get(receiveKeychainIndex);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String getPublicKeySerialized() {
        // Change the path of the key to match the BIP32 paths i.e. 0H/<account index>H
        DeterministicKey key = keys.getWatchingKey();
        ImmutableList<ChildNumber> path = ImmutableList.of(key.getChildNumber());
        key = new DeterministicKey(path, key.getChainCode(), key.getPubKeyPoint(), null, null);
        return key.serializePubB58();
    }

    @Override
    public boolean isWatchedScript(Script script) {
        // Not supported
        return false;
    }

    @Override
    public boolean isPayToScriptHashMine(byte[] payToScriptHash) {
        if (!type.getSupportedAddressTypes().contains(AddressType.COMPATIBLE)) {
            return false;
        }
        // For P2SH-P2WPKH (compatibility/BIP49) addresses the redeemScript is
        // OP_0 PUSH_20 <pubKeyHash>, and the scriptHash = hash160(redeemScript).
        lock.lock();
        try {
            for (SimpleHDKeyChain keychain : keychains) {
                for (DeterministicKey key : keychain.getActiveKeys()) {
                    byte[] pubKeyHash = key.getPubKeyHash();
                    byte[] redeemScript = new byte[22];
                    redeemScript[0] = 0x00; // OP_0
                    redeemScript[1] = 0x14; // PUSH 20 bytes
                    System.arraycopy(pubKeyHash, 0, redeemScript, 2, 20);
                    byte[] scriptHash = org.bitcoinj.core.Utils.sha256hash160(redeemScript);
                    if (java.util.Arrays.equals(scriptHash, payToScriptHash)) return true;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Locates a keypair from the basicKeyChain given the hash of the public key. This is needed
     * when finding out which key we need to use to redeem a transaction output.
     *
     * @return ECKey object or null if no such key was found.
     */
    @Nullable
    @Override
    public ECKey findKeyFromPubHash(byte[] pubkeyHash) {
        lock.lock();
        try {
            for (SimpleHDKeyChain keychain : keychains) {
                ECKey key = keychain.findKeyFromPubHash(pubkeyHash);
                if (key != null) return key;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Locates a keypair from the basicKeyChain given the raw public key bytes.
     * @return ECKey or null if no such key was found.
     */
    @Nullable
    @Override
    public ECKey findKeyFromPubKey(byte[] pubkey) {
        lock.lock();
        try {
            for (SimpleHDKeyChain keychain : keychains) {
                ECKey key = keychain.findKeyFromPubKey(pubkey);
                if (key != null) return key;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    @Nullable
    @Override
    public RedeemData findRedeemDataFromScriptHash(byte[] bytes) {
        // bitcoinj's RedeemData models P2SH-multisig, not P2SH-P2WPKH, so we do not vend
        // it here. P2SH-segwit redemption is resolved via findKeyForP2shP2wpkhScriptHash().
        return null;
    }

    /**
     * For a P2SH-P2WPKH output whose scriptHash = hash160(OP_0 PUSH_20 &lt;pubKeyHash&gt;),
     * return the owning key across every branch (so a bundled account can spend P2SH-segwit
     * outputs on its 49' branch). Returns null if no branch owns it.
     */
    @Nullable
    public ECKey findKeyForP2shP2wpkhScriptHash(byte[] scriptHash) {
        if (!type.getSupportedAddressTypes().contains(AddressType.COMPATIBLE)) {
            return null;
        }
        lock.lock();
        try {
            for (SimpleHDKeyChain keychain : keychains) {
                for (DeterministicKey key : keychain.getActiveKeys()) {
                    byte[] pubKeyHash = key.getPubKeyHash();
                    byte[] redeemScript = new byte[22];
                    redeemScript[0] = 0x00; // OP_0
                    redeemScript[1] = 0x14; // PUSH 20 bytes
                    System.arraycopy(pubKeyHash, 0, redeemScript, 2, 20);
                    byte[] hash = org.bitcoinj.core.Utils.sha256hash160(redeemScript);
                    if (java.util.Arrays.equals(hash, scriptHash)) {
                        return key;
                    }
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public byte[] getPublicKey() {
        lock.lock();
        try {
            return keys.getRootKey().getPubKey();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public AbstractAddress getChangeAddress() {
        return currentAddress(CHANGE);
    }

    @Override
    public AbstractAddress getReceiveAddress() {
        return currentAddress(RECEIVE_FUNDS);
    }

    public AbstractAddress getRefundAddress() { return currentAddress(REFUND); }

    @Override
    public boolean hasUsedAddresses() {
        return getNumberIssuedReceiveAddresses() != 0;
    }

    @Override
    public boolean canCreateNewAddresses() {
        return true;
    }

    @Override
    public AbstractAddress getReceiveAddress(boolean isManualAddressManagement) {
        return getAddress(RECEIVE_FUNDS, isManualAddressManagement);
    }

    @Override
    public AbstractAddress getRefundAddress(boolean isManualAddressManagement) {
        return getAddress(REFUND, isManualAddressManagement);
    }

    /**
     * Get the last used receiving address
     */
    @Nullable
    public AbstractAddress getLastUsedAddress(SimpleHDKeyChain.KeyPurpose purpose) {
        lock.lock();
        try {
            int idx = keychainIndexForPurpose(purpose);
            DeterministicKey lastUsedKey = keychains.get(idx).getLastIssuedKey(purpose);
            if (lastUsedKey != null) {
                return bundled
                        ? type.addressFromKey(lastUsedKey, purposes.get(idx))
                        : type.addressFromKey(lastUsedKey);
            } else {
                return null;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns true is it is possible to create new fresh receive addresses, false otherwise
     */
    public boolean canCreateFreshReceiveAddress() {
        lock.lock();
        try {
            DeterministicKey currentUnusedKey = keys.getCurrentUnusedKey(RECEIVE_FUNDS);
            int maximumKeyIndex = SimpleHDKeyChain.LOOKAHEAD - 1;

            // If there are used keys
            if (!addressesStatus.isEmpty()) {
                int lastUsedKeyIndex = 0;
                // Find the last used key index
                for (Map.Entry<AbstractAddress, String> entry : addressesStatus.entrySet()) {
                    if (entry.getValue() == null) continue;
                    DeterministicKey usedKey = keys.findKeyFromPubHash(getHash160(entry.getKey()));
                    if (usedKey != null && keys.isExternal(usedKey) && usedKey.getChildNumber().num() > lastUsedKeyIndex) {
                        lastUsedKeyIndex = usedKey.getChildNumber().num();
                    }
                }
                maximumKeyIndex = lastUsedKeyIndex + SimpleHDKeyChain.LOOKAHEAD;
            }

            log.info("Maximum key index for new key is {}", maximumKeyIndex);

            // If we exceeded the BIP44 look ahead threshold
            return currentUnusedKey.getChildNumber().num() < maximumKeyIndex;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get a fresh address by marking the current receive address as used. It will throw
     * {@link Bip44KeyLookAheadExceededException} if we requested too many addresses that
     * exceed the BIP44 look ahead threshold.
     */
    public AbstractAddress getFreshReceiveAddress() throws Bip44KeyLookAheadExceededException {
        lock.lock();
        try {
            if (!canCreateFreshReceiveAddress()) {
                throw new Bip44KeyLookAheadExceededException();
            }
            keys.getKey(RECEIVE_FUNDS);
            return currentAddress(RECEIVE_FUNDS);
        } finally {
            lock.unlock();
            walletSaveNow();
        }
    }

    public AbstractAddress getFreshReceiveAddress(boolean isManualAddressManagement)
            throws Bip44KeyLookAheadExceededException {
        lock.lock();
        try {
            AbstractAddress newAddress = null;
            AbstractAddress freshAddress = getFreshReceiveAddress();
            if (isManualAddressManagement) {
                newAddress = getLastUsedAddress(RECEIVE_FUNDS);
            }
            if (newAddress == null) {
                newAddress = freshAddress;
            }
            return newAddress;
        } finally {
            lock.unlock();
            walletSaveNow();
        }
    }

    private static final Comparator<DeterministicKey> HD_KEY_COMPARATOR =
            new Comparator<DeterministicKey>() {
                @Override
                public int compare(final DeterministicKey k1, final DeterministicKey k2) {
                    int key1Num = k1.getChildNumber().num();
                    int key2Num = k2.getChildNumber().num();
                    // In reality Integer.compare(key2Num, key1Num) but is not available on older devices
                    return (key2Num < key1Num) ? -1 : ((key2Num == key1Num) ? 0 : 1);
                }
            };

    /**
     * Returns the number of issued receiving keys
     */
    public int getNumberIssuedReceiveAddresses() {
        lock.lock();
        try {
            return keys.getNumIssuedExternalKeys();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a list of addresses that have been issued.
     * The list is sorted in descending chronological order: older in the end
     */
    public List<AbstractAddress> getIssuedReceiveAddresses() {
        lock.lock();
        try {
            ArrayList<DeterministicKey> issuedKeys = keys.getIssuedExternalKeys();
            ArrayList<AbstractAddress> receiveAddresses = new ArrayList<>();

            Collections.sort(issuedKeys, HD_KEY_COMPARATOR);

            for (ECKey key : issuedKeys) {
                receiveAddresses.add(type.addressFromKey(key));
            }
            return receiveAddresses;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the currently used receiving and change addresses
     */
    public Set<AbstractAddress> getUsedAddresses() {
        lock.lock();
        try {
            HashSet<AbstractAddress> usedAddresses = new HashSet<>();

            for (Map.Entry<AbstractAddress, String> entry : addressesStatus.entrySet()) {
                if (entry.getValue() != null) {
                    usedAddresses.add(entry.getKey());
                }
            }

            return usedAddresses;
        } finally {
            lock.unlock();
        }
    }

    public AbstractAddress getAddress(SimpleHDKeyChain.KeyPurpose purpose,
                              boolean isManualAddressManagement) {
        AbstractAddress receiveAddress = null;
        if (isManualAddressManagement) {
            receiveAddress = getLastUsedAddress(purpose);
        }

        if (receiveAddress == null) {
            receiveAddress = currentAddress(purpose);
        }
        return receiveAddress;
    }

    /**
     * Get the currently latest unused address by purpose.
     */
    @VisibleForTesting AbstractAddress currentAddress(SimpleHDKeyChain.KeyPurpose purpose) {
        lock.lock();
        try {
            if (!bundled) {
                return type.addressFromKey(keys.getCurrentUnusedKey(purpose));
            }
            int idx = keychainIndexForPurpose(purpose);
            DeterministicKey key = keychains.get(idx).getCurrentUnusedKey(purpose);
            return type.addressFromKey(key, purposes.get(idx));
        } finally {
            lock.unlock();
            subscribeToAddressesIfNeeded();
        }
    }

    /**
     * Used to force keys creation, could take long time to complete so use it in a background
     * thread.
     */
    @VisibleForTesting
    public void maybeInitializeAllKeys() {
        lock.lock();
        try {
            for (SimpleHDKeyChain keychain : keychains) {
                keychain.maybeLookAhead();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String getPublicKeyMnemonic() {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public List<AbstractAddress> getActiveAddresses() {
        lock.lock();
        try {
            ImmutableList.Builder<AbstractAddress> activeAddresses = ImmutableList.builder();
            boolean segwitOn = type.isSegwitActivatedAt(getLastBlockSeenHeight());
            if (bundled) {
                // Each branch has its OWN keys; emit only that branch's script type so the
                // watched addresses match Coinomi's per-purpose derivation exactly.
                for (int i = 0; i < keychains.size(); i++) {
                    AddressType purpose = purposes.get(i);
                    boolean segwitPurpose = purpose == AddressType.COMPATIBLE
                            || purpose == AddressType.NATIVE_SEGWIT;
                    SimpleHDKeyChain kc = keychains.get(i);
                    for (DeterministicKey key : kc.getActiveKeys()) {
                        // Pre-activation, skip a segwit branch's not-yet-issued keys.
                        if (segwitPurpose && !segwitOn && !kc.isIssued(key)) continue;
                        activeAddresses.add(type.addressFromKey(key, purpose));
                    }
                }
            } else {
                // Single-path pocket: emit legacy for every key, and segwit types either
                // once activated at the synced height or for already-issued (used) keys.
                Set<AddressType> supported = type.getSupportedAddressTypes();
                for (DeterministicKey key : keys.getActiveKeys()) {
                    activeAddresses.add(type.addressFromKey(key, AddressType.LEGACY));
                    boolean emitSegwit = segwitOn || keys.isIssued(key);
                    if (emitSegwit && supported.contains(AddressType.COMPATIBLE)) {
                        activeAddresses.add(type.addressFromKey(key, AddressType.COMPATIBLE));
                    }
                    if (emitSegwit && supported.contains(AddressType.NATIVE_SEGWIT)) {
                        activeAddresses.add(type.addressFromKey(key, AddressType.NATIVE_SEGWIT));
                    }
                    // TAPROOT is receive/display only — not included in watch list
                }
            }
            return activeAddresses.build();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markAddressAsUsed(AbstractAddress address) {
        checkArgument(address.getType().equals(type), "Wrong address type");
        if (!bundled) {
            // Single-path pocket: preserve the exact historical behaviour.
            if (address instanceof BitAddress) {
                markAddressAsUsed((BitAddress) address);
            } else if (address instanceof SegwitAddress) {
                // SegwitAddress shares the same HASH160 as the corresponding legacy key,
                // so markPubHashAsUsed correctly advances the HD keychain.
                keys.markPubHashAsUsed(((SegwitAddress) address).getHash160());
            } else {
                throw new IllegalArgumentException("Wrong address class: " + address.getClass());
            }
            return;
        }
        // Bundled pocket: the address may belong to any branch and, for a P2SH-segwit
        // address, its hash160 is the redeem-script hash rather than a pubkey hash.
        if (address instanceof SegwitAddress) {
            markPubHashAsUsedAnyKeychain(((SegwitAddress) address).getHash160());
        } else if (address instanceof BitAddress) {
            BitAddress ba = (BitAddress) address;
            if (isP2SHAddress(ba)) {
                markP2shScriptHashAsUsed(ba.getHash160());
            } else {
                markPubHashAsUsedAnyKeychain(ba.getHash160());
            }
        } else {
            throw new IllegalArgumentException("Wrong address class: " + address.getClass());
        }
    }

    public void markAddressAsUsed(BitAddress address) {
        keys.markPubHashAsUsed(address.getHash160());
    }

    /** Advance whichever branch owns the key with this pubkey hash (legacy / native-segwit). */
    private void markPubHashAsUsedAnyKeychain(byte[] pubKeyHash) {
        lock.lock();
        try {
            for (SimpleHDKeyChain keychain : keychains) {
                if (keychain.markPubHashAsUsed(pubKeyHash)) return;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Advance whichever branch owns the key whose P2SH-P2WPKH redeem script hashes to this. */
    private void markP2shScriptHashAsUsed(byte[] scriptHash) {
        lock.lock();
        try {
            for (SimpleHDKeyChain keychain : keychains) {
                for (DeterministicKey key : keychain.getActiveKeys()) {
                    byte[] pubKeyHash = key.getPubKeyHash();
                    byte[] redeemScript = new byte[22];
                    redeemScript[0] = 0x00; // OP_0
                    redeemScript[1] = 0x14; // PUSH 20 bytes
                    System.arraycopy(pubKeyHash, 0, redeemScript, 2, 20);
                    if (java.util.Arrays.equals(
                            org.bitcoinj.core.Utils.sha256hash160(redeemScript), scriptHash)) {
                        keychain.markKeyAsUsed(key);
                        return;
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        return WalletPocketHD.class.getSimpleName() + " " + id.substring(0, 4)+ " " + type;
    }
}
