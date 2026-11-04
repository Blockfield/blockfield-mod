package com.lunares.blockfield.client.music;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/** Мешок треков: каждый звучит раз за круг, и новый круг не начинается с только что сыгранного. */
public final class ShuffleBag {
    private final List<String> items;
    private final Random random;
    private final Deque<String> bag = new ArrayDeque<>();
    private String last;

    public ShuffleBag(List<String> items, Random random) {
        this.items = List.copyOf(items);
        this.random = random;
    }

    public String next() {
        if (items.isEmpty()) return null;
        if (bag.isEmpty()) {
            List<String> round = new ArrayList<>(items);
            Collections.shuffle(round, random);
            if (round.size() > 1 && round.get(0).equals(last))
                Collections.swap(round, 0, round.size() - 1);
            bag.addAll(round);
        }
        last = bag.poll();
        return last;
    }

    /** Продолжить круг, начатый в прошлом запуске игры: сыгранные в нём треки ждут нового круга. */
    public void restore(List<String> played, String last) {
        bag.clear();
        List<String> round = new ArrayList<>(items);
        round.removeAll(played);
        Collections.shuffle(round, random);
        if (round.size() > 1 && round.get(0).equals(last))
            Collections.swap(round, 0, round.size() - 1);
        bag.addAll(round);
        this.last = last;
    }

    /** Треки, уже сыгранные в текущем круге (весь набор, если круг закончился). */
    public List<String> playedThisRound() {
        List<String> played = new ArrayList<>(items);
        played.removeAll(bag);
        return played;
    }

    public String last() {
        return last;
    }

    /** Трек сыгран вне мешка (первый трек меню): в этом круге он больше не выпадет. */
    public void played(String id) {
        bag.remove(id);
        last = id;
    }
}
