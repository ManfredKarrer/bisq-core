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

package bisq.core.dao.param;

import bisq.core.app.BisqEnvironment;
import bisq.core.dao.state.events.ChangeParamEvent;

import bisq.common.app.DevEnv;
import bisq.common.proto.persistable.PersistedDataHost;
import bisq.common.storage.Storage;

import javax.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Maintains a list of Dao parameter change events which gets created in case a Parameter change proposal gets accepted
 * in the voting process. The events contain the blockHeight when they become valid. When obtaining the value of an
 * parameter we look up the latest change in case we have any changeEvents, otherwise we use the default value from the
 * DaoParam.
 * We do not need to sync that data structure with the StateService or have handling for snapshots because changes by
 * voting are safe against blockchain re-orgs as we use sufficient breaks between the phases. So even in case the
 * BsqBlockchain gets changed due a re-org we will not suffer from a stale state.
 */
@Slf4j
public class DaoParamService implements PersistedDataHost {
    private final Storage<ChangeParamEventList> storage;
    @Getter
    private final ChangeParamEventList changeParamEventList = new ChangeParamEventList();

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////


    @Inject
    public DaoParamService(Storage<ChangeParamEventList> storage) {
        this.storage = storage;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // PersistedDataHost
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void readPersisted() {
        if (BisqEnvironment.isDAOActivatedAndBaseCurrencySupportingBsq()) {
            ChangeParamEventList persisted = storage.initAndGetPersisted(changeParamEventList, 20);
            if (persisted != null) {
                this.changeParamEventList.clear();
                this.changeParamEventList.addAll(persisted.getList());
            }
        }
    }
    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onAllServicesInitialized() {
        persist();
    }

    public void shutDown() {
    }

    public long getDaoParamValue(DaoParam daoParam, int blockHeight) {
        final List<ChangeParamEvent> sortedFilteredList = getParamChangeEventListForParam(daoParam).stream()
                .filter(e -> e.getHeight() <= blockHeight)
                .sorted(Comparator.comparing(ChangeParamEvent::getHeight))
                .collect(Collectors.toList());

        if (sortedFilteredList.isEmpty()) {
            return daoParam.getDefaultValue();
        } else {
            final ChangeParamEvent mostRecentEvent = sortedFilteredList.get(sortedFilteredList.size() - 1);
            return mostRecentEvent.getValue();
        }
    }

    public void addChangeEvent(ChangeParamEvent event) {
        if (!changeParamEventList.contains(event)) {
            if (!hasConflictingValue(getParamChangeEventListForParam(event.getDaoParam()), event)) {
                changeParamEventList.add(event);
            } else {
                String msg = "We have already an ChangeParamEvent with the same blockHeight but a different value. " +
                        "That must not happen.";
                DevEnv.logErrorAndThrowIfDevMode(msg);
            }
        } else {
            log.warn("We have that ChangeParamEvent already in our list. ChangeParamEvent={}", event);
        }
        persist();
    }

    private List<ChangeParamEvent> getParamChangeEventListForParam(DaoParam daoParam) {
        return changeParamEventList.getList().stream()
                .filter(e -> e.getDaoParam() == daoParam)
                .collect(Collectors.toList());
    }

    private boolean hasConflictingValue(List<ChangeParamEvent> list, ChangeParamEvent event) {
        return list.stream()
                .filter(e -> e.getHeight() == event.getHeight())
                .anyMatch(e -> e.getValue() != event.getValue());
    }

    private void persist() {
        storage.queueUpForSave();
    }
}
