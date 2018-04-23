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

package bisq.core.dao.votereveal;

import bisq.core.btc.exceptions.TransactionVerificationException;
import bisq.core.btc.exceptions.WalletException;
import bisq.core.btc.wallet.BsqWalletService;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.btc.wallet.TxBroadcastException;
import bisq.core.btc.wallet.TxBroadcastTimeoutException;
import bisq.core.btc.wallet.TxBroadcaster;
import bisq.core.btc.wallet.TxMalleabilityException;
import bisq.core.btc.wallet.WalletsManager;
import bisq.core.dao.blindvote.BlindVote;
import bisq.core.dao.blindvote.BlindVoteConsensus;
import bisq.core.dao.blindvote.BlindVoteList;
import bisq.core.dao.blindvote.BlindVoteService;
import bisq.core.dao.myvote.MyBlindVoteService;
import bisq.core.dao.myvote.MyVote;
import bisq.core.dao.period.PeriodService;
import bisq.core.dao.period.Phase;
import bisq.core.dao.state.StateService;
import bisq.core.dao.state.blockchain.TxOutput;

import bisq.common.util.Utilities;

import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;

import javax.inject.Inject;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import java.io.IOException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

//TODO case that user misses reveal phase not impl. yet


// TODO We could also broadcast the winning list at the moment the reveal period is over and have the break
// interval as time buffer for all nodes to receive that winning list. All nodes which are in sync with the
// majority data view can broadcast. That way it will become a very unlikely case that a node is missing
// data.

@Slf4j
public class VoteRevealService {
    private final StateService stateService;
    private final BlindVoteService blindVoteService;
    private final MyBlindVoteService myBlindVoteService;
    private final PeriodService periodService;
    private final BsqWalletService bsqWalletService;
    private final BtcWalletService btcWalletService;
    private final WalletsManager walletsManager;

    @Getter
    private final ObservableList<VoteRevealException> voteRevealExceptions = FXCollections.observableArrayList();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public VoteRevealService(StateService stateService,
                             BlindVoteService blindVoteService,
                             MyBlindVoteService myBlindVoteService,
                             PeriodService periodService,
                             BsqWalletService bsqWalletService,
                             BtcWalletService btcWalletService,
                             WalletsManager walletsManager) {
        this.stateService = stateService;
        this.blindVoteService = blindVoteService;
        this.myBlindVoteService = myBlindVoteService;
        this.periodService = periodService;
        this.bsqWalletService = bsqWalletService;
        this.btcWalletService = btcWalletService;
        this.walletsManager = walletsManager;

        voteRevealExceptions.addListener((ListChangeListener<VoteRevealException>) c -> {
            c.next();
            if (c.wasAdded())
                c.getAddedSubList().forEach(exception -> log.error(exception.toString()));
        });

        periodService.addPeriodStateChangeListener(chainHeight -> {
            // do we want call before parser?
            maybeRevealVotes(chainHeight);
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void start() {
        maybeRevealVotes(periodService.getChainHeight());
    }

    public BlindVoteList getSortedBlindVoteListOfCycle() {
        return getSortedBlindVoteListOfCycle(blindVoteService.getBlindVoteList());
    }

    public BlindVoteList getSortedBlindVoteListOfCycle(BlindVoteList blindVoteList) {
        final List<BlindVote> list = blindVoteList.stream()
                .filter(blindVote -> blindVoteService.isBlindVoteValid(blindVote, blindVoteList.getList()))
                .collect(Collectors.toList());
        if (list.isEmpty())
            log.warn("sortBlindVoteList is empty");
        BlindVoteConsensus.sortBlindVoteList(list);
        log.info("getSortedBlindVoteListForCurrentCycle list={}", list);
        return new BlindVoteList(list);
    }

    public byte[] getHashOfBlindVoteList() {
        BlindVoteList list = getSortedBlindVoteListOfCycle();
        return VoteRevealConsensus.getHashOfBlindVoteList(list);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Creation of vote reveal tx is done without user activity!
    // We create automatically the vote reveal tx when we enter the reveal phase of the current cycle when
    // the blind vote was created in case we have not done it already.
    // The voter need to be at least once online in the reveal phase when he has a blind vote created,
    // otherwise his vote becomes invalid and his locked stake will get unlocked
    private void maybeRevealVotes(int chainHeight) {
        if (periodService.getPhaseForHeight(chainHeight) == Phase.VOTE_REVEAL) {
            myBlindVoteService.getMyVoteList().stream()
                    .filter(myVote -> myVote.getRevealTxId() == null) // we have not already revealed
                    .filter(myVote -> periodService.isTxInPhase(myVote.getTxId(), Phase.BLIND_VOTE))
                    .filter(myVote -> periodService.isTxInCorrectCycle(myVote.getTxId(), chainHeight))
                    .forEach(myVote -> {
                        // We handle the exception here inside the stream iteration as we have not get triggered from an
                        // outside user intent anyway. We keep errors in a observable list so clients can observe that to
                        // get notified if anything went wrong.
                        try {
                            revealVote(myVote, chainHeight);
                        } catch (IOException | WalletException | TransactionVerificationException
                                | InsufficientMoneyException e) {
                            voteRevealExceptions.add(new VoteRevealException("Exception at calling revealVote.",
                                    e, myVote.getTxId()));
                        } catch (VoteRevealException e) {
                            voteRevealExceptions.add(e);
                        }
                    });
        }
    }

    private void revealVote(MyVote myVote, int chainHeight) throws IOException, WalletException,
            InsufficientMoneyException, TransactionVerificationException, VoteRevealException {
        // We collect all valid blind vote items we received via the p2p network.
        // It might be that different nodes have a different collection of those items.
        // To ensure we get a consensus of the data for later calculating the result we will put a hash of each
        // voters  blind vote collection into the opReturn data and check for a majority at issuance time.
        // The voters "vote" with their stake at the reveal tx for their version of the blind vote collection.
        byte[] hashOfBlindVoteList = getHashOfBlindVoteList();

        log.info("Sha256Ripemd160 hash of hashOfBlindVoteList " + Utilities.bytesAsHexString(hashOfBlindVoteList));
        byte[] opReturnData = VoteRevealConsensus.getOpReturnData(hashOfBlindVoteList, myVote.getSecretKey());

        // We search for my unspent stake output.
        // myVote is already tested if it is in current cycle at maybeRevealVotes
        final Set<TxOutput> blindVoteStakeTxOutputs = stateService.getUnspentBlindVoteStakeTxOutputs();
        // We expect that the blind vote tx and stake output is available. If not we throw an exception.
        TxOutput stakeTxOutput = blindVoteStakeTxOutputs.stream()
                .filter(txOutput -> txOutput.getTxId().equals(myVote.getTxId()))
                .findFirst()
                .orElseThrow(() -> new VoteRevealException("stakeTxOutput is not found for myVote.", myVote));

        // TxOutput has to be in the current cycle. Phase is checked in the parser anyway.
        if (periodService.isTxInCorrectCycle(stakeTxOutput.getTxId(), chainHeight)) {
            Transaction voteRevealTx = getVoteRevealTx(stakeTxOutput, opReturnData);
            log.info("voteRevealTx={}", voteRevealTx);
            walletsManager.publishAndCommitBsqTx(voteRevealTx, new TxBroadcaster.Callback() {
                @Override
                public void onSuccess(Transaction transaction) {
                    log.info("voteRevealTx successfully broadcasted.");
                    myBlindVoteService.applyRevealTxId(myVote, voteRevealTx.getHashAsString());

                    //Republish list of blind votes to gain more resilience
                    blindVoteService.republishAllBlindVotesOfCycle();
                }

                @Override
                public void onTimeout(TxBroadcastTimeoutException exception) {
                    log.error(exception.toString());
                    // TODO handle
                    voteRevealExceptions.add(new VoteRevealException("Publishing of voteRevealTx failed.",
                            exception, voteRevealTx));
                }

                @Override
                public void onTxMalleability(TxMalleabilityException exception) {
                    log.error(exception.toString());
                    // TODO handle
                    voteRevealExceptions.add(new VoteRevealException("Publishing of voteRevealTx failed.",
                            exception, voteRevealTx));
                }

                @Override
                public void onFailure(TxBroadcastException exception) {
                    log.error(exception.toString());
                    // TODO handle
                    voteRevealExceptions.add(new VoteRevealException("Publishing of voteRevealTx failed.",
                            exception, voteRevealTx));
                }
            });
        } else {
            final String msg = "Tx of stake out put is not in our cycle. That must not happen.";
            log.error("{}. chainHeight={},  blindVoteTxId()={}", msg, chainHeight, myVote.getTxId());
            voteRevealExceptions.add(new VoteRevealException(msg,
                    stakeTxOutput.getTxId()));
        }
    }

    private Transaction getVoteRevealTx(TxOutput stakeTxOutput, byte[] opReturnData)
            throws InsufficientMoneyException, WalletException, TransactionVerificationException {
        Transaction preparedTx = bsqWalletService.getPreparedVoteRevealTx(stakeTxOutput);
        Transaction txWithBtcFee = btcWalletService.completePreparedVoteRevealTx(preparedTx, opReturnData);
        return bsqWalletService.signTx(txWithBtcFee);
    }
}