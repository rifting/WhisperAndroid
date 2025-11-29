<p align="center">
  <img src="https://raw.githubusercontent.com/rifting/WhisperAndroid/a970f8e08e843a74d80deeea826d231135b92b7c/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" />
</p>

# WhisperAndroid
A Wisp protocol client for Android, meant for circumventing censorship in strict environments.

# Compilation
If you are only changing the frontend, you do not need to recompile tun2socks or libwisp2socks.so unless you would like not to use the prebuilts I've included.
The same goes for compiling libwisp2socks.so - tun2socks does not have to be recompiled.

Requirements:
- Android SDK
- Android NDK
- Rust
- Go
- Java
- Linux based OS. I haven't tested compilation on Windows or MacOS.

The commands here are for ndk version r27d with x86_64 target. You may have to change these depending on your setup.

## Cloning
```
git clone https://github.com/rifting/WhisperAndroid.git
cd WhisperAndroid
git submodule update --init
```

## Tun2Socks
```
cd tun2socks
go get golang.org/x/mobile/bind@latest
go install golang.org/x/mobile/cmd/gobind@latest
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init -ndk $ANDROID_NDK_HOME -androidapi 21
sdkmanager --install "ndk;28.0.12433566"
export ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/28.0.12433566
export GOANDROIDMINAPI=19
DEST=$(pwd)/app/libs
mkdir -p "$DEST"
gomobile bind -androidapi 21 -o "$DEST/tun2socks.aar" -target android ./engine
cp -r "$DEST"/* android/app/libs/
```

## libwisp2socks.so
[Guide for setting up Rust toolchain for Android targets](https://mozilla.github.io/firefox-browser-architecture/experiments/2017-09-21-rust-on-android.html)

x86_64 example:
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
