FROM djvdw7f2ro/android-builder-soh:v1.0.4@sha256:73d44429eefdd78cecf61bcfd3ecb83c632b51bc0d674a516b5a6f6126464846
ARG ANDROID_NDK_VERSION=29.0.14206865
ENV ANDROID_SDK_ROOT=/opt/android-studio
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
       ca-certificates clang cmake lld ninja-build python3 xz-utils \
    && rm -rf /var/lib/apt/lists/* \
    && yes | /opt/android-studio/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-studio --licenses >/dev/null \
    && /opt/android-studio/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-studio "ndk;${ANDROID_NDK_VERSION}"
COPY build.sh /usr/local/bin/build-wiicompiled-llvm
RUN chmod 755 /usr/local/bin/build-wiicompiled-llvm
WORKDIR /src
