package com.legendsayantan.adbtools.services;
import com.legendsayantan.adbtools.services.ICommandCallback;
import android.os.ParcelFileDescriptor;

interface IShizuToolsService {
    void setAppOpMode(String pkgName, int uid, int opCode, int mode);
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

    void setPlayerVolume(int uid, float volume);
    int[] getActiveAudioUids();
}
