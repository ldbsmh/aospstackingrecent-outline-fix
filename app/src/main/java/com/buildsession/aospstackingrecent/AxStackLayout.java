/*
 * Port of AxionOS AxStackLayout (ax_launcher/quickstep/views/AxStackLayout.java).
 * Algorithm and constants copied verbatim; only package/imports changed.
 */
package com.buildsession.aospstackingrecent;

final class AxStackLayout {
    static final int TASK_PRELOAD_RANGE = 5;

    private static final float PORTRAIT_STACK_SPACING_FACTOR = 0.23f;
    private static final float LANDSCAPE_STACK_SPACING_FACTOR = 0.18f;
    private static final float ABOVE_TASK_GAP_FACTOR = 0.076f;
    private static final float SCALE_STEP = 0.07f;
    private static final float MAX_STACK_RANGE = 4.5f;
    private static final float TASK_ALPHA_MIN = 0.75f;
    private static final float TASK_ALPHA_MAX = 1.0f;
    private static final float TASK_ALPHA_STEP = 0.06f;
    private static final float MENU_FULL_ALPHA_DISTANCE = 0.28f;
    private static final float MENU_ZERO_ALPHA_DISTANCE = 0.72f;
    private static final float DECELERATE_70 = 0.7f;

    boolean isDistanceActive(float distance, boolean naturalLayout) {
        return distance >= -TASK_PRELOAD_RANGE && distance <= 4f;
    }

    static float getTaskAlpha(float distance, boolean naturalLayout) {
        if (distance <= 2.5f) {
            return 1f;
        }
        if (distance >= 3.5f) {
            return 0f;
        }
        return 1f - (distance - 2.5f);
    }

    private static float getMenuAlpha(float distance) {
        float absoluteDistance = Math.abs(distance);
        if (absoluteDistance <= MENU_FULL_ALPHA_DISTANCE) {
            return 1f;
        }
        if (absoluteDistance >= MENU_ZERO_ALPHA_DISTANCE) {
            return 0f;
        }
        float progress = (absoluteDistance - MENU_FULL_ALPHA_DISTANCE)
                / (MENU_ZERO_ALPHA_DISTANCE - MENU_FULL_ALPHA_DISTANCE);
        return 1f - decelerate(progress, DECELERATE_70);
    }

    static float decelerate(float t, float factor) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return (float) (1f - Math.pow(1f - t, 1f + factor * 2f));
    }

    /** Result holder mirroring AxStack's Transform struct. */
    static final class Transform {
        float scale = 1f;
        float translationX = 0f;
        float alpha = 1f;
        float menuAlpha = 1f;
        float rank = 0f;
        void set(float scale, float tx, float alpha, float menuAlpha) {
            this.scale = scale;
            this.translationX = tx;
            this.alpha = alpha;
            this.menuAlpha = menuAlpha;
        }
    }

    /** Static entry point used by the hook. Delegates to the verbatim AxStack transform. */
    public static Transform compute(float distance, float normalDelta, float pageDistance,
            float primarySize) {
        if (distance < -TASK_PRELOAD_RANGE || distance > MAX_STACK_RANGE) return null;
        Transform out = new Transform();
        getTransform(distance, normalDelta, 0f, distance, primarySize, true, false, out);
        out.rank = distance <= 0f ? -distance : 0f;
        return out;
    }

    private static float getTransform(float distance, float normalDelta, float reflowTranslation,
            float anchorDistance, float primarySize, boolean naturalLayout, boolean rtl,
            Transform out) {
        if (distance < -TASK_PRELOAD_RANGE || distance > 4.5f) {
            out.set(1f, 0f, 0f, 0f);
            return;
        }
        if (distance <= 0f) {
            float translation = anchorDistance <= 0f ? -reflowTranslation : 0f;
            float aboveGap = primarySize * ABOVE_TASK_GAP_FACTOR;
            float extraGap = -distance * aboveGap;
            float totalTranslation = translation + (rtl ? -extraGap : extraGap);
            float scale = getAboveTaskScale(distance);
            out.set(scale, rtl ? -totalTranslation : totalTranslation, 1f, distance < 0f ? 0f : 1f);
            return;
        }
        if (anchorDistance <= 0f) {
            float translation = -reflowTranslation;
            out.set(1f, rtl ? -translation : translation, 1f, 1f);
            return;
        }
        float stackSpacing = getStackSpacing(primarySize, naturalLayout);
        float desiredDelta = distance * stackSpacing;
        float scale = getStackScale(distance, naturalLayout);
        float alpha = getTaskAlpha(distance, naturalLayout);
        float translation = desiredDelta - normalDelta;
        out.set(scale, rtl ? -translation : translation, alpha, getMenuAlpha(distance));
    }

    private static float getStackSpacing(float primarySize, boolean naturalLayout) {
        float factor = naturalLayout ? PORTRAIT_STACK_SPACING_FACTOR : LANDSCAPE_STACK_SPACING_FACTOR;
        return primarySize * factor;
    }

    float getStackDepth(float distance, boolean naturalLayout) {
        if (distance <= 0f) {
            return 1f;
        }
        return Math.max(0f, 1f - distance / 3.0f);
    }

    private static float getStackScale(float distance, boolean naturalLayout) {
        if (distance <= 0f) {
            return 1f;
        }
        float step = naturalLayout ? SCALE_STEP : 0.05f;
        return Math.max(0.75f, 1f - distance * step);
    }

    private static float getAboveTaskScale(float distance) {
        if (distance >= 0f) {
            return 1f;
        }
        // Cards stacked above the focused one shrink slightly as they recede.
        return Math.max(0.85f, 1f - (-distance) * 0.02f);
    }



}
