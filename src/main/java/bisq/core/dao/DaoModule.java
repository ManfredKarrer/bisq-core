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

package bisq.core.dao;

import bisq.core.dao.blockchain.json.JsonBlockChainExporter;
import bisq.core.dao.node.BsqNodeProvider;
import bisq.core.dao.node.NodeExecutor;
import bisq.core.dao.node.consensus.BsqTxController;
import bisq.core.dao.node.consensus.GenesisTxController;
import bisq.core.dao.node.consensus.GenesisTxOutputController;
import bisq.core.dao.node.consensus.OpReturnBlindVoteController;
import bisq.core.dao.node.consensus.OpReturnCompReqController;
import bisq.core.dao.node.consensus.OpReturnController;
import bisq.core.dao.node.consensus.OpReturnProposalController;
import bisq.core.dao.node.consensus.OpReturnVoteRevealController;
import bisq.core.dao.node.consensus.TxInputController;
import bisq.core.dao.node.consensus.TxInputsController;
import bisq.core.dao.node.consensus.TxOutputController;
import bisq.core.dao.node.consensus.TxOutputsController;
import bisq.core.dao.node.full.FullNode;
import bisq.core.dao.node.full.FullNodeExecutor;
import bisq.core.dao.node.full.FullNodeParser;
import bisq.core.dao.node.full.network.FullNodeNetworkService;
import bisq.core.dao.node.full.rpc.RpcService;
import bisq.core.dao.node.lite.LiteNode;
import bisq.core.dao.node.lite.LiteNodeExecutor;
import bisq.core.dao.node.lite.LiteNodeParser;
import bisq.core.dao.node.lite.network.LiteNodeNetworkService;
import bisq.core.dao.param.DaoParamService;
import bisq.core.dao.state.ChainState;
import bisq.core.dao.state.ChainStateService;
import bisq.core.dao.state.SnapshotManager;
import bisq.core.dao.vote.PeriodService;
import bisq.core.dao.vote.blindvote.BlindVoteService;
import bisq.core.dao.vote.blindvote.BlindVoteValidator;
import bisq.core.dao.vote.myvote.MyVoteService;
import bisq.core.dao.vote.proposal.MyProposalService;
import bisq.core.dao.vote.proposal.ProposalListService;
import bisq.core.dao.vote.proposal.ProposalPayloadValidator;
import bisq.core.dao.vote.proposal.ProposalService;
import bisq.core.dao.vote.proposal.compensation.CompensationRequestPayloadValidator;
import bisq.core.dao.vote.proposal.compensation.CompensationRequestService;
import bisq.core.dao.vote.proposal.generic.GenericProposalService;
import bisq.core.dao.vote.voteresult.VoteResultService;
import bisq.core.dao.vote.voteresult.issuance.IssuanceService;
import bisq.core.dao.vote.votereveal.VoteRevealService;

import bisq.common.app.AppModule;

import org.springframework.core.env.Environment;

import com.google.inject.Singleton;
import com.google.inject.name.Names;

import static com.google.inject.name.Names.named;

public class DaoModule extends AppModule {

    public DaoModule(Environment environment) {
        super(environment);
    }

    @Override
    protected void configure() {
        bind(DaoSetup.class).in(Singleton.class);

        // node
        bind(BsqNodeProvider.class).in(Singleton.class);
        bind(NodeExecutor.class).in(Singleton.class);
        bind(RpcService.class).in(Singleton.class);
        bind(FullNode.class).in(Singleton.class);
        bind(FullNodeExecutor.class).in(Singleton.class);
        bind(FullNodeNetworkService.class).in(Singleton.class);
        bind(FullNodeParser.class).in(Singleton.class);
        bind(LiteNode.class).in(Singleton.class);
        bind(LiteNodeNetworkService.class).in(Singleton.class);
        bind(LiteNodeExecutor.class).in(Singleton.class);
        bind(LiteNodeParser.class).in(Singleton.class);

        // chain state
        bind(ChainState.class).in(Singleton.class);
        bind(ChainStateService.class).in(Singleton.class);
        bind(SnapshotManager.class).in(Singleton.class);
        bind(DaoParamService.class).in(Singleton.class);
        bind(JsonBlockChainExporter.class).in(Singleton.class);

        // blockchain parser
        bind(GenesisTxController.class).in(Singleton.class);
        bind(GenesisTxOutputController.class).in(Singleton.class);
        bind(BsqTxController.class).in(Singleton.class);
        bind(TxInputsController.class).in(Singleton.class);
        bind(TxInputController.class).in(Singleton.class);
        bind(TxOutputsController.class).in(Singleton.class);
        bind(TxOutputController.class).in(Singleton.class);
        bind(OpReturnController.class).in(Singleton.class);
        bind(OpReturnProposalController.class).in(Singleton.class);
        bind(OpReturnCompReqController.class).in(Singleton.class);
        bind(OpReturnBlindVoteController.class).in(Singleton.class);
        bind(OpReturnVoteRevealController.class).in(Singleton.class);

        bind(PeriodService.class).in(Singleton.class);

        // proposals
        bind(ProposalService.class).in(Singleton.class);
        bind(ProposalListService.class).in(Singleton.class);
        bind(MyProposalService.class).in(Singleton.class);
        bind(ProposalPayloadValidator.class).in(Singleton.class);
        bind(CompensationRequestService.class).in(Singleton.class);
        bind(CompensationRequestPayloadValidator.class).in(Singleton.class);
        bind(GenericProposalService.class).in(Singleton.class);

        // vote
        bind(MyVoteService.class).in(Singleton.class);
        bind(BlindVoteService.class).in(Singleton.class);
        bind(BlindVoteValidator.class).in(Singleton.class);
        bind(VoteRevealService.class).in(Singleton.class);
        bind(VoteResultService.class).in(Singleton.class);
        bind(IssuanceService.class).in(Singleton.class);

        // constants
        String genesisTxId = environment.getProperty(DaoOptionKeys.GENESIS_TX_ID, String.class, ChainStateService.BTC_GENESIS_TX_ID);
        bind(String.class).annotatedWith(Names.named(DaoOptionKeys.GENESIS_TX_ID)).toInstance(genesisTxId);

        Integer genesisBlockHeight = environment.getProperty(DaoOptionKeys.GENESIS_BLOCK_HEIGHT, Integer.class, ChainStateService.BTC_GENESIS_BLOCK_HEIGHT);
        bind(Integer.class).annotatedWith(Names.named(DaoOptionKeys.GENESIS_BLOCK_HEIGHT)).toInstance(genesisBlockHeight);

        // options
        bindConstant().annotatedWith(named(DaoOptionKeys.RPC_USER)).to(environment.getRequiredProperty(DaoOptionKeys.RPC_USER));
        bindConstant().annotatedWith(named(DaoOptionKeys.RPC_PASSWORD)).to(environment.getRequiredProperty(DaoOptionKeys.RPC_PASSWORD));
        bindConstant().annotatedWith(named(DaoOptionKeys.RPC_PORT)).to(environment.getRequiredProperty(DaoOptionKeys.RPC_PORT));
        bindConstant().annotatedWith(named(DaoOptionKeys.RPC_BLOCK_NOTIFICATION_PORT))
                .to(environment.getRequiredProperty(DaoOptionKeys.RPC_BLOCK_NOTIFICATION_PORT));
        bindConstant().annotatedWith(named(DaoOptionKeys.DUMP_BLOCKCHAIN_DATA))
                .to(environment.getRequiredProperty(DaoOptionKeys.DUMP_BLOCKCHAIN_DATA));
        bindConstant().annotatedWith(named(DaoOptionKeys.FULL_DAO_NODE))
                .to(environment.getRequiredProperty(DaoOptionKeys.FULL_DAO_NODE));
    }
}

