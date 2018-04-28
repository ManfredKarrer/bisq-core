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

package bisq.core.dao.voting.ballot.proposal;

import bisq.core.dao.voting.ballot.vote.VoteConsensusCritical;

import bisq.network.p2p.storage.payload.PersistableNetworkPayload;

import bisq.common.crypto.Hash;
import bisq.common.proto.persistable.PersistableEnvelope;

import io.bisq.generated.protobuffer.PB;

import com.google.protobuf.ByteString;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.concurrent.Immutable;

/**
 * Wrapper for proposal to be stored in the append-only ProposalStore storage.
 */
@Immutable
@Slf4j
@Getter
@EqualsAndHashCode
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class ProposalPayload implements PersistableNetworkPayload, PersistableEnvelope, VoteConsensusCritical {
    private Proposal proposal;
    protected final byte[] hash;

    public ProposalPayload(Proposal proposal) {
        this(proposal, Hash.getSha256Ripemd160hash(proposal.toProtoMessage().toByteArray()));
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private ProposalPayload(Proposal proposal, byte[] hash) {
        this.proposal = proposal;
        this.hash = hash;
    }

    private PB.ProposalPayload.Builder getProposalBuilder() {
        return PB.ProposalPayload.newBuilder()
                .setProposal(proposal.toProtoMessage())
                .setHash(ByteString.copyFrom(hash));
    }

    @Override
    public PB.PersistableNetworkPayload toProtoMessage() {
        return PB.PersistableNetworkPayload.newBuilder().setProposalPayload(getProposalBuilder()).build();
    }

    public static ProposalPayload fromProto(PB.ProposalPayload proto) {
        return new ProposalPayload(Proposal.fromProto(proto.getProposal()), proto.getHash().toByteArray());
    }

    public PB.ProposalPayload toProtoProposalPayload() {
        return getProposalBuilder().build();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PersistableNetworkPayload
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public boolean verifyHashSize() {
        return hash.length == 20;
    }

    @Override
    public byte[] getHash() {
        return hash;
    }
}
