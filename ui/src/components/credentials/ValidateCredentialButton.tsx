import { Button, Tooltip } from 'antd';
import { SafetyCertificateOutlined } from '@ant-design/icons';
import { CredentialInterface } from '../../hooks/credentials';
import { ExternalLocationInterface } from '../../hooks/externalLocations';
import { useVendPathCredentials } from '../../hooks/pathCredentials';
import { useNotification } from '../../utils/NotificationContext';
import { PathOperation } from '../../types/api/catalog.gen';

interface ValidateCredentialButtonProps {
  credential: CredentialInterface;
  externalLocations: ExternalLocationInterface[];
  size?: 'small' | 'middle';
}

/**
 * Validates that the Unity Catalog server can actually use a credential
 * (i.e. assume the RAM/IAM role, or use the static AK/SK) by vending
 * temporary PATH_READ credentials for an external location bound to it —
 * the exact server-side flow real table access uses. Requires the
 * credential to be bound to at least one external location.
 */
export default function ValidateCredentialButton({
  credential,
  externalLocations,
  size = 'small',
}: ValidateCredentialButtonProps) {
  const mutation = useVendPathCredentials();
  const { setNotification } = useNotification();

  const boundLocation = externalLocations.find(
    (location) => location.credential_name === credential.name,
  );

  return (
    <Tooltip
      title={
        boundLocation
          ? `Probe credential vending for ${boundLocation.url} (the first external location bound to this credential)`
          : 'Bind this credential to an external location first, then it can be validated'
      }
    >
      <Button
        size={size}
        icon={<SafetyCertificateOutlined />}
        disabled={!boundLocation}
        loading={mutation.isPending}
        onClick={(e) => {
          e.stopPropagation();
          if (!boundLocation?.url) return;
          const probedUrl = boundLocation.url;
          mutation.mutate(
            {
              url: probedUrl,
              operation: PathOperation.PATH_READ,
            },
            {
              onSuccess: () => {
                // Scope the claim to the exact probe: vending succeeded for
                // this one path. It does not guarantee every bound location
                // works, and for static AK/SK the server may return the key
                // without a live round-trip to the cloud provider.
                setNotification(
                  `Credential vending succeeded for ${probedUrl} using ${credential.name}`,
                  'success',
                );
              },
              onError: (error: Error) => {
                setNotification(
                  `Credential vending failed for ${probedUrl} using ${credential.name}: ${error.message}`,
                  'error',
                );
              },
            },
          );
        }}
      >
        Validate
      </Button>
    </Tooltip>
  );
}
