# Releasing for Obtainium

Obtainium installs the APK attached to a GitHub release. Memos Pocket publishes one universal APK for each stable tag.

## One-time signing setup

Create the release keystore on a trusted local machine. Do not create it in GitHub Actions.

1. Create a private directory:

   `install -d -m 700 ~/.local/share/memos-pocket`

2. Generate the permanent key. `keytool` asks for the passwords and certificate details:

   `keytool -genkeypair -v -keystore ~/.local/share/memos-pocket/release.jks -alias memos-pocket -keyalg RSA -keysize 4096 -validity 36500`

3. Make an encrypted offline backup of `release.jks` and its passwords. Test that the backup can be restored before publishing the first release. Future APKs must use this same key.

4. Add the keystore to GitHub Secrets:

   `base64 -w 0 ~/.local/share/memos-pocket/release.jks | gh secret set MEMOS_POCKET_KEYSTORE_BASE64`

5. Add the three remaining secrets. Each command prompts for the value:

   `gh secret set MEMOS_POCKET_STORE_PASSWORD`

   `gh secret set MEMOS_POCKET_KEY_ALIAS`

   `gh secret set MEMOS_POCKET_KEY_PASSWORD`

The key alias is `memos-pocket` when the command above is used. Never commit the keystore, passwords, or Base64 value.

## Publish a release

Release tags must use `vMAJOR.MINOR.PATCH`. The workflow converts the tag to both Android version fields. For example, `v0.2.0` produces `versionName=0.2.0` and `versionCode=2000`.

1. Confirm that the intended commit is on `main` and Android CI passed.
2. Create an annotated tag: `git tag -a v0.2.0 -m "Memos Pocket 0.2.0"`
3. Push the tag: `git push origin v0.2.0`
4. Check the **Publish Android release** workflow on GitHub.
5. Download the APK once and verify that Android reports it as signed before sharing the release.

The workflow runs unit tests and lint, builds a signed release APK, verifies its signature, and publishes:

- `memos-pocket-<version>.apk`
- `memos-pocket-<version>.apk.sha256`

Do not reuse a tag or replace an APK after publication. Publish a new patch version instead.

## Add Memos Pocket to Obtainium

Use this source URL:

`https://github.com/ovestokke/memos-pocket`

Choose the GitHub source if Obtainium does not detect it automatically. Stable releases need no custom APK filter because each release contains one APK.

The debug build installed during development has a different signing certificate. Before the first Obtainium installation, uninstall the debug app and install the release APK. This clears the app's local credentials and requires one new login. Later Obtainium releases update normally when they use the permanent release key and a higher `versionCode`.
