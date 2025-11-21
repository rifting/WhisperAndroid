# WhisperAndroid
A Wisp protocol client for Android, meant for circumventing censorship in strict environments.

This is still in development - the codebase is quite cursed, for example, every UDP packet being forwarded to Cloudflare's DNS server. I would not reccomend using this for production applications quite yet.

# Compilation
Yeah so compiling this sucks lol

You may skip the tun2socks step if you'd like to use the prebuilt aar file I've provided.
Requirements:
- Android SDK
- Android NDK (older version for tun2socks, you will need to install <=21)
- Rust
- Go
- Java
The commands here are for ndk version r27d with x86_64 target. You may have to change these depending on your setup.

## Cloning
```
git clone https://github.com/rifting/WhisperAndroid.git
cd WhisperAndroid`
git submodule update --init
```

## Tun2Socks
```
cd tun2socks
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
sdkmanager --install "platforms;android-21"
sdkmanager --install "ndk;21.4.7075529"
export ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/21.4.7075529
export GOANDROIDMINAPI=19
DEST=$(pwd)/app/libs
mkdir -p "$DEST"
gomobile bind -o "$DEST/tun2socks.aar" -target android ./engine
cp -r "$DEST"/* android/app/libs/
```

## libwisp2socks.so
[Guide for setting up Rust toolchain for Android targets](https://mozilla.github.io/firefox-browser-architecture/experiments/2017-09-21-rust-on-android.html)

```
cd wisp2socks
export PATH=$PATH:~/NDK/x86_64/bin
export ANDROID_NDK_HOME=~/android-ndk-r27d 
export OPENSSL_DIR=~/android-ndk-r27d/toolchains/llvm/prebuilt/linux-x86_64
cargo build --target x86_64-linux-android --release
cp target/release/x86_64-linux-android/libwisp2socks.so ../android/app/src/main/jniLibs/x86_64
```

## App
Just build in Android Studio like usual
