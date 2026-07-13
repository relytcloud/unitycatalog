// jest-dom adds custom jest matchers for asserting on DOM nodes.
// allows you to do things like:
// expect(element).toHaveTextContent(/react/i)
// learn more: https://github.com/testing-library/jest-dom
import '@testing-library/jest-dom';
import { notification } from 'antd';

// antd v5's responsive observer requires window.matchMedia, which jsdom does
// not implement.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});

// antd Modal/Select use getComputedStyle in ways jsdom partially supports.
window.HTMLElement.prototype.scrollIntoView = function () {};

// antd Select/Modal styles include selectors jsdom's nwsapi cannot parse;
// they get exercised through getScrollBarSize -> getComputedStyle. Scrollbar
// math is meaningless in jsdom anyway, so stub it.
jest.mock('rc-util/lib/getScrollBarSize', () => ({
  __esModule: true,
  default: () => 0,
  getTargetScrollBarSize: () => ({ width: 0, height: 0 }),
}));

// jsdom applies every injected stylesheet on getComputedStyle via nwsapi,
// which cannot parse some antd v5 css-in-js selectors and throws
// SyntaxError from inside component effects. Cascaded styles are meaningless
// in jsdom, so fall back to an empty style declaration when that happens.
const originalGetComputedStyle = window.getComputedStyle.bind(window);
window.getComputedStyle = ((element: Element, pseudo?: string | null) => {
  try {
    return originalGetComputedStyle(element, pseudo);
  } catch {
    return document.createElement('div').style;
  }
}) as typeof window.getComputedStyle;

// antd's static notification API portals toasts into document.body, outside
// RTL's container, so RTL cleanup never removes them; destroy them between
// tests so a prior test's toast can't satisfy a later findByText.
afterEach(() => notification.destroy());
