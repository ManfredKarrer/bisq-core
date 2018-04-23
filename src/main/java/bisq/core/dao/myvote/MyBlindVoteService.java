/*
 * This file is part of bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.dao.myvote;

import bisq.core.app.BisqEnvironment;
import bisq.core.btc.exceptions.TransactionVerificationException;
import bisq.core.btc.exceptions.WalletException;
import bisq.core.btc.wallet.BsqWalletService;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.btc.wallet.TxBroadcastException;
import bisq.core.btc.wallet.TxBroadcastTimeoutException;
import bisq.core.btc.wallet.TxBroadcaster;
import bisq.core.btc.wallet.TxMalleabilityException;
import bisq.core.btc.wallet.WalletsManager;
import bisq.core.dao.ballot.Ballot;
import bisq.core.dao.ballot.BallotList;
import bisq.core.dao.ballot.BallotListService;
import bisq.core.dao.blindvote.BlindVote;
import bisq.core.dao.blindvote.BlindVoteConsensus;
import bisq.core.dao.blindvote.BlindVotePayload;
import bisq.core.dao.blindvote.BlindVoteService;
import bisq.core.dao.period.PeriodService;
import bisq.core.dao.period.Phase;
import bisq.core.dao.proposal.param.ChangeParamService;
import bisq.core.dao.state.StateService;
import bisq.core.dao.state.blockchain.Tx;

import bisq.network.p2p.P2PService;

import bisq.common.UserThread;
import bisq.common.app.DevEnv;
import bisq.common.crypto.CryptoException;
import bisq.common.crypto.Encryption;
import bisq.common.crypto.KeyRing;
import bisq.common.handlers.ExceptionHandler;
import bisq.common.handlers.ResultHandler;
import bisq.common.proto.persistable.PersistedDataHost;
import bisq.common.storage.Storage;
import bisq.common.util.Utilities;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;

import javax.inject.Inject;

import javafx.beans.value.ChangeListener;

import javax.crypto.SecretKey;

import java.security.PublicKey;

import java.io.IOException;

import java.util.List;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates and published blind vote and blind vote tx. After broadcast it creates myVote which gets persisted and holds
 * all ballots.
 * Republished all my active myVotes at startup and applies the revealTxId to myVote once the reveal tx is published.
 * <p>
 * Executed from the user tread.
 */
@Slf4j
public class MyBlindVoteService implements PersistedDataHost {
    private final PeriodService periodService;
    private final StateService stateService;
    private final P2PService p2PService;
    private final WalletsManager walletsManager;
    private final BsqWalletService bsqWalletService;
    private final BtcWalletService btcWalletService;
    private final BallotListService ballotListService;
    private final BlindVoteService blindVoteService;
    private final ChangeParamService changeParamService;
    private final Storage<MyVoteList> storage;
    private final PublicKey signaturePubKey;

    private final MyVoteList myVoteList = new MyVoteList();
    private final ChangeListener<Number> numConnectedPeersListener;

    // This is the list we made a snapshot when we entered blindVote phase and we keep that until the voteResult
    // phase and if our version matches the majority we add it to the state.
    private final BallotList sortedBallotListForCycle = new BallotList();
    // The fee which is used in that cycle
    @Getter
    private Coin blindVoteFeeForCycle;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public MyBlindVoteService(PeriodService periodService,
                              StateService stateService,
                              P2PService p2PService,
                              WalletsManager walletsManager,
                              BsqWalletService bsqWalletService,
                              BtcWalletService btcWalletService,
                              BallotListService ballotListService,
                              BlindVoteService blindVoteService,
                              ChangeParamService changeParamService,
                              KeyRing keyRing,
                              Storage<MyVoteList> storage) {
        this.periodService = periodService;
        this.stateService = stateService;
        this.p2PService = p2PService;
        this.walletsManager = walletsManager;
        this.bsqWalletService = bsqWalletService;
        this.btcWalletService = btcWalletService;
        this.ballotListService = ballotListService;
        this.blindVoteService = blindVoteService;
        this.changeParamService = changeParamService;
        this.storage = storage;

        signaturePubKey = keyRing.getPubKeyRing().getSignaturePubKey();

        numConnectedPeersListener = (observable, oldValue, newValue) -> publishMyBlindVotesIfWellConnected();
        p2PService.getNumConnectedPeers().addListener(numConnectedPeersListener);

        periodService.addPeriodStateChangeListener(chainHeight -> {
            maybeMakeSnapshotForCycle();
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PersistedDataHost
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void readPersisted() {
        if (BisqEnvironment.isDAOActivatedAndBaseCurrencySupportingBsq()) {
            MyVoteList persisted = storage.initAndGetPersisted(myVoteList, 20);
            if (persisted != null) {
                this.myVoteList.clear();
                this.myVoteList.addAll(persisted.getList());
            }
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void start() {
        maybeMakeSnapshotForCycle();

        // Republish own active blindVotes once we are well connected
        publishMyBlindVotesIfWellConnected();
    }

    // For showing fee estimation in confirmation popup
    public Transaction getDummyBlindVoteTx(Coin stake, Coin fee)
            throws InsufficientMoneyException, WalletException, TransactionVerificationException {
        // We set dummy opReturn data
        return getBlindVoteTx(stake, fee, new byte[22]);
    }

    private Transaction getBlindVoteTx(Coin stake, Coin fee, byte[] opReturnData)
            throws InsufficientMoneyException, WalletException, TransactionVerificationException {
        Transaction preparedTx = bsqWalletService.getPreparedBlindVoteTx(fee, stake);
        Transaction txWithBtcFee = btcWalletService.completePreparedBlindVoteTx(preparedTx, opReturnData);
        return bsqWalletService.signTx(txWithBtcFee);
    }

    public void publishBlindVote(Coin stake, ResultHandler resultHandler, ExceptionHandler exceptionHandler) {
        try {
            log.info("BallotList used in blind vote. sortedBallotList={}", sortedBallotListForCycle);

            SecretKey secretKey = BlindVoteConsensus.getSecretKey();
            byte[] encryptedBallotList = BlindVoteConsensus.getEncryptedBallotList(sortedBallotListForCycle, secretKey);

            final byte[] hash = BlindVoteConsensus.getHashOfEncryptedProposalList(encryptedBallotList);
            log.info("Sha256Ripemd160 hash of encryptedBallotList: " + Utilities.bytesAsHexString(hash));
            byte[] opReturnData = BlindVoteConsensus.getOpReturnData(hash);

            final Transaction blindVoteTx = getBlindVoteTx(stake, blindVoteFeeForCycle, opReturnData);
            log.info("blindVoteTx={}", blindVoteTx);
            walletsManager.publishAndCommitBsqTx(blindVoteTx, new TxBroadcaster.Callback() {
                @Override
                public void onSuccess(Transaction transaction) {
                    onTxBroadcasted(encryptedBallotList, blindVoteTx, stake, resultHandler, exceptionHandler,
                            secretKey);
                }

                @Override
                public void onTimeout(TxBroadcastTimeoutException exception) {
                    // TODO handle
                    // We need to handle cases where a timeout happens and
                    // the tx might get broadcasted at a later restart!
                    // We need to be sure that in case of a failed tx the locked stake gets unlocked!
                    exceptionHandler.handleException(exception);
                }

                @Override
                public void onTxMalleability(TxMalleabilityException exception) {
                    // TODO handle
                    // We need to be sure that in case of a failed tx the locked stake gets unlocked!
                    exceptionHandler.handleException(exception);
                }

                @Override
                public void onFailure(TxBroadcastException exception) {
                    // TODO handle
                    // We need to be sure that in case of a failed tx the locked stake gets unlocked!
                    exceptionHandler.handleException(exception);
                }
            });
        } catch (CryptoException | TransactionVerificationException | InsufficientMoneyException |
                WalletException | IOException exception) {
            exceptionHandler.handleException(exception);
        }
    }

    public void applyRevealTxId(MyVote myVote, String voteRevealTxId) {
        myVote.setRevealTxId(voteRevealTxId);
        log.info("Applied revealTxId to myVote.\nmyVote={}\nvoteRevealTxId={}", myVote, voteRevealTxId);
        persist();
    }

    public List<MyVote> getMyVoteList() {
        return myVoteList.getList();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////


    private void onTxBroadcasted(byte[] encryptedBallotList, Transaction blindVoteTx, Coin stake,
                                 ResultHandler resultHandler, ExceptionHandler exceptionHandler,
                                 SecretKey secretKey) {
        BlindVote blindVote = new BlindVote(encryptedBallotList, blindVoteTx.getHashAsString(), stake.value);
        BlindVotePayload blindVotePayload = new BlindVotePayload(blindVote, signaturePubKey);

        blindVoteService.addMyBlindVote(blindVote);

        if (p2PService.addProtectedStorageEntry(blindVotePayload, true)) {
            log.info("Added blindVotePayload to P2P network.\nblindVotePayload={}", blindVotePayload);
            resultHandler.handleResult();
        } else {
            final String msg = "Adding of blindVotePayload to P2P network failed.\nblindVotePayload=" + blindVotePayload;
            log.error(msg);
            //TODO define specific exception
            exceptionHandler.handleException(new Exception(msg));
        }

        MyVote myVote = new MyVote(periodService.getChainHeight(), sortedBallotListForCycle, Encryption.getSecretKeyBytes(secretKey), blindVote);
        log.info("Add new MyVote to myVotesList list.\nMyVote=" + myVote);
        myVoteList.add(myVote);
        persist();
    }


    private void publishMyBlindVotesIfWellConnected() {
        // Delay a bit for localhost testing to not fail as isBootstrapped is false. Also better for production version
        // to avoid activity peaks at startup
        UserThread.runAfter(() -> {
            if ((p2PService.getNumConnectedPeers().get() > 4 && p2PService.isBootstrapped()) || DevEnv.isDevMode()) {
                p2PService.getNumConnectedPeers().removeListener(numConnectedPeersListener);
                publishMyBlindVotes();
            }
        }, 2);
    }

    private void publishMyBlindVotes() {
        getMyVoteList().stream()
                .filter(myVote -> periodService.isTxInPhase(myVote.getTxId(), Phase.BLIND_VOTE))
                .filter(myVote -> periodService.isTxInCorrectCycle(myVote.getTxId(), stateService.getChainHeight()))
                .forEach(myVote -> {
                    if (myVote.getRevealTxId() == null) {
                        BlindVotePayload blindVotePayload = new BlindVotePayload(myVote.getBlindVote(), signaturePubKey);
                        if (addBlindVoteToP2PNetwork(blindVotePayload)) {
                            log.info("Added BlindVotePayload to P2P network.\nBlindVotePayload={}", myVote.getBlindVote());
                        } else {
                            log.warn("Adding of BlindVotePayload to P2P network failed.\nBlindVotePayload={}", myVote.getBlindVote());
                        }
                    } else {
                        final String msg = "revealTxId have to be null at publishMyBlindVotes.\nmyVote=" + myVote;
                        //DevEnv.logErrorAndThrowIfDevMode(msg);
                    }
                });
    }

    private boolean addBlindVoteToP2PNetwork(BlindVotePayload blindVotePayload) {
        return p2PService.addProtectedStorageEntry(blindVotePayload, true);
    }

    private void persist() {
        storage.queueUpForSave();
    }


    private void maybeMakeSnapshotForCycle() {
        int chainHeight = periodService.getChainHeight();

        if (periodService.getFirstBlockOfPhase(chainHeight, Phase.PROPOSAL) == chainHeight) {
            blindVoteFeeForCycle = BlindVoteConsensus.getFee(changeParamService, stateService.getChainHeight());
        }

        if (periodService.getFirstBlockOfPhase(chainHeight, Phase.BLIND_VOTE) == chainHeight) {
            sortedBallotListForCycle.clear();
            final List<Ballot> ballots = ballotListService.getBallotList().stream()
                    .filter(ballot -> stateService.getTx(ballot.getTxId()).isPresent())
                    .filter(ballot -> isTxInProposalPhaseAndCycle(stateService.getTx(ballot.getTxId()).get(), chainHeight))
                    .collect(Collectors.toList());
            BlindVoteConsensus.sortProposalList(ballots);
            sortedBallotListForCycle.addAll(ballots);
        }
    }

    private boolean isTxInProposalPhaseAndCycle(Tx tx, int chainHeight) {
        return periodService.isInPhase(tx.getBlockHeight(), Phase.PROPOSAL) &&
                periodService.isTxInCorrectCycle(tx.getBlockHeight(), chainHeight);
    }
}