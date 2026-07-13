import { fireEvent, screen } from '@testing-library/react';

/**
 * Opens the n-th antd Select on the page and clicks the option with the
 * given text (options render into a body portal).
 */
export async function selectAntdOption(
  selectorIndex: number,
  optionText: string | RegExp,
) {
  const selectors = document.querySelectorAll('.ant-select-selector');
  fireEvent.mouseDown(selectors[selectorIndex]);
  const option = await screen.findByText(optionText, {
    selector: '.ant-select-item-option-content',
  });
  fireEvent.click(option);
}

/**
 * Clicks the modal footer's primary (OK) button. Preferred over
 * getByRole('button', { name }) because the form also contains a hidden
 * submit button with the same accessible name.
 */
export function clickModalOk() {
  const okButton = document.querySelector('.ant-modal-footer .ant-btn-primary');
  if (!okButton) throw new Error('Modal OK button not found');
  fireEvent.click(okButton);
}
