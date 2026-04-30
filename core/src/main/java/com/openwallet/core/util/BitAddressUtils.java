package com.openwallet.core.util;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.families.bitcoin.BitAddress;
import com.openwallet.core.wallet.families.bitcoin.SegwitAddress;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.ScriptException;
import org.bitcoinj.core.WrongNetworkException;
import org.bitcoinj.script.Script;

import java.util.Arrays;

import static com.openwallet.core.Preconditions.checkArgument;

/**
 * @author John L. Jegutanis
 */
public class BitAddressUtils {
    public static boolean isP2SHAddress(AbstractAddress address) {
        checkArgument(address instanceof BitAddress, "This address cannot be a P2SH address");
        return ((BitAddress) address).isP2SHAddress();
    }

    public static byte[] getHash160(AbstractAddress address) {
        checkArgument(address instanceof BitAddress, "Cannot get hash160 from this address");
        return ((BitAddress) address).getHash160();
    }

    public static boolean producesAddress(Script script, AbstractAddress address) {
        try {
            if (address instanceof SegwitAddress) {
                // P2WPKH script: OP_0 (0x00) + OP_PUSHBYTES_20 (0x14) + 20-byte hash160
                byte[] prog = script.getProgram();
                if (prog.length == 22 && (prog[0] & 0xff) == 0x00 && (prog[1] & 0xff) == 0x14) {
                    byte[] scriptHash = new byte[20];
                    System.arraycopy(prog, 2, scriptHash, 0, 20);
                    return Arrays.equals(scriptHash, ((SegwitAddress) address).getHash160());
                }
                return false;
            }
            return BitAddress.from(address.getType(), script).equals(address);
        } catch (AddressMalformedException | ScriptException e) {
            return false;
        }
    }
}
