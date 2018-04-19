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

package bisq.core.dao.consensus.node.lite;

import bisq.core.dao.consensus.node.BsqNode;
import bisq.core.dao.consensus.node.blockchain.exceptions.BlockNotConnectingException;
import bisq.core.dao.consensus.node.lite.network.LiteNodeNetworkService;
import bisq.core.dao.consensus.node.messages.GetBsqBlocksResponse;
import bisq.core.dao.consensus.node.messages.NewBsqBlockBroadcastMessage;
import bisq.core.dao.consensus.state.SnapshotManager;
import bisq.core.dao.consensus.state.StateService;
import bisq.core.dao.consensus.state.blockchain.TxBlock;

import bisq.network.p2p.P2PService;
import bisq.network.p2p.network.Connection;

import bisq.common.UserThread;
import bisq.common.handlers.ErrorMessageHandler;

import com.google.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Main class for lite nodes which receive the BSQ transactions from a full node (e.g. seed nodes).
 * <p>
 * Verification of BSQ transactions is done also by the lite node.
 */
@Slf4j
public class LiteNode extends BsqNode {
    private final LiteNodeExecutor bsqLiteNodeExecutor;
    private final LiteNodeNetworkService liteNodeNetworkService;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @SuppressWarnings("WeakerAccess")
    @Inject
    public LiteNode(StateService stateService,
                    SnapshotManager snapshotManager,
                    P2PService p2PService,
                    LiteNodeExecutor bsqLiteNodeExecutor,
                    LiteNodeNetworkService liteNodeNetworkService) {
        super(stateService,
                snapshotManager,
                p2PService);
        this.bsqLiteNodeExecutor = bsqLiteNodeExecutor;
        this.liteNodeNetworkService = liteNodeNetworkService;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onAllServicesInitialized(ErrorMessageHandler errorMessageHandler) {
        super.onInitialized();
    }

    public void shutDown() {
        liteNodeNetworkService.shutDown();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Protected
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void onP2PNetworkReady() {
        super.onP2PNetworkReady();

        liteNodeNetworkService.addListener(new LiteNodeNetworkService.Listener() {
            @Override
            public void onRequestedBlocksReceived(GetBsqBlocksResponse getBsqBlocksResponse) {
                LiteNode.this.onRequestedBlocksReceived(new ArrayList<>(getBsqBlocksResponse.getTxBlocks()));
            }

            @Override
            public void onNewBlockReceived(NewBsqBlockBroadcastMessage newBsqBlockBroadcastMessage) {
                LiteNode.this.onNewBlockReceived(newBsqBlockBroadcastMessage.getTxBlock());
            }

            @Override
            public void onNoSeedNodeAvailable() {
            }

            @Override
            public void onFault(String errorMessage, @Nullable Connection connection) {
            }
        });

        // delay a bit to not stress too much at startup
        UserThread.runAfter(this::startParseBlocks, 2);
    }

    // First we request the blocks from a full node
    @Override
    protected void startParseBlocks() {
        liteNodeNetworkService.requestBlocks(getStartBlockHeight());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We received the missing blocks
    private void onRequestedBlocksReceived(List<TxBlock> txBlockList) {
        log.info("onRequestedBlocksReceived: blocks with {} items", txBlockList.size());
        if (txBlockList.size() > 0)
            log.info("block height of last item: {}", txBlockList.get(txBlockList.size() - 1).getHeight());
        // We clone with a reset of all mutable data in case the provider would not have done it.
        List<TxBlock> clonedTxBlockList = txBlockList.stream()
                .map(bsqBlock -> TxBlock.clone(bsqBlock))
                .collect(Collectors.toList());
        bsqLiteNodeExecutor.parseBlocks(clonedTxBlockList,
                this::onNewBsqBlock,
                this::onParseBlockChainComplete,
                getErrorHandler());
    }

    // We received a new block
    private void onNewBlockReceived(TxBlock txBlock) {
        log.info("onNewBlockReceived: txBlock={}", txBlock.getHeight());

        // We clone with a reset of all mutable data in case the provider would not have done it.
        TxBlock clonedTxBlock = TxBlock.clone(txBlock);
        if (!stateService.containsTxBlock(clonedTxBlock)) {
            //TODO check block height and prev block it it connects to existing blocks
            bsqLiteNodeExecutor.parseBlock(clonedTxBlock, this::onNewBsqBlock, getErrorHandler());
        }
    }

    private void onNewBsqBlock(TxBlock txBlock) {
        log.debug("new txBlock parsed: " + txBlock);
    }

    @NotNull
    private Consumer<Throwable> getErrorHandler() {
        return throwable -> {
            if (throwable instanceof BlockNotConnectingException) {
                startReOrgFromLastSnapshot();
            } else {
                log.error(throwable.toString());
                throwable.printStackTrace();
            }
        };
    }
}