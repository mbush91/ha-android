# Signed APK releases for the fork

The [Signed Android APK workflow](workflows/signed-apk.yml) runs after each push to `main` (including merged pull requests). It can also be run manually on `main` from **Actions**. It compiles `:app:assembleFullRelease`, verifies the APK signature with Android's `apksigner`, and attaches the signed APK to a GitHub **prerelease** with a `main-<commit>` tag.

## Configure signing once

Go to **Settings → Secrets and variables → Actions → New repository secret** in `mbush91/ha-android`. Add all four required secrets:

| Secret | Value |
| --- | --- |
| `APK_KEYSTORE_BASE64` | The complete release keystore, base64-encoded as a single string |
| `APK_KEYSTORE_PASSWORD` | The keystore password |
| `APK_KEY_ALIAS` | The alias of the signing key |
| `APK_KEY_PASSWORD` | The password for the signing key |

If you don't have an existing release key, create one locally **once** (do not generate a new key on every CI run):

```bash
keytool -genkeypair -v -keystore companion-release.jks -alias companion \
  -keyalg RSA -keysize 3072 -validity 10000
base64 -w 0 companion-release.jks
```

Put the base64 output in `APK_KEYSTORE_BASE64`. Use the passwords and alias supplied to `keytool` for the other three secrets. Back up the `.jks` file and passwords securely outside the repository. **Do not commit the keystore or any signing secrets.** You need the same signing key to update installs of your fork.

**Optional Firebase setting:** Add `GOOGLESERVICES` with the raw contents of a valid `google-services.json` for `io.homeassistant.companion.android` to enable Firebase push functionality. Without it, the workflow uses the repository's mock configuration and the APK can build and be signed, but Firebase push notifications will not work. The optional `SENTRY_DSN` secret is also supported.

## Releases and installation

After the workflow succeeds, download `home-assistant-full-<commit>.apk` from the repository's **Releases** page. The workflow fails rather than publishing anything if the signing secrets are absent, the Gradle build fails, or APK signature verification fails.

The full-release variant uses the same Android package ID as the official Home Assistant app. An APK signed with a **different key cannot be installed as an update** to the official app; Android will reject it. Back up settings and uninstall the official app before installing this fork, or use a separate application ID if both need to coexist.

The workflow deliberately does **not** upload to Google Play, reuse upstream Home Assistant's private signing keys, or publish unsigned builds.

If GitHub returns a permission error while creating a release, check repository **Settings → Actions → General → Workflow permissions** and organization policy to ensure the workflow can request `contents: write`.
