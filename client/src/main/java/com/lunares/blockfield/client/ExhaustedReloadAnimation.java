package com.lunares.blockfield.client;

/**
 * Флаг между миксинами TACZ: AnimationController ставит его, пока обновляет анимации ствола в руке
 * локального игрока при пустой стамине, ObjectAnimationRunner по нему замедляет перезарядку. Рендер
 * однопоточный, поэтому хватает статического поля.
 */
public final class ExhaustedReloadAnimation {
    public static boolean slowOwnGun;

    /**
     * Поза перезарядки PlayerAnimator (третье лицо любого игрока) запускается для выдохшегося
     * игрока.
     */
    public static boolean slowPose;

    private ExhaustedReloadAnimation() {}
}
