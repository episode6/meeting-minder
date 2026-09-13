# The CI image: every build/test dependency of the containered CI job,
# prebuilt, so a run pulls one image from GHCR and fetches nothing but the
# project's own maven dependencies (those live in the gradle cache that
# gradle/actions/setup-gradle restores). This file is the single canonical
# list of build dependencies; build-installers.yml runs its gradle job inside
# it. Same scheme as collins.
#
# One content-addressed tag: .github/workflows/ci-image.yml names the image by
# the first 12 hex of hashFiles(this file + the gradle wrapper + the daemon JVM
# pin) and only builds when GHCR does not already have that tag —
#   ghcr.io/episode6/meeting-minder-ci:<hash>
# Edit any of those files and the next run rebuilds; revert and the old tag is
# still there. Nothing rebuilds on its own, so bump the date below to pick up
# base-image / package updates.
#
# refreshed: 2026-09-13
#
# What has to move together (or `check` goes red inside the image, which is
# the point — a bump that forgets the image fails the PR, not main):
#   compileSdk (app/build.gradle.kts)   -> the platforms;android-* package
#   AGP's default build-tools            -> the build-tools;* package
#   gradle-wrapper.properties            -> baked in via the hash
#   gradle-daemon-jvm.properties         -> baked in via the hash
#
# Base: Azul's own Zulu 21 image, because gradle/gradle-daemon-jvm.properties
# pins the daemon JVM to vendor AZUL / version 21 — with a matching JDK already
# on the image the toolchain resolver detects it instead of downloading one per
# run through foojay. It launches gradlew too.
FROM azul/zulu-openjdk:21
ENV DEBIAN_FRONTEND=noninteractive
# git: actions/checkout, and the snapshot versionCode (commit count) the root
# build.gradle.kts derives. curl/unzip: the SDK bootstrap below.
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates git curl unzip \
  && rm -rf /var/lib/apt/lists/*

# Android SDK, root-owned on purpose: AGP can auto-install a missing platform
# or build-tools into ANDROID_HOME, and we want that to fail loudly here
# (permission denied) rather than silently download a component this file
# does not list. The cmdline-tools revision is the "_latest" zip's number on
# https://developer.android.com/studio as of the refreshed date above.
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=$ANDROID_HOME
ENV PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
RUN curl -fsSL -o /tmp/cmdline-tools.zip \
      https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip \
  && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools \
  && mkdir -p $ANDROID_HOME/cmdline-tools \
  && mv /tmp/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
  && rm -rf /tmp/cmdline-tools /tmp/cmdline-tools.zip \
  && yes | sdkmanager --licenses > /dev/null \
  && sdkmanager --install \
       "platform-tools" \
       "platforms;android-37.0" \
       "build-tools;36.0.0" \
  && rm -rf /root/.android
# No NDK, deliberately. The bare runner happened to have AGP's default NDK
# (28.2.13676358 for AGP 9.3), so stripDebugSymbols stripped the two prebuilt
# androidx .so files (graphics-path, datastore shared counter); without one AGP
# warns "Unable to strip ... packaging them as they are". Those libraries are
# ~10 KB each and already stripped upstream, so the warning costs nothing,
# and the NDK would add ~3 GB to the image. Revisit only if the app ever
# gains real native code.

# Container jobs run as root unless the image says otherwise. uid 1001 is the
# hosted runner's own uid, so the bind-mounted workspace is writable (and git
# raises no "dubious ownership" complaint) without a chown.
RUN useradd --uid 1001 --create-home runner
USER runner
WORKDIR /home/runner

# Warm the gradle user home as the runner user: the pinned gradle distribution
# lands under ~/.gradle/wrapper/dists and, should the resolver not take the
# image's own Zulu 21, the daemon JVM under ~/.gradle/jdks — exactly what a CI
# run would otherwise download first. `help` on an empty project is enough to
# start (and, with --no-daemon, stop) a daemon.
COPY --chown=runner:runner gradlew /tmp/warm/gradlew
COPY --chown=runner:runner gradle/wrapper /tmp/warm/gradle/wrapper
COPY --chown=runner:runner gradle/gradle-daemon-jvm.properties /tmp/warm/gradle/gradle-daemon-jvm.properties
RUN cd /tmp/warm && touch settings.gradle.kts \
  && ./gradlew --no-daemon -q help \
  && rm -rf /tmp/warm ~/.gradle/daemon ~/.gradle/.tmp
