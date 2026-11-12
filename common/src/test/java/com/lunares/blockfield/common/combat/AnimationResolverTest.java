package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.combat.AnimationFixtures.*;
import static com.lunares.blockfield.common.combat.AnimationResources.*;
import static com.lunares.blockfield.common.combat.AnimationTypes.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class AnimationResolverTest {
    private record Fixture(
            AnimationState state,
            AnimationEvent.ObserverSeed seed,
            AnimationStartReport.Victim starts,
            Store resources) {
        AnimationResolver.Request request(Time r, Time a, long h) {
            return new AnimationResolver.Request(
                    state.identity(), h, r, a, seed, starts, state.graph());
        }
    }

    private static Fixture fixture() {
        var state = with(sample(AnimationState.class), "graph", AnimationGraphState.neutral());
        var seed =
                with(
                        with(sample(AnimationEvent.ObserverSeed.class), "graph", state.graph()),
                        "checkpoint",
                        state);
        var row = victim(0, List.of());
        row =
                with(
                        row,
                        "presentationDigest",
                        AnimationResolver.presentationDigest(seed, state.graph(), row));
        var store = new Store();
        Set<ResourceRef> refs = new HashSet<>(AnimationSchema.references(state));
        refs.addAll(AnimationSchema.references(seed));
        store.installAll(
                refs.stream()
                        .map(ref -> new Definition(ref, new Bytes(new byte[0]), List.of()))
                        .toList());
        return new Fixture(state, seed, row, store);
    }

    @Test
    void commonResolverSelectsExactlyCeilEndpointAndRejectsAnimationGaps() {
        var fixture = fixture();
        List<AnimationState> history = new ArrayList<>();
        for (long t = 98; t <= 103; t++) history.add(with(fixture.state(), "serverTick", t));
        var result =
                AnimationResolver.resolve(
                        fixture.request(new Time(98, 1), new Time(100, 1), 103),
                        history,
                        fixture.resources(),
                        policy());
        assertTrue(result.exact(), result.reason());
        assertEquals(99, result.recipe().placementTick());
        assertEquals(101, result.recipe().animationTick());
        assertEquals(1 / 65536f, result.recipe().animationPartial());
        history.removeIf(s -> s.serverTick() == 101);
        var unavailable =
                AnimationResolver.resolve(
                        fixture.request(new Time(98, 1), new Time(100, 1), 103),
                        history,
                        fixture.resources(),
                        policy());
        assertEquals(AnimationResolver.Status.STATE_UNAVAILABLE, unavailable.status());
        assertNull(unavailable.recipe());
    }

    @Test
    void everyLifeSubscriptionAndResourceBoundaryBetweenRAndAFails() {
        var fixture = fixture();
        for (String generation :
                List.of(
                        "connectionId",
                        "observerStreamEpoch",
                        "sceneGeneration",
                        "subscriptionGeneration",
                        "targetEpoch",
                        "seatGeneration",
                        "teleportGeneration",
                        "resourceGeneration",
                        "policyGeneration")) {
            List<AnimationState> history = new ArrayList<>();
            for (long t = 98; t <= 103; t++) history.add(with(fixture.state(), "serverTick", t));
            var boundary =
                    with(
                            history.get(1),
                            "identity",
                            with(fixture.state().identity(), generation, 1L));
            history.set(1, boundary);
            var result =
                    AnimationResolver.resolve(
                            fixture.request(Time.whole(98), Time.whole(103), 103),
                            history,
                            fixture.resources(),
                            policy());
            assertEquals(
                    AnimationResolver.Status.GENERATION_UNAVAILABLE, result.status(), generation);
        }
    }

    @Test
    void missingResourcesWrongSeedWrongSubscriptionAndDigestNeverBecomeExact() {
        var fixture = fixture();
        var request = fixture.request(Time.whole(0), Time.whole(0), 0);
        assertTrue(
                AnimationResolver.resolve(
                                request, List.of(fixture.state()), fixture.resources(), policy())
                        .exact());
        assertEquals(
                AnimationResolver.Status.RESOURCE_UNAVAILABLE,
                AnimationResolver.resolve(request, List.of(fixture.state()), new Store(), policy())
                        .status());
        var wrong = with(fixture.starts(), "subscriptionGeneration", 1L);
        var bad =
                new AnimationResolver.Request(
                        request.identity(),
                        0,
                        request.r(),
                        request.a(),
                        request.seed(),
                        wrong,
                        request.projectedGraph());
        assertEquals(
                AnimationResolver.Status.GENERATION_UNAVAILABLE,
                AnimationResolver.resolve(
                                bad, List.of(fixture.state()), fixture.resources(), policy())
                        .status());
        wrong = with(fixture.starts(), "seedRef", 1L);
        bad =
                new AnimationResolver.Request(
                        request.identity(),
                        0,
                        request.r(),
                        request.a(),
                        request.seed(),
                        wrong,
                        request.projectedGraph());
        assertEquals(
                AnimationResolver.Status.SEED_UNAVAILABLE,
                AnimationResolver.resolve(
                                bad, List.of(fixture.state()), fixture.resources(), policy())
                        .status());
        wrong = with(fixture.starts(), "presentationDigest", new Digest(7, 8, 9, 10));
        bad =
                new AnimationResolver.Request(
                        request.identity(),
                        0,
                        request.r(),
                        request.a(),
                        request.seed(),
                        wrong,
                        request.projectedGraph());
        assertEquals(
                AnimationResolver.Status.REPORT_UNAVAILABLE,
                AnimationResolver.resolve(
                                bad, List.of(fixture.state()), fixture.resources(), policy())
                        .status());
    }

    @Test
    void recipeDigestIncludesBothFractionsAndRetainedGraphIdentity() {
        var fixture = fixture();
        List<AnimationState> history = new ArrayList<>();
        for (long t = 98; t <= 103; t++) history.add(with(fixture.state(), "serverTick", t));
        var first =
                AnimationResolver.resolve(
                        fixture.request(new Time(98, 1), new Time(100, 1), 103),
                        history,
                        fixture.resources(),
                        policy());
        var r =
                AnimationResolver.resolve(
                        fixture.request(new Time(98, 2), new Time(100, 1), 103),
                        history,
                        fixture.resources(),
                        policy());
        var a =
                AnimationResolver.resolve(
                        fixture.request(new Time(98, 1), new Time(100, 2), 103),
                        history,
                        fixture.resources(),
                        policy());
        assertTrue(first.exact());
        assertTrue(r.exact());
        assertTrue(a.exact());
        assertNotEquals(first.recipe().decodedDigest(), r.recipe().decodedDigest());
        assertNotEquals(first.recipe().decodedDigest(), a.recipe().decodedDigest());
    }
}
