import { Button } from 'antd';
import { WindowsOutlined } from '@ant-design/icons';
import { UC_AUTH_API_PREFIX } from '../../utils/constants';

interface EntraAuthButtonProps {
  /** In-app path to return to after login. */
  returnTo?: string;
}

/**
 * The server-hosted login entry point. Unlike the Google/Okta buttons this
 * runs no OAuth client in the browser: the server redirects to Microsoft,
 * redeems the code with a secret that never leaves it, and comes back with the
 * UC_TOKEN cookie set. Only a relative path may be handed over as the return
 * target; the server enforces the same rule.
 */
export function hostedLoginUrl(returnTo?: string): string {
  const redirect =
    returnTo && returnTo.startsWith('/') && !returnTo.startsWith('//')
      ? returnTo
      : '/';
  return `${UC_AUTH_API_PREFIX}/auth/login?redirect=${encodeURIComponent(redirect)}`;
}

/** Microsoft's own blue, so the button reads as the Microsoft one at a glance. */
const MICROSOFT_BLUE = '#0078D4';

export default function EntraAuthButton({ returnTo }: EntraAuthButtonProps) {
  return (
    <Button
      type="primary"
      icon={<WindowsOutlined style={{ fontSize: 20, marginRight: 16 }} />}
      iconPosition={'start'}
      style={{
        width: 240,
        height: 40,
        justifyContent: 'flex-start',
        backgroundColor: MICROSOFT_BLUE,
        borderColor: MICROSOFT_BLUE,
      }}
      onClick={() => window.location.assign(hostedLoginUrl(returnTo))}
    >
      Sign in with Microsoft
    </Button>
  );
}
