using System.IO;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEditor.SceneManagement;
using UnityEngine;

namespace MedAdherence.EditorTools
{
    /// <summary>
    /// One-click Android setup and build.
    ///   Menu:        MedAdherence > Configure for Android, MedAdherence > Build Android APK
    ///   Batch mode:  Unity -batchmode -quit -projectPath MedAdherenceUnity
    ///                      -executeMethod MedAdherence.EditorTools.BuildAndroid.Build
    /// </summary>
    public static class BuildAndroid
    {
        const string ScenePath = "Assets/MedAdherence/Scenes/Main.unity";
        const string ApkPath = "Builds/Android/MedAdherence.apk";
        const string AppId = "com.chemrob.medadherence";

        [MenuItem("MedAdherence/Configure for Android")]
        public static void Configure()
        {
            EnsureScene();

            PlayerSettings.companyName = "ChemRob";
            PlayerSettings.productName = "MedAdherence";
            PlayerSettings.bundleVersion = "1.0.0";
            PlayerSettings.Android.bundleVersionCode = 1;
            PlayerSettings.SetApplicationIdentifier(BuildTargetGroup.Android, AppId);
            PlayerSettings.SetScriptingBackend(BuildTargetGroup.Android, ScriptingImplementation.IL2CPP);
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64 | AndroidArchitecture.ARMv7;
            PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel24;
            PlayerSettings.Android.targetSdkVersion = (AndroidSdkVersions)34;
            PlayerSettings.defaultInterfaceOrientation = UIOrientation.Portrait;
            PlayerSettings.runInBackground = false;
            PlayerSettings.SplashScreen.showUnityLogo = false;

            EditorBuildSettings.scenes = new[] { new EditorBuildSettingsScene(ScenePath, true) };
            if (EditorUserBuildSettings.activeBuildTarget != BuildTarget.Android)
                EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.Android, BuildTarget.Android);
            AssetDatabase.SaveAssets();
            Debug.Log("[MedAdherence] Project configured for Android (" + AppId + ").");
        }

        [MenuItem("MedAdherence/Build Android APK")]
        public static void Build()
        {
            Configure();
            EditorUserBuildSettings.buildAppBundle = false;
            Directory.CreateDirectory(Path.GetDirectoryName(ApkPath));
            var report = BuildPipeline.BuildPlayer(new BuildPlayerOptions
            {
                scenes = new[] { ScenePath },
                locationPathName = ApkPath,
                target = BuildTarget.Android,
                options = BuildOptions.None,
            });

            if (report.summary.result == BuildResult.Succeeded)
            {
                Debug.Log("[MedAdherence] APK built: " + Path.GetFullPath(ApkPath));
                if (!Application.isBatchMode) EditorUtility.RevealInFinder(ApkPath);
            }
            else
            {
                Debug.LogError("[MedAdherence] Build failed: " + report.summary.result);
                if (Application.isBatchMode) EditorApplication.Exit(1);
            }
        }

        /// <summary>Creates the single scene the app needs; all UI is built from code at runtime.</summary>
        [MenuItem("MedAdherence/Create Main Scene")]
        public static void EnsureScene()
        {
            if (File.Exists(ScenePath)) return;
            Directory.CreateDirectory(Path.GetDirectoryName(ScenePath));
            var scene = EditorSceneManager.NewScene(NewSceneSetup.DefaultGameObjects, NewSceneMode.Single);
            var cam = Camera.main;
            if (cam != null)
            {
                cam.clearFlags = CameraClearFlags.SolidColor;
                cam.backgroundColor = new Color(0.95f, 0.96f, 0.97f);
            }
            new GameObject("MedAdherenceApp").AddComponent<MedAdherenceApp>();
            EditorSceneManager.SaveScene(scene, ScenePath);
            AssetDatabase.Refresh();
        }
    }
}
