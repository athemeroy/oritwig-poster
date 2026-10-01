# Building Oritwig Poster

See the root [README](../README.md) for this standalone checkout's build/install/test commands. Only `:apps:poster` and the pinned `:shared:core` are included.

`tools/setup-jdk.sh <directory>` installs the pinned verified JDK. Set `JAVA_HOME` afterward. Set `ANDROID_HOME` before running `tools/setup-android-sdk.sh --accept-sdk-license`; read the linked Google SDK terms before choosing that option. Existing compatible SDKs can be used directly.

`tools/package-artifacts.sh poster` refuses an uncommitted or changed source tree, rebuilds checks, and bundles a debug-signed development APK, unsigned release APK, exact complete source archive, build information and checksums. Release signing and store distribution are separate distributor responsibilities. The repository never contains production signing keys.

Hardware CI checks `/dev/kvm` access before starting a dedicated emulator. Emulator and system-image updates fail closed when their expected revisions change; toolchain pins do not claim indefinite reproducibility of Google's mutable SDK package endpoints.
