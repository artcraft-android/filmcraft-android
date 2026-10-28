# F-Droid

FilmCraft for Android is built for F-Droid as a **reproducible build**: F-Droid's build server
builds each tagged release from source and checks it is byte-for-byte the APK on GitHub Releases,
then ships ours (signed with our key), so people can move between F-Droid and GitHub builds without
reinstalling.

`io.github.artcraftandroid.filmcraft.yml` is the recipe for
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) (`metadata/io.github.artcraftandroid.filmcraft.yml`).
The store listing (title, descriptions, changelogs, icon, screenshots) is in
`fastlane/metadata/android/en-US/` at the repository root; F-Droid reads it from there.

## What makes the build reproducible

- **One Rust release** (`rust-toolchain.toml`), **one cargo-ndk** (4.1.2) and **one NDK**
  (29.0.14206865) everywhere: CI, F-Droid and your machine.
- **No build paths in the Rust library**: the Gradle task that runs cargo-ndk remaps the checkout
  and the Cargo home (`--remap-path-prefix`), and no crate built for Android keeps its source
  folder's path (`env!("CARGO_MANIFEST_DIR")`), so where it was built leaves no trace.
- **No AGP extras that differ**: no VCS info and no dependency metadata block in the APK
  (F-Droid's checker rejects the latter), and no baseline profile.
- **Nothing is downloaded during the build** but source. The speech models for captions and the
  natural voices are not in the APK at all: the app downloads them only when the user asks (each
  file pinned to a revision and checked against its SHA-256).
- **One APK, for 64-bit ARM**, with version code (version code × 10) + 2. F-Droid builds it with
  `filmcraft.unsigned=true` and compares it with ours.

Releases are built by the release workflow on Linux, as F-Droid's are.

## A release

1. Set `filmcraft.version` and `filmcraft.versionCode` in
   `apps/filmcraft-android/android/gradle.properties` (the build checks they agree: the code is
   `((major·100 + minor)·100 + patch)·100 + (pre-release number, or 99)`, e.g. 0.0.2-beta → 201).
2. Add `fastlane/metadata/android/en-US/changelogs/<code>2.txt` (at most 500 characters).
3. Commit, tag `v<version>` and push the tag; the workflow refuses a tag that isn't the version in
   gradle.properties. Publish the draft release it makes.
4. F-Droid's update check finds the tag and adds the build itself (`AutoUpdateMode: Version`).

The release key is the shared `artcraft-android` key (`CN=artcraft-android`), the one every
artcraft-android app signs with. Its certificate SHA-256 (`AllowedAPKSigningKeys`):
`75c802d4487299b7a5ff236077b90aed19124ca49fe046b381d467af038ceff2`.
