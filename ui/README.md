# Unity Catalog UI

Unity Catalog UI is an intuitive user interface designed to manage and interact with Unity Catalog. It facilitates handling data permissions, auditing, and resource discovery in a user-friendly manner. Through this UI, users can efficiently view, create, update, and delete resources within the Unity Catalog server.

For more details on how to use the Unity Catalog UI, please refer to the [UI Documentation](https://github.com/unitycatalog/unitycatalog/tree/main/docs/ui).

![UC UI](../docs/assets/images/uc-ui.png)

# Prerequisite

Node: https://nodejs.org/en/download/package-manager

Yarn: https://classic.yarnpkg.com/lang/en/docs/install

## Get started

Spin up a localhost Unity Catalog server (e.g., `./bin/start-uc-server`), see https://github.com/unitycatalog/unitycatalog/blob/main/README.md#run-the-uc-server

Then in the project directory, you can run:

### `yarn`

Install all the necessary dependencies

### `yarn start`

Runs the app in the development mode.\
Open [http://localhost:3000](http://localhost:3000) to view it in the browser.

The page will reload if you make edits.\
You will also see any lint errors in the console.

### Authenticate and Login

OSS Unity Catalog supports Sign in with Google. You can authenticate with Google by clicking the "Sign in with Google" button on the login page, once OAuth has been configured. To configure this, follow the steps to obtain a [Google API Client ID](https://developers.google.com/identity/gsi/web/guides/get-google-api-clientid) and configure your OAuth consent screen.

NOTE: The google client ID should match what is configured in the server.properties file on the server side. See README in root directory. In order for login to work, authentication must be enabled on server side AND UI side and users must be added to users table.

Once you have the client ID, add it to the `.env` file after `REACT_APP_GOOGLE_CLIENT_ID=` and change the `REACT_APP_GOOGLE_AUTH_ENABLED` flag from false to true. Restart yarn. 

## Permissions in the UI

The UI manages Unity Catalog permissions with a deliberately simplified model
(issue #6). The server is always the enforcement floor: every grant, revoke,
create, update and delete is re-authorized server-side, and the UI's enabled /
disabled button states are only a usability hint.

### Simplified access levels (catalog / schema / table)

UC's authorizer has **no privilege inheritance**: to read one table a
non-owner needs `USE CATALOG` (catalog) + `USE SCHEMA` (schema) + `SELECT`
(table); a grant on an ancestor never cascades down. Exposing that raw model
is error-prone, so catalog/schema/table pages use two collapsed levels and
auto-complete the required grants (see `src/hooks/access.ts`):

| Level | Granted privileges |
| --- | --- |
| table · **read** | catalog `USE CATALOG` + schema `USE SCHEMA` + table `SELECT` |
| schema · **create** | catalog `USE CATALOG` + schema `USE SCHEMA` + schema `CREATE TABLE` |
| catalog · **create** | catalog `USE CATALOG` + catalog `CREATE SCHEMA` |

**Revoking a level removes only the last (leaf) privilege** — `SELECT`,
`CREATE TABLE` or `CREATE SCHEMA` respectively. The `USE_*` completions stay
in place because sibling objects in the same catalog/schema typically share
them; cascading their removal would silently break other grants. This is a
pure UI convention — no backend change, no inheritance introduced.

The panels on catalog/schema/table pages display these simplified tags
(derived from the leaf privilege). Raw privileges remain visible in the
per-user views on the Users page. External locations and credentials are no
longer granted through the UI; the raw grant modal on the Users page keeps
only metastore- and catalog-level admin privileges.

### Per-user permission views (server-load warning)

Unity Catalog has **no API to look up privileges by principal** — permissions
can only be read per securable, and the server returns other principals' rows
only to owners/admins. Consequently:

- The user drawer on the Users page shows a **catalog + schema summary**
  (one permissions query per catalog and per schema — bounded fan-out).
- The **"Details" button** on the Users list opens the full per-user view,
  which **traverses every catalog → schema → table** and issues one
  permissions GET per object plus the LIST calls. **On a metastore with many
  tables this puts significant load on the UC server and is not
  recommended** — the scan never starts automatically (explicit "Scan
  permissions" button) and the same warning is shown in the drawer. See
  `src/hooks/userAccess.ts`.

### Button gating (positive signals only)

Action buttons render enabled only on a *positive* authorization signal
(`src/hooks/authz.ts`):

1. unknown identity (auth disabled) → everything enabled, server decides;
2. the securable's `owner` field matches the current user (ancestors count
   where the server accepts them);
3. the current user's own privileges on the securable satisfy the endpoint's
   rule (e.g. `USE SCHEMA` + `CREATE TABLE` for Create Table);
4. the permissions listing shows other principals' rows — which the server
   only does for owners of the object, an ancestor, or the metastore.

Known blind spot: there is no read-only endpoint that reveals the metastore
owner, so a metastore admin who does not own a resource — in a system with no
visible grants anywhere — may see a disabled button even though the server
would authorize the call. Any visible grant (e.g. one metastore-level
privilege) restores the owner-side signal. The server remains the final
authority either way.

### Layout

The schema-browser ↔ content split and the details-page content ↔ sidebar
split are draggable (`src/components/layouts/ResizableSplit.tsx`); the chosen
widths persist in `localStorage`.

## References

This project has been merged into the main Unity Catalog repository. Per [Merging unitycatalog-ui repo into unitycatalog (main) repo (#349)](https://github.com/unitycatalog/unitycatalog/discussions/349).