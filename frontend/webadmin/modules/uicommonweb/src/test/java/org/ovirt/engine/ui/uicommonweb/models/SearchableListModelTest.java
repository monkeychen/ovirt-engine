package org.ovirt.engine.ui.uicommonweb.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.ovirt.engine.ui.uicommonweb.junit.UiCommonSetupExtension;

@ExtendWith(UiCommonSetupExtension.class)
public class SearchableListModelTest {
    @Test
    public void testSelectionRestoredOnNewSetItems() {
        SearchableListModel<Void, Integer> listModel =
                mock(SearchableListModel.class, withSettings().useConstructor().defaultAnswer(Answers.CALLS_REAL_METHODS));
        listModel.setItems(Arrays.asList(1, 2, 3));
        listModel.setSelectedItem(2);

        listModel.setItems(Arrays.asList(1, 2));

        assertEquals((Integer) 2, listModel.getSelectedItem());
    }
}
