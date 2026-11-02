# Releasing

Release APKs are signed with the project's private release key. That key exists only as
encrypted secrets in this repository's GitHub settings. It is never committed, and it isn't
available to forks or to pull requests from forks.

**Repository secrets** (Settings → Secrets and variables → Actions):

| Secret | Content |
|---|---|
| `OPENMK3_KEYSTORE_BASE64` | The release keystore (PKCS12), base64-encoded |
| `OPENMK3_KEYSTORE_PASSWORD` | Keystore password |
| `OPENMK3_KEY_ALIAS` | Key alias |
| `OPENMK3_KEY_PASSWORD` | Key password |

**What CI does** (`.github/workflows/build.yml`):

| Trigger | Result |
|---|---|
| Every push and pull request | Debug APKs of both apps, as workflow artifacts |
| Push to `main`, secrets present | Signed release APKs, published as the `latest` pre-release |
| A `v*` tag (e.g. `v0.2.0`) | Signed APKs, published as a normal release |

If the secrets are missing, the signing steps are skipped and the build still succeeds.

**Keep an offline backup of the keystore and its password.** Android only installs an update
over an existing app when it's signed with the same key. If the key is lost, users have to
uninstall and reinstall.

**To make a release:**
1. Bump `versionCode` / `versionName` in `full/build.gradle.kts` and `vanilla/build.gradle.kts`.
2. Tag the commit `vX.Y.Z` and push the tag.
