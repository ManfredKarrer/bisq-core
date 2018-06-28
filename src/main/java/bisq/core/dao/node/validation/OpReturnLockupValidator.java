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

package bisq.core.dao.node.validation;

import bisq.core.dao.state.StateService;
import bisq.core.dao.state.period.DaoPhase;
import bisq.core.dao.state.period.PeriodService;
import bisq.core.dao.voting.proposal.param.Param;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class OpReturnLockupValidator {
    private final StateService stateService;

    @Inject
    public OpReturnLockupValidator(StateService stateService) {
        this.stateService = stateService;
    }

    // We do not check the version as if we upgrade the a new version old clients would fail. Rather we need to make
    // a change backward compatible so that new clients can handle both versions and old clients are tolerant.
    boolean validate(byte[] opReturnData, int lockTime, int blockHeight, TxState txState) {
        return txState.getLockupOutput() != null && opReturnData.length == 5 &&
                lockTime >= stateService.getParamValue(Param.LOCKTIME_MIN, blockHeight) &&
                lockTime <= stateService.getParamValue(Param.LOCKTIME_MAX, blockHeight);
    }
}
