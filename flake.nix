{
  description = "Food you development environment";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs?ref=nixos-unstable";
  };

  outputs =
    { self, nixpkgs }:
    let
      system = "x86_64-linux";

      pkgs = import nixpkgs {
        inherit system;
        config.android_sdk.accept_license = true;
        config.allowUnfree = true;
      };

      buildToolsVersion = "35.0.0";
      platformVersion = "36";

      androidComposition = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ platformVersion ];
        buildToolsVersions = [ buildToolsVersion ];
        includeEmulator = false;
        includeNDK = false;
        abiVersions = [ "x86_64" ];
        includeSystemImages = false;
      };

      ktfmtJar = pkgs.fetchurl {
        url = "https://github.com/facebook/ktfmt/releases/download/v0.61/ktfmt-0.61-with-dependencies.jar";
        sha256 = "b2a6ef02352a4c4ada96610196038129a877d7cddd34fe5290c764bce98cd5f9";
      };
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        buildInputs = with pkgs; [
          just
          temurin-bin-21
          zensical
          androidComposition.androidsdk
        ];

        KTFMT_JAR = "${ktfmtJar}";

        ANDROID_HOME = "${androidComposition.androidsdk}/libexec/android-sdk";
        ANDROID_SDK_ROOT = "${androidComposition.androidsdk}/libexec/android-sdk";
        ANDROID_NDK_ROOT = "${androidComposition.androidsdk}/libexec/android-sdk/ndk-bundle";
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidComposition.androidsdk}/libexec/android-sdk/build-tools/${buildToolsVersion}/aapt2";

        shellHook = ''
          export PATH="$ANDROID_HOME/build-tools/${buildToolsVersion}:$PATH"
          export PATH="$ANDROID_HOME/platform-tools:$PATH"
          export PATH="$ANDROID_HOME/emulator:$PATH"
          export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

          just | ${pkgs.lolcat}/bin/lolcat
        '';
      };
    };
}
