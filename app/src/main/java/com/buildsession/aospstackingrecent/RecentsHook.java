package com.buildsession.aospstackingrecent;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.os.SystemClock;
import android.animation.TimeInterpolator;
import android.view.animation.LinearInterpolator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_MethodHook;

/**
 * 核心 Hook 逻辑，通过修改 RecentsView 实现多任务界面的卡片堆叠视觉效果。
 */
public class RecentsHook {
    private static final String PREFS_NAME = "com.buildsession.aospstackingrecent_preferences";
    private static final String KEY_SPACING = "card_spacing_factor";
    private static final String KEY_TIGHTNESS = "card_tightness_factor";
    private static final float DEFAULT_SPACING = 4.7f;
    private static final float DEFAULT_TIGHTNESS = 0.72f;
    private static final long DEBUG_LOG_THROTTLE_MS = 1500;
    private static final float DISMISS_FILL_SHIFT_FACTOR = 0.75f;

    // AxStack's AxStackLayout works in page indices, not pixels:
    //   stackSpacing = primarySize * 0.23   (one stack step)
    //   cards beyond STACK_RANGE_PAGES are not drawn at all
    private static final float STACK_SPACING_FACTOR = 0.23f;
    private static final float STACK_RANGE_PAGES = 4.5f;

    private final ClassLoader classLoader;
    private final XSharedPreferences prefs;

    private Class<?> recentsViewClass;
    private Class<?> taskViewClass;
    private Class<?> orientationHandlerClass;
    
    private Field persistentTranslationXField;
    private Field nonGridTranslationXField;
    private Field dismissTranslationXField;
    
    private Method getPagedOrientationHandlerMethod;
    private Method getPrimaryScrollMethod;
    private Method getMeasuredSizeMethod;
    private Method getChildStartMethod;
    private Method getOffsetAdjustmentMethod;

    public RecentsHook(ClassLoader classLoader) {
        this.classLoader = classLoader;
        this.prefs = new XSharedPreferences("com.buildsession.aospstackingrecent");
        
        try {
            java.io.File file = this.prefs.getFile();
            XposedBridge.log("AOSPStackingRecent: Checking prefs at: " + file.getAbsolutePath());
            if (file.exists()) {
                XposedBridge.log("AOSPStackingRecent: Prefs file found. Readable: " + file.canRead());
            } else {
                XposedBridge.log("AOSPStackingRecent: Prefs file NOT found! This usually means MainActivity hasn't saved settings yet or permissions are restricted.");
            }
        } catch (Throwable t) {
            XposedBridge.log("AOSPStackingRecent: Error checking prefs file: " + t.getMessage());
        }
    }

    public void initHooks() {
        XposedBridge.log("AOSPStackingRecent: Initializing hooks for ClassLoader: " + classLoader);
        try {
            recentsViewClass = XposedHelpers.findClassIfExists("com.android.quickstep.views.RecentsView", classLoader);
            taskViewClass = XposedHelpers.findClassIfExists("com.android.quickstep.views.TaskView", classLoader);
            orientationHandlerClass = XposedHelpers.findClassIfExists("com.android.launcher3.touch.PagedOrientationHandler", classLoader);

            if (recentsViewClass == null || taskViewClass == null || orientationHandlerClass == null) {
                XposedBridge.log("AOSPStackingRecent: Critical classes not found, aborting.");
                return;
            }

            // Probing Fields
            persistentTranslationXField = findField(taskViewClass, "persistentTranslationX", "mPersistentTranslationX");
            nonGridTranslationXField = findField(taskViewClass, "nonGridTranslationX", "mNonGridTranslationX");
            dismissTranslationXField = findField(taskViewClass, "dismissTranslationX", "mDismissTranslationX");

            // Probing Methods
            getPagedOrientationHandlerMethod = findMethod(recentsViewClass, "getPagedOrientationHandler");
            getPrimaryScrollMethod = findMethod(orientationHandlerClass, "getPrimaryScroll", View.class);
            getMeasuredSizeMethod = findMethod(orientationHandlerClass, "getMeasuredSize", View.class);
            getChildStartMethod = findMethod(orientationHandlerClass, "getChildStart", View.class);
            
            // Hook getOffsetAdjustment to report our stack offset to Launcher's layout/snapping logic.
            // This ensures that snap-to-page and hit-testing work correctly with our shifts.
            Method getOffsetAdjustmentMethod = findMethod(taskViewClass, "getOffsetAdjustment");
            if (getOffsetAdjustmentMethod != null) {
                XposedBridge.hookMethod(getOffsetAdjustmentMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        View child = (View) param.thisObject;
                        Float extra = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_offset");
                        if (extra != null && extra != 0f) {
                            float original = (float) param.getResult();
                            param.setResult(original + extra);
                        }
                    }
                });
                XposedBridge.log("AOSPStackingRecent: Hooked getOffsetAdjustment");
             }

             // Hook getPageSpacing to physically bring cards closer and reduce scroll distance
             Method getPageSpacingMethod = findMethod(recentsViewClass, "getPageSpacing");
             if (getPageSpacingMethod != null) {
                 XposedBridge.hookMethod(getPageSpacingMethod, new XC_MethodHook() {
                     @Override
                     protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                         prefs.reload();
                         float spacingFactor = prefs.getFloat(KEY_SPACING, DEFAULT_SPACING);
                         int original = (int) param.getResult();
                         float deltaFactor = spacingFactor - DEFAULT_SPACING;
                         int deltaPx = Math.round(deltaFactor * 10f);
                         param.setResult(original + deltaPx - 120);
                         // --- DIAGNOSTIC ---
                         try {
                             Log.i("AStack", "PAGESPACING original=" + original
                                     + " factor=" + spacingFactor
                                     + " delta=" + deltaPx
                                     + " result=" + (original + deltaPx - 120));
                         } catch (Throwable ignored) {}
                     }
                 });
                 XposedBridge.log("AOSPStackingRecent: Hooked getPageSpacing");
             }

             // Unify entry state: Hook onStateTransitionStart and onGestureAnimationStart
             // to ensure the internal mPageSpacing field is updated before any animation
             Class<?> launcherRecentsViewClass = XposedHelpers.findClassIfExists("com.android.quickstep.views.LauncherRecentsView", classLoader);
             XC_MethodHook entryHook = new XC_MethodHook() {
                 @Override
                 protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                     updatePageSpacingField(param.thisObject);
                 }
             };

             if (launcherRecentsViewClass != null) {
                 XposedBridge.hookAllMethods(launcherRecentsViewClass, "onStateTransitionStart", entryHook);
             }
             XposedBridge.hookAllMethods(recentsViewClass, "onGestureAnimationStart", entryHook);

             // Additional hooks for early state synchronization
             XposedBridge.hookAllMethods(recentsViewClass, "onMeasure", new XC_MethodHook() {
                 @Override
                 protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                     updatePageSpacingField(param.thisObject);
                 }
             });

             XposedBridge.hookAllMethods(recentsViewClass, "onVisibilityChanged", new XC_MethodHook() {
                 @Override
                 protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                     if (param.args != null && param.args.length >= 2 && (int)param.args[1] == View.VISIBLE) {
                         updatePageSpacingField(param.thisObject);
                     }
                 }
             });

             // Ensure mPageSpacing is updated for dismiss animations too (createTaskDismissAnimation uses the field)
             Class<?> pendingAnimationClass = XposedHelpers.findClassIfExists(
                     "com.android.launcher3.anim.PendingAnimation", classLoader);
             if (pendingAnimationClass != null) {
                 Method createTaskDismissAnimationMethod = findMethod(
                         recentsViewClass,
                         "createTaskDismissAnimation",
                         pendingAnimationClass,
                         taskViewClass,
                         boolean.class,
                         boolean.class,
                         long.class,
                         boolean.class
                 );
                 if (createTaskDismissAnimationMethod != null) {
                     XposedBridge.hookMethod(createTaskDismissAnimationMethod, new XC_MethodHook() {
                         @Override
                         protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                             updatePageSpacingField(param.thisObject);
                         }
                     });
                     XposedBridge.log("AOSPStackingRecent: Hooked createTaskDismissAnimation (sync page spacing)");
                 }
             }

             // Hook TaskView.applyTranslationX and Y to inject our stack offset
            // NOTE: On many Launcher versions, applyTranslationX already calls getOffsetAdjustment().
            // If it does, our hook above handles it. If it doesn't, we might need this hook as a fallback.
            // We'll keep this hook but ONLY apply the subtraction of dismissTranslationX here,
            // to avoid double-adding the apple_stack_offset if getOffsetAdjustment is already working.
            Method applyTranslationXMethod = findMethod(taskViewClass, "applyTranslationX");
            if (applyTranslationXMethod != null) {
                XposedBridge.hookMethod(applyTranslationXMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        View child = (View) param.thisObject;
                        // The stack position is applied absolutely by getOffsetAdjustment().
                        // This hook deliberately does NOT add any offset: the launcher calls it
                        // every frame and accumulating a delta here is what produced the jitter.
                        
                        // --- DIAGNOSTIC: dump TaskView structure + offset values ---
                        try {
                            float offv = extra == null ? 0f : extra;
                            if (Math.abs(offv) > 0.01f) {
                                Object lastObj = XposedHelpers.getAdditionalInstanceField(child, "astep_last_log");
                                long now = SystemClock.uptimeMillis();
                                boolean due = !(lastObj instanceof Long) || now - (Long) lastObj >= 120L;
                                if (due) {
                                XposedHelpers.setAdditionalInstanceField(child, "astep_last_log", now);
                                StringBuilder sb = new StringBuilder();
                                sb.append("applyTX tv=").append(System.identityHashCode(child))
                                  .append(" off=").append(String.format("%.1f", extra))
                                  .append(" tx=").append(String.format("%.1f", child.getTranslationX()))
                                  .append(" left=").append(child.getLeft())
                                  .append(" scrollX=").append(child.getScrollX())
                                  .append(" | kids=");
                                if (child instanceof ViewGroup) {
                                    ViewGroup vg = (ViewGroup) child;
                                    for (int i = 0; i < vg.getChildCount(); i++) {
                                        View sub = vg.getChildAt(i);
                                        if (sub == null) continue;
                                        sb.append("[").append(i).append(":")
                                          .append(sub.getClass().getSimpleName())
                                          .append(" id=").append(sub.getId())
                                          .append(" tx=").append(String.format("%.1f", sub.getTranslationX()))
                                          .append(" l=").append(sub.getLeft())
                                          .append("] ");
                                    }
                                }
                                Log.i("AStack", sb.toString());
                                }
                            }
                        } catch (Throwable ignored) {}
                        
                        // IMPORTANT: When a task is being dismissed, Launcher applies its own 'dismissTranslationX'.
                        // We counteract it because we handle the gap-filling ourselves via physical linkage.
                        if (dismissTranslationXField != null) {
                            try {
                                float dismissTx = dismissTranslationXField.getFloat(child);
                                if (Math.abs(dismissTx) > 0.1f) {
                                    child.setTranslationX(child.getTranslationX() - dismissTx);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                });
            }

            Method applyTranslationYMethod = findMethod(taskViewClass, "applyTranslationY");
            if (applyTranslationYMethod != null) {
                XposedBridge.hookMethod(applyTranslationYMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        View child = (View) param.thisObject;
                        Float extra = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_stack_offset_y");
                        if (extra != null && extra != 0f) {
                            child.setTranslationY(child.getTranslationY() + extra);
                        }
                    }
                });
            }

            // Hook resetViewTransforms to maintain our Z-index and force re-apply our stack translation
             Method resetViewTransformsMethod = findMethod(taskViewClass, "resetViewTransforms");
             if (resetViewTransformsMethod != null) {
                 XposedBridge.hookMethod(resetViewTransformsMethod, new XC_MethodHook() {
                     @Override
                     protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                         View child = (View) param.thisObject;
                         Float targetZ = (Float) XposedHelpers.getAdditionalInstanceField(child, "apple_target_z");
                         if (targetZ != null) {
                             child.setTranslationZ(targetZ);
                         }
                         // Re-apply our custom X/Y translation after the system reset
                         XposedHelpers.callMethod(child, "applyTranslationX");
                         XposedHelpers.callMethod(child, "applyTranslationY");
                     }
                 });
             }

            Method updateCurvePropertiesMethod = findMethod(recentsViewClass, "updateCurveProperties");
            if (updateCurvePropertiesMethod != null) {
                XposedBridge.hookMethod(updateCurvePropertiesMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        handleUpdateCurveProperties(param);
                    }
                });
                XposedBridge.log("AOSPStackingRecent: Successfully hooked updateCurveProperties");
            }

            XposedBridge.hookAllMethods(recentsViewClass, "dispatchDraw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // --- DIAGNOSTIC: dump the full hierarchy once per settle ---
                    try {
                        ViewGroup rv = (ViewGroup) param.thisObject;
                        long nowH = SystemClock.uptimeMillis();
                        Object lastH = XposedHelpers.getAdditionalInstanceField(rv, "astep_tree_log");
                        if (!(lastH instanceof Long) || nowH - (Long) lastH >= 1500L) {
                            XposedHelpers.setAdditionalInstanceField(rv, "astep_tree_log", nowH);
                            Log.i("AStack", "==== TREE ====");
                            dumpTree(rv, 0);
                        }
                    } catch (Throwable ignored) {}
                    // --- DIAGNOSTIC: who is in the scrollable container, and their real positions ---
                    try {
                        ViewGroup rv = (ViewGroup) param.thisObject;
                        long nowD = SystemClock.uptimeMillis();
                        Object lastD = XposedHelpers.getAdditionalInstanceField(rv, "astep_last_draw_log");
                        boolean dueD = !(lastD instanceof Long) || nowD - (Long) lastD >= 120L;
                        if (dueD) {
                        XposedHelpers.setAdditionalInstanceField(rv, "astep_last_draw_log", nowD);
                        StringBuilder sb = new StringBuilder("RecentsView kids=" + rv.getChildCount()
                                + " scrollX=" + rv.getScrollX() + " | ");
                        for (int i = 0; i < rv.getChildCount(); i++) {
                            View c = rv.getChildAt(i);
                            if (c == null) continue;
                            sb.append("[").append(i).append(":")
                              .append(c.getClass().getSimpleName())
                              .append(" l=").append(c.getLeft())
                              .append(" tx=").append(String.format("%.1f", c.getTranslationX()))
                              .append(" sx=").append(c.getScrollX())
                              .append("] ");
                        }
                        Log.i("AStack", sb.toString());
                        }
                    } catch (Throwable ignored) {}
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object untilObj = XposedHelpers.getAdditionalInstanceField(param.thisObject, "apple_dismiss_until");
                        if (untilObj instanceof Long) {
                            long until = (Long) untilObj;
                            if (SystemClock.uptimeMillis() < until) {
                                XposedHelpers.callMethod(param.thisObject, "updateCurveProperties");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            XposedBridge.hookAllMethods(recentsViewClass, "createTaskDismissAnimation", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    updatePageSpacingField(param.thisObject);
                    try {
                        if (param.args != null && param.args.length >= 5) {
                            Object durationObj = param.args[4];
                            long duration = durationObj instanceof Long ? (Long) durationObj
                                    : durationObj instanceof Integer ? ((Integer) durationObj).longValue()
                                    : 0L;
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_dismiss_until",
                                    SystemClock.uptimeMillis() + duration + 200L);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            XposedBridge.hookAllMethods(recentsViewClass, "translateTaskWhenDismissed", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    handleTranslateTaskWhenDismissed(param);
                }
            });

            // Fallback for interaction: hook getTaskViewAt if getOffsetAdjustment is not enough
            Method getTaskViewAtMethod = findMethod(recentsViewClass, "getTaskViewAt", float.class, float.class);
            if (getTaskViewAtMethod != null) {
                XposedBridge.hookMethod(getTaskViewAtMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.getResult() == null) {
                            handleGetTaskViewAt(param);
                        }
                    }
                });
            }

        } catch (Throwable t) {
            XposedBridge.log("AOSPStackingRecent: Error during initHooks: " + Log.getStackTraceString(t));
        }
    }

    private Field findField(Class<?> clazz, String... names) {
        for (String name : names) {
            Field f = XposedHelpers.findFieldIfExists(clazz, name);
            if (f != null) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        Method m = XposedHelpers.findMethodExactIfExists(clazz, name, (Object[]) parameterTypes);
        if (m != null) {
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    private float lastSpacingFactor = -1;
    private float lastTightnessFactor = -1;
    private long lastDismissHookLogUptime;

    private void dumpTree(View v, int depth) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < depth; i++) sb.append("  ");
            sb.append(v.getClass().getSimpleName())
              .append(" x=").append(String.format("%.1f", v.getX()))
              .append(" y=").append(String.format("%.1f", v.getY()))
              .append(" w=").append(v.getWidth()).append(" h=").append(v.getHeight())
              .append(" tx=").append(String.format("%.1f", v.getTranslationX()))
              .append(" a=").append(String.format("%.2f", v.getAlpha()));
            if (v.getBackground() != null) {
                sb.append(" bg=").append(v.getBackground().getClass().getSimpleName());
            }
            Log.i("AStack", sb.toString());
            if (depth < 6 && v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c != null) dumpTree(c, depth + 1);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void handleUpdateCurveProperties(XC_MethodHook.MethodHookParam param) {
        ViewGroup recentsView = (ViewGroup) param.thisObject;
        if (recentsView == null) return;

        try {
            if (getPagedOrientationHandlerMethod == null) return;
            Object handler = getPagedOrientationHandlerMethod.invoke(recentsView);
            if (handler == null) return;

            // Reload prefs to get dynamic changes
            prefs.reload();
            float spacingFactor = prefs.getFloat(KEY_SPACING, DEFAULT_SPACING);
            float tightnessFactor = prefs.getFloat(KEY_TIGHTNESS, DEFAULT_TIGHTNESS);
            if (spacingFactor != lastSpacingFactor || tightnessFactor != lastTightnessFactor) {
                XposedBridge.log("AOSPStackingRecent: Parameters updated - Spacing: " + spacingFactor + ", Tightness: " + tightnessFactor);
                lastSpacingFactor = spacingFactor;
                lastTightnessFactor = tightnessFactor;
            }

            int scroll = (int) getPrimaryScrollMethod.invoke(handler, recentsView);
            int measuredSize = (int) getMeasuredSizeMethod.invoke(handler, recentsView);
            
            if (measuredSize <= 0) return; // Avoid invalid calculations on first frame

            float center = scroll + measuredSize / 2f;
            
            // --- DIAGNOSTIC: verify coordinate space ---
            try {
                long nowC = SystemClock.uptimeMillis();
                Object lastC = XposedHelpers.getAdditionalInstanceField(recentsView, "astep_coord_log");
                if (!(lastC instanceof Long) || nowC - (Long) lastC >= 300L) {
                    XposedHelpers.setAdditionalInstanceField(recentsView, "astep_coord_log", nowC);
                    StringBuilder csb = new StringBuilder("COORD scroll=" + scroll
                            + " measuredSize=" + measuredSize
                            + " center=" + String.format("%.1f", center) + " | ");
                    for (int ci = 0; ci < recentsView.getChildCount(); ci++) {
                        View cv = recentsView.getChildAt(ci);
                        if (cv == null || !taskViewClass.isInstance(cv)) continue;
                        int cStart = (int) getChildStartMethod.invoke(handler, cv);
                        int cSize = (int) getMeasuredSizeMethod.invoke(handler, cv);
                        float cCenter = cStart + cSize / 2f;
                        csb.append("[i=").append(ci)
                           .append(" start=").append(cStart)
                           .append(" size=").append(cSize)
                           .append(" center=").append(String.format("%.0f", cCenter))
                           .append(" dist=").append(String.format("%.0f", cCenter - center))
                           .append(" left=").append(cv.getLeft())
                           .append(" tx=").append(String.format("%.0f", cv.getTranslationX()))
                           .append("] ");
                    }
                    Log.i("AStack", csb.toString());
                }
            } catch (Throwable ignored) {}
            
            // Physics Linkage Fallback:
            // When entering from an app, scroll is often 0 initially while the 'Running Task' 
            // is actually the primary focus. We use the running task's logical center 
            // as our reference to prevent all cards from overlapping at the beginning.
            try {
                View runningTask = (View) XposedHelpers.callMethod(recentsView, "getRunningTaskView");
                if (runningTask != null) {
                    // Check if scroll is 0 but running task is at a later index
                    int runningIndex = (int) XposedHelpers.callMethod(recentsView, "indexOfChild", runningTask);
                    if (scroll == 0 && runningIndex > 0) {
                        int childStart = (int) getChildStartMethod.invoke(handler, runningTask);
                        int childSize = (int) getMeasuredSizeMethod.invoke(handler, runningTask);
                        center = childStart + childSize / 2f;
                    }
                }
            } catch (Throwable ignored) {}

            long nowUptime = SystemClock.uptimeMillis();
            boolean isDismissing = false;
            long dismissUntil = 0L;
            try {
                Object untilObj = XposedHelpers.getAdditionalInstanceField(recentsView, "apple_dismiss_until");
                if (untilObj instanceof Long) {
                    dismissUntil = (Long) untilObj;
                    isDismissing = nowUptime < dismissUntil;
                }
            } catch (Throwable ignored) {
            }

            int childCount = recentsView.getChildCount();

            // Page pitch = card width + page spacing. AxStack divides pixel deltas by this to get
            // a dimensionless page index; without it the stack maths works in pixels and blows up.
            float pageSpacing = 0f;
            try {
                Object ps = XposedHelpers.callMethod(recentsView, "getPageSpacing");
                if (ps instanceof Number) pageSpacing = ((Number) ps).floatValue();
                if (pageSpacing <= 0f && childCount > 1) {
                    // Fall back to measuring the gap between two adjacent pages.
                    float s0 = Float.NaN, s1 = Float.NaN;
                    int seen = 0;
                    for (int i = 0; i < childCount && seen < 2; i++) {
                        View cv = recentsView.getChildAt(i);
                        if (cv == null || !taskViewClass.isInstance(cv)) continue;
                        float s = (int) getChildStartMethod.invoke(handler, cv);
                        if (seen == 0) s0 = s; else s1 = s;
                        seen++;
                    }
                    if (!Float.isNaN(s1)) {
                        int cw = (int) getMeasuredSizeMethod.invoke(handler,
                                recentsView.getChildAt(0));
                        pageSpacing = Math.max(0f, Math.abs(s1 - s0) - cw);
                    }
                }
            } catch (Throwable ignored) {}

            // Android 17 (SDK 37): getChildStart() returns the child's ABSOLUTE layout position
            // inside the scrolling container, not an offset relative to the current page. The
            // original code assumed it matched `scroll + measuredSize/2`, which no longer holds.
            // Find the page the container is actually resting on and use its start as origin,
            // otherwise every card's "distance" comes out ~1000px per page too large and gets
            // translated right off the screen (the ghost card behind the first one).
            float pageStartRef = Float.NaN;
            float bestGap = Float.MAX_VALUE;
            for (int i = 0; i < childCount; i++) {
                View cv = recentsView.getChildAt(i);
                if (cv == null || !taskViewClass.isInstance(cv)) continue;
                try {
                    float cs = (int) getChildStartMethod.invoke(handler, cv);
                    float gap = Math.abs(cs - scroll);
                    if (gap < bestGap) {
                        bestGap = gap;
                        pageStartRef = cs;
                    }
                } catch (Throwable ignored) {}
            }
            boolean havePageRef = !Float.isNaN(pageStartRef);

            // Skip the offset pass on the first frame after entering recents: layout has not
            // settled (scroll==0 while the current page is not yet centred), and applying the
            // stack curve there shoves the card a full width off-screen for one frame.
            if (havePageRef && bestGap > measuredSize * 0.25f) {
                pageStartRef = Float.NaN;
                havePageRef = false;
            }

            // === AxStack layout ported verbatim from AxStackLayout.getTransform() ===
            // Measure the page pitch (card-to-card distance) once.
            float pageDistance = 0f;
            {
                float s0 = Float.NaN, s1 = Float.NaN;
                int seen = 0;
                for (int k = 0; k < childCount && seen < 2; k++) {
                    View cv = recentsView.getChildAt(k);
                    if (cv == null || !taskViewClass.isInstance(cv)) continue;
                    float s = (int) getChildStartMethod.invoke(handler, cv);
                    if (seen == 0) s0 = s; else s1 = s;
                    seen++;
                }
                if (!Float.isNaN(s1)) pageDistance = Math.abs(s1 - s0);
                if (pageDistance <= 0f) pageDistance = measuredSize;
            }

            // Anchor: the card the container is resting on.
            int centerIndex = -1;
            int centerScroll = 0;
            {
                float best = Float.MAX_VALUE;
                for (int k = 0; k < childCount; k++) {
                    View cv = recentsView.getChildAt(k);
                    if (cv == null || !taskViewClass.isInstance(cv)) continue;
                    float s = (int) getChildStartMethod.invoke(handler, cv);
                    float d = Math.abs(s - scroll);
                    if (d < best) { best = d; centerIndex = k; }
                }
                if (centerIndex >= 0) {
                    centerScroll = (int) getChildStartMethod.invoke(
                            handler, recentsView.getChildAt(centerIndex));
                }
            }

            for (int i = 0; i < childCount; i++) {
                View child = recentsView.getChildAt(i);
                if (child == null || !taskViewClass.isInstance(child)) continue;

                int pageScroll = (int) getChildStartMethod.invoke(handler, child);

                // getVisualDelta(): scroll difference clamped to one container width
                float rawDelta = pageScroll - centerScroll;
                float normalDelta = Math.max(-measuredSize, Math.min(measuredSize, rawDelta));
                float distance = normalDelta / Math.max(1f, pageDistance);

                XposedHelpers.setAdditionalInstanceField(child, "apple_stack_distance", distance);
                XposedHelpers.setAdditionalInstanceField(child, "apple_stack_pixel_delta", normalDelta);
                XposedHelpers.setAdditionalInstanceField(child, "apple_stack_page_distance", pageDistance);

                AxStackLayout.Transform t = AxStackLayout.compute(
                        distance, normalDelta, pageDistance, measuredSize);

                if (t == null) {
                    XposedHelpers.setAdditionalInstanceField(child, "apple_stack_offset", 0f);
                    child.setAlpha(0f);
                    continue;
                }

                XposedHelpers.setAdditionalInstanceField(child, "apple_stack_offset", t.translationX);
                // Set the absolute position here; applyTranslationX() must not add anything on top.
                child.setTranslationX(t.translationX);
                child.setScaleX(t.scale);
                child.setScaleY(t.scale);
                child.setAlpha(t.alpha);

                float targetZ = 100f + t.rank;
                XposedHelpers.setAdditionalInstanceField(child, "apple_target_z", targetZ);
                child.setTranslationZ(targetZ);

                if (!isDismissing && dismissTranslationXField != null) {
                    try {
                        Object targetObj = XposedHelpers.getAdditionalInstanceField(child, "apple_dismiss_target");
                        if (targetObj instanceof Integer) {
                            float dismissTx = dismissTranslationXField.getFloat(child);
                            if (Math.abs(dismissTx) < 0.1f) {
                                XposedHelpers.setAdditionalInstanceField(child, "apple_dismiss_target", null);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            // log once
        }
    }

    private void handleTranslateTaskWhenDismissed(XC_MethodHook.MethodHookParam param) {
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastDismissHookLogUptime > DEBUG_LOG_THROTTLE_MS) {
                lastDismissHookLogUptime = now;
                XposedBridge.log("AOSPStackingRecent: translateTaskWhenDismissed hooked");
            }

            if (param.args == null || param.args.length < 6) {
                return;
            }
            View view = (View) param.args[0];
            if (view == null || taskViewClass == null || !taskViewClass.isInstance(view)) {
                return;
            }
            int indexDiff = (int) param.args[1];
            int scrollDiffPerPage = (int) param.args[2];
            Object pendingAnimation = param.args[3];
            int index = (int) param.args[5];

            prefs.reload();
            float spacingFactor = prefs.getFloat(KEY_SPACING, DEFAULT_SPACING);

            float animationStartProgress = clamp(0.35f + 0.04f * indexDiff, 0f, 1f);
            float animationEndProgress = 1f;
            TimeInterpolator clampedInterpolator = clampToProgress(new LinearInterpolator(),
                    animationStartProgress, animationEndProgress);

            Object translationProperty = XposedHelpers.callMethod(view, "getPrimaryDismissTranslationProperty");

            int[] mDismissPrimaryTranslations = (int[]) XposedHelpers.getObjectField(param.thisObject, "mDismissPrimaryTranslations");
            if (mDismissPrimaryTranslations != null && index >= 0 && index < mDismissPrimaryTranslations.length) {
                mDismissPrimaryTranslations[index] = scrollDiffPerPage;
            }

            XposedHelpers.setAdditionalInstanceField(view, "apple_dismiss_target", scrollDiffPerPage);
            XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_last_scroll_step", (float)scrollDiffPerPage);
            XposedHelpers.callMethod(pendingAnimation, "setFloat", view, translationProperty,
                    (float) scrollDiffPerPage, clampedInterpolator);

            try {
                long duration = 350L;
                try {
                    Object d = XposedHelpers.callMethod(pendingAnimation, "getDuration");
                    if (d instanceof Long) {
                        duration = (Long) d;
                    } else if (d instanceof Integer) {
                        duration = ((Integer) d).longValue();
                    }
                } catch (Throwable ignored) {
                }
                Object untilObj = XposedHelpers.getAdditionalInstanceField(param.thisObject, "apple_dismiss_until");
                long until = untilObj instanceof Long ? (Long) untilObj : 0L;
                long newUntil = SystemClock.uptimeMillis() + duration + 200L;
                if (newUntil > until) {
                    XposedHelpers.setAdditionalInstanceField(param.thisObject, "apple_dismiss_until", newUntil);
                }
            } catch (Throwable ignored) {
            }

            boolean mEnableDrawingLiveTile = XposedHelpers.getBooleanField(param.thisObject, "mEnableDrawingLiveTile");
            if (mEnableDrawingLiveTile && (boolean)XposedHelpers.callMethod(view, "isRunningTask")) {
                XposedHelpers.callMethod(pendingAnimation, "addOnFrameCallback", (Runnable) () -> {
                    try {
                        XposedHelpers.callMethod(param.thisObject, "redrawLiveTile");
                    } catch (Exception ignored) {}
                });
            }

            try {
                Object added = XposedHelpers.getAdditionalInstanceField(pendingAnimation, "apple_curve_framecb");
                if (!(added instanceof Boolean) || !((Boolean) added)) {
                    XposedHelpers.setAdditionalInstanceField(pendingAnimation, "apple_curve_framecb", true);
                    XposedHelpers.callMethod(pendingAnimation, "addOnFrameCallback", (Runnable) () -> {
                        try {
                            XposedHelpers.callMethod(param.thisObject, "updateCurveProperties");
                        } catch (Throwable ignored) {
                        }
                    });
                }
            } catch (Throwable ignored) {
            }

            // We NO LONGER set param.setResult(null) here.
            // Letting the original method run ensures Launcher3 updates its internal state correctly,
            // which is critical for snapping-to-page logic when a dismiss is canceled.
        } catch (Throwable t) {
            XposedBridge.log("AOSPStackingRecent: Error in handleTranslateTaskWhenDismissed: " + t.getMessage());
        }
    }

    private static TimeInterpolator clampToProgress(TimeInterpolator base, float start, float end) {
        final float s = clamp(start, 0f, 1f);
        final float e = clamp(end, 0f, 1f);
        if (e <= s) {
            return input -> input < s ? 0f : 1f;
        }
        return input -> {
            if (input <= s) return 0f;
            if (input >= e) return 1f;
            float t = (input - s) / (e - s);
            t = clamp(t, 0f, 1f);
            return base.getInterpolation(t);
        };
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private void handleGetTaskViewAt(XC_MethodHook.MethodHookParam param) {
        ViewGroup recentsView = (ViewGroup) param.thisObject;
        float x = (float) param.args[0];
        float y = (float) param.args[1];
        
        int scrollX = recentsView.getScrollX();
        int scrollY = recentsView.getScrollY();

        int childCount = recentsView.getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            View child = recentsView.getChildAt(i);
            // Check alpha to avoid hitting invisible/dismissing cards
            if (child == null || child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.2f || !taskViewClass.isInstance(child)) continue;
            
            // Calculate visual bounds in container coordinates
            float left = child.getLeft() + child.getTranslationX() - scrollX;
            float top = child.getTop() + child.getTranslationY() - scrollY;
            float width = child.getWidth() * child.getScaleX();
            float height = child.getHeight() * child.getScaleY();
            
            // Account for scale centering
            if (child.getScaleX() != 1.0f) {
                left += (child.getWidth() - width) / 2f;
            }
            if (child.getScaleY() != 1.0f) {
                top += (child.getHeight() - height) / 2f;
            }

            if (x >= left && x <= left + width && y >= top && y <= top + height) {
                param.setResult(child);
                return;
            }
        }
    }

    private void applyTranslation(View child, float translation) {
        try {
            // 1. Set the internal fields used by Launcher's own logic
            if (persistentTranslationXField != null) {
                persistentTranslationXField.set(child, translation);
            } else if (nonGridTranslationXField != null) {
                nonGridTranslationXField.set(child, translation);
            }
            
            // 2. Force the actual translationX property to update.
            // This ensures the entire view container (including background/border) moves.
            child.setTranslationX(translation);
            
            // 3. In some Launcher versions, the icon or other overlays are handled via separate fields.
            // We attempt to find and sync them if they exist.
            try {
                // If there's an IconView that needs separate translation, it might be a field in TaskView.
                // However, setting translationX on the TaskView parent usually moves the icon too.
            } catch (Exception ignored) {}

        } catch (Exception e) {
        }
    }

    private void updatePageSpacingField(Object recentsView) {
        try {
            int newSpacing;
            try {
                 newSpacing = (int) XposedHelpers.callMethod(recentsView, "getPageSpacing");
             } catch (Throwable ignored) {
                 prefs.reload();
                 float spacingFactor = prefs.getFloat(KEY_SPACING, DEFAULT_SPACING);
                 float deltaFactor = spacingFactor - DEFAULT_SPACING;
                 newSpacing = Math.round(deltaFactor * 10f) - 120;
             }
            
            // 1. Update the mPageSpacing field in PagedView
            Field f = XposedHelpers.findFieldIfExists(recentsView.getClass(), "mPageSpacing");
            if (f == null) {
                f = XposedHelpers.findFieldIfExists(recentsView.getClass().getSuperclass(), "mPageSpacing");
            }
            
            if (f != null) {
                f.setAccessible(true);
                int oldSpacing = f.getInt(recentsView);
                if (oldSpacing != newSpacing) {
                    f.setInt(recentsView, newSpacing);
                    
                    // 2. Clear mPageScrolls to force re-calculation ONLY if spacing changed.
                    // IMPORTANT: We avoid clearing this if a gesture is active (running task exists)
                    // to prevent the "overlap and snap" flicker during the transition.
                    boolean isGestureActive = false;
                    try {
                        Object runningTask = XposedHelpers.callMethod(recentsView, "getRunningTaskView");
                        isGestureActive = (runningTask != null);
                    } catch (Throwable ignored) {}

                    if (!isGestureActive) {
                         Field scrollsField = XposedHelpers.findFieldIfExists(recentsView.getClass().getSuperclass(), "mPageScrolls");
                         if (scrollsField == null) {
                             scrollsField = XposedHelpers.findFieldIfExists(recentsView.getClass(), "mPageScrolls");
                         }
                         
                         if (scrollsField != null) {
                             scrollsField.setAccessible(true);
                             scrollsField.set(recentsView, null);
                         }
                     }
                }
            }
        } catch (Throwable ignored) {}
    }
}
