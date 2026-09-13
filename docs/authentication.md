# Authentication

Memos Pocket uses the standard Memos authentication API. It does not require the reminder-enabled server fork.

## Password sign-in

The app reads the instance's general settings before showing the sign-in form. Username and password fields appear only when the server allows password authentication.

The password is sent once to the selected Memos server over HTTPS. It is not saved by the app. Memos Pocket stores the returned refresh credential with Android Keystore encryption and keeps the short-lived access token in process memory.

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

## Existing access-token installations

Memos Pocket no longer offers personal access token entry on the sign-in screen. Credentials saved by older versions continue to work until the user disconnects the account. Signing in again replaces the old credential with a rotating account session.
