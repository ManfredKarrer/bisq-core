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

package bisq.core.dao.state.blockchain;

import bisq.core.dao.node.btcd.PubKeyScript;

import bisq.common.proto.persistable.PersistablePayload;
import bisq.common.util.JsonExclude;
import bisq.common.util.Utilities;

import io.bisq.generated.protobuffer.PB;

import com.google.protobuf.ByteString;

import java.util.Optional;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * RawTxOutput as we get it from the blockchain without BSQ specific data.
 */
@Immutable
@Value
@Slf4j
public final class RawTxOutput implements PersistablePayload {

    public static RawTxOutput clone(RawTxOutput txOutput) {
        return new RawTxOutput(txOutput.getIndex(),
                txOutput.getValue(),
                txOutput.getTxId(),
                txOutput.getPubKeyScript(),
                txOutput.getAddress(),
                txOutput.getOpReturnData(),
                txOutput.getBlockHeight());
    }

    public static RawTxOutput cloneFromTxOutput(TxOutput txOutput) {
        return new RawTxOutput(txOutput.getIndex(),
                txOutput.getValue(),
                txOutput.getTxId(),
                txOutput.getPubKeyScript(),
                txOutput.getAddress(),
                txOutput.getOpReturnData(),
                txOutput.getBlockHeight());
    }

    private final int index;
    private final long value;
    private final String txId;

    // Only set if dumpBlockchainData is true
    @Nullable
    private final PubKeyScript pubKeyScript;
    @Nullable
    private final String address;
    @Nullable
    @JsonExclude
    private final byte[] opReturnData;
    private final int blockHeight;

    public RawTxOutput(int index,
                       long value,
                       String txId,
                       @Nullable PubKeyScript pubKeyScript,
                       @Nullable String address,
                       @Nullable byte[] opReturnData,
                       int blockHeight) {
        this.index = index;
        this.value = value;
        this.txId = txId;
        this.pubKeyScript = pubKeyScript;
        this.address = address;
        this.opReturnData = opReturnData;
        this.blockHeight = blockHeight;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    public PB.RawTxOutput toProtoMessage() {
        final PB.RawTxOutput.Builder builder = PB.RawTxOutput.newBuilder()
                .setIndex(index)
                .setValue(value)
                .setTxId(txId)
                .setBlockHeight(blockHeight);

        Optional.ofNullable(pubKeyScript).ifPresent(e -> builder.setPubKeyScript(pubKeyScript.toProtoMessage()));
        Optional.ofNullable(address).ifPresent(e -> builder.setAddress(address));
        Optional.ofNullable(opReturnData).ifPresent(e -> builder.setOpReturnData(ByteString.copyFrom(opReturnData)));

        return builder.build();
    }

    public static RawTxOutput fromProto(PB.RawTxOutput proto) {
        return new RawTxOutput(proto.getIndex(),
                proto.getValue(),
                proto.getTxId(),
                proto.hasPubKeyScript() ? PubKeyScript.fromProto(proto.getPubKeyScript()) : null,
                proto.getAddress().isEmpty() ? null : proto.getAddress(),
                proto.getOpReturnData().isEmpty() ? null : proto.getOpReturnData().toByteArray(),
                proto.getBlockHeight());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Util
    ///////////////////////////////////////////////////////////////////////////////////////////

    public TxOutputKey getKey() {
        return new TxOutputKey(txId, index);
    }

    @Override
    public String toString() {
        return "RawTxOutput{" +
                "\n     index=" + index +
                ",\n     value=" + value +
                ",\n     txId='" + txId + '\'' +
                ",\n     pubKeyScript=" + pubKeyScript +
                ",\n     address='" + address + '\'' +
                ",\n     opReturnData=" + Utilities.bytesAsHexString(opReturnData) +
                ",\n     blockHeight=" + blockHeight +
                "\n}";
    }
}
