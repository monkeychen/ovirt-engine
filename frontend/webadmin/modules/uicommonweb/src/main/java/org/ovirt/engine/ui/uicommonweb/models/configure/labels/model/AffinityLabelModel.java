package org.ovirt.engine.ui.uicommonweb.models.configure.labels.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.ovirt.engine.core.common.action.ActionType;
import org.ovirt.engine.core.common.action.LabelActionParameters;
import org.ovirt.engine.core.common.businessentities.Label;
import org.ovirt.engine.core.common.businessentities.comparators.NameableComparator;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.ui.frontend.Frontend;
import org.ovirt.engine.ui.uicommonweb.UICommand;
import org.ovirt.engine.ui.uicommonweb.dataprovider.AsyncDataProvider;
import org.ovirt.engine.ui.uicommonweb.models.EntityModel;
import org.ovirt.engine.ui.uicommonweb.models.ListModel;
import org.ovirt.engine.ui.uicommonweb.models.Model;
import org.ovirt.engine.ui.uicommonweb.models.configure.scheduling.affinity_groups.HostsSelectionModel;
import org.ovirt.engine.ui.uicommonweb.models.configure.scheduling.affinity_groups.VmsSelectionModel;
import org.ovirt.engine.ui.uicommonweb.validation.I18NNameValidation;
import org.ovirt.engine.ui.uicommonweb.validation.IValidation;
import org.ovirt.engine.ui.uicommonweb.validation.LengthValidation;
import org.ovirt.engine.ui.uicommonweb.validation.NotEmptyValidation;

public abstract class AffinityLabelModel extends Model {
    private final Label affinityLabel;
    private final ListModel<?> sourceListModel;
    private final ActionType saveActionType;

    private EntityModel<String> name;
    private final Guid clusterId;
    private final String clusterName;

    private VmsSelectionModel vmsSelectionModel;
    private HostsSelectionModel hostsSelectionModel;

    public AffinityLabelModel(Label affinityLabel, ListModel<?> sourceListModel,
                              ActionType saveActionType,
                              Guid clusterId,
                              String clusterName) {
        this.affinityLabel = affinityLabel;
        this.sourceListModel = sourceListModel;
        this.saveActionType = saveActionType;
        this.clusterId = clusterId;
        this.clusterName = clusterName;

        setName(new EntityModel<>());

        setVmsSelectionModel(new VmsSelectionModel());
        setHostsSelectionModel(new HostsSelectionModel());

        addCommands();
    }

    public void init() {
        startProgress();

        AsyncDataProvider.getInstance().getVmListByClusterName(new AsyncQuery<>(vmList -> {
            Set<Guid> vmIds = getAffinityLabel().getVms();
            getVmsSelectionModel().init(vmList, vmIds != null ? new ArrayList<>(vmIds) : new ArrayList<>());
            stopProgressOnVmsAndHostsInit();
        }), clusterName);

        AsyncDataProvider.getInstance().getHostListByClusterId(new AsyncQuery<>(hostList -> {
            Set<Guid> hostIds = getAffinityLabel().getHosts();
            Collections.sort(hostList, new NameableComparator());
            getHostsSelectionModel().init(hostList, hostIds != null ? new ArrayList<>(hostIds) : new ArrayList<>());
            stopProgressOnVmsAndHostsInit();
        }), clusterId);
    }

    private void stopProgressOnVmsAndHostsInit() {
        if (getVmsSelectionModel().isInitialized() && getHostsSelectionModel().isInitialized()) {
            stopProgress();
        }
    }

    protected Label getAffinityLabel() {
        return affinityLabel;
    }

    protected void addCommands() {
        getCommands().add(UICommand.createDefaultOkUiCommand("OnSave", this)); //$NON-NLS-1$
        getCommands().add(UICommand.createCancelUiCommand("Cancel", this)); //$NON-NLS-1$
    }

    public EntityModel<String> getName() {
        return name;
    }

    private void setName(EntityModel<String> name) {
        this.name = name;
    }

    public VmsSelectionModel getVmsSelectionModel() {
        return vmsSelectionModel;
    }

    private void setVmsSelectionModel(VmsSelectionModel vmsSelectionModel) {
        this.vmsSelectionModel = vmsSelectionModel;
    }

    public HostsSelectionModel getHostsSelectionModel() {
        return hostsSelectionModel;
    }

    private void setHostsSelectionModel(HostsSelectionModel hostsSelectionModel) {
        this.hostsSelectionModel = hostsSelectionModel;
    }

    protected void cancel() {
        sourceListModel.setWindow(null);
        sourceListModel.setConfirmWindow(null);
    }

    void onSave() {
        if (!validate() || (getProgress() != null)) {
            return;
        }

        Label label = getAffinityLabel();
        label.setName(getName().getEntity());

        label.setVms(new HashSet<>(getVmsSelectionModel().getSelectedVmIds()));
        label.setHosts(new HashSet<>(getHostsSelectionModel().getSelectedHostIds()));

        startProgress();

        Frontend.getInstance().runAction(saveActionType,
                new LabelActionParameters(label),
                result -> {
                    stopProgress();
                    if (result != null && result.getReturnValue() != null && result.getReturnValue().getSucceeded()) {
                        cancel();
                    }
                },
                this);
    }

    protected boolean validate() {
        getName().validateEntity(new IValidation[] {
                new NotEmptyValidation(),
                new LengthValidation(255),
                new I18NNameValidation() });

        return getName().getIsValid();
    }

    @Override
    public void executeCommand(UICommand command) {
        super.executeCommand(command);

        if ("OnSave".equals(command.getName())) { //$NON-NLS-1$
            onSave();
        } else if ("Cancel".equals(command.getName())) { //$NON-NLS-1$
            cancel();
        }
    }
}
