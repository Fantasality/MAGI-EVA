package android.content;

import java.util.Map;
import java.util.Set;

/**
 * 测试替身：只为在 JVM 上跑通 core/Prefs.java 的接口形状，不含任何 Android 行为。
 * 真正进 APK 的是 android.jar 里的实现，这个文件<不参与打包>。
 */
public interface SharedPreferences {

    String getString(String key, String defValue);

    boolean getBoolean(String key, boolean defValue);

    int getInt(String key, int defValue);

    Set<String> getStringSet(String key, Set<String> defValues);

    Editor edit();

    interface Editor {
        Editor putString(String key, String value);

        Editor putBoolean(String key, boolean value);

        Editor putInt(String key, int value);

        Editor putStringSet(String key, Set<String> values);

        Editor remove(String key);

        Editor clear();

        void apply();

        boolean commit();
    }

    /** 便于测试遍历用，非 Android 原接口。 */
    Map<String, ?> getAll();
}
