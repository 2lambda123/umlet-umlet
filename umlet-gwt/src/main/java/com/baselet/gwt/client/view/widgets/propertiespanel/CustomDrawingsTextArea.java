package com.baselet.gwt.client.view.widgets.propertiespanel;

import java.util.List;

import com.baselet.element.interfaces.HasPanelAttributes;
import com.baselet.gui.AutocompletionText;
import com.baselet.gwt.client.view.widgets.OwnTextArea;
import com.google.gwt.uibinder.client.UiConstructor;

public class CustomDrawingsTextArea extends TextArea {

	@UiConstructor
	public CustomDrawingsTextArea() {
		super();
	}

	public CustomDrawingsTextArea(final MySuggestOracle oracle, OwnTextArea textArea) {
		super(oracle, textArea);
	}

	/**
	 * also fire texthandlers if a text is inserted via choosing a selectionbox entry
	 */
	@Override
	public void setText(String text) {
		super.setText(text);
		textArea.fireHandler();
	}

	@Override
	public String getGridElementAttributes(HasPanelAttributes panelAttributeProvider) {
		return panelAttributeProvider.getCustomDrawingsCode();
	}

	@Override
	public void setGridElementAttributes() {
		gridElement.setCustomDrawingsCode(getValue());
	}

	@Override
	protected List<AutocompletionText> getAutoCompletionList(HasPanelAttributes panelAttributeProvider) {
		return panelAttributeProvider.getCustomDrawingsAutocompletionList();
	}

}
