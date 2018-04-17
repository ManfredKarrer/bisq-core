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

package bisq.core.dao.state;

import bisq.core.dao.DaoOptionKeys;
import bisq.core.dao.state.blockchain.SpentInfo;
import bisq.core.dao.state.blockchain.TxBlock;
import bisq.core.dao.state.blockchain.TxOutput;
import bisq.core.dao.state.blockchain.TxOutputType;
import bisq.core.dao.state.blockchain.TxType;
import bisq.core.dao.vote.period.Cycle;

import org.bitcoinj.core.Coin;

import javax.inject.Inject;
import javax.inject.Named;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;


/**
 * Encapsulates the access to the State of the DAO.
 * Write access is in the context of the nodeExecutor thread. Read access can be any thread.
 * <p>
 * TODO check if locks are required.
 */
@Slf4j
public class UserThreadStateService extends BaseStateService {
    @Delegate
    private final State userThreadState;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public UserThreadStateService(State state, StateService stateService,
                                  @Named(DaoOptionKeys.GENESIS_TX_ID) String genesisTxId,
                                  @Named(DaoOptionKeys.GENESIS_BLOCK_HEIGHT) int genesisBlockHeight) {
        super();


        stateService.addBlockListener(block -> blockListeners.forEach(l -> l.onBlockAdded(block)));

        userThreadState = new State(genesisTxId, genesisBlockHeight);

        state.addStateChangeListener(new StateChangeListener() {
            @Override
            public void addBlock(Block block) {
                userThreadState.addBlock(block);
            }

            @Override
            public void putTxType(String txId, TxType txType) {
                userThreadState.putTxType(txId, txType);
            }

            @Override
            public void putBurntFee(String txId, long burnedFee) {
                userThreadState.putBurntFee(txId, burnedFee);
            }

            @Override
            public void addUnspentTxOutput(TxOutput txOutput) {
                userThreadState.addUnspentTxOutput(txOutput);
            }

            @Override
            public void removeUnspentTxOutput(TxOutput txOutput) {
                userThreadState.removeUnspentTxOutput(txOutput);
            }

            @Override
            public void putIssuanceBlockHeight(TxOutput txOutput, int chainHeight) {
                userThreadState.putIssuanceBlockHeight(txOutput, chainHeight);
            }

            @Override
            public void putSpentInfo(TxOutput txOutput, int blockHeight, String txId, int inputIndex) {
                userThreadState.putSpentInfo(txOutput, blockHeight, txId, inputIndex);
            }

            @Override
            public void putTxOutputType(TxOutput txOutput, TxOutputType txOutputType) {
                userThreadState.putTxOutputType(txOutput, txOutputType);
            }

            @Override
            public void addCycle(Cycle cycle) {
                userThreadState.addCycle(cycle);
            }
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Read access
    ///////////////////////////////////////////////////////////////////////////////////////////

    public List<TxBlock> getClonedBlocksFrom(int fromBlockHeight) {
        final LinkedList<Block> clonedBlocks = new LinkedList<>(getBlocks());
        return getTxBlocks(clonedBlocks).stream()
                .filter(block -> block.getHeight() >= fromBlockHeight)
                .collect(Collectors.toList());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Protected provider methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Genesis
    public String getGenesisTxId() {
        return userThreadState.getGenesisTxId();
    }

    public int getGenesisBlockHeight() {
        return userThreadState.getGenesisBlockHeight();
    }

    public Coin getGenesisTotalSupply() {
        return userThreadState.getGenesisTotalSupply();
    }

    // Block
    @Override
    public LinkedList<Block> getBlocks() {
        return userThreadState.getBlocks();
    }

    // Tx
    @Override
    public Map<String, TxType> getTxTypeMap() {
        return userThreadState.getTxTypeMap();
    }

    @Override
    public Map<String, Long> getBurntFeeMap() {
        return userThreadState.getBurntFeeMap();
    }

    @Override
    public Map<String, Integer> getIssuanceBlockHeightMap() {
        return userThreadState.getIssuanceBlockHeightMap();
    }

    // TxOutput
    @Override
    public Map<TxOutput.Key, TxOutput> getUnspentTxOutputMap() {
        return userThreadState.getUnspentTxOutputMap();
    }

    @Override
    public Map<TxOutput.Key, SpentInfo> getTxOutputSpentInfoMap() {
        return userThreadState.getSpentInfoMap();
    }

    @Override
    public Map<TxOutput.Key, TxOutputType> getTxOutputTypeMap() {
        return userThreadState.getTxOutputTypeMap();
    }

    // Cycle
    @Override
    public List<Cycle> getCycles() {
        return userThreadState.getCycles();
    }
}
