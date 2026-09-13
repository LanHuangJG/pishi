package pishi.gradle.plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the methodMap (method signature -> id) produced during instrumentation so a
 * task-level hook can persist it once the AGP ASM task finishes. The map is consumed
 * by the patch generation build (pishi-autopatch plugin), same file format as Robust.
 */
public final class PishiRegistry {

    public static final Map<String, Map<String, Integer>> METHOD_MAPS = new ConcurrentHashMap<>();

    private PishiRegistry() {
    }
}
