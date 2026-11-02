# Publishing Celium updates

Celium 7.0.5 introduces in-app updates from the public releases of
`Tanveer-rajpurohit/celium`. Users see release notes and choose **Update**, **Cancel**,
or **Skip update**. Settings > App updates includes a manual check and an automatic
check switch. Automatic checks happen once per day on app startup; manual checks
ignore both the daily interval and skipped releases. Network failures do not block
startup. The new Celium update preference defaults to on independently of the old,
disabled Cloudstream update preference.

The updater checks the installed application ID, requires a newer version code,
selects the APK named in `celium-update.json`, and verifies its SHA-256 before
launching Android's installer. Android then verifies the APK signature. Installation
requires user action. The existing package installer and legacy installer are both
supported. No login token is embedded in the app; the release repository must be public.

## One-time signing setup

The APKs previously shared with users have application ID `app.celium.android.debug`.
The release workflow keeps that ID by default but produces a non-debuggable release
build. **Use the exact keystore that signed the previously distributed APK.** A
different key cannot update those installations in place. Do not generate a fresh
debug key on each CI runner. Keep the original key backed up and private.

In the repository's Settings > Secrets and variables > Actions, configure:

| Secret | Value |
| --- | --- |
| `CELIUM_KEYSTORE_BASE64` | Base64 contents of the original signing keystore |
| `CELIUM_KEYSTORE_PASSWORD` | Keystore password |
| `CELIUM_KEY_ALIAS` | Signing key alias |
| `CELIUM_KEY_PASSWORD` | Signing key password |

Only if distributing a different package, set repository variable
`CELIUM_APPLICATION_ID` to its exact application ID. This changes the installation
identity; it does not migrate existing users. Keep this variable and the key stable
for every subsequent update. Never commit keystores or their passwords.

Before the first release, compare the original APK's signing certificate to the new
APK with Android SDK `apksigner verify --print-certs <apk>`. The certificate SHA-256
digests must match. If the old key is unavailable, preserve user data by exporting a
backup before any manual migration; do not uninstall automatically.

The previously shared `celium-7.0.4.apk` has been inspected: package
`app.celium.android.debug`, version code `75`, signer `CN=Android Debug, O=Android, C=US`.
Its signing certificate SHA-256 is
`58e74ca1ff3c34bf1b49917b8837dae3927a0af60e66abed63fbd482dd25e476`.
This is a public certificate fingerprint, not a private signing key. Use it to
identify the matching original keystore before producing an update.

## Release a tested version

1. Update `versionName` and increase `versionCode` in `app/build.gradle.kts`.
   Current values are `7.0.5-celium` and `76`.
2. Commit and push the changes. Tag the intended commit with the matching numeric
   version, for example `git tag v7.0.5`, then `git push origin v7.0.5`.
3. The **Celium release** workflow runs the release packaging tests and Android unit
   tests, builds and verifies the signed universal APK, and generates the update
   manifest and `SHA256SUMS.txt` from Gradle's actual APK metadata.
4. It uploads all assets to a draft release before publishing it as latest. The
   app never discovers a partially uploaded release. The workflow generates release
   notes automatically; edit them on GitHub to give users a clear changelog.

The tag must match the APK version (`v7.0.5` for `7.0.5-celium`). Missing signing
secrets, unsigned APKs, mismatched package IDs, and mismatched tags fail the workflow.
The old Cloudstream prerelease/archive workflows are restricted to their original
repository and do not run against Celium's unavailable upstream secrets.

The first updater-enabled APK must be shared through the existing download/install
method. Older Celium versions cannot discover this feature on their own. Once that
APK is installed, later published releases are offered inside the app. Validate an
update on a device with saved titles and settings before sharing the first release.

## Local verification

Run `python -m unittest discover -s scripts/tests -v` and
`./gradlew :shared:jvmTest :app:testStableDebugUnitTest :app:assembleStableDebug` for developer checks.
For a signed distribution build, set `CELIUM_KEYSTORE_PATH`,
`CELIUM_KEYSTORE_PASSWORD`, `CELIUM_KEY_ALIAS`, and `CELIUM_KEY_PASSWORD` in the
environment, then run:

```sh
./gradlew :app:testStableReleaseUnitTest :app:assembleStableRelease -PceliumApplicationId=app.celium.android.debug
python scripts/prepare_release.py --metadata app/build/outputs/apk/stable/release/output-metadata.json --output build/celium-release --tag v7.0.5 --application-id app.celium.android.debug
```

Keep signed local artifacts private until signing compatibility is verified. Do not
upload `local.properties`, keys, passwords, or a personal GitHub token with a release.
