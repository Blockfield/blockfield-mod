package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AnnotationNode;

class CombatMixinTargetsTest {
    static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        return null;
    }

    @Test
    void sendAndRaknetHooksFailLoudlyOnChangedPinnedTarget() throws Exception {
        var send =
                CombatPinnedWritersTest.bytes(
                        "com.lunares.blockfield.client.mixin.CombatViewSendMixin");
        var wraps =
                send.methods.stream()
                        .flatMap(
                                m ->
                                        m.visibleAnnotations == null
                                                ? java.util.stream.Stream.<AnnotationNode>empty()
                                                : m.visibleAnnotations.stream())
                        .filter(a -> a.desc.endsWith("/WrapMethod;"))
                        .toList();
        if (wraps.isEmpty())
            wraps =
                    send.methods.stream()
                            .flatMap(
                                    m ->
                                            m.invisibleAnnotations == null
                                                    ? java.util.stream.Stream
                                                            .<AnnotationNode>empty()
                                                    : m.invisibleAnnotations.stream())
                            .filter(a -> a.desc.endsWith("/WrapMethod;"))
                            .toList();
        assertEquals(1, wraps.size());
        assertEquals(List.of("send"), value(wraps.getFirst(), "method"));
        assertEquals(1, value(wraps.getFirst(), "require"));
        var rak =
                CombatPinnedWritersTest.bytes(
                        "com.lunares.blockfield.client.mixin.CombatRaknetChannelMixin");
        var annotations =
                rak.methods.stream()
                        .flatMap(
                                m -> {
                                    var a = new java.util.ArrayList<AnnotationNode>();
                                    if (m.visibleAnnotations != null)
                                        a.addAll(m.visibleAnnotations);
                                    if (m.invisibleAnnotations != null)
                                        a.addAll(m.invisibleAnnotations);
                                    return a.stream();
                                })
                        .filter(a -> a.desc.endsWith("/Inject;"))
                        .toList();
        assertEquals(1, annotations.size());
        var inject = annotations.getFirst();
        assertEquals(1, value(inject, "require"));
        assertEquals(true, value(inject, "cancellable"));
        assertEquals(
                List.of("getPacketChannelOverride(Ljava/lang/Class;Z)I"), value(inject, "method"));
        @SuppressWarnings("unchecked")
        var ats = (List<AnnotationNode>) value(inject, "at");
        assertEquals("HEAD", value(ats.getFirst(), "value"));
    }
}
