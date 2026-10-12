package android.content;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 测试替身：进程内内存版 SharedPreferences，语义与 Android 一致——
 * {@code edit()} 返回<b>快照式</b> Editor，只有 {@code apply()} 才写回。
 *
 * <p>这一点很关键：如果把 Editor 写成直接改底层 map，就测不出真实的提交时序，
 * 而 MAGI 的设置页正是「编辑 → apply」这个模式。
 */
public class Context {

    public static final int MODE_PRIVATE = 0;

    private final Map<String, MemPrefs> stores = new HashMap<String, MemPrefs>();

    public SharedPreferences getSharedPreferences(String name, int mode) {
        MemPrefs p = stores.get(name);
        if (p == null) {
            p = new MemPrefs();
            stores.put(name, p);
        }
        return p;
    }

    private static final class MemPrefs implements SharedPreferences {
        private final Map<String, Object> map = new HashMap<String, Object>();

        public String getString(String k, String d) {
            Object v = map.get(k);
            return v instanceof String ? (String) v : d;
        }

        public boolean getBoolean(String k, boolean d) {
            Object v = map.get(k);
            return v instanceof Boolean ? (Boolean) v : d;
        }

        public int getInt(String k, int d) {
            Object v = map.get(k);
            return v instanceof Integer ? (Integer) v : d;
        }

        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String k, Set<String> d) {
            Object v = map.get(k);
            return v instanceof Set ? (Set<String>) v : d;
        }

        public Editor edit() {
            return new MemEditor(this);
        }

        public Map<String, ?> getAll() {
            return Collections.unmodifiableMap(map);
        }
    }

    private static final class MemEditor implements SharedPreferences.Editor {
        private final MemPrefs target;
        private final Map<String, Object> pending = new HashMap<String, Object>();
        private final Set<String> removals = new HashSet<String>();
        private boolean cleared;

        MemEditor(MemPrefs target) {
            this.target = target;
        }

        public SharedPreferences.Editor putString(String k, String v) {
            pending.put(k, v);
            removals.remove(k);
            return this;
        }

        public SharedPreferences.Editor putBoolean(String k, boolean v) {
            pending.put(k, Boolean.valueOf(v));
            removals.remove(k);
            return this;
        }

        public SharedPreferences.Editor putInt(String k, int v) {
            pending.put(k, Integer.valueOf(v));
            removals.remove(k);
            return this;
        }

        public SharedPreferences.Editor putStringSet(String k, Set<String> v) {
            pending.put(k, new HashSet<String>(v));
            removals.remove(k);
            return this;
        }

        public SharedPreferences.Editor remove(String k) {
            removals.add(k);
            pending.remove(k);
            return this;
        }

        public SharedPreferences.Editor clear() {
            cleared = true;
            return this;
        }

        public void apply() {
            if (cleared) {
                target.map.clear();
                cleared = false;
            }
            for (String k : removals) target.map.remove(k);
            target.map.putAll(pending);
            pending.clear();
            removals.clear();
        }

        public boolean commit() {
            apply();
            return true;
        }
    }
}
