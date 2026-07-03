package com.openwallet.core.wallet;

import com.openwallet.core.coins.CoinID;
import com.openwallet.core.coins.CoinType;
import com.openwallet.core.protos.Protos;
import com.openwallet.core.wallet.families.zcash.ZcashSdkWallet;

import org.bitcoinj.store.UnreadableWalletException;

/**
 * Persists {@link ZcashSdkWallet} accounts.
 *
 * Only the account identity survives a round-trip: coin type (network identifier),
 * account id, and the BIP-44 derived fallback t-address (stored as a single
 * AddressStatus entry). Balances and transactions are intentionally NOT persisted
 * here — the zcash-android-sdk keeps its own database (keyed by SDK alias) and
 * repopulates them on the next sync.
 */
public class ZcashSdkWalletProtobufSerializer {

    public static Protos.WalletPocket toProtobuf(ZcashSdkWallet pocket) {
        Protos.WalletPocket.Builder builder = Protos.WalletPocket.newBuilder();
        builder.setNetworkIdentifier(pocket.getCoinType().getId());
        builder.setId(pocket.getId());
        if (pocket.getDescription() != null) {
            builder.setDescription(pocket.getDescription());
        }
        String tAddress = pocket.getFallbackTAddressString();
        if (!tAddress.isEmpty()) {
            builder.addAddressStatus(Protos.AddressStatus.newBuilder()
                    .setAddress(tAddress)
                    .setStatus("")
                    .build());
        }
        return builder.build();
    }

    public static ZcashSdkWallet readWallet(Protos.WalletPocket proto)
            throws UnreadableWalletException {
        CoinType type;
        try {
            type = CoinID.typeFromId(proto.getNetworkIdentifier());
        } catch (IllegalArgumentException e) {
            throw new UnreadableWalletException(
                    "Unknown network identifier " + proto.getNetworkIdentifier(), e);
        }
        String id = proto.hasId() ? proto.getId() : type.getId() + ":0";
        String tAddress = proto.getAddressStatusCount() > 0
                ? proto.getAddressStatus(0).getAddress() : "";
        ZcashSdkWallet pocket = new ZcashSdkWallet(type, id, tAddress);
        if (proto.hasDescription()) {
            pocket.setDescription(proto.getDescription());
        }
        return pocket;
    }
}
