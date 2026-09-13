package pishi.gradle.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Pishi Gradle DSL — the modern replacement for robust.xml:
 *
 * <pre>
 * pishi {
 *     hotfixPackages = ['com.example.app']
 *     exceptPackages = []
 *     forceInsertLambda = false
 * }
 * </pre>
 *
 * Values set here take precedence over robust.xml (kept for Robust migrants).
 */
public class PishiExtension {

    private List<String> hotfixPackages = new ArrayList<>();
    private List<String> exceptPackages = new ArrayList<>();
    private List<String> hotfixMethods = new ArrayList<>();
    private List<String> exceptMethods = new ArrayList<>();
    private Boolean forceInsertLambda;

    public List<String> getHotfixPackages() {
        return hotfixPackages;
    }

    public void setHotfixPackages(List<String> hotfixPackages) {
        this.hotfixPackages = hotfixPackages == null ? new ArrayList<>() : hotfixPackages;
    }

    public List<String> getExceptPackages() {
        return exceptPackages;
    }

    public void setExceptPackages(List<String> exceptPackages) {
        this.exceptPackages = exceptPackages == null ? new ArrayList<>() : exceptPackages;
    }

    public List<String> getHotfixMethods() {
        return hotfixMethods;
    }

    public void setHotfixMethods(List<String> hotfixMethods) {
        this.hotfixMethods = hotfixMethods == null ? new ArrayList<>() : hotfixMethods;
    }

    public List<String> getExceptMethods() {
        return exceptMethods;
    }

    public void setExceptMethods(List<String> exceptMethods) {
        this.exceptMethods = exceptMethods == null ? new ArrayList<>() : exceptMethods;
    }

    /** null = inherit from robust.xml (default false) */
    public Boolean getForceInsertLambda() {
        return forceInsertLambda;
    }

    public void setForceInsertLambda(Boolean forceInsertLambda) {
        this.forceInsertLambda = forceInsertLambda;
    }
}
