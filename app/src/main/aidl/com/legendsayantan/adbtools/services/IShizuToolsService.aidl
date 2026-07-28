package com.legendsayantan.adbtools.services;
import com.legendsayantan.adbtools.services.ICommandCallback;
import android.os.ParcelFileDescriptor;

interface IShizuToolsService {
    // Returns whether the mode change actually took effect (verified via a readback) - some
    // Android versions/OEM builds silently no-op an appops write for another package issued from
    // shell-level privilege, reporting success while nothing actually changes.
    boolean setAppOpMode(String pkgName, int uid, int opCode, int mode);
    // Current mode for a single package/op (AppOpsManager.MODE_* - 0 allow,1 ignore,2 errored/deny,3 default,4 foreground).
    int getAppOpMode(String pkgName, int uid, int opCode);
    // Bulk snapshot across every package with a non-default entry for any of the given op codes,
    // as "pkg|opCode|mode\n" lines.
    String queryAppOpStates(in int[] opCodes);
    void runCommand(String command, ICommandCallback callback, int lineBundle);
    void installApks(in List<String> apkPaths, int flags, in android.content.IntentSender statusReceiver);
    void setAppEnabledState(String pkgName, boolean enabled);
    void setAppHiddenState(String pkgName, boolean hidden);
    void uninstallApp(String pkgName, in android.content.IntentSender statusReceiver);
    void restoreUninstalledApp(String pkgName, in android.content.IntentSender statusReceiver);
    List<String> getPackageStates();
    List<String> getInstalledPackages(int userId);
    void grantPermission(String pkgName, String permission);
    String getGlobalSetting(String key);
    void putGlobalSetting(String key, String value);
    // table is "system", "secure" or "global" - value "null" (case-insensitive) clears the key.
    void putSetting(String table, String key, String value);
    void forceStopPackage(String pkgName);
    void injectKeyEvent(int displayId, int keyCode);
    void injectTap(int displayId, int x, int y);
    void setAppStandbyBucket(String packageName, int bucketIndex, int userId);
    int getAppStandbyBucket(String packageName, int userId);
    void setBucketLock(String packageName, int bucket, boolean locked);

    // Virtual Mount Filesystem Operations
    ParcelFileDescriptor listDirectory(String absolutePath);
    ParcelFileDescriptor openFile(String absolutePath, String mode);
    boolean deletePath(String absolutePath);
    String createDocument(String parentPath, String mimeType, String displayName);
    // [0]=exists(0/1), [1]=isDirectory(0/1), [2]=size, [3]=lastModified. Null if the privileged
    // call itself failed (Shizuku not running/permission missing) - distinct from "doesn't exist".
    long[] statDocument(String absolutePath);

    void setPlayerVolume(int uid, float volume);
    int[] getActiveAudioUids();
}
