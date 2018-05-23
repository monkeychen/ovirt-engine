package org.ovirt.engine.ui.common.presenter;

import org.ovirt.engine.ui.common.widget.action.ActionButtonDefinition;

import com.gwtplatform.dispatch.annotation.GenEvent;

/**
 * Event triggered when {@link ActionPanelPresenter} should add new action button to the action panel.
 */
@GenEvent
public class AddActionButton {

    String historyToken;

    ActionButtonDefinition<?> buttonDefinition;

}
