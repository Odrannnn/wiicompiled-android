FROM rust:1.90-slim@sha256:7fa728f3678acf5980d5db70960cf8491aff9411976789086676bdf0c19db39e AS rust

FROM djvdw7f2ro/android-builder-soh:v1.0.4@sha256:73d44429eefdd78cecf61bcfd3ecb83c632b51bc0d674a516b5a6f6126464846
ARG RUST_TOOLCHAIN=1.88.0
ARG ANDROID_NDK_VERSION=29.0.14206865
COPY --from=rust /usr/local/cargo /usr/local/cargo
COPY --from=rust /usr/local/rustup /usr/local/rustup
ENV PATH="/usr/local/cargo/bin:${PATH}" \
    RUSTUP_HOME=/usr/local/rustup \
    CARGO_HOME=/usr/local/cargo \
    ANDROID_SDK_ROOT=/opt/android-studio
RUN rustup set auto-self-update disable \
    && rustup toolchain install "${RUST_TOOLCHAIN}" --profile minimal --target aarch64-linux-android \
    && yes | /opt/android-studio/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-studio --licenses >/dev/null \
    && /opt/android-studio/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-studio "ndk;${ANDROID_NDK_VERSION}"
WORKDIR /src
