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

package bisq.core.dao.consensus.proposal;

import bisq.core.app.BisqEnvironment;
import bisq.core.dao.consensus.ballot.Ballot;
import bisq.core.dao.consensus.ballot.BallotFactory;
import bisq.core.dao.consensus.ballot.BallotList;
import bisq.core.dao.consensus.period.PeriodService;
import bisq.core.dao.consensus.period.Phase;
import bisq.core.dao.consensus.state.StateChangeEventsProvider;
import bisq.core.dao.consensus.state.StateService;
import bisq.core.dao.consensus.state.blockchain.Tx;
import bisq.core.dao.consensus.state.blockchain.TxBlock;
import bisq.core.dao.consensus.state.events.ProposalEvent;
import bisq.core.dao.consensus.state.events.StateChangeEvent;

import bisq.network.p2p.storage.HashMapChangedListener;
import bisq.network.p2p.storage.P2PDataStorage;
import bisq.network.p2p.storage.payload.ProtectedStorageEntry;
import bisq.network.p2p.storage.payload.ProtectedStoragePayload;

import bisq.common.app.DevEnv;
import bisq.common.proto.persistable.PersistedDataHost;
import bisq.common.storage.Storage;

import javax.inject.Inject;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Listens on the StateService for new txBlocks and for Proposal from the P2P network.
 * We configure the P2P network listener thread context aware so we get our listeners called in the parser
 * thread created by the single threaded executor created in the NodeExecutor.
 * <p>
 * When the last block of the break after the proposal phase is parsed we will add all proposalPayloads we have received
 * from the P2P network to the stateChangeEvents and pass that back to the stateService where they get accumulated and be
 * included in that block's stateChangeEvents.
 * <p>
 * We maintain as well the openBallotList which gets persisted independently at the moment when the proposal arrives
 * and remove the proposal at the moment we put it to the stateChangeEvent.
 */
@Slf4j
public class ProposalService implements PersistedDataHost, StateChangeEventsProvider {
    private final P2PDataStorage p2pDataStorage;
    private final PeriodService periodService;
    private final StateService stateService;
    private final ProposalValidator proposalValidator;
    private final Storage<BallotList> storage;

    @Getter
    private final BallotList ballotList = new BallotList();

    @Inject
    public ProposalService(P2PDataStorage p2pDataStorage,
                           PeriodService periodService,
                           StateService stateService,
                           ProposalValidator proposalValidator,
                           Storage<BallotList> storage) {
        this.p2pDataStorage = p2pDataStorage;
        this.periodService = periodService;
        this.stateService = stateService;
        this.proposalValidator = proposalValidator;
        this.storage = storage;

        stateService.registerStateChangeEventsProvider(this);

        p2pDataStorage.addHashMapChangedListener(new HashMapChangedListener() {
            @Override
            public boolean executeOnUserThread() {
                return false;
            }

            @Override
            public void onAdded(ProtectedStorageEntry entry) {
                onAddedProtectedStorageEntry(entry, true);
            }

            @Override
            public void onRemoved(ProtectedStorageEntry entry) {
                onRemovedProtectedStorageEntry(entry);
            }
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PersistedDataHost
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We get called from the user thread at startup.
    @Override
    public void readPersisted() {
        if (BisqEnvironment.isDAOActivatedAndBaseCurrencySupportingBsq()) {
            BallotList persisted = storage.initAndGetPersisted(ballotList, 20);
            if (persisted != null) {
                this.ballotList.clear();
                this.ballotList.addAll(persisted.getList());
            }
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void start() {
        // We apply already existing protectedStorageEntries
        p2pDataStorage.getMap().values()
                .forEach(entry -> onAddedProtectedStorageEntry(entry, false));
    }

    public boolean isInPhaseOrUnconfirmed(Optional<Tx> optionalProposalTx, String txId, Phase phase,
                                          int blockHeight) {
        return isUnconfirmed(txId) ||
                optionalProposalTx.filter(tx -> periodService.isTxInPhase(txId, phase))
                        .filter(tx -> periodService.isTxInCorrectCycle(tx.getBlockHeight(), blockHeight))
                        .isPresent();
    }

    // We use the StateService not the TransactionConfidence from the wallet to not mix 2 different and possibly
    // out of sync data sources.
    public boolean isUnconfirmed(String txId) {
        return !stateService.getTx(txId).isPresent();
    }

    public void persist() {
        storage.queueUpForSave();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // StateChangeEventsProvider
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public Set<StateChangeEvent> provideStateChangeEvents(TxBlock txBlock) {

        // TODO remove
        Set<StateChangeEvent> stateChangeEvents = new HashSet<>();
        Set<Proposal> toRemove = new HashSet<>();
        ballotList.stream()
                .map(Ballot::getProposal)
                .map(proposalPayload -> {
                    final Optional<StateChangeEvent> optional = getAddProposalPayloadEvent(proposalPayload, txBlock.getHeight());

                    // If we are in the correct block and we add a ProposalEvent to the state we remove
                    // the proposal from our list after we have completed iteration.
                    //TODO activate once we persist state
                       /* if (optional.isPresent())
                            toRemove.add(proposal);*/

                    return optional;
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(stateChangeEvents::add);

        // We remove those proposals we have just added to the state.
        toRemove.forEach(this::removeProposalFromList);

        return stateChangeEvents;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onAddedProtectedStorageEntry(ProtectedStorageEntry protectedStorageEntry, boolean storeLocally) {
        final ProtectedStoragePayload protectedStoragePayload = protectedStorageEntry.getProtectedStoragePayload();
        if (protectedStoragePayload instanceof ProposalPayload) {
            final ProposalPayload proposalPayload = (ProposalPayload) protectedStoragePayload;
            if (!listContains(proposalPayload.getProposal())) {
                // For adding a proposalPayload we need to be before the last block in BREAK1 as in the last block at BREAK1
                // we write our proposals to the state.
                if (isInToleratedBlockRange(stateService.getChainHeight())) {
                    log.info("We received a Proposal from the P2P network. Proposal.uid=" +
                            proposalPayload.getProposal().getUid());
                    Ballot ballot = BallotFactory.getBallotFromProposal(proposalPayload.getProposal());
                    ballotList.add(ballot);

                    if (storeLocally)
                        persist();
                } else {
                    log.warn("We are not in the tolerated phase anymore and ignore that " +
                                    "proposalPayload. proposalPayload={}, height={}", proposalPayload,
                            stateService.getChainHeight());
                }
            } else {
                if (storeLocally)
                    log.debug("We have that proposalPayload already in our list. proposalPayload={}", proposalPayload);
            }
        }
    }

    // We allow removal only if we are in the correct phase and cycle or the tx is unconfirmed
    private void onRemovedProtectedStorageEntry(ProtectedStorageEntry entry) {
        final ProtectedStoragePayload protectedStoragePayload = entry.getProtectedStoragePayload();
        if (protectedStoragePayload instanceof Proposal) {
            final Proposal proposal = (Proposal) protectedStoragePayload;
            findProposal(proposal)
                    .ifPresent(payload -> {
                        if (isInPhaseOrUnconfirmed(stateService.getTx(payload.getTxId()), payload.getTxId(),
                                Phase.PROPOSAL,
                                stateService.getChainHeight())) {
                            removeProposalFromList(proposal);
                        } else {
                            final String msg = "onRemoved called of a Ballot which is outside of the Request phase " +
                                    "is invalid and we ignore it.";
                            DevEnv.logErrorAndThrowIfDevMode(msg);
                        }
                    });
        }
    }

    // We add a ProposalEvent if the tx is already available and proposal and tx are valid.
    // We only add it after the proposal phase to avoid handling of remove operation (user can remove a proposal
    // during the proposal phase).
    // We use the last block in the BREAK1 phase to set all proposals for that cycle.
    // If a proposal would arrive later it will be ignored.
    private Optional<StateChangeEvent> getAddProposalPayloadEvent(Proposal proposal, int height) {
        return stateService.getTx(proposal.getTxId())
                .filter(tx -> isLastToleratedBlock(height))
                .filter(tx -> periodService.isTxInCorrectCycle(tx.getBlockHeight(), height))
                .filter(tx -> periodService.isInPhase(tx.getBlockHeight(), Phase.PROPOSAL))
                .filter(tx -> proposalValidator.isValid(proposal))
                .map(tx -> new ProposalEvent(proposal, height));
    }

    private boolean isLastToleratedBlock(int height) {
        return height == periodService.getLastBlockOfPhase(height, Phase.BREAK1);
    }

    private boolean isInToleratedBlockRange(int height) {
        return height < periodService.getLastBlockOfPhase(height, Phase.BREAK1);
    }

    private void removeProposalFromList(Proposal proposal) {
        if (ballotList.remove(proposal))
            persist();
        else
            log.warn("We called removeProposalFromList at a proposal which was not in our list");
    }

    private boolean listContains(Proposal proposal) {
        return findProposal(proposal).isPresent();
    }

    private Optional<Ballot> findProposal(Proposal proposal) {
        return ballotList.stream()
                .filter(proposal::equals)
                .findAny();
    }
}
