package princeps.behavior;

/** Pure activation gate shared by every automatic survival inventory action. */
final class SurvivalActivityPolicy {

    private SurvivalActivityPolicy() {}

    static boolean isActive(boolean autoSurvivalEnabled, boolean navigationActive) {
        return autoSurvivalEnabled && navigationActive;
    }
}
