package com.lunares.blockfield.client.gui.tablet;

import com.lunares.blockfield.client.combat.FirstShotDrawGuard;
import com.lunares.blockfield.client.gui.BfIcon;
import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.gui.UiMotion;
import com.lunares.blockfield.client.gui.screen.MenuScreen;
import com.lunares.blockfield.client.render.TabletAnimations;
import com.lunares.blockfield.network.packet.MenuActionC2SPacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/**
 * Планшет: меню командира на стекле 3D-предмета (М, ПКМ по предмету, запрос с сервера). Без
 * видимого предмета используется плоское меню. Открывается сразу в режиме курсора: вкладки
 * «Меню»/«Карта» ({@link TabletMapTab}), WASD по-прежнему ведёт игрока (см. {@link
 * #passThroughMovement}), стрельба и прицеливание блокированы миксинами ({@code TabletInputMixin},
 * `tacz/TabletAimBlockMixin`, `tacz/ClientShootGateMixin}), Esc/M и урон закрывают экран. Выбор
 * класса и точки высадки -- вкладка «Высадка» ({@link #openDeployment}).
 */
public final class Tablet {

    public enum Tab {
        DEPLOY,
        MENU,
        MATCHES,
        STATS,
        MAP,
        RESULT
    }

    private static final TabletAnim ANIM = new TabletAnim();
    private static boolean open;
    private static Tab tab = Tab.MENU;
    private static final UiMotion tabPosition = new UiMotion();
    private static final UiMotion tabReveal = new UiMotion();
    private static final int TAB_W = 106;

    /** Итог боя поднимает планшет снизу, а не проявляет масштабом (см. {@link #rise}). */
    private static final long RISE_NS = 600_000_000L;

    private static long riseStart = Long.MIN_VALUE;

    private static MenuScreen menu;
    private static String menuJson;
    private static String menuId = "";
    private static int menuW;
    private static int menuH;
    private static boolean menuRequested;

    /** Холст меню: меню рисуется в своём размере и ужимается в экран планшета -- без прокрутки. */
    private static TabletCanvas canvas;

    private static String canvasJson;

    /** Выбор класса или точки высадки с сервера; тот же экран, вписанный в стекло, как меню. */
    private static Screen deployment;

    private static Screen deploymentFitted;
    private static TabletCanvas deploymentCanvas;
    private static int deploymentW;
    private static int deploymentH;
    // A chosen class is usually followed by the spawn choice: wait before going down, so the
    // tablet does not dip and rise again (closeDeploymentSoon).
    private static final long NEXT_CHOICE_NS = 300_000_000L;
    private static long closeAt;

    /**
     * Last server-driven deployment, monotonic ns. The kit replaces the inventory, so the draw
     * right after it must not pay the tablet put-away: the tablet was a menu, never in hand.
     */
    private static long deployedAtNs = Long.MIN_VALUE;

    /** Draws within this window after a deployment skip the tablet put-away. */
    static final long DEPLOY_PUT_AWAY_WINDOW_NS = 5_000_000_000L;

    private static KeyMapping key;

    /** Сдвиг/масштаб, который {@code TabletScissorMixin} применяет к scissor вложенного меню. */
    private static boolean scissorShift;

    private Tablet() {}

    public static void init(KeyMapping tabletKey) {
        key = tabletKey;
        ClientTickEvents.START_CLIENT_TICK.register(Tablet::onTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onLogout());
        // A proxy server switch (workshop -> lobby) reconfigures without DISCONNECT: the old menu
        // must not survive it.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onLogout());
    }

    public static boolean isOpen() {
        return open;
    }

    public static Tab tab() {
        return tab;
    }

    /**
     * Клавиша планшета: сравниваем по её текущей привязке (используется из {@link TabletScreen}).
     */
    static boolean isTabletKey(int keyCode) {
        if (key == null) return false;
        InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(key);
        return bound.getType() == InputConstants.Type.KEYSYM && bound.getValue() == keyCode;
    }

    private static void onTick(Minecraft mc) {
        if (mc.player == null) {
            if (open) closeNow(mc);
            return;
        }
        if (com.lunares.blockfield.client.render.WeaponHud.inWorkshop()) {
            if (key != null) while (key.consumeClick()) {}
            if (open) closeNow(mc);
            return;
        }
        if (closeAt != 0 && System.nanoTime() >= closeAt) {
            closeAt = 0;
            if (deployment == null && tab == Tab.DEPLOY) requestClose(mc);
        }
        // Чужой экран (смерть, чат) поверх планшета: два интерфейса разом не нужны.
        if (open
                && !(mc.screen instanceof TabletScreen)
                && !(mc.screen instanceof com.lunares.blockfield.client.workshop.WorkshopAiScreen))
            closeNow(mc);
        if (mc.screen instanceof TabletScreen) passThroughMovement(mc);
        // Попадание закрывает планшет: под огнём он не должен закрывать обзор. Высадку -- нет: она
        // открывается сразу после смерти, ещё с отсчётом урона.
        if (open && tab != Tab.DEPLOY && mc.player.hurtTime > 0) {
            requestClose(mc);
            if (mc.screen instanceof com.lunares.blockfield.client.workshop.WorkshopAiScreen)
                mc.setScreen(null);
        }

        // Открытие клавишей: клики читаем только пока нет экрана -- с открытым планшетом M и Esc
        // обрабатывает сам TabletScreen.keyPressed (ваниль не шлёт click() в клавиатуру, когда её
        // читает активный экран).
        if (key != null && mc.screen == null) {
            while (key.consumeClick()) {
                openScreen(
                        mc,
                        deployment != null
                                ? Tab.DEPLOY
                                : "vote".equals(menuId)
                                        ? Tab.MENU
                                        : TabletResultTab.fresh()
                                                ? Tab.RESULT
                                                : TabletMapTab.available() ? Tab.MAP : Tab.MENU);
            }
        }
        // Итог приходит, когда у игрока может быть открыт чат или экран смерти: ждём, пока он их
        // закроет.
        if (mc.screen == null && !open && TabletResultTab.takeAutoOpen()) showResult(mc);
        if (open && tab == Tab.RESULT && !TabletResultTab.available())
            setTab(TabletMapTab.available() ? Tab.MAP : Tab.MENU);
        if (open && tab == Tab.MENU) requestMenu(false);
    }

    /** Итог боя: планшет сам поднимается и открывается на нём; поверх чужого экрана не лезет. */
    static void showResult(Minecraft mc) {
        if (open && mc.screen instanceof TabletScreen) {
            setTab(Tab.RESULT);
            return;
        }
        if (mc.screen != null) return;
        openScreen(mc, Tab.RESULT);
        riseStart = System.nanoTime() + openDelay;
    }

    /** Доля высоты экрана, на которую планшет ещё опущен: 1 -- за нижним краем, 0 -- на месте. */
    static float rise(long now) {
        float t = (now - riseStart) / (float) RISE_NS;
        if (t >= 1f || t < 0f) return 0f;
        return 1f - TabletResultTab.back(t);
    }

    /** How long the last opening waited for the 3D tablet before the surface appeared. */
    private static long openDelay;

    private static void openScreen(Minecraft mc, Tab initial) {
        open = true;
        tab = initial;
        riseStart = Long.MIN_VALUE;
        tabPosition.snap(slot(initial));
        tabReveal.snap(1f);
        openDelay = TabletAnimations.open(mc.player);
        ANIM.open(System.nanoTime() + openDelay);
        mc.setScreen(new TabletScreen());
        if (initial == Tab.MENU) requestMenu(true);
    }

    /**
     * Начинает анимацию закрытия; сам экран уберёт себя, когда она доиграет (см. {@link
     * TabletScreen#render}).
     */
    public static void requestClose(Minecraft mc) {
        if (!open) return;
        open = false;
        menuRequested = false;
        ANIM.close(System.nanoTime());
    }

    private static void closeNow(Minecraft mc) {
        requestClose(mc);
        if (mc.screen instanceof TabletScreen) {
            mc.setScreen(null);
            passThroughMovement(mc);
        }
    }

    /** Вызывается самим {@link TabletScreen}, когда анимация закрытия доиграла до конца. */
    static void finishClosing(Minecraft mc) {
        if (mc.screen instanceof TabletScreen) {
            mc.setScreen(null);
            passThroughMovement(mc);
        }
    }

    public static void setTab(Tab value) {
        if (tab != value) {
            tab = value;
            long now = System.nanoTime();
            tabPosition.to(slot(value), now);
            tabReveal.snap(0f);
            tabReveal.to(1f, now);
        }
        if (value == Tab.MENU) requestMenu(true);
        else if (value == Tab.MATCHES) requestTabMenu("rooms");
        else if (value == Tab.STATS) requestTabMenu("top:all:0");
    }

    /**
     * Меню строит сервер: вкладку «Меню» наполняет то же действие, что и ПКМ по планшету. force —
     * новое открытие (показываем прошлый снимок, пока не придёт свежий).
     */
    private static void requestMenu(boolean force) {
        if (!force && (menuJson != null || menuRequested)) return;
        if (!ClientPlayNetworking.canSend(MenuActionC2SPacket.TYPE)) return;
        menuRequested = true;
        ClientPlayNetworking.send(new MenuActionC2SPacket("menu"));
    }

    /**
     * Вкладки серверных списков: содержимое запрашивает то же действие, что и прежние кнопки
     * подвала; свежий список приходит обычным меню и встаёт на свою вкладку через {@link
     * #tabForMenu}.
     */
    private static void requestTabMenu(String action) {
        if (!ClientPlayNetworking.canSend(MenuActionC2SPacket.TYPE)) return;
        menuRequested = true;
        ClientPlayNetworking.send(new MenuActionC2SPacket(action));
    }

    /**
     * Экран с сервера (ПКМ по планшету, компас, `/bf menu`): открывает планшет, если он ещё закрыт.
     * Списки комнат и статистики встают на свои вкладки, остальное — на «Меню».
     */
    static Tab tabForMenu(String id) {
        if ("rooms".equals(id)) return Tab.MATCHES;
        if ("leaderboard".equals(id)) return Tab.STATS;
        return Tab.MENU;
    }

    public static void openMenu(String json, String id) {
        if ("vote".equals(id)) {
            dropDeployment();
            closeAt = 0;
        }
        if (!id.equals(menuId)) menu = null;
        menuJson = json;
        canvasJson = null;
        menuId = id;
        menuRequested = true;
        Tab at = tabForMenu(id);
        Minecraft mc = Minecraft.getInstance();
        if (!open || !(mc.screen instanceof TabletScreen)) {
            open = true;
            tab = at;
            tabPosition.snap(slot(at));
            tabReveal.snap(1f);
            ANIM.open(System.nanoTime() + TabletAnimations.open(mc.player));
            mc.setScreen(new TabletScreen());
        } else if (tab != at) {
            tab = at;
            tabPosition.to(slot(at), System.nanoTime());
            tabReveal.snap(0f);
            tabReveal.to(1f, System.nanoTime());
        }
    }

    public static void updateMenu(String json) {
        menuJson = json;
        // Новый состав меню -- заново подобрать холст, иначе выросшее содержимое снова заскроллит.
        canvasJson = null;
    }

    public static boolean hasMenu(String id) {
        return menuJson != null && open && menuId.equals(id);
    }

    /**
     * Live-ответ с другим id, пока открыта вкладка меню: место игрока сменилось (переход), и
     * держать прошлое меню — значит врать; принимаем как новое. Закрытый планшет и фоновые вкладки
     * не трогаем: возврат на меню запросит свежее, а протухший ответ иначе дёрнул бы вкладку из-под
     * курсора.
     */
    public static boolean adoptMenuUpdate(String id) {
        return adoptMenuUpdate(open, tab, id);
    }

    static boolean adoptMenuUpdate(boolean isOpen, Tab current, String id) {
        return isOpen && current == Tab.MENU && tabForMenu(id) == Tab.MENU;
    }

    public static void closeMenu() {
        menuJson = null;
        menu = null;
        menuId = "";
        menuRequested = false;
        if (open) requestClose(Minecraft.getInstance());
    }

    /** The deployment screen on the tablet, null when the server is not asking for one. */
    public static Screen deployment() {
        return deployment;
    }

    /**
     * Raises the tablet on the deployment tab with `screen` (class or spawn point choice); a
     * replaced screen is fitted again.
     */
    public static void openDeployment(Screen screen) {
        deployment = screen;
        closeAt = 0;
        Minecraft mc = Minecraft.getInstance();
        if (open && mc.screen instanceof TabletScreen) setTab(Tab.DEPLOY);
        else openScreen(mc, Tab.DEPLOY);
    }

    /** Deployed, or the server closed the choice: the tablet goes down if it shows it. */
    public static void closeDeployment() {
        noteDeployment(System.nanoTime());
        if (dropDeployment()) requestClose(Minecraft.getInstance());
    }

    /**
     * Shared context-transition: exit without a deploy (range/hub/match change, round end,
     * reconnect). Drops visible/hidden choice and leaves DEPLOY without marking a deploy, so the
     * put-away guard stays honest. Keeps the tablet open for the next menu/result to adopt.
     */
    public static void cancelDeployment() {
        closeAt = 0;
        dropDeployment();
        if (tab == Tab.DEPLOY) {
            tab = Tab.MENU;
            if (open) {
                tabPosition.snap(slot(tab));
                tabReveal.snap(1f);
            }
        }
    }

    /** Test seam: record a deployment at an explicit monotonic time. */
    static void noteDeployment(long nowNs) {
        deployedAtNs = nowNs;
    }

    /**
     * True shortly after a server-driven deployment. The draw that follows the kit must not charge
     * the tablet put-away (see {@code TabletAnimations}): the tablet was a menu, never in hand.
     */
    public static boolean freshDeploy(long nowNs) {
        return deployedAtNs != Long.MIN_VALUE && nowNs - deployedAtNs <= DEPLOY_PUT_AWAY_WINDOW_NS;
    }

    /** A class was chosen: goes down only if no spawn choice follows within NEXT_CHOICE_NS. */
    public static void closeDeploymentSoon() {
        if (dropDeployment()) closeAt = System.nanoTime() + NEXT_CHOICE_NS;
    }

    /** Вкладки серверного меню: клики и клавиши уходят в тот же MenuScreen. */
    private static boolean isMenuTab() {
        return tab == Tab.MENU || tab == Tab.MATCHES || tab == Tab.STATS;
    }

    /** Clears the choice; true when the tablet was showing it. */
    private static boolean dropDeployment() {
        if (deployment == null) return false;
        deployment = null;
        deploymentFitted = null;
        if (open && tab == Tab.DEPLOY) return true;
        // The deployment tab left the strip: the underline follows its neighbours' new slots.
        if (open) tabPosition.snap(slot(tab));
        return false;
    }

    public static void onLogout() {
        MapPings.clear();
        TabletResultTab.clear();
        open = false;
        tab = Tab.MENU;
        menu = null;
        menuJson = null;
        menuId = "";
        menuRequested = false;
        deployment = null;
        deploymentFitted = null;
        closeAt = 0;
        deployedAtNs = Long.MIN_VALUE;
        FirstShotDrawGuard.reset();
    }

    /**
     * Режим курсора: экран Minecraft забирает клавиатуру, поэтому движение раздаём сами по сырому
     * состоянию GLFW -- игрок реально ходит с открытым планшетом. Мышь в это время ведёт курсор.
     *
     * <p>Раздаём через {@link KeyMapping#set} (а не setDown у одной привязки): наш {@code
     * KeyMappingSpreadMixin} разносит состояние по всем биндам этой клавиши. Иначе на Shift оживал
     * только ванильный спринт, а ParCool FastRun оставался «отпущенным» и каждый тик гасил спринт
     * обратно -- это и был рывками идущий бег с поднятым планшетом.
     */
    public static void passThroughMovement(Minecraft mc) {
        if (mc.options == null || mc.getWindow() == null) return;
        long window = mc.getWindow().getWindow();
        for (KeyMapping mapping :
                new KeyMapping[] {
                    mc.options.keyUp,
                    mc.options.keyDown,
                    mc.options.keyLeft,
                    mc.options.keyRight,
                    mc.options.keyJump,
                    mc.options.keyShift,
                    mc.options.keySprint
                }) {
            InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(mapping);
            if (bound.getType() != InputConstants.Type.KEYSYM
                    || bound.getValue() == InputConstants.UNKNOWN.getValue()) {
                continue;
            }
            KeyMapping.set(bound, InputConstants.isKeyDown(window, bound.getValue()));
        }
    }

    // ---- отрисовка ----

    static TabletAnim anim() {
        return ANIM;
    }

    /**
     * The tablet surface under a scaling pose (fallback menu or the glass framebuffer): scissor
     * rectangles follow the pose.
     */
    static void renderSurface(
            GuiGraphics g, Minecraft mc, TabletFrame f, int mouseX, int mouseY, float partialTick) {
        withScissorShift(() -> drawSurface(g, mc, f, mouseX, mouseY, partialTick));
    }

    /** Whether the menu is on screen, including its fade-out. */
    public static boolean isShown() {
        return !com.lunares.blockfield.client.render.WeaponHud.inWorkshop()
                && (open || ANIM.visible(System.nanoTime()));
    }

    private static void drawSurface(
            GuiGraphics g, Minecraft mc, TabletFrame f, int mouseX, int mouseY, float partialTick) {
        g.fill(
                f.contentX(),
                f.tabsY(),
                f.contentX() + f.contentW(),
                f.contentY() + f.contentH(),
                BfTheme.BG);
        tabs(g, mc, f, mouseX, mouseY);
        if (tab == Tab.MAP) {
            TabletMapTab.render(g, f, mouseX, mouseY);
        } else if (tab == Tab.RESULT) {
            TabletResultTab.render(g, f);
        } else if (tab == Tab.DEPLOY) {
            renderDeployment(g, mc, f, mouseX, mouseY, partialTick);
        } else {
            renderMenu(g, mc, f, mouseX, mouseY, partialTick);
        }
        float reveal = tabReveal.value(System.nanoTime());
        if (reveal < 1f) {
            g.flush();
            g.fill(
                    f.contentX(),
                    f.contentY(),
                    f.contentX() + f.contentW(),
                    f.contentY() + f.contentH(),
                    BfTheme.alpha(BfTheme.BG, Math.round(210 * (1f - reveal))));
        }
        BfTheme.outline(
                g,
                f.contentX() - 1,
                f.tabsY() - 1,
                f.contentW() + 2,
                f.contentY() + f.contentH() - f.tabsY() + 2,
                BfTheme.BORDER);
        hint(g, mc, f);
    }

    private static void tabs(GuiGraphics g, Minecraft mc, TabletFrame f, int mouseX, int mouseY) {
        var font = BfTheme.font();
        g.fill(
                f.contentX(),
                f.tabsY(),
                f.contentX() + f.contentW(),
                f.tabsY() + f.tabsH(),
                BfTheme.BG_ELEVATED);
        for (Tab t : visibleTabs()) {
            int x = f.contentX() + slot(t) * TAB_W;
            boolean active = tab == t;
            boolean hovered = BfTheme.hit(mouseX, mouseY, x, f.tabsY(), TAB_W, f.tabsH());
            if (hovered || active)
                g.fill(x, f.tabsY(), x + TAB_W, f.tabsY() + f.tabsH(), BfTheme.CARD);
            int ink = active ? BfTheme.AMBER : hovered ? BfTheme.TEXT : BfTheme.MUTED;
            (switch (t) {
                        case DEPLOY -> BfIcon.TARGET;
                        case MAP -> BfIcon.MAP;
                        case RESULT -> BfIcon.RANK;
                        case MENU -> BfIcon.MENU;
                        case MATCHES -> BfIcon.SQUAD;
                        case STATS -> BfIcon.CHART;
                    })
                    .draw(g, x + 10, f.tabsY() + 7, 12, ink);
            g.drawString(
                    font,
                    switch (t) {
                        case DEPLOY -> "ВЫСАДКА";
                        case MAP -> "КАРТА";
                        case RESULT -> "ИТОГ БОЯ";
                        case MENU -> "МЕНЮ";
                        case MATCHES -> "МАТЧИ";
                        case STATS -> "СТАТИСТИКА";
                    },
                    x + 30,
                    f.tabsY() + 9,
                    ink,
                    false);
        }
        float x = f.contentX() + TAB_W * tabPosition.value(System.nanoTime());
        g.pose().pushPose();
        g.pose().translate(x, f.tabsY() + f.tabsH() - 2, 0);
        g.fill(0, 0, TAB_W, 2, BfTheme.AMBER);
        g.pose().popPose();
    }

    private static void hint(GuiGraphics g, Minecraft mc, TabletFrame f) {
        var font = BfTheme.font();
        String credits = com.lunares.blockfield.client.render.HudRenderer.creditsText();
        String text = credits.isEmpty() ? "ESC — ЗАКРЫТЬ" : credits + "   ·   ESC — ЗАКРЫТЬ";
        String code =
                tab == Tab.MAP
                        ? TabletMapTab.subtitle()
                        : tab == Tab.RESULT ? TabletResultTab.subtitle() : "";
        int available = f.contentW() - visibleTabs().size() * TAB_W - 20;
        String feedback =
                com.lunares.blockfield.client.workshop.WorkshopFeedback.current(System.nanoTime());
        if (!feedback.isEmpty() && available >= 80) {
            int ink = com.lunares.blockfield.client.workshop.WorkshopFeedback.color();
            var lines =
                    font.getSplitter()
                            .splitLines(
                                    feedback, available, net.minecraft.network.chat.Style.EMPTY);
            String first = lines.get(0).getString();
            if (lines.size() == 1) {
                g.drawString(
                        font,
                        first,
                        f.contentX() + f.contentW() - font.width(first) - 10,
                        f.tabsY() + 9,
                        ink,
                        false);
                return;
            }
            String rest =
                    BfTheme.ellipsize(font, feedback.substring(first.length()).strip(), available);
            int x = f.contentX() + f.contentW() - available - 10;
            g.drawString(font, first, x, f.tabsY() + 3, ink, false);
            g.drawString(font, rest, x, f.tabsY() + 14, ink, false);
            return;
        }
        if (font.width(text) > available && !credits.isEmpty()) text = credits;
        if (!code.isEmpty() && font.width(code + "   ·   " + text) <= available)
            text = code + "   ·   " + text;
        if (font.width(text) <= available)
            g.drawString(
                    font,
                    text,
                    f.contentX() + f.contentW() - font.width(text) - 10,
                    f.tabsY() + 9,
                    BfTheme.MUTED,
                    false);
    }

    private static void renderMenu(
            GuiGraphics g, Minecraft mc, TabletFrame f, int mouseX, int mouseY, float partialTick) {
        if (menuJson == null) {
            g.drawCenteredString(
                    mc.font,
                    "ЗАПРОС МЕНЮ…",
                    f.contentX() + f.contentW() / 2,
                    f.contentY() + f.contentH() / 2 - 4,
                    BfTheme.MUTED);
            return;
        }
        fitMenu(mc, f);
        renderEmbedded(g, menu, canvas, f, mouseX, mouseY, partialTick);
    }

    private static void renderDeployment(
            GuiGraphics g, Minecraft mc, TabletFrame f, int mouseX, int mouseY, float partialTick) {
        // Closing after the deployment: the glass stays empty while it goes down.
        if (deployment == null) return;
        if (deploymentFitted != deployment
                || deploymentW != f.contentW()
                || deploymentH != f.contentH()) {
            // The screens scale their own 800x480 layout into whatever size they get.
            deploymentW = f.contentW();
            deploymentH = f.contentH();
            deploymentCanvas = TabletCanvas.forInner(deploymentW, deploymentH);
            deployment.init(mc, deploymentCanvas.width(), deploymentCanvas.height());
            deploymentFitted = deployment;
        }
        renderEmbedded(g, deployment, deploymentCanvas, f, mouseX, mouseY, partialTick);
    }

    /** A whole screen drawn in its canvas size, shrunk into the glass content area. */
    private static void renderEmbedded(
            GuiGraphics g,
            Screen screen,
            TabletCanvas at,
            TabletFrame f,
            int mouseX,
            int mouseY,
            float partialTick) {
        g.pose().pushPose();
        g.pose().translate(f.contentX(), f.contentY(), 0);
        g.pose().scale(at.scale(), at.scale(), 1f);
        boolean shifted = scissorShift;
        scissorShift = true;
        try {
            screen.render(
                    g,
                    (int) at.toCanvasX(mouseX, f.contentX()),
                    (int) at.toCanvasY(mouseY, f.contentY()),
                    partialTick);
        } finally {
            scissorShift = shifted;
            g.pose().popPose();
        }
    }

    /**
     * Подбирает размер холста так, чтобы меню поместилось целиком: сколько не влезло -- на столько
     * холст и подрастает (а масштаб падает). Прокрутки в планшете нет, это требование владельца.
     */
    private static void fitMenu(Minecraft mc, TabletFrame f) {
        if (menu != null
                && canvas != null
                && menuW == f.contentW()
                && menuH == f.contentH()
                && menuJson.equals(canvasJson)) {
            return;
        }
        menuW = f.contentW();
        menuH = f.contentH();
        canvasJson = menuJson;
        if (menu == null) menu = new MenuScreen(menuJson);
        else menu.update(menuJson);
        // Кнопка confirm закрывает не себя (она никогда не Minecraft.screen), а планшет -- с его
        // анимацией закрытия, а не мгновенным Minecraft.setScreen(null) мимо неё.
        menu.setCloseAction(() -> requestClose(mc));
        menu.setPaged(false);
        canvas = TabletCanvas.forInner(menuW, menuH);
        menu.init(mc, canvas.width(), canvas.height());
        for (int attempt = 0; attempt < 4; attempt++) {
            int overflow = menu.overflowHeight();
            if (overflow <= 0) break;
            TabletCanvas grown = canvas.grownBy(overflow, menuW, menuH);
            // Long menus use whole-row pages instead of making every label and hit target tiny.
            if (grown.height() == canvas.height() || grown.scale() < 0.9f) break;
            canvas = grown;
            menu.init(mc, canvas.width(), canvas.height());
        }
        if (menu.overflowHeight() > 0) menu.setPaged(true);
    }

    /**
     * Вкладки в порядке полосы: высадка -- пока её ждут, карта -- только в бою, итог -- после.
     * Матчи и статистика -- только у хабовых меню (там раньше были эти кнопки в подвале).
     */
    static java.util.List<Tab> visibleTabs() {
        java.util.List<Tab> tabs = new java.util.ArrayList<>(6);
        if (deployment != null) tabs.add(Tab.DEPLOY);
        tabs.add(Tab.MENU);
        if (serverTabs(menuId)) {
            tabs.add(Tab.MATCHES);
            tabs.add(Tab.STATS);
        }
        if (TabletMapTab.available()) tabs.add(Tab.MAP);
        if (TabletResultTab.available()) tabs.add(Tab.RESULT);
        return tabs;
    }

    /** Хабовые меню: выбор режима, casual, списки комнат и статистики. */
    static boolean serverTabs(String id) {
        return id.equals("hub")
                || id.equals("casual")
                || id.equals("rooms")
                || id.equals("leaderboard");
    }

    private static int slot(Tab t) {
        return Math.max(0, visibleTabs().indexOf(t));
    }

    /** Ctrl+Tab: следующая видимая вкладка по кругу. */
    static void nextTab() {
        java.util.List<Tab> tabs = visibleTabs();
        setTab(tabs.get((tabs.indexOf(tab) + 1) % tabs.size()));
    }

    // ---- scissor вложенного меню (см. TabletScissorMixin) ----

    /**
     * Холст вне меню (итог боя) тоже рисуется со сдвигом и масштабом: его scissor пересчитывает
     * миксин.
     */
    public static void withScissorShift(Runnable draw) {
        boolean shifted = scissorShift;
        scissorShift = true;
        try {
            draw.run();
        } finally {
            scissorShift = shifted;
        }
    }

    /** Публичны для {@code TabletScissorMixin} (другой пакет). */
    public static boolean scissorShift() {
        return scissorShift;
    }

    // ---- ввод в режиме курсора ----

    static boolean mouseClicked(
            Minecraft mc, TabletFrame f, double mouseX, double mouseY, int button) {
        if (button == 2 && tab == Tab.MAP && TabletMapTab.ping(mouseX, mouseY, f)) {
            return true;
        }
        if (button == 1 && tab == Tab.MAP && TabletMapTab.teleport(mouseX, mouseY, f)) {
            return true;
        }
        if (button == 0 && clickTab(mc, f, mouseX, mouseY)) return true;
        if (menu != null && canvas != null && isMenuTab() && f.inContent(mouseX, mouseY)) {
            menu.mouseClicked(
                    canvas.toCanvasX(mouseX, f.contentX()),
                    canvas.toCanvasY(mouseY, f.contentY()),
                    button);
            return true;
        }
        if (deployment != null
                && deploymentFitted == deployment
                && tab == Tab.DEPLOY
                && f.inContent(mouseX, mouseY)) {
            deployment.mouseClicked(
                    deploymentCanvas.toCanvasX(mouseX, f.contentX()),
                    deploymentCanvas.toCanvasY(mouseY, f.contentY()),
                    button);
            return true;
        }
        return false;
    }

    private static boolean clickTab(Minecraft mc, TabletFrame f, double mouseX, double mouseY) {
        int x = f.contentX();
        for (Tab t : visibleTabs()) {
            int w = TAB_W;
            if (BfTheme.hit((int) mouseX, (int) mouseY, x, f.tabsY(), w, f.tabsH())) {
                setTab(t);
                return true;
            }
            x += w;
        }
        return false;
    }

    static boolean scroll(TabletFrame f, double mouseX, double mouseY, double scrollY) {
        if (tab == Tab.DEPLOY
                && deployment != null
                && deploymentFitted == deployment
                && f.inContent(mouseX, mouseY)) {
            return deployment.mouseScrolled(
                    deploymentCanvas.toCanvasX(mouseX, f.contentX()),
                    deploymentCanvas.toCanvasY(mouseY, f.contentY()),
                    0,
                    scrollY);
        }
        if (tab == Tab.RESULT && f.inContent(mouseX, mouseY))
            return TabletResultTab.scroll(scrollY);
        return tab == Tab.MAP && TabletMapTab.zoom(mouseX, mouseY, scrollY, f);
    }

    /** Keyboard zoom toward the frame centre, for players without a wheel. */
    static boolean zoomKey(TabletFrame f, int keyCode) {
        int step =
                switch (keyCode) {
                    case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> 1;
                    case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> -1;
                    default -> 0;
                };
        return step != 0
                && tab == Tab.MAP
                && TabletMapTab.zoom(
                        f.contentX() + f.contentW() / 2.0,
                        f.contentY() + f.contentH() / 2.0,
                        step,
                        f);
    }

    static boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (tab == Tab.DEPLOY)
            return deployment != null && deployment.keyPressed(keyCode, scanCode, modifiers);
        if (tab == Tab.RESULT) {
            if (keyCode == GLFW.GLFW_KEY_PAGE_UP) return TabletResultTab.pageBy(-1);
            if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) return TabletResultTab.pageBy(1);
            return false;
        }
        return menu != null && isMenuTab() && menu.keyPressed(keyCode, scanCode, modifiers);
    }
}
