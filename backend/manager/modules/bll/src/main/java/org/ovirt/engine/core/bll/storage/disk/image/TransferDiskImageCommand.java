package org.ovirt.engine.core.bll.storage.disk.image;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.enterprise.inject.Instance;
import javax.enterprise.inject.Typed;
import javax.inject.Inject;

import org.ovirt.engine.core.bll.CommandActionState;
import org.ovirt.engine.core.bll.LockMessagesMatchUtil;
import org.ovirt.engine.core.bll.NonTransactiveCommandAttribute;
import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.quota.QuotaConsumptionParameter;
import org.ovirt.engine.core.bll.quota.QuotaStorageConsumptionParameter;
import org.ovirt.engine.core.bll.quota.QuotaStorageDependent;
import org.ovirt.engine.core.bll.tasks.CommandCoordinatorUtil;
import org.ovirt.engine.core.bll.tasks.CommandHelper;
import org.ovirt.engine.core.bll.tasks.interfaces.CommandCallback;
import org.ovirt.engine.core.bll.utils.PermissionSubject;
import org.ovirt.engine.core.bll.validator.storage.DiskImagesValidator;
import org.ovirt.engine.core.bll.validator.storage.DiskValidator;
import org.ovirt.engine.core.bll.validator.storage.StorageDomainValidator;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.FeatureSupported;
import org.ovirt.engine.core.common.VdcObjectType;
import org.ovirt.engine.core.common.action.ActionReturnValue;
import org.ovirt.engine.core.common.action.ActionType;
import org.ovirt.engine.core.common.action.AddDiskParameters;
import org.ovirt.engine.core.common.action.LockProperties;
import org.ovirt.engine.core.common.action.RemoveDiskParameters;
import org.ovirt.engine.core.common.action.TransferDiskImageParameters;
import org.ovirt.engine.core.common.action.TransferImageStatusParameters;
import org.ovirt.engine.core.common.businessentities.ActionGroup;
import org.ovirt.engine.core.common.businessentities.StorageDomain;
import org.ovirt.engine.core.common.businessentities.VDS;
import org.ovirt.engine.core.common.businessentities.VM;
import org.ovirt.engine.core.common.businessentities.storage.DiskImage;
import org.ovirt.engine.core.common.businessentities.storage.ImageStatus;
import org.ovirt.engine.core.common.businessentities.storage.ImageTicketInformation;
import org.ovirt.engine.core.common.businessentities.storage.ImageTransfer;
import org.ovirt.engine.core.common.businessentities.storage.ImageTransferPhase;
import org.ovirt.engine.core.common.businessentities.storage.TransferType;
import org.ovirt.engine.core.common.businessentities.storage.VolumeFormat;
import org.ovirt.engine.core.common.config.Config;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.common.errors.EngineException;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.common.locks.LockingGroup;
import org.ovirt.engine.core.common.utils.Pair;
import org.ovirt.engine.core.common.utils.SizeConverter;
import org.ovirt.engine.core.common.vdscommands.AddImageTicketVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.ExtendImageTicketVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.GetImageTicketVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.ImageActionsVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.PrepareImageVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.RemoveImageTicketVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.SetVolumeLegalityVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.common.vdscommands.VDSReturnValue;
import org.ovirt.engine.core.compat.CommandStatus;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogDirector;
import org.ovirt.engine.core.dao.DiskDao;
import org.ovirt.engine.core.dao.ImageDao;
import org.ovirt.engine.core.dao.ImageTransferDao;
import org.ovirt.engine.core.dao.StorageDomainDao;
import org.ovirt.engine.core.dao.VdsDao;
import org.ovirt.engine.core.dao.VmDao;
import org.ovirt.engine.core.utils.EngineLocalConfig;
import org.ovirt.engine.core.utils.JsonHelper;
import org.ovirt.engine.core.utils.crypt.EngineEncryptionUtils;
import org.ovirt.engine.core.uutils.crypto.ticket.TicketEncoder;
import org.ovirt.engine.core.uutils.net.HttpURLConnectionBuilder;
import org.ovirt.engine.core.vdsbroker.vdsbroker.PrepareImageReturn;

@NonTransactiveCommandAttribute
public class TransferDiskImageCommand<T extends TransferDiskImageParameters> extends BaseImagesCommand<T> implements QuotaStorageDependent {
    // Some token/"claim" names are from RFC 7519 on JWT
    private static final String TOKEN_NOT_BEFORE = "nbf";
    private static final String TOKEN_EXPIRATION = "exp";
    private static final String TOKEN_ISSUED_AT = "iat";
    private static final String TOKEN_IMAGED_HOST_URI = "imaged-uri";
    private static final String TOKEN_TRANSFER_TICKET = "transfer-ticket";
    private static final boolean LEGAL_IMAGE = true;
    private static final boolean ILLEGAL_IMAGE = false;
    private static final String HTTP_SCHEME = "http://";
    private static final String HTTPS_SCHEME = "https://";
    private static final String IMAGES_PATH = "/images";
    private static final String TICKETS_PATH = "/tickets/";
    private static final String FILE_URL_SCHEME = "file://";

    @Inject
    private ImageTransferDao imageTransferDao;
    @Inject
    private AuditLogDirector auditLogDirector;
    @Inject
    private DiskDao diskDao;
    @Inject
    private StorageDomainDao storageDomainDao;
    @Inject
    private VmDao vmDao;
    @Inject
    private ImageTransferUpdater imageTransferUpdater;
    @Inject
    private ImageDao imageDao;
    @Inject
    private VdsDao vdsDao;
    @Inject
    private CommandCoordinatorUtil commandCoordinatorUtil;
    @Inject
    @Typed(TransferImageCommandCallback.class)
    private Instance<TransferImageCommandCallback> callbackProvider;

    public TransferDiskImageCommand(T parameters, CommandContext cmdContext) {
        super(parameters, cmdContext);
    }

    @Override
    protected void setActionMessageParameters() {
        addValidationMessage(EngineMessage.VAR__TYPE__DISK);
        addValidationMessage(EngineMessage.VAR__ACTION__TRANSFER);
    }

    protected boolean validateCreateImage() {
        ActionReturnValue returnValue = CommandHelper.validate(ActionType.AddDisk, getAddDiskParameters(),
                getContext().clone());
        getReturnValue().setValidationMessages(returnValue.getValidationMessages());
        return returnValue.isValid();
    }

    protected void createImage() {
        runInternalAction(ActionType.AddDisk, getAddDiskParameters(), cloneContextAndDetachFromParent());
    }

    protected String prepareImage(Guid vdsId) {
        VDSReturnValue vdsRetVal = runVdsCommand(VDSCommandType.PrepareImage,
                    getPrepareParameters(vdsId));
        return FILE_URL_SCHEME + ((PrepareImageReturn) vdsRetVal.getReturnValue()).getImagePath();
    }

    protected boolean validateImageTransfer() {
        DiskImage diskImage = getDiskImage();
        DiskValidator diskValidator = getDiskValidator(diskImage);
        DiskImagesValidator diskImagesValidator = getDiskImagesValidator(diskImage);
        StorageDomainValidator storageDomainValidator = getStorageDomainValidator(
                storageDomainDao.getForStoragePool(diskImage.getStorageIds().get(0), diskImage.getStoragePoolId()));
        return validate(diskValidator.isDiskExists())
                && validateActiveDiskPluggedToAnyNonDownVm(diskImage, diskValidator)
                && validate(diskImagesValidator.diskImagesNotIllegal())
                && validate(diskImagesValidator.diskImagesNotLocked())
                && validate(storageDomainValidator.isDomainExistAndActive());
    }

    private boolean validateActiveDiskPluggedToAnyNonDownVm(DiskImage diskImage, DiskValidator diskValidator) {
        return diskImage.isDiskSnapshot() || validate(diskValidator.isDiskPluggedToAnyNonDownVm(false));
    }

    protected DiskImagesValidator getDiskImagesValidator(DiskImage diskImage) {
        return new DiskImagesValidator(diskImage);
    }

    protected DiskValidator getDiskValidator(DiskImage diskImage) {
        return new DiskValidator(diskImage);
    }

    protected StorageDomainValidator getStorageDomainValidator(StorageDomain storageDomain) {
        return new StorageDomainValidator(storageDomain);
    }

    private PrepareImageVDSCommandParameters getPrepareParameters(Guid vdsId) {
        return new PrepareImageVDSCommandParameters(vdsId,
                getStoragePool().getId(),
                getStorageDomainId(),
                getDiskImage().getId(),
                getDiskImage().getImageId(), true);
    }

    protected void tearDownImage(Guid vdsId) {
        try {
            VDSReturnValue vdsRetVal = runVdsCommand(VDSCommandType.TeardownImage,
                    getImageActionsParameters(vdsId));
            if (!vdsRetVal.getSucceeded()) {
                EngineException engineException = new EngineException();
                engineException.setVdsError(vdsRetVal.getVdsError());
                throw engineException;
            }
        } catch (EngineException e) {
            DiskImage image = getDiskImage();
            log.warn("Failed to tear down image '{}' for image transfer session: {}",
                    image, e.getVdsError());

            // Invoke log method directly rather than relying on infra, because teardown
            // failure may occur during command execution, e.g. if the upload is paused.
            addCustomValue("DiskAlias", image != null ? image.getDiskAlias() : "(unknown)");
            auditLogDirector.log(this, AuditLogType.TRANSFER_IMAGE_TEARDOWN_FAILED);
        }
    }

    private AddDiskParameters getAddDiskParameters() {
        AddDiskParameters diskParameters = getParameters().getAddDiskParameters();
        diskParameters.setParentCommand(getActionType());
        diskParameters.setParentParameters(getParameters());
        diskParameters.setShouldRemainIllegalOnFailedExecution(true);
        diskParameters.setSkipDomainCheck(true);
        return diskParameters;
    }

    protected ImageActionsVDSCommandParameters getImageActionsParameters(Guid vdsId) {
        return new ImageActionsVDSCommandParameters(vdsId,
                getStoragePool().getId(),
                getStorageDomainId(),
                getDiskImage().getId(),
                getDiskImage().getImageId());
    }

    protected String getImageAlias() {
        return  getParameters().getAddDiskParameters() != null ?
                getParameters().getAddDiskParameters().getDiskInfo().getDiskAlias() :
                getDiskImage().getDiskAlias();
    }

    protected String getImageType() {
        return "disk";
    }

    protected DiskImage getDiskImage() {
        if (!Guid.isNullOrEmpty(getParameters().getImageId())) {
            setImageId(getParameters().getImageId());
            return super.getDiskImage();
        }
        DiskImage diskImage = super.getDiskImage();
        if (diskImage == null) {
            diskImage = (DiskImage) diskDao.get(getParameters().getImageGroupID());
        }
        return diskImage;
    }

    @Override
    public List<QuotaConsumptionParameter> getQuotaStorageConsumptionParameters() {
        List<QuotaConsumptionParameter> list = new ArrayList<>();
        if (getParameters().getAddDiskParameters() != null) {
            AddDiskParameters parameters = getAddDiskParameters();
            list.add(new QuotaStorageConsumptionParameter(
                    ((DiskImage) parameters.getDiskInfo()).getQuotaId(),
                    null,
                    QuotaConsumptionParameter.QuotaAction.CONSUME,
                    getStorageDomainId(),
                    (double) parameters.getDiskInfo().getSize() / SizeConverter.BYTES_IN_GB));
        }

        return list;
    }

    @Override
    public List<PermissionSubject> getPermissionCheckSubjects() {
        List<PermissionSubject> listPermissionSubjects = new ArrayList<>();
        if (isImageProvided()) {
            listPermissionSubjects.add(new PermissionSubject(getParameters().getImageGroupID(),
                    VdcObjectType.Disk,
                    ActionGroup.EDIT_DISK_PROPERTIES));
        } else {
            listPermissionSubjects.add(new PermissionSubject(getParameters().getStorageDomainId(),
                    VdcObjectType.Storage,
                    ActionGroup.CREATE_DISK));
        }

        return listPermissionSubjects;
    }

    @Override
    protected Map<String, Pair<String, String>> getSharedLocks() {
        Map<String, Pair<String, String>> locks = new HashMap<>();
        if (getDiskImage() != null) {
            locks.put(getDiskImage().getId().toString(),
                    LockMessagesMatchUtil.makeLockingPair(LockingGroup.DISK, EngineMessage.ACTION_TYPE_FAILED_DISK_IS_LOCKED));
        }
        if (!Guid.isNullOrEmpty(getParameters().getImageId())) {
            List<VM> vms = vmDao.getVmsListForDisk(getDiskImage().getId(), true);
            vms.forEach(vm -> locks.put(vm.getId().toString(),
                    LockMessagesMatchUtil.makeLockingPair(LockingGroup.VM, EngineMessage.ACTION_TYPE_FAILED_VM_IS_LOCKED)));
        }
        return locks;
    }

    @Override
    protected void executeCommand() {
        log.info("Creating ImageTransfer entity for command '{}'", getCommandId());
        ImageTransfer entity = new ImageTransfer(getCommandId());
        entity.setCommandType(getActionType());
        entity.setPhase(ImageTransferPhase.INITIALIZING);
        entity.setType(getParameters().getTransferType());
        entity.setActive(false);
        entity.setLastUpdated(new Date());
        entity.setBytesTotal(getParameters().getTransferSize());
        entity.setClientInactivityTimeout(getParameters().getClientInactivityTimeout() != null ?
                getParameters().getClientInactivityTimeout() :
                getTransferImageClientInactivityTimeoutInSeconds());
        imageTransferDao.save(entity);

        if (isImageProvided()) {
            handleImageIsReadyForTransfer();
        } else {
            if (getParameters().getTransferType() == TransferType.Download) {
                failValidation(EngineMessage.ACTION_TYPE_FAILED_IMAGE_NOT_SPECIFIED_FOR_DOWNLOAD);
                setSucceeded(false);
                return;
            }
            log.info("Creating {} image", getImageType());
            createImage();
        }

        setActionReturnValue(getCommandId());
        setSucceeded(true);
    }

    private boolean isImageProvided() {
        return !Guid.isNullOrEmpty(getParameters().getImageId()) ||
                !Guid.isNullOrEmpty(getParameters().getImageGroupID());
    }

    public void proceedCommandExecution(Guid childCmdId) {
        ImageTransfer entity = imageTransferDao.get(getCommandId());
        if (entity == null || entity.getPhase() == null) {
            log.error("Image transfer status entity corrupt or missing from database"
                         + " for image transfer command '{}'", getCommandId());
            setCommandStatus(CommandStatus.FAILED);
            return;
        }
        if (entity.getDiskId() != null) {
            // Make the disk id available for all states below.  If the transfer is still
            // initializing, this may be set below in the INITIALIZING block instead.
            setImageGroupId(entity.getDiskId());
        }

        long ts = System.currentTimeMillis() / 1000;
        executeStateHandler(entity, ts, childCmdId);
    }

    public void executeStateHandler(ImageTransfer entity, long timestamp, Guid childCmdId) {
        StateContext context = new StateContext();
        context.entity = entity;
        context.iterationTimestamp = timestamp;
        context.childCmdId = childCmdId;

        // State handler methods are responsible for calling setCommandStatus
        // as well as updating the entity to reflect transitions.
        switch (entity.getPhase()) {
            case INITIALIZING:
                handleInitializing(context);
                break;
            case RESUMING:
                handleResuming(context);
                break;
            case TRANSFERRING:
                handleTransferring(context);
                break;
            case PAUSED_SYSTEM:
                handlePausedSystem(context);
                break;
            case PAUSED_USER:
                handlePausedUser(context);
                break;
            case CANCELLED:
                handleCancelled();
                break;
            case FINALIZING_SUCCESS:
                handleFinalizingSuccess(context);
                break;
            case FINALIZING_FAILURE:
                handleFinalizingFailure(context);
                break;
            case FINISHED_SUCCESS:
                handleFinishedSuccess();
                break;
            case FINISHED_FAILURE:
                handleFinishedFailure();
                break;
            }
    }

    private void handleInitializing(final StateContext context) {
        if (context.childCmdId == null) {
            // Guard against callback invocation before executeCommand() is complete
            return;
        }

        switch (commandCoordinatorUtil.getCommandStatus(context.childCmdId)) {
            case NOT_STARTED:
            case ACTIVE:
                log.info("Waiting for {} to be added for image transfer command '{}'",
                        getImageType(), getCommandId());
                return;
            case SUCCEEDED:
                break;
            default:
                log.error("Failed to add {} for image transfer command '{}'",
                        getImageType(), getCommandId());
                setCommandStatus(CommandStatus.FAILED);
                return;
        }

        ActionReturnValue addDiskRetVal = commandCoordinatorUtil.getCommandReturnValue(context.childCmdId);
        if (addDiskRetVal == null || !addDiskRetVal.getSucceeded()) {
            log.error("Failed to add {} (command status was success, but return value was failed)"
                    + " for image transfer command '{}'", getImageType(), getCommandId());
            setReturnValue(addDiskRetVal);
            setCommandStatus(CommandStatus.FAILED);
            return;
        }

        Guid createdId = addDiskRetVal.getActionReturnValue();
        // Saving disk id in the parameters in order to persist it in command_entities table
        getParameters().setImageGroupID(createdId);
        handleImageIsReadyForTransfer();
    }

    protected void handleImageIsReadyForTransfer() {
        DiskImage image = getDiskImage();
        Guid domainId = image.getStorageIds().get(0);

        getParameters().setStorageDomainId(domainId);
        getParameters().setDestinationImageId(image.getImageId());

        // ovirt-imageio-daemon must know the boundaries of the target image for writing permissions.
        getParameters().setTransferSize(getTransferSize(image, domainId));

        persistCommand(getParameters().getParentCommand(), true);
        setImage(image);
        setStorageDomainId(domainId);

        log.info("Successfully added {} for image transfer command '{}'",
                getTransferDescription(), getCommandId());

        // ImageGroup is empty when downloading a disk snapshot
        if (!Guid.isNullOrEmpty(getParameters().getImageGroupID())) {
            ImageTransfer updates = new ImageTransfer();
            updates.setDiskId(getParameters().getImageGroupID());
            updateEntity(updates);
        }

        // The image will remain locked until the transfer command has completed.
        lockImage();
        startImageTransferSession();
        log.info("Returning from proceedCommandExecution after starting transfer session"
                + " for image transfer command '{}'", getCommandId());

        resetPeriodicPauseLogTime(0);
    }

    private long getTransferSize(DiskImage image, Guid domainId) {
        if (getParameters().getTransferType() == TransferType.Download) {
            DiskImage imageInfoFromVdsm = imagesHandler.getVolumeInfoFromVdsm(
                    image.getStoragePoolId(), domainId, image.getId(), image.getImageId());
            return imageInfoFromVdsm.getApparentSizeInBytes();
        }
        // Upload
        if (getParameters().getTransferSize() != 0) {
            // TransferSize is only set by the webadmin
            return getParameters().getTransferSize();
        }
        boolean isBlockDomain = getDiskImage().getStorageTypes().get(0).isBlockDomain();
        return isBlockDomain ? getDiskImage().getActualSizeInBytes() : getDiskImage().getSize();
    }

    private void handleResuming(final StateContext context) {
        lockImage();

        log.info("Resuming transfer for {}", getTransferDescription());
        auditLog(this, AuditLogType.TRANSFER_IMAGE_RESUMED_BY_USER);
        extendTicketIfNecessary(context);
        updateEntityPhase(ImageTransferPhase.TRANSFERRING);

        resetPeriodicPauseLogTime(0);
    }

    private void handleTransferring(final StateContext context) {
        // While the transfer is in progress, we're responsible
        // for keeping the transfer session alive.
        extendTicketIfNecessary(context);
        resetPeriodicPauseLogTime(0);
        pollTransferStatus(context);
    }

    private void extendTicketIfNecessary(final StateContext context) {
        // The polling interval is user-configurable and grows
        // exponentially, make sure to set it with time to spare.
        if (context.iterationTimestamp
                >= getParameters().getSessionExpiration() - getHostTicketRefreshAllowance()) {
            log.info("Renewing transfer ticket for {}", getTransferDescription());
            boolean extendSucceeded = extendImageTransferSession(context.entity);
            if (!extendSucceeded) {
                log.warn("Failed to renew transfer ticket for {}", getTransferDescription());
                if (getParameters().isRetryExtendTicket()) {
                    // Set 'extendTicketFailed' flag to true for giving a grace period
                    // for another extend attempt.
                    getParameters().setRetryExtendTicket(false);
                } else {
                    updateEntityPhaseToStoppedBySystem(
                            AuditLogType.TRANSFER_IMAGE_STOPPED_BY_SYSTEM_TICKET_RENEW_FAILURE);
                    getParameters().setRetryExtendTicket(true);
                }
            }
        } else {
            log.debug("Not yet renewing transfer ticket for {}", getTransferDescription());
        }
    }

    private void pollTransferStatus(final StateContext context) {
        if (context.entity.getVdsId() == null || context.entity.getImagedTicketId() == null ||
                !FeatureSupported.getImageTicketSupported(
                        vdsDao.get(context.entity.getVdsId()).getClusterCompatibilityVersion())) {
            // Old engines update the transfer status in UploadImageHandler::updateBytesSent.
            return;
        }
        ImageTicketInformation ticketInfo;
        try {
            ticketInfo = (ImageTicketInformation) runVdsCommand(VDSCommandType.GetImageTicket,
                    new GetImageTicketVDSCommandParameters(
                            context.entity.getVdsId(), context.entity.getImagedTicketId())).getReturnValue();
        } catch (EngineException e) {
            log.error("Could not get image ticket '{}' from vdsm", context.entity.getImagedTicketId(), e);
            updateEntityPhaseToStoppedBySystem(
                    AuditLogType.TRANSFER_IMAGE_STOPPED_BY_SYSTEM_MISSING_TICKET);
            return;
        }
        ImageTransfer upToDateImageTransfer = updateTransferStatusWithTicketInformation(context.entity, ticketInfo);
        if (getParameters().getTransferType() == TransferType.Download) {
            finalizeDownloadIfNecessary(context, upToDateImageTransfer);
        }

        // Check conditions for pausing the transfer (ie UI is MIA)
        stopTransferIfNecessary(upToDateImageTransfer, context.iterationTimestamp, ticketInfo.getIdleTime());
    }

    private ImageTransfer updateTransferStatusWithTicketInformation(ImageTransfer oldImageTransfer,
            ImageTicketInformation ticketInfo) {
        if (!Objects.equals(oldImageTransfer.getActive(), ticketInfo.isActive()) ||
                !Objects.equals(oldImageTransfer.getBytesSent(), ticketInfo.getTransferred())) {
            // At least one of the status fields (bytesSent or active) should be updated.
            ImageTransfer updatesFromTicket = new ImageTransfer();
            updatesFromTicket.setBytesSent(ticketInfo.getTransferred());
            updatesFromTicket.setActive(ticketInfo.isActive());
            ActionReturnValue returnValue = runInternalAction(ActionType.TransferImageStatus,
                    new TransferImageStatusParameters(getCommandId(), updatesFromTicket));
            if (returnValue == null || !returnValue.getSucceeded()) {
                log.debug("Failed to update transfer status.");
                return oldImageTransfer;
            }
            return returnValue.getActionReturnValue();
        }
        return oldImageTransfer;
    }

    private void finalizeDownloadIfNecessary(final StateContext context, ImageTransfer upToDateImageTransfer) {
        if (upToDateImageTransfer.getBytesTotal() != 0 &&
                // Frontend flow (REST API should close the connection on its own).
                getParameters().getTransferSize() == upToDateImageTransfer.getBytesSent() &&
                !upToDateImageTransfer.getActive()) {
            // Heuristic - once the transfer is inactive, we want to wait another COCO iteration
            // to decrease the chances that the few last packets are still on the way to the client.
            if (!context.entity.getActive()) { // The entity from the previous COCO iteration.
                // This is the second COCO iteration that the transfer is inactive.
                ImageTransfer statusUpdate = new ImageTransfer();
                statusUpdate.setPhase(ImageTransferPhase.FINALIZING_SUCCESS);
                runInternalAction(ActionType.TransferImageStatus,
                        new TransferImageStatusParameters(getCommandId(), statusUpdate));
            }
        }
    }

    private void handlePausedUser(final StateContext context) {
        auditLog(this, AuditLogType.TRANSFER_IMAGE_PAUSED_BY_USER);
        handlePaused(context);
    }

    private void handlePausedSystem(final StateContext context) {
        handlePaused(context);
    }

    private void handleCancelled() {
        log.info("Transfer cancelled for {}", getTransferDescription());
        setAuditLogTypeFromPhase(ImageTransferPhase.CANCELLED);
        updateEntityPhase(ImageTransferPhase.FINALIZING_FAILURE);
    }

    private void handleFinalizingSuccess(final StateContext context) {
        log.info("Finalizing successful transfer for {}", getTransferDescription());

        // If stopping the session did not succeed, don't change the transfer state.
        if (stopImageTransferSession(context.entity)) {
            Guid transferingVdsId = context.entity.getVdsId();
            // Verify image is relevant only on upload
            if (getParameters().getTransferType() == TransferType.Download) {
                unLockImage();
                updateEntityPhase(ImageTransferPhase.FINISHED_SUCCESS);
                setAuditLogTypeFromPhase(ImageTransferPhase.FINISHED_SUCCESS);
            } else if (verifyImage(transferingVdsId)) {
                // We want to use the transferring vds for image actions for having a coherent log when transferring.
                setVolumeLegalityInStorage(LEGAL_IMAGE);
                if (getDiskImage().getVolumeFormat().equals(VolumeFormat.COW)) {
                    setQcowCompat(getDiskImage().getImage(),
                            getStoragePool().getId(),
                            getDiskImage().getId(),
                            getDiskImage().getImageId(),
                            getStorageDomainId(),
                            transferingVdsId);
                    imageDao.update(getDiskImage().getImage());
                }
                unLockImage();
                updateEntityPhase(ImageTransferPhase.FINISHED_SUCCESS);
                setAuditLogTypeFromPhase(ImageTransferPhase.FINISHED_SUCCESS);
            } else {
                setImageStatus(ImageStatus.ILLEGAL);
                updateEntityPhase(ImageTransferPhase.FINALIZING_FAILURE);
            }

            // Finished using the image, tear it down.
            tearDownImage(context.entity.getVdsId());
        }
    }

    private boolean verifyImage(Guid transferingVdsId) {
        ImageActionsVDSCommandParameters parameters =
                new ImageActionsVDSCommandParameters(transferingVdsId, getStoragePool().getId(),
                        getStorageDomainId(),
                        getDiskImage().getId(),
                        getDiskImage().getImageId());

        try {
            // As we currently support a single volume image, we only need to verify that volume.
            vdsBroker.runVdsCommand(VDSCommandType.VerifyUntrustedVolume, parameters);
        } catch (RuntimeException e) {
            log.error("Failed to verify transferred image: {}", e);
            return false;
        }
        return true;
    }

    private void handleFinalizingFailure(final StateContext context) {
        log.error("Finalizing failed transfer. {}", getTransferDescription());
        stopImageTransferSession(context.entity);
        // Setting disk status to ILLEGAL only on upload failure
        // (only if not disk snapshot)
        if (!Guid.isNullOrEmpty(getParameters().getImageGroupID())) {
            setImageStatus(getParameters().getTransferType() == TransferType.Upload ?
                    ImageStatus.ILLEGAL : ImageStatus.OK);
        }
        Guid vdsId = context.entity.getVdsId() != null ? context.entity.getVdsId() : getVdsId();
        // Teardown is required for all scenarios as we call prepareImage when
        // starting a new session.
        tearDownImage(vdsId);
        updateEntityPhase(ImageTransferPhase.FINISHED_FAILURE);
        setAuditLogTypeFromPhase(ImageTransferPhase.FINISHED_FAILURE);
    }

    private void handleFinishedSuccess() {
        log.info("Transfer was successful. {}", getTransferDescription());
        setCommandStatus(CommandStatus.SUCCEEDED);
    }

    private void handleFinishedFailure() {
        log.error("Transfer failed. {}", getTransferDescription());
        setCommandStatus(CommandStatus.FAILED);
    }

    private void handlePaused(final StateContext context) {
        periodicPauseLog(context.entity, context.iterationTimestamp);
    }

    /**
     * Verify conditions for continuing the transfer, stopping it if necessary.
     */
    private void stopTransferIfNecessary(ImageTransfer entity, long ts, Integer idleTimeFromTicket) {
        if (shouldAbortOnClientInactivityTimeout(entity, ts, idleTimeFromTicket)) {
            if (getParameters().getTransferType() == TransferType.Download) {
                // In download flows, we can cancel the transfer if there was no activity
                // for a while, as the download is handled by the client.
                auditLog(this, AuditLogType.DOWNLOAD_IMAGE_CANCELED_TIMEOUT);
                updateEntityPhase(ImageTransferPhase.CANCELLED);
            } else {
                updateEntityPhaseToStoppedBySystem(
                        AuditLogType.UPLOAD_IMAGE_PAUSED_BY_SYSTEM_TIMEOUT);
            }
        }
    }

    private boolean shouldAbortOnClientInactivityTimeout(ImageTransfer entity, long ts, Integer idleTimeFromTicket) {
        int inactivityTimeout = entity.getClientInactivityTimeout();
        // For new daemon (1.3.0), we check timeout according to 'idle_time' in ticket;
        // otherwise, fallback to check according to entity's 'lastUpdated'.
        boolean timeoutExceeded = idleTimeFromTicket != null ?
                idleTimeFromTicket > inactivityTimeout :
                ts > (entity.getLastUpdated().getTime() / 1000) + inactivityTimeout;
        return inactivityTimeout > 0
                && timeoutExceeded
                && !entity.getActive();
    }

    private void resetPeriodicPauseLogTime(long ts) {
        if (getParameters().getLastPauseLogTime() != ts) {
            getParameters().setLastPauseLogTime(ts);
            persistCommand(getParameters().getParentCommand(), true);
        }
    }

    private void periodicPauseLog(ImageTransfer entity, long ts) {
        if (ts >= getParameters().getLastPauseLogTime() + getPauseLogInterval()) {
            log.info("Transfer was paused by {}. {}",
                    entity.getPhase() == ImageTransferPhase.PAUSED_SYSTEM ? "system" : "user",
                    getTransferDescription());
            resetPeriodicPauseLogTime(ts);
        }
    }

    /**
     * Start the ovirt-image-daemon session
     */
    protected void startImageTransferSession() {
        if (!initializeVds()) {
            log.error("Could not find a suitable host for image data transfer");
            updateEntityPhaseToStoppedBySystem(
                    AuditLogType.TRANSFER_IMAGE_STOPPED_BY_SYSTEM_MISSING_HOST);
            return;
        }
        Guid imagedTicketId = Guid.newGuid();

        // Create the signed ticket first because we can just throw it away if we fail to start the image
        // transfer session.  The converse would require us to close the transfer session on failure.
        String signedTicket = createSignedTicket(getVds(), imagedTicketId);
        if (signedTicket == null) {
            log.error("Failed to create a signed image ticket");
            updateEntityPhaseToStoppedBySystem(
                    AuditLogType.TRANSFER_IMAGE_STOPPED_BY_SYSTEM_FAILED_TO_CREATE_TICKET);
            return;
        }

        long timeout = getHostTicketLifetime();
        if (!addImageTicketToDaemon(imagedTicketId, timeout)) {
            log.error("Failed to add image ticket to ovirt-imageio-daemon");
            updateEntityPhaseToStoppedBySystem(
                    AuditLogType.TRANSFER_IMAGE_STOPPED_BY_SYSTEM_FAILED_TO_ADD_TICKET_TO_DAEMON);
            return;
        }
        if (!addImageTicketToProxy(imagedTicketId, signedTicket)) {
            log.error("Failed to add image ticket to ovirt-imageio-proxy");
            updateEntityPhaseToStoppedBySystem(
                    AuditLogType.TRANSFER_IMAGE_STOPPED_BY_SYSTEM_FAILED_TO_ADD_TICKET_TO_PROXY);
            return;
        }

        ImageTransfer updates = new ImageTransfer();
        updates.setVdsId(getVdsId());
        updates.setImagedTicketId(imagedTicketId);
        updates.setProxyUri(getProxyUri() + IMAGES_PATH);
        updates.setDaemonUri(getImageDaemonUri(getVds().getHostName()) + IMAGES_PATH);
        updates.setSignedTicket(signedTicket);
        updateEntity(updates);

        setNewSessionExpiration(timeout);
        updateEntityPhase(ImageTransferPhase.TRANSFERRING);
    }

    private boolean addImageTicketToDaemon(Guid imagedTicketId, long timeout) {
        String imagePath;
        try {
            imagePath = prepareImage(getVdsId());
        } catch (Exception e) {
            log.error("Failed to prepare image for transfer session: {}", e);
            return false;
        }

        if (getParameters().getTransferType() == TransferType.Upload &&
                !setVolumeLegalityInStorage(ILLEGAL_IMAGE)) {
            return false;
        }

        String[] transferOps = new String[] {getParameters().getTransferType().getAllowedOperation()};
        AddImageTicketVDSCommandParameters transferCommandParams = new AddImageTicketVDSCommandParameters(getVdsId(),
                imagedTicketId,
                transferOps,
                timeout,
                getParameters().getTransferSize(),
                imagePath,
                getParameters().getDownloadFilename());

        // TODO This is called from doPolling(), we should run it async (runFutureVDSCommand?)
        VDSReturnValue vdsRetVal;
        try {
            vdsRetVal = vdsBroker.runVdsCommand(VDSCommandType.AddImageTicket, transferCommandParams);
        } catch (RuntimeException e) {
            log.error("Failed to start image transfer session: {}", e);
            return false;
        }

        if (!vdsRetVal.getSucceeded()) {
            log.error("Failed to start image transfer session");
            return false;
        }
        log.info("Started transfer session with ticket id {}, timeout {} seconds",
                imagedTicketId.toString(), timeout);

        return true;
    }

    private boolean addImageTicketToProxy(Guid imagedTicketId, String signedTicket) {
        log.info("Adding image ticket to ovirt-imageio-proxy, id {}", imagedTicketId);
        try {
            HttpURLConnection connection = getProxyConnection(getProxyUri() + TICKETS_PATH);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestMethod("PUT");
            // Send request
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(signedTicket.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new RuntimeException(String.format(
                        "Request to imageio-proxy failed, response code: %s", responseCode));
            }
        } catch (Exception ex) {
            log.error("Failed to add image ticket to ovirt-imageio-proxy", ex.getMessage());
            return false;
        }
        return true;
    }

    private HttpURLConnection getProxyConnection(String url) {
        try {
            HttpURLConnectionBuilder builder = new HttpURLConnectionBuilder().setURL(url);
            // Set SSL details
            builder.setTrustStore(EngineLocalConfig.getInstance().getPKITrustStore().getAbsolutePath())
                    .setTrustStorePassword(EngineLocalConfig.getInstance().getPKITrustStorePassword())
                    .setTrustStoreType(EngineLocalConfig.getInstance().getPKITrustStoreType())
                    .setHttpsProtocol(Config.getValue(ConfigValues.ExternalCommunicationProtocol));
            HttpURLConnection connection = builder.create();
            connection.setDoOutput(true);
            return connection;
        } catch (Exception ex) {
            throw new RuntimeException(String.format(
                    "Failed to communicate with ovirt-imageio-proxy: %s", ex.getMessage()));
        }
    }

    private boolean setVolumeLegalityInStorage(boolean legal) {
        SetVolumeLegalityVDSCommandParameters parameters =
                new SetVolumeLegalityVDSCommandParameters(getStoragePool().getId(),
                        getStorageDomainId(),
                        getDiskImage().getId(),
                        getDiskImage().getImageId(),
                        legal);
        try {
            runVdsCommand(VDSCommandType.SetVolumeLegality, parameters);
        } catch (EngineException e) {
            log.error("Failed to set image's volume's legality to {} for image {} and volume {}: {}",
                    legal, getImage().getImage().getDiskId(), getImage().getImageId(), e);
            return false;
        }
        return true;
    }

    @Override
    protected boolean validate() {
        if (isImageProvided()) {
            return validateImageTransfer();
        } else if (getParameters().getTransferType() == TransferType.Download) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_IMAGE_NOT_SPECIFIED_FOR_DOWNLOAD);
        }
        return validateCreateImage();
    }

    private boolean extendImageTransferSession(final ImageTransfer entity) {
        if (entity.getImagedTicketId() == null) {
            log.error("Failed to extend image transfer session: no existing session to extend");
            return false;
        }

        long timeout = getHostTicketLifetime();
        Guid resourceId = entity.getImagedTicketId();
        ExtendImageTicketVDSCommandParameters
                transferCommandParams = new ExtendImageTicketVDSCommandParameters(entity.getVdsId(),
                entity.getImagedTicketId(), timeout);

        // TODO This is called from doPolling(), we should run it async (runFutureVDSCommand?)
        VDSReturnValue vdsRetVal;
        try {
            vdsRetVal = vdsBroker.runVdsCommand(VDSCommandType.ExtendImageTicket, transferCommandParams);
        } catch (RuntimeException e) {
            log.error("Failed to extend image transfer session for ticket '{}': {}",
                    resourceId.toString(), e);
            return false;
        }

        if (!vdsRetVal.getSucceeded()) {
            log.error("Failed to extend image transfer session");
            return false;
        }
        log.info("Transfer session with ticket id {} extended, timeout {} seconds",
                resourceId.toString(), timeout);

        setNewSessionExpiration(timeout);
        return true;
    }

    private void setNewSessionExpiration(long timeout) {
        getParameters().setSessionExpiration((System.currentTimeMillis() / 1000) + timeout);
        persistCommand(getParameters().getParentCommand(), true);
    }

    private boolean stopImageTransferSession(ImageTransfer entity) {
        if (entity.getImagedTicketId() == null) {
            log.warn("Failed to stop image transfer session. Ticket does not exist for image '{}'", entity.getDiskId());
            return false;
        }

        if (!removeImageTicketFromDaemon(entity.getImagedTicketId(), entity.getVdsId())) {
            return false;
        }
        if (!removeImageTicketFromProxy(entity.getImagedTicketId())) {
            return false;
        }

        ImageTransfer updates = new ImageTransfer();
        updateEntity(updates, true);
        return true;
    }

    private boolean removeImageTicketFromDaemon(Guid imagedTicketId, Guid vdsId) {
        RemoveImageTicketVDSCommandParameters parameters = new RemoveImageTicketVDSCommandParameters(
                vdsId, imagedTicketId);
        VDSReturnValue vdsRetVal;
        try {
            vdsRetVal = vdsBroker.runVdsCommand(VDSCommandType.RemoveImageTicket, parameters);
        } catch (RuntimeException e) {
            log.error("Failed to stop image transfer session for ticket '{}': {}", imagedTicketId, e);
            return false;
        }

        if (!vdsRetVal.getSucceeded()) {
            log.warn("Failed to stop image transfer session for ticket '{}'", imagedTicketId);
            return false;
        }
        log.info("Successfully stopped image transfer session for ticket '{}'", imagedTicketId);
        return true;
    }

    private boolean removeImageTicketFromProxy(Guid imagedTicketId) {
        log.info("Removing image ticket from ovirt-imageio-proxy, id {}", imagedTicketId);
        try {
            HttpURLConnection connection = getProxyConnection(getProxyUri() + TICKETS_PATH + imagedTicketId);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestMethod("DELETE");
            connection.connect();
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_NO_CONTENT) {
                throw new RuntimeException(String.format(
                        "Request to imageio-proxy failed, response code: %s", responseCode));
            }
        } catch (Exception ex) {
            log.error("Failed to remove image ticket from ovirt-imageio-proxy", ex.getMessage());
            return false;
        }
        return true;
    }

    private String createSignedTicket(VDS vds, Guid transferToken) {
        String ticket;

        Map<String, Object> elements = new HashMap<>();
        long ts = System.currentTimeMillis() / 1000;
        elements.put(TOKEN_NOT_BEFORE, ts);
        elements.put(TOKEN_ISSUED_AT, ts);
        elements.put(TOKEN_EXPIRATION, ts + getClientTicketLifetime());
        elements.put(TOKEN_IMAGED_HOST_URI, getImageDaemonUri(vds.getHostName()));
        elements.put(TOKEN_TRANSFER_TICKET, transferToken.toString());

        String payload;
        try {
            payload = JsonHelper.mapToJson(elements);
        } catch (Exception e) {
            log.error("Failed to create JSON payload for signed ticket", e);
            return null;
        }
        log.debug("Signed ticket payload: {}", payload);

        try {
            ticket = new TicketEncoder(
                    EngineEncryptionUtils.getPrivateKeyEntry().getCertificate(),
                    EngineEncryptionUtils.getPrivateKeyEntry().getPrivateKey(),
                    getClientTicketLifetime()
            ).encode(payload);
        } catch (Exception e) {
            log.error("Failed to encode ticket for image transfer", e);
            return null;
        }

        return ticket;
    }

    private void updateEntityPhase(ImageTransferPhase phase) {
        ImageTransfer updates = new ImageTransfer(getCommandId());
        updates.setPhase(phase);
        updateEntity(updates);
    }

    private void updateEntityPhaseToStoppedBySystem(AuditLogType stoppedBySystemReason) {
        auditLog(this, stoppedBySystemReason);
        if (getParameters().getTransferType() == TransferType.Upload) {
            updateEntityPhase(ImageTransferPhase.PAUSED_SYSTEM);
        } else {
            updateEntityPhase(ImageTransferPhase.CANCELLED);
        }
    }

    private void updateEntity(ImageTransfer updates) {
        updateEntity(updates, false);
    }

    private void updateEntity(ImageTransfer updates, boolean clearResourceId) {
        imageTransferUpdater.updateEntity(updates, getCommandId(), clearResourceId);
    }

    private int getHostTicketRefreshAllowance() {
        return Config.<Integer>getValue(ConfigValues.ImageTransferHostTicketRefreshAllowanceInSeconds);
    }

    private int getHostTicketLifetime() {
        return Config.<Integer>getValue(ConfigValues.ImageTransferHostTicketValidityInSeconds);
    }

    private int getClientTicketLifetime() {
        return Config.<Integer>getValue(ConfigValues.ImageTransferClientTicketValidityInSeconds);
    }

    private int getPauseLogInterval() {
        return Config.<Integer>getValue(ConfigValues.ImageTransferPausedLogIntervalInSeconds);
    }

    private int getTransferImageClientInactivityTimeoutInSeconds() {
        return Config.<Integer>getValue(ConfigValues.TransferImageClientInactivityTimeoutInSeconds);
    }

    private String getProxyUri() {
        String scheme = Config.<Boolean> getValue(ConfigValues.ImageProxySSLEnabled)?  HTTPS_SCHEME : HTTP_SCHEME;
        String address = Config.getValue(ConfigValues.ImageProxyAddress);
        return scheme + address;
    }

    private String getImageDaemonUri(String daemonHostname) {
        String port = Config.getValue(ConfigValues.ImageDaemonPort);
        return HTTPS_SCHEME + daemonHostname + ":" + port;
    }

    private void setAuditLogTypeFromPhase(ImageTransferPhase phase) {
        if (getParameters().getAuditLogType() != null) {
            // Some flows, e.g. cancellation, may set the log type more than once.
            // In this case, the first type is the most accurate.
            return;
        }

        if (phase == ImageTransferPhase.FINISHED_SUCCESS) {
            getParameters().setAuditLogType(AuditLogType.TRANSFER_IMAGE_SUCCEEDED);
        } else if (phase == ImageTransferPhase.CANCELLED) {
            getParameters().setAuditLogType(AuditLogType.TRANSFER_IMAGE_CANCELLED);
        } else if (phase == ImageTransferPhase.FINISHED_FAILURE) {
            getParameters().setAuditLogType(AuditLogType.TRANSFER_IMAGE_FAILED);
        }
    }

    @Override
    public AuditLogType getAuditLogTypeValue() {
        addCustomValue("DiskAlias", getImageAlias());
        addCustomValue("TransferType", getParameters().getTransferType().name());
        return getActionState() == CommandActionState.EXECUTE
                ? AuditLogType.TRANSFER_IMAGE_INITIATED : getParameters().getAuditLogType();
    }

    // Return a string describing the transfer, safe for use before the new image
    // is successfully created; e.g. "disk 'NewDisk' (id '<uuid>')".
    private String getTransferDescription() {
        return String.format("%s %s '%s' (disk id: '%s', image id: '%s')",
                getParameters().getTransferType().name(),
                getImageType(),
                getImageAlias(),
                getDiskImage().getId(),
                getDiskImage().getImageId());
    }

    public void onSucceeded() {
        updateEntityPhase(ImageTransferPhase.FINISHED_SUCCESS);
        log.debug("Removing ImageTransfer id {}", getCommandId());
        imageTransferDao.remove(getCommandId());
        endSuccessfully();
        log.info("Successfully transferred disk '{}' (command id '{}')",
                getParameters().getImageId(), getCommandId());
    }

    public void onFailed() {
        updateEntityPhase(ImageTransferPhase.FINISHED_FAILURE);
        log.debug("Removing ImageTransfer id {}", getCommandId());
        imageTransferDao.remove(getCommandId());
        endWithFailure();
        log.error("Failed to transfer disk '{}' (command id '{}')",
                getParameters().getImageId(), getCommandId());
    }

    @Override
    protected void endSuccessfully() {
        if (getParameters().getTransferType() == TransferType.Upload) {
            // Update image data in DB, set Qcow Compat, etc
            // (relevant only for upload)
            super.endSuccessfully();
        }
        setSucceeded(true);
    }

    @Override
    protected void endWithFailure() {
        if (getParameters().getTransferType() == TransferType.Upload) {
            // Do rollback only for upload - i.e. remove the disk added in 'createImage()'
            runInternalAction(ActionType.RemoveDisk, new RemoveDiskParameters(getParameters().getImageGroupID()));
        }
        setSucceeded(true);
    }

    @Override
    public CommandCallback getCallback() {
        return callbackProvider.get();
    }

    @Override
    protected LockProperties applyLockProperties(LockProperties lockProperties) {
        return lockProperties.withScope(LockProperties.Scope.Command);
    }

    // Container for context needed by state machine handlers
    class StateContext {
        ImageTransfer entity;
        long iterationTimestamp;
        Guid childCmdId;
    }
}
