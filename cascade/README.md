# Cascade VPN

Android distribution of v2rayNG with the shared Cascade client policy. Install the
universal APK from this fork's Releases, import your personal VLESS profile using
QR or clipboard, select it, and approve Android's VPN request.

The APK contains no user profile. DNS and other defaults, 26 routing rules, and
13 selected application packages come from `policy/`. The original files were
copied from the administrator wizard's `client/templates/common/` and
`client/templates/android/settings.json`. Routing and application checksums are
verified before each build. To change the policy, update these files and the
checksums in `policy/policy.json` together. The settings checksum is stored in
`distribution.json`. Policy JSON is kept byte-exact by `.gitattributes`.

Only selected applications enter the VPN. The routing rules then decide which
connections use the proxy, go directly, or are blocked. A selection containing
no installed usable application cannot fall back to a device-wide VPN. Users
can change their selection in the regular per-app settings.

## Existing installations and assets

Distribution defaults fill absent setting keys, including on later launches.
Existing false, empty, and customized values are retained, with one versioned
exception for complete factory DNS tuples from releases .1/.2/.3 together with
the exact previous factory routing. Remote DNS uses private `172.29.172.254`
through the cascade; Direct DNS uses `https://8.8.8.8/dns-query` directly.
Local DNS is enabled, Fake DNS disabled, and VPN DNS remains `1.1.1.1`.
Install and verify server-side AmneziaDNS before publishing this distribution.

Changing legacy DNS values, enabling Fake DNS, or customizing routing preserves
both DNS and routing. Migration is recorded once; later user changes are retained even if
they restore an old value. Seeding and migration share the MMKV settings lock and
roll back changed values on a failed write. Profiles are not reimported.
The two endpoint rules precede private/direct rules; the final direct fallback
stays last. Native endpoint lookup still uses Android DNS. No upstream core or
configuration-generator changes are required for this DNS policy.
Profile, subscription,
selected-server, and statistics stores are never seeded by the distribution.

When the main client initializes its assets, missing `geoip.dat` and `geosite.dat`
entries are added to Asset files. Entries are matched by filename, preserving a
user's existing source URL and GUID. Missing files are copied from the APK using
a temporary file and rename. Existing files are retained, including newer or
custom databases. A failed copy leaves no partial target and is retried next time.

Automatic restoration and the normal asset downloader share one mutation lock.
Each download has a unique temporary file and acquires the lock only to commit
the completed download. If restoration is already copying a missing file, the
manual update commits afterward. If a manual update commits first, restoration
sees the existing file and skips it.

Repair checks presence, not the validity of an existing user file. It retains a
zero-byte or incompatible existing database and does not remove pre-existing
duplicate entries. Restoring one missing database also does not replace the
other, so a user-customized pair can contain different database versions.

The packaged databases come from the same stable
`runetfreedom/russia-v2ray-rules-dat` release, verified against release digests,
sizes, and SHA-256 checksum files. Their exact version and hashes appear in
`build-report.json`. Every GeoIP and GeoSite category referenced by an enabled
routing rule must also exist in the downloaded databases before a build can
proceed. Existing geodata can be refreshed with the client's normal
asset download action; repairing a missing file is separate from replacing one.

## Build and update channel

`Cascade Android` checks the latest published stable v2rayNG release every twelve
hours and can also be started manually in Actions. GitHub's schedule is best
effort. Each build checks out the exact tag and recursive native submodules,
then `prepare.py` applies the small distribution overlay. No automatic merge
or force-push can discard the policy. An incompatible upstream layout fails the
build and leaves the previous published release available.

`distribution.json` defines the label, application ID, repository, and distribution
revision. The version format is `upstreamVersion.revision`, for example
`2.3.15.1`; the Android base version code is `upstreamCode * 1000 + revision`.
Increase `revision` for a new release of distribution changes on the same upstream
version. Already published versions are not rebuilt. Keep the application ID and
signing key stable so updates preserve installed data.

The update-check endpoint points to this fork. The normal Check updates action
downloads this distribution's APK; Android handles installation confirmation.
The app has a separate application ID and installs alongside official v2rayNG.

The fork keeps the upstream source tree for reference. The source actually used
to build each APK is the selected stable tag plus `cascade/`; the resulting full
Android source is attached to each release as `source.zip`.

## Validation and signing

The workflow runs distribution script tests, Kotlin regression tests (including
legacy DNS migration, custom values, rollback and retry), the full
Play Store debug JVM suite, debug compilation and assembly, and signed release
assembly. It verifies all four native ABIs, the universal APK, and packaged DAT
hashes. An Android emulator checks a fresh installation, dummy-profile import,
reinstallation with profile/settings preservation, missing-asset repair, and
migration of the previous release's DNS values after reinstall. It also checks
that the generated native configuration includes DoH and the DNS port-53 route.
Publication occurs only after these checks pass.

Not run by this workflow: a real VPN connection using a personal server profile,
physical-device acceptance, and the service lifecycle scenarios from the scoped
service guide. A successful build or emulator configuration test does not verify
these scenarios.

Repository secrets are `CASCADE_KEYSTORE_BASE64`, `CASCADE_KEYSTORE_PASSWORD`,
and `CASCADE_KEY_ALIAS`. They hold the persistent PKCS#12 signing key. Keep an
independent backup of that key and password; the repository contains neither.

Local script checks need only Python's standard library:

```sh
python3 -m unittest discover -s cascade/tests -v
```
