/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.util;

import bisq.core.monetary.Volume;

import bisq.common.util.MathUtils;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.Fiat;

public class CoinUtil {

    public static Coin getFeePerBtc(Coin feePerBtc, Coin amount) {
        double feePerBtcAsDouble = (double) feePerBtc.value;
        double amountAsDouble = (double) amount.value;
        double btcAsDouble = (double) Coin.COIN.value;
        return Coin.valueOf(Math.round(feePerBtcAsDouble * (amountAsDouble / btcAsDouble)));
    }

    public static Coin minCoin(Coin a, Coin b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    public static Coin maxCoin(Coin a, Coin b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    public static double getFeePerByte(Coin miningFee, int txSize) {
        return MathUtils.roundDouble(((double) miningFee.value / (double) txSize), 2);
    }

    public static Volume roundVolume(Volume volumeByAmount) {
        if (volumeByAmount.getMonetary() instanceof Fiat) {
            final long rounded = MathUtils.roundDoubleToLong(volumeByAmount.getValue() / 10000D) * 10000L;
            long val = Math.max(10000L, rounded); // We don't allow 0 value
            return new Volume(Fiat.valueOf(volumeByAmount.getCurrencyCode(), val));
        } else {
            return volumeByAmount;
        }
    }
}
