package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * U9a pin/manifest audit (no U1 schema needed): the pinned ParCool bf7 and TaCZ Tweaks alpha.10
 * getters the policy hooks still exist with the hooked descriptors, the hook call sites still read
 * them, the 26-name registry still matches {@code AnimatorList} bytecode, and the Tweaks release
 * copy still has the ADR4 §13 digest. A pin bump that renames any of this fails loudly instead of
 * silently dropping the force-on policy.
 */
class AnimationPolicyPinTest {
    static ClassNode bytes(String name) throws Exception {
        try (var in =
                AnimationPolicyPinTest.class.getResourceAsStream(
                        "/" + name.replace('.', '/') + ".class")) {
            assertNotNull(in, name);
            var n = new ClassNode();
            new ClassReader(in).accept(n, 0);
            return n;
        }
    }

    static ClassNode pinned(ZipFile jar, String name) throws Exception {
        var entry = jar.getEntry(name.replace('.', '/') + ".class");
        assertNotNull(entry, name);
        try (var in = jar.getInputStream(entry)) {
            var n = new ClassNode();
            new ClassReader(in).accept(n, 0);
            return n;
        }
    }

    static List<String> fieldReads(ClassNode n, String method) {
        var result = new ArrayList<String>();
        n.methods.stream()
                .filter(m -> m.name.equals(method))
                .forEach(
                        m -> {
                            for (var ins : m.instructions)
                                if (ins instanceof FieldInsnNode f
                                        && f.getOpcode() == Opcodes.GETSTATIC)
                                    result.add(f.owner + "." + f.name);
                        });
        return result;
    }

    static List<String> calls(ClassNode n, String method) {
        var result = new ArrayList<String>();
        n.methods.stream()
                .filter(m -> m.name.equals(method))
                .forEach(
                        m -> {
                            for (var ins : m.instructions)
                                if (ins instanceof MethodInsnNode c)
                                    result.add(c.owner + "." + c.name + c.desc);
                        });
        return result;
    }

    static List<AnnotationNode> allAnnotations(org.objectweb.asm.tree.MethodNode m) {
        var all = new ArrayList<AnnotationNode>();
        if (m.visibleAnnotations != null) all.addAll(m.visibleAnnotations);
        if (m.invisibleAnnotations != null) all.addAll(m.invisibleAnnotations);
        return all;
    }

    static Object annotationValue(AnnotationNode a, String key) {
        if (a.values == null) return null;
        for (int i = 0; i < a.values.size(); i += 2)
            if (a.values.get(i).equals(key)) return a.values.get(i + 1);
        return null;
    }

    static List<String> annotationStrings(AnnotationNode a, String key) {
        var v = annotationValue(a, key);
        if (v instanceof String s) return List.of(s);
        if (v instanceof List<?> l)
            return l.stream().map(AnimationPolicyPinTest::normalize).toList();
        if (v != null) return List.of(normalize(v));
        return List.of();
    }

    /**
     * ASM stores class-literal annotation values as {@code Type}s; compare in binary-name form.
     * Plain strings (method descriptors, target names) pass through untouched.
     */
    static String normalize(Object v) {
        if (v instanceof org.objectweb.asm.Type t) {
            String s = t.getDescriptor().replace('/', '.');
            if (s.startsWith("L") && s.endsWith(";")) s = s.substring(1, s.length() - 1);
            return s;
        }
        return v.toString();
    }

    @Test
    void parCoolRegistryStillHas26AnimatorsAndNoAlternate() throws Exception {
        var list = bytes("com.alrex.parcool.client.animation.AnimatorList");
        var init =
                list.methods.stream()
                        .filter(m -> m.name.equals("<clinit>"))
                        .findFirst()
                        .orElseThrow();
        var wired = new ArrayList<String>();
        for (var ins : init.instructions)
            if (ins instanceof LdcInsnNode ldc
                    && ldc.cst instanceof org.objectweb.asm.Type t
                    && t.getClassName().startsWith("com.alrex.parcool.client.animation.impl."))
                wired.add(t.getClassName().substring(t.getClassName().lastIndexOf('.') + 1));
        // Native order, not sorted: ANIMATORS indexes the U1 AnimatorTag registry.
        assertEquals(AnimationPolicy.ANIMATORS, wired);
        assertFalse(wired.contains("ExaggeratedSideDodgeAnimator"));
    }

    @Test
    void parCoolGetterHooksStillPointAtPinnedSignatures() throws Exception {
        var booleans = bytes("com.alrex.parcool.config.ParCoolConfig$Client$Booleans");
        for (var name : AnimationPolicy.FORCED_PARCOOL_NAMES)
            assertNotNull(
                    booleans.fields.stream().filter(f -> f.name.equals(name)).findFirst(), name);
        assertTrue(
                booleans.methods.stream()
                        .anyMatch(
                                m ->
                                        m.name.equals("get")
                                                && m.desc.equals("()Ljava/lang/Boolean;")));
        var client = bytes("com.alrex.parcool.config.ParCoolConfig$Client");
        assertTrue(
                client.methods.stream()
                        .anyMatch(
                                m ->
                                        m.name.equals("canAnimate")
                                                && m.desc.startsWith("(Ljava/lang/Class;)")));
        // Every forced read flows through a hooked getter: the only canAnimate consumer and the
        // three global toggle sites.
        var animation = bytes("com.alrex.parcool.common.attachment.client.Animation");
        var setAnimator = calls(animation, "setAnimator");
        assertTrue(
                setAnimator.stream()
                        .anyMatch(c -> c.contains("Booleans.get()Ljava/lang/Boolean;")));
        assertTrue(
                setAnimator.stream().anyMatch(c -> c.contains(".canAnimate(Ljava/lang/Class;)")));
        var passive = bytes("com.alrex.parcool.client.animation.PassiveCustomAnimation");
        assertTrue(
                calls(passive, "animate").stream()
                        .anyMatch(c -> c.contains("Booleans.get()Ljava/lang/Boolean;")));
        var fastRun = bytes("com.alrex.parcool.client.animation.impl.FastRunningAnimator");
        assertTrue(
                fieldReads(fastRun, "rotatePost").stream()
                        .anyMatch(f -> f.endsWith("Booleans.EnableLeanAnimationOfFastRun")));
    }

    @Test
    void ownMixinContractsMatchThePinnedTargets() throws Exception {
        var parCool = bytes("com.lunares.blockfield.client.mixin.AnimationPolicyParCoolMixin");
        assertTrue(
                mixinTargets(parCool)
                        .contains("com.alrex.parcool.config.ParCoolConfig$Client$Booleans"));
        assertInject(parCool, "get()Ljava/lang/Boolean;", 1, true);
        var animator = bytes("com.lunares.blockfield.client.mixin.AnimationPolicyAnimatorMixin");
        assertTrue(
                mixinTargets(animator)
                        .contains("com.alrex.parcool.common.attachment.client.Animation"));
        assertInject(
                animator, "setAnimator(Lcom/alrex/parcool/client/animation/Animator;)V", 1, true);
        assertTrue(
                calls(animator, "blockfield$forceAnimator").stream()
                        .anyMatch(c -> c.contains("AnimationPolicy.forcing")));
        var screen = bytes("com.lunares.blockfield.client.mixin.AnimationPolicyParCoolScreenMixin");
        assertTrue(
                mixinTargets(screen)
                        .contains("com.alrex.parcool.client.gui.SettingBooleanConfigScreen"));
        assertInject(screen, "<init>", 1, false);
        var screenCalls = calls(screen, "blockfield$lockPolicy");
        assertTrue(screenCalls.stream().anyMatch(c -> c.contains("AnimationPolicy.lockedParCool")));
        assertTrue(
                screen.methods.stream()
                        .filter(m -> m.name.equals("blockfield$lockPolicy"))
                        .flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                        .anyMatch(
                                ins ->
                                        ins instanceof FieldInsnNode f
                                                && f.name.equals("active")
                                                && f.getOpcode() == Opcodes.PUTFIELD),
                "screen lock must disable the widget");
        var tweaks = bytes("com.lunares.blockfield.client.mixin.tweaks.AnimationPolicyTweaksMixin");
        assertTrue(
                mixinTargets(tweaks)
                        .contains("me.muksc.tacztweaks.config.Config$AudioAndVisuals$Visuals"));
        assertInject(tweaks, "smootherCrawlAnimation()Z", 1, true);
        var tweaksScreen =
                bytes(
                        "com.lunares.blockfield.client.mixin.tweaks.AnimationPolicyTweaksScreenMixin");
        assertTrue(mixinTargets(tweaksScreen).contains("me.muksc.tacztweaks.config.Config"));
        assertInject(tweaksScreen, TWEAKS_OPTION_LAMBDA, 1, false);
        var lockCalls = calls(tweaksScreen, "blockfield$lockSmoother");
        assertTrue(
                lockCalls.stream()
                        .anyMatch(c -> c.contains("AnimationPolicy.lockedSmootherCrawl")));
        assertTrue(lockCalls.stream().anyMatch(c -> c.contains(".disable(")));
        assertTrue(
                tweaksScreen.methods.stream()
                        .flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                        .anyMatch(ins -> ins instanceof LdcInsnNode l && "available".equals(l.cst)),
                "screen lock must call YACL Option.Builder.available");
    }

    /**
     * Item 3 ordering (U9p): pinned ParCool bf7 snapshots ClientInfo through the hooked {@code
     * Booleans.get()} and sends it from {@code PlayerJoinHandler}, which porting-lib fires at
     * {@code ClientLevel.addEntity} HEAD inside {@code handleLogin}. Fabric fires JOIN at {@code
     * handleLogin} RETURN and INIT at listener construction, so the policy must install on INIT.
     */
    @Test
    void policyInstallsBeforeParCoolJoinSnapshot() throws Exception {
        var policy = bytes("com.lunares.blockfield.client.combat.AnimationPolicy");
        var events = "net/fabricmc/fabric/api/client/networking/v1/ClientPlayConnectionEvents.";
        var registered = fieldReads(policy, "register");
        assertTrue(registered.contains(events + "INIT"), registered.toString());
        assertFalse(registered.contains(events + "JOIN"), "JOIN is after the first ClientInfo");
        var join =
                calls(bytes("com.alrex.parcool.common.handlers.PlayerJoinHandler"), "onPlayerJoin");
        int snapshot = indexOf(join, "ClientSetting.readFromLocalConfig");
        int send = indexOf(join, "PacketDistributor.sendToServer");
        assertTrue(snapshot >= 0 && snapshot < send, join.toString());
        assertTrue(
                calls(bytes("com.alrex.parcool.common.info.ClientSetting"), "readFromLocalConfig")
                        .stream()
                        .anyMatch(c -> c.contains("Booleans.get()Ljava/lang/Boolean;")));
        assertTrue(
                calls(bytes("net.minecraft.client.multiplayer.ClientPacketListener"), "handleLogin")
                        .stream()
                        .anyMatch(c -> c.contains("ClientLevel.addEntity(")));
        var fabric =
                bytes("net.fabricmc.fabric.mixin.networking.client.ClientPlayNetworkHandlerMixin");
        assertEquals(List.of("<init>", "RETURN"), injectPoint(fabric, "initAddon"));
        assertEquals(List.of("onGameJoin", "RETURN"), injectPoint(fabric, "handleServerPlayReady"));
    }

    static int indexOf(List<String> calls, String needle) {
        for (int i = 0; i < calls.size(); i++) if (calls.get(i).contains(needle)) return i;
        return -1;
    }

    static List<String> injectPoint(ClassNode n, String handler) {
        var m = n.methods.stream().filter(x -> x.name.equals(handler)).findFirst().orElseThrow();
        var inject =
                allAnnotations(m).stream()
                        .filter(a -> a.desc.endsWith("/Inject;"))
                        .findFirst()
                        .orElseThrow();
        var at = (AnnotationNode) ((List<?>) annotationValue(inject, "at")).getFirst();
        return List.of(
                annotationStrings(inject, "method").getFirst(),
                (String) annotationValue(at, "value"));
    }

    static List<String> mixinTargets(ClassNode n) {
        var out = new ArrayList<String>();
        var anns = new ArrayList<AnnotationNode>();
        if (n.visibleAnnotations != null) anns.addAll(n.visibleAnnotations);
        if (n.invisibleAnnotations != null) anns.addAll(n.invisibleAnnotations);
        for (var a : anns)
            if (a.desc.endsWith("/Mixin;")) {
                out.addAll(annotationStrings(a, "targets"));
                out.addAll(annotationStrings(a, "value"));
            }
        return out;
    }

    static void assertInject(ClassNode n, String method, int require, boolean cancellable) {
        var found =
                n.methods.stream()
                        .flatMap(
                                m ->
                                        allAnnotations(m).stream()
                                                .filter(a -> a.desc.endsWith("/Inject;"))
                                                .map(a -> new Object[] {m, a}))
                        .filter(
                                pair ->
                                        annotationStrings((AnnotationNode) pair[1], "method")
                                                .contains(method))
                        .toList();
        assertEquals(1, found.size(), method);
        var a = (AnnotationNode) found.getFirst()[1];
        assertEquals(require, annotationValue(a, "require"));
        if (cancellable) assertEquals(true, annotationValue(a, "cancellable"));
    }

    static Path workspaceRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("client/mods/tacz-tweaks.pw.toml"))) return dir;
            dir = dir.getParent();
        }
        return null;
    }

    static final String TWEAKS_SHA256 =
            "9b8606ee22a59ad1a618813110818b7f03cf8aa58e6b28ccf6b9ec8c76a965df";

    static String sha256(Path file) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    /**
     * The Gradle compile copy strips {@code Fabric-Loom-Version} from the manifest, so only its
     * {@code upstream.jar} sibling still carries the release digest. Either layout verifies.
     */
    static Path tweaksAuditJar(Path jar) throws Exception {
        Path upstream = jar.getParent().resolve("upstream.jar");
        if (Files.isRegularFile(upstream)) {
            assertEquals(TWEAKS_SHA256, sha256(upstream));
            assertSameEntriesExceptManifest(upstream, jar);
        } else {
            assertEquals(TWEAKS_SHA256, sha256(jar));
        }
        return jar;
    }

    static void assertSameEntriesExceptManifest(Path expected, Path actual) throws Exception {
        try (var a = new ZipFile(expected.toFile());
                var b = new ZipFile(actual.toFile())) {
            var names =
                    a.stream()
                            .map(e -> e.getName())
                            .filter(n -> !n.equals("META-INF/MANIFEST.MF"))
                            .sorted()
                            .toList();
            assertEquals(
                    names,
                    b.stream()
                            .map(e -> e.getName())
                            .filter(n -> !n.equals("META-INF/MANIFEST.MF"))
                            .sorted()
                            .toList());
            for (var name : names)
                assertEquals(a.getEntry(name).getCrc(), b.getEntry(name).getCrc(), name);
        }
    }

    static Path tweaksJar() {
        String prop = System.getProperty("blockfield.test.taczTweaksJar");
        if (prop != null && Files.isRegularFile(Path.of(prop))) return Path.of(prop);
        Path root = workspaceRoot();
        if (root != null) {
            Path jar =
                    root.resolve(
                            ".dev/release/shared/server/files/mods/tacz-tweaks-3.0.0-alpha.10+1.21.1-fabric.jar");
            if (Files.isRegularFile(jar)) return jar;
        }
        return null;
    }

    /** TaCZ Tweaks pin audit: exact ADR4 §13 digest plus the hooked getter and its mixin math. */
    @Test
    void taczTweaksPinDigestGetterAndMixinMath() throws Exception {
        Path sys = tweaksJar();
        org.junit.jupiter.api.Assumptions.assumeTrue(sys != null, "Tweaks jar not present");
        Path jar = tweaksAuditJar(sys);
        try (var zip = new ZipFile(jar.toFile())) {
            var visuals = pinned(zip, "me.muksc.tacztweaks.config.Config$AudioAndVisuals$Visuals");
            assertTrue(
                    visuals.methods.stream()
                            .anyMatch(
                                    m ->
                                            m.name.equals("smootherCrawlAnimation")
                                                    && m.desc.equals("()Z")));
            var mixin =
                    pinned(
                            zip,
                            "me.muksc.tacztweaks.mixin.feature.audio_and_visuals.visuals.smoother_crawl_animation.PlayerRendererMixin");
            int refs = 0;
            boolean hasPointFour = false;
            for (var m : mixin.methods)
                for (var ins : m.instructions) {
                    if (ins instanceof MethodInsnNode c
                            && c.name.equals("smootherCrawlAnimation")
                            && c.desc.equals("()Z")) refs++;
                    if (ins instanceof LdcInsnNode ldc && ldc.cst instanceof Float f && f == 0.4f)
                        hasPointFour = true;
                }
            assertTrue(refs >= 3, "expected alwaysTranslate/modifyYTranslate/lerpZTranslate refs");
            assertTrue(hasPointFour, "expected the Y (swim - 0.4f) lerp constant");
        }
    }

    static final String GROUP_PROPERTY =
            "Lme/muksc/tacztweaks/config/Config$OptionGroupBuilder;Lkotlin/reflect/KProperty0;";

    static boolean constructs(MethodNode m, String type) {
        for (var ins : m.instructions)
            if (ins instanceof TypeInsnNode t
                    && t.getOpcode() == Opcodes.NEW
                    && t.desc.equals(type)) return true;
        return false;
    }

    static boolean bootstraps(MethodNode m, String lambda) {
        for (var ins : m.instructions)
            if (ins instanceof InvokeDynamicInsnNode indy)
                for (var arg : indy.bsmArgs)
                    if (arg instanceof Handle h && (h.getName() + h.getDesc()).equals(lambda))
                        return true;
        return false;
    }

    static final String TWEAKS_OPTION_LAMBDA =
            "option$lambda$1(Lkotlin/reflect/KProperty0;Lkotlin/jvm/functions/Function1;"
                    + "Lme/muksc/tacztweaks/config/Config$OptionBuilder;)Lkotlin/Unit;";

    /**
     * Item 2 (U9p): the screen lock hooks the option lambda every property-bound YACL option goes
     * through, and the smoother-crawl widget is built that way. Whole-jar closure: the only native
     * reads of the value are the hooked static getter's three renderer calls; the entry accessor is
     * reached only by that getter and the screen's property reference.
     */
    @Test
    void taczTweaksScreenLockTargetAndReadClosure() throws Exception {
        Path sys = tweaksJar();
        org.junit.jupiter.api.Assumptions.assumeTrue(sys != null, "Tweaks jar not present");
        String visuals = "me/muksc/tacztweaks/config/Config$AudioAndVisuals$Visuals";
        String config = "me/muksc/tacztweaks/config/Config";
        var getterCallers = new java.util.TreeSet<String>();
        var entryCallers = new java.util.TreeSet<String>();
        var delegateUsers = new java.util.TreeSet<String>();
        try (var zip = new ZipFile(tweaksAuditJar(sys).toFile())) {
            for (var entry : java.util.Collections.list(zip.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                var n = pinned(zip, entry.getName().replace(".class", "").replace('/', '.'));
                for (var m : n.methods)
                    for (var ins : m.instructions) {
                        if (ins instanceof MethodInsnNode c && c.owner.equals(visuals)) {
                            if (c.name.equals("smootherCrawlAnimation"))
                                getterCallers.add(n.name + "." + m.name);
                            if (c.name.equals("getSmootherCrawlAnimation"))
                                entryCallers.add(n.name + "." + m.name);
                        }
                        if (ins instanceof FieldInsnNode f
                                && f.name.equals("smootherCrawlAnimation$delegate"))
                            delegateUsers.add(n.name + "." + m.name);
                    }
            }
            String mixin =
                    "me/muksc/tacztweaks/mixin/feature/audio_and_visuals/visuals/"
                            + "smoother_crawl_animation/PlayerRendererMixin.";
            assertEquals(
                    java.util.Set.of(
                            mixin + "tacztweaks$setupRotations$visualTweak$alwaysTranslate",
                            mixin + "tacztweaks$setupRotations$visualTweak$modifyYTranslate",
                            mixin + "tacztweaks$setupRotations$visualTweak$lerpZTranslate"),
                    getterCallers);
            assertEquals(
                    java.util.Set.of(
                            visuals + ".smootherCrawlAnimation",
                            config + "$generateConfigScreen$1$4$3$7.get"),
                    entryCallers);
            assertEquals(
                    java.util.Set.of(visuals + ".<clinit>", visuals + ".getSmootherCrawlAnimation"),
                    delegateUsers);
            // The screen builds the widget from that property reference via option(KProperty0),
            // whose lambda is the hooked option$lambda$1 (binding, then the option's own setup).
            var cfg = pinned(zip, config.replace('/', '.'));
            String screenProperty = config + "$generateConfigScreen$1$4$3$7";
            var builder = cfg.methods.stream().filter(m -> constructs(m, screenProperty)).toList();
            assertEquals(1, builder.size());
            assertTrue(
                    calls(cfg, builder.getFirst().name).stream()
                            .anyMatch(
                                    c ->
                                            c.contains(
                                                    "option$default(L"
                                                            + config
                                                            + ";"
                                                            + GROUP_PROPERTY)));
            var option =
                    cfg.methods.stream()
                            .filter(
                                    m ->
                                            m.name.equals("option")
                                                    && m.desc.startsWith("(" + GROUP_PROPERTY))
                            .findFirst()
                            .orElseThrow();
            assertTrue(bootstraps(option, TWEAKS_OPTION_LAMBDA), "option(KProperty0) lambda");
            var lambda = calls(cfg, "option$lambda$1");
            int bind = indexOf(lambda, "Option$Builder.binding(");
            int setup = indexOf(lambda, "Function1.invoke(");
            assertTrue(bind >= 0 && bind < setup, lambda.toString());
        }
    }
}
