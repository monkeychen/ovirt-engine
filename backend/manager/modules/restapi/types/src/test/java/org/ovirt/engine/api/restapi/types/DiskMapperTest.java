package org.ovirt.engine.api.restapi.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.ovirt.engine.api.restapi.types.MappingTestHelper.populate;

import org.junit.jupiter.api.Test;
import org.ovirt.engine.api.model.Disk;
import org.ovirt.engine.api.model.DiskFormat;
import org.ovirt.engine.api.model.DiskStatus;
import org.ovirt.engine.api.model.DiskStorageType;
import org.ovirt.engine.api.model.ScsiGenericIO;
import org.ovirt.engine.core.common.businessentities.storage.DiskImage;

public class DiskMapperTest extends AbstractInvertibleMappingTest<Disk, DiskImage, DiskImage> {

    public DiskMapperTest() {
        super(Disk.class, DiskImage.class, DiskImage.class);
    }

    @Override
    protected Disk postPopulate(Disk model) {
        model.setFormat(MappingTestHelper.shuffle(DiskFormat.class));
        model.setSgio(MappingTestHelper.shuffle(ScsiGenericIO.class));
        model.setStatus(MappingTestHelper.shuffle(DiskStatus.class));
        model.setLunStorage(null);
        return model;
    }

    @Override
    protected void verify(Disk model, Disk transform) {
        assertNotNull(transform);
        assertEquals(model.getId(), transform.getId());
        assertEquals(model.getImageId(), transform.getImageId());
        assertEquals(model.getFormat(), transform.getFormat());
        assertEquals(model.getDescription(), transform.getDescription());
        assertEquals(model.getLogicalName(), transform.getLogicalName());
        assertEquals(model.getOpenstackVolumeType().getName(), transform.getOpenstackVolumeType().getName());
        assertNotNull(model.getSnapshot());
        assertEquals(model.getSnapshot().getId(), transform.getSnapshot().getId());
        assertEquals(model.getStatus(), transform.getStatus(), "unexpected status");
        assertEquals(model.isSparse(), transform.isSparse(), "unexpected sparse");
        assertEquals(model.isPropagateErrors(), transform.isPropagateErrors(), "unexpected propagate errors");
        assertEquals(model.isWipeAfterDelete(), transform.isWipeAfterDelete(), "unexpected wipe after delete");
        assertEquals(model.isShareable(), transform.isShareable(), "unexpected shareable");
    }

    @Test
    @Override
    public void testRoundtrip() throws Exception {
        Disk model = Disk.class.cast(populate(Disk.class));
        model = postPopulate(model);
        Mapper<Disk, org.ovirt.engine.core.common.businessentities.storage.Disk> out =
                getMappingLocator().getMapper(Disk.class, org.ovirt.engine.core.common.businessentities.storage.Disk.class);
        Mapper<org.ovirt.engine.core.common.businessentities.storage.Disk, Disk> back =
                getMappingLocator().getMapper(org.ovirt.engine.core.common.businessentities.storage.Disk.class, Disk.class);
        DiskImage to = (DiskImage) out.map(model, null);
        DiskImage inverse = getInverse(to);
        Disk transform = back.map(inverse, null);
        verify(model, transform);
    }

    @Test
    public void testInitialSize() {
        Long initalSize = 54321L;
        Disk model = new Disk();
        model.setStorageType(DiskStorageType.IMAGE);
        model.setInitialSize(initalSize);

        DiskImage entity = (DiskImage) DiskMapper.map(model, null);
        assertEquals(initalSize.longValue(), entity.getActualSizeInBytes(),
                "ActualSizeInBytes doesn't match the initial size as expected");

        model = DiskMapper.map(entity, null);
        assertNull(model.getInitialSize(), "initial size shouldn't be mapped back to the model");
    }
}
