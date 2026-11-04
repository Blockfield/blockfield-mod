package com.lunares.blockfield.client.combat;

/** The connection's single adjacency monitor, shared with application reports. */
public interface CombatViewSender {
    CombatAttackSender blockfield$combatSender();
}
