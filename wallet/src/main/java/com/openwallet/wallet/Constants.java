package com.openwallet.wallet;

import android.text.format.DateUtils;

import com.openwallet.core.coins.BitcoinMain;
import com.openwallet.core.coins.BitcoinTest;
import com.openwallet.core.coins.CardanoMain;
import com.openwallet.core.coins.ChiaMain;
import com.openwallet.core.coins.CoinID;
import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.DashMain;
import com.openwallet.core.coins.DigibyteMain;
import com.openwallet.core.coins.DogecoinMain;
import com.openwallet.core.coins.DogecoinTest;
import com.openwallet.core.coins.FeathercoinMain;
import com.openwallet.core.coins.LitecoinMain;
import com.openwallet.core.coins.LitecoinTest;
import com.openwallet.core.coins.NewYorkCoinMain;
import com.openwallet.core.coins.PotcoinMain;
import com.openwallet.core.coins.ReddcoinMain;
import com.openwallet.core.coins.VertcoinMain;
import com.openwallet.core.coins.ZcashMain;
import com.openwallet.core.network.CoinAddress;
import com.openwallet.stratumj.ServerAddress;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author John L. Jegutanis
 * @author Andreas Schildbach
 */
public class Constants {

    public static final int SEED_ENTROPY_DEFAULT = 192;
    public static final int SEED_ENTROPY_EXTRA = 256;

    public static final String ARG_SEED = "seed";
    public static final String ARG_PASSWORD = "password";
    public static final String ARG_SEED_PASSWORD = "seed_password";
    public static final String ARG_RESTORED_WALLET = "restored_wallet";
    public static final String ARG_EMPTY_WALLET = "empty_wallet";
    public static final String ARG_SEND_TO_ADDRESS = "send_to_address";
    public static final String ARG_SEND_TO_COIN_TYPE = "send_to_coin_type";
    public static final String ARG_SEND_TO_ACCOUNT_ID = "send_to_account_id";
    public static final String ARG_SEND_VALUE = "send_value";
    public static final String ARG_TX_MESSAGE = "tx_message";
    public static final String ARG_COIN_ID = "coin_id";
    public static final String ARG_ACCOUNT_ID = "account_id";
    public static final String ARG_MULTIPLE_COIN_IDS = "multiple_coin_ids";
    public static final String ARG_MULTIPLE_CHOICE = "multiple_choice";
    public static final String ARG_SEND_REQUEST = "send_request";
    public static final String ARG_TRANSACTION_ID = "transaction_id";
    public static final String ARG_ERROR = "error";
    public static final String ARG_MESSAGE = "message";
    public static final String ARG_ADDRESS = "address";
    public static final String ARG_ADDRESS_STRING = "address_string";
    public static final String ARG_EXCHANGE_ENTRY = "exchange_entry";
    public static final String ARG_URI = "test_wallet";
    public static final String ARG_PRIVATE_KEY = "private_key";

    public static final int PERMISSIONS_REQUEST_CAMERA = 0;

    public static final String WALLET_FILENAME_PROTOBUF = "wallet";
    public static final long WALLET_WRITE_DELAY = 5;
    public static final TimeUnit WALLET_WRITE_DELAY_UNIT = TimeUnit.SECONDS;

    public static final long STOP_SERVICE_AFTER_IDLE_SECS = 30 * 60; // 30 mins

    public static final String HTTP_CACHE_NAME = "http_cache";
    public static final int HTTP_CACHE_SIZE = 256 * 1024; // 256 KiB
    public static final int NETWORK_TIMEOUT_MS = 15 * (int) DateUtils.SECOND_IN_MILLIS;

    public static final String TX_CACHE_NAME = "tx_cache";
    public static final int TX_CACHE_SIZE = 5 * 1024 * 1024; // 5 MiB, TODO currently not enforced

    public static final long RATE_UPDATE_FREQ_MS = 30 * DateUtils.SECOND_IN_MILLIS;

    /** Default currency to use if all default mechanisms fail. */
    public static final String DEFAULT_EXCHANGE_CURRENCY = "USD";

    public static final Charset UTF_8 = Charset.forName("UTF-8");
    public static final Charset US_ASCII = Charset.forName("US-ASCII");

    public static final char CHAR_HAIR_SPACE = '\u200a';
    public static final char CHAR_THIN_SPACE = '\u2009';
    public static final char CHAR_ALMOST_EQUAL_TO = '\u2248';
    public static final char CHAR_CHECKMARK = '\u2713';
    public static final char CURRENCY_PLUS_SIGN = '+';
    public static final char CURRENCY_MINUS_SIGN = '-';

    public static final String MARKET_APP_URL = "market://details?id=%s";
    public static final String BINARY_URL = "https://gitlab.com/openwallet/openwallet-releases";

    public static final String VERSION_URL = "https://openwallet.xyz/version";
    public static final String SUPPORT_EMAIL = "https://reddit.com/r/openwallet";

    // TODO move to resource files
    public static final List<CoinAddress> DEFAULT_COINS_SERVERS = ImmutableList.of(
            new CoinAddress(NewYorkCoinMain.get(),
                    new ServerAddress("electrum.paywith.nyc", 50002, true),
                    new ServerAddress("electrum.paywith.nyc", 50002, true)),
            new CoinAddress(BitcoinMain.get(),      new ServerAddress("electrum.blockstream.info", 50002, true),
                                                    new ServerAddress("electrum.bitaroo.net", 50002, true)),
            new CoinAddress(BitcoinTest.get(),      new ServerAddress("electrum.blockstream.info", 60002, true),
                                                    new ServerAddress("electrum.blockstream.info", 60002, true)),
            new CoinAddress(LitecoinMain.get(),     new ServerAddress("electrum-ltc.bysh.me", 50002, true),
                                                    new ServerAddress("electrum.ltc.xurious.com", 50002, true)),
            new CoinAddress(LitecoinTest.get(),     new ServerAddress("ltc-testnet-cce-1.coinomi.net", 15002),
                                                    new ServerAddress("ltc-testnet-cce-2.coinomi.net", 15002)),
            new CoinAddress(DogecoinMain.get(),     new ServerAddress("electrum1.cipig.net", 20060, true),
                                                    new ServerAddress("electrum2.cipig.net", 20060, true)),
            new CoinAddress(DogecoinTest.get(),     new ServerAddress("doge-testnet-cce-1.coinomi.net", 15003),
                                                    new ServerAddress("doge-testnet-cce-2.coinomi.net", 15003)),
            new CoinAddress(DashMain.get(),         new ServerAddress("drk-cce-1.coinomi.net", 5013),
                                                    new ServerAddress("drk-cce-2.coinomi.net", 5013)),
            new CoinAddress(ReddcoinMain.get(),     new ServerAddress("rdd-cce-1.coinomi.net", 5014),
                                                    new ServerAddress("rdd-cce-2.coinomi.net", 5014)),
            new CoinAddress(FeathercoinMain.get(),  new ServerAddress("ftc-cce-1.coinomi.net", 5017),
                                                    new ServerAddress("ftc-cce-2.coinomi.net", 5017)),
            new CoinAddress(DigibyteMain.get(),     new ServerAddress("dgb-cce-1.coinomi.net", 5023),
                                                    new ServerAddress("dgb-cce-2.coinomi.net", 5023)),
            new CoinAddress(VertcoinMain.get(),     new ServerAddress("vtc-cce-1.coinomi.net", 5028),
                                                    new ServerAddress("vtc-cce-2.coinomi.net", 5028)),
            new CoinAddress(PotcoinMain.get(),      new ServerAddress("pot-cce-1.coinomi.net", 5039),
                                                    new ServerAddress("pot-cce-2.coinomi.net", 5039)),
            new CoinAddress(ZcashMain.get(),
                    new ServerAddress("zec.rocks", 443, true),
                    new ServerAddress("zec-node.cakewallet.com", 443, true))
    );

    public static final HashMap<CoinType, Integer> COINS_ICONS;
    public static final HashMap<CoinType, String> COINS_BLOCK_EXPLORERS;
    static {
        COINS_ICONS = new HashMap<>();
        COINS_ICONS.put(CoinID.NEWYORKCOIN_MAIN.getCoinType(), R.drawable.newyorkcoin);
        COINS_ICONS.put(CoinID.BITCOIN_MAIN.getCoinType(), R.drawable.bitcoin);
        COINS_ICONS.put(CoinID.BITCOIN_TEST.getCoinType(), R.drawable.bitcoin_test);
        COINS_ICONS.put(CoinID.LITECOIN_MAIN.getCoinType(), R.drawable.litecoin);
        COINS_ICONS.put(CoinID.LITECOIN_TEST.getCoinType(), R.drawable.litecoin_test);
        COINS_ICONS.put(CoinID.DOGECOIN_MAIN.getCoinType(), R.drawable.dogecoin);
        COINS_ICONS.put(CoinID.DOGECOIN_TEST.getCoinType(), R.drawable.dogecoin_test);
        COINS_ICONS.put(CoinID.DASH_MAIN.getCoinType(), R.drawable.dash);
        COINS_ICONS.put(CoinID.REDDCOIN_MAIN.getCoinType(), R.drawable.reddcoin);
        COINS_ICONS.put(CoinID.FEATHERCOIN_MAIN.getCoinType(), R.drawable.feathercoin);
        COINS_ICONS.put(CoinID.DIGIBYTE_MAIN.getCoinType(), R.drawable.digibyte);
        COINS_ICONS.put(CoinID.VERTCOIN_MAIN.getCoinType(), R.drawable.vertcoin);
        COINS_ICONS.put(CoinID.POTCOIN_MAIN.getCoinType(), R.drawable.potcoin);
        COINS_ICONS.put(CoinID.ZCASH_MAIN.getCoinType(), R.drawable.zcash);
        COINS_BLOCK_EXPLORERS = new HashMap<CoinType, String>();
        COINS_BLOCK_EXPLORERS.put(CoinID.NEWYORKCOIN_MAIN.getCoinType(),
                "https://chainz.cryptoid.info/nyc/tx.dws?%s.htm");
        COINS_BLOCK_EXPLORERS.put(CoinID.BITCOIN_MAIN.getCoinType(), "https://blockchain.info/tx/%s");
        COINS_BLOCK_EXPLORERS.put(CoinID.BITCOIN_TEST.getCoinType(), "https://chain.so/tx/BTCTEST/%s");
        COINS_BLOCK_EXPLORERS.put(CoinID.LITECOIN_MAIN.getCoinType(), "http://ltc.blockr.io/tx/info/%s");
        COINS_BLOCK_EXPLORERS.put(CoinID.LITECOIN_TEST.getCoinType(), "https://chain.so/tx/LTCTEST/%s");
        COINS_BLOCK_EXPLORERS.put(CoinID.DOGECOIN_MAIN.getCoinType(), "https://chain.so/tx/DOGE/%s");
        COINS_BLOCK_EXPLORERS.put(CoinID.DOGECOIN_TEST.getCoinType(), "https://chain.so/tx/DOGETEST/%s");
        COINS_BLOCK_EXPLORERS.put(CoinID.DASH_MAIN.getCoinType(), "http://explorer.dashpay.io/tx/%s");
        COINS_BLOCK_EXPLORERS.put(CoinID.REDDCOIN_MAIN.getCoinType(), "http://live.reddcoin.com/tx/%s");
        COINS_BLOCK_EXPLORERS.put(CoinID.FEATHERCOIN_MAIN.getCoinType(), "http://explorer.feathercoin.com/tx/%s");
        COINS_BLOCK_EXPLORERS.put(CoinID.DIGIBYTE_MAIN.getCoinType(), "https://digiexplorer.info/tx/%s");
        COINS_BLOCK_EXPLORERS.put(CoinID.VERTCOIN_MAIN.getCoinType(), "https://bitinfocharts.com/vertcoin/tx/%s");
        COINS_BLOCK_EXPLORERS.put(CoinID.POTCOIN_MAIN.getCoinType(), "https://chainz.cryptoid.info/pot/tx.dws?%s");
        COINS_BLOCK_EXPLORERS.put(CoinID.ZCASH_MAIN.getCoinType(),
                "https://explorer.zcha.in/transactions/%s");
    }

    public static final CoinType DEFAULT_COIN = NewYorkCoinMain.get();
    public static final List<CoinType> DEFAULT_COINS = ImmutableList.of((CoinType) NewYorkCoinMain.get());
    public static final ArrayList<String> DEFAULT_TEST_COIN_IDS = Lists.newArrayList(
            BitcoinTest.get().getId(),
            LitecoinTest.get().getId(),
            DogecoinTest.get().getId()
    );

    public static final List<CoinType> SUPPORTED_COINS = ImmutableList.of(
            NewYorkCoinMain.get(),
            BitcoinMain.get(),
            LitecoinMain.get(),
            DashMain.get(),
            DigibyteMain.get(),
            DogecoinMain.get(),
            ZcashMain.get(),
            ReddcoinMain.get(),
            VertcoinMain.get(),
            BitcoinTest.get(),
            LitecoinTest.get(),
            DogecoinTest.get()
    );
}
