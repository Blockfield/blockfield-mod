package com.lunares.blockfield.client.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CreditFeedTest {
    @Test
    void oneLinePerSourceAccumulatesAndExpiresAfterTheLastGain() {
        CreditFeed feed = new CreditFeed();
        feed.add("objective", "Удержание точки", 2, 0);
        feed.add("objective", "Удержание точки", 2, 1000);
        feed.add("kill", "Убийство", 60, 1000);
        feed.add("kill", "Двойное убийство", 75, 3000);
        feed.add("kill", "Мусор", Double.NaN, 3000);
        feed.add("teamkill", "Огонь по своим", -60, 3000);
        assertEquals(-60, feed.lines(3000).get(2).amount);
        assertEquals(3, feed.lines(3000).size());
        assertEquals(4, feed.lines(3000).get(0).amount);
        assertEquals(135, feed.lines(3000).get(1).amount);
        assertEquals("Двойное убийство", feed.lines(3000).get(1).label);
        assertEquals(1f, feed.lines(3000).get(1).pop(3000));
        assertEquals(0.1f, feed.lines(1000).get(0).pop(1000), 1e-6);

        long objectiveGone = 1000 + CreditFeed.HOLD_MS + CreditFeed.FADE_MS;
        assertEquals(2, feed.lines(objectiveGone).size());
        feed.add("objective", "Удержание точки", 2, objectiveGone);
        assertEquals(2, feed.lines(objectiveGone).get(2).amount);
    }

    @Test
    void fineShowsAsItsOwnLineWithoutCelebratingOnTheCounter() {
        CreditFeed feed = new CreditFeed();
        feed.add("teamkill", "Огонь по своим", -60, 0);
        assertEquals(0f, feed.balancePop(0));
        assertEquals(1f, feed.lines(0).get(0).pop(0));
    }

    @Test
    void balanceRollsTowardsTargetAndSnapsAfterABreak() {
        CreditFeed feed = new CreditFeed();
        assertEquals(100, feed.rollBalance(100, 0));
        double mid = feed.rollBalance(200, 50);
        assertTrue(mid > 100 && mid < 200);
        assertEquals(200, feed.rollBalance(200, 900), 0.5);
        assertEquals(500, feed.rollBalance(500, 5000));
    }
}
