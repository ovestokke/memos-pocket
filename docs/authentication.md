# Authentication

Memos Pocket uses the standard Memos authentication API. It does not require the reminder-enabled server fork.

## Password sign-in

The app reads the instance's general settings before showing the sign-in form. Username and password fields appear only when the server allows password authentication.

The password is sent once to the selected Memos server over HTTPS. It is not saved by the app. Memos Pocket stores the returned refresh credential with Android Keystore encryption and keeps the short-lived access token in process memory. Credential rotation is committed synchronously before the refreshed session is used for another resource request; a storage failure leaves the newest credential in memory, blocks further resource calls/rotations until a save succeeds, and is shown as a sync failure rather than silently falling back to an older credential. A failed reauthentication save does not replace the active account or clear its local cache.

## Single sign-on

The app lists the OAuth providers advertised by `/api/v1/identity-providers`. Provider names, endpoints, client IDs and scopes come from the server; Authelia is not hardcoded.

The authorization request opens in the system browser and uses Authorization Code, a random state value and PKCE with `S256`. The callback URI is derived from the installed application's package ID:

`com.vstokke.memos:/oauth2redirect`

A fork with another application ID automatically uses that ID as its callback scheme. Side-by-side debug builds use `com.vstokke.memos.debug:/oauth2redirect`; registering that second URI is optional and only needed for debug SSO testing.

To enable mobile SSO, add the exact callback URI to the redirect URI list for the OAuth client used by Memos. Keep the existing web callback. For the standard package, an Authelia client contains both entries:

```yaml
redirect_uris:
  - 'https://memos.example.com/auth/callback'
  - 'com.vstokke.memos:/oauth2redirect'
```

Keep Authorization Code enabled and require PKCE with the `S256` challenge method. Memos performs the token exchange with the provider; the mobile app never receives the OAuth client secret.

If an identity provider refuses private-use callback schemes, password sign-in remains available. Supporting that provider with a verified HTTPS callback requires a callback domain controlled by the app publisher or a separately built app tied to the instance domain.

## Silent session recovery and limits

During ordinary sync, an expired or rejected short-lived access token causes at most one refresh-token rotation and one bounded retry. A resource request that still returns unauthorized after that retry remains a visible sync failure and does not permanently disable a session that may still have a valid refresh credential. Older installations with an ambiguous `auth_required` marker are allowed to validate the encrypted refresh credential again; successful authenticated recovery clears that marker.

A refresh endpoint rejection is different: it is recorded as a confirmed session failure and survives process restart, so the app asks for sign-in instead of repeatedly sending a rejected credential. Network errors, server overload responses, malformed refresh responses and refresh 403 responses are not treated as proof that the credential was revoked and remain retryable. Explicit disconnect still clears the account and local data according to the app's logout behavior; local cached memos and pending changes are not cleared by an authentication failure.

This sync barrier applies to ordinary background/manual synchronization; explicit sign-in and logout may contact the server to complete their requested account transition. It provides persistent device login while the server accepts the rotating refresh credential. Server-side refresh-token lifetime, revocation, rotation grace and proxy policies remain authoritative; no client can renew a revoked or expired refresh credential, and the app never stores the password.

## Existing access-token installations

Memos Pocket no longer offers personal access token entry on the sign-in screen. Credentials saved by older versions continue to work until the user disconnects the account. Signing in again replaces the old credential with a rotating account session.
