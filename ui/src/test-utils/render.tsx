import { ReactElement } from 'react';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { NotificationProvider } from '../utils/NotificationContext';

/**
 * Renders a component with the app's real providers (react-query without
 * retries, an in-memory router and the notification provider) so component
 * tests exercise the same wiring as the app.
 */
export function renderWithProviders(
  ui: ReactElement,
  /** Address to start at, for components that read the path or query string. */
  options: { route?: string } = {},
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(
    <NotificationProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[options.route ?? '/']}>
          {ui}
        </MemoryRouter>
      </QueryClientProvider>
    </NotificationProvider>,
  );
}
