package com.babbur.waypointer.screen;

import java.util.ArrayList;
import java.util.Objects;

final class EditHistory<T> {
    private final ArrayList<T> states = new ArrayList<>();
    private int index;

    EditHistory(T initial) { states.add(initial); }

    void record(T state) {
        if (Objects.equals(states.get(index), state)) return;
        states.subList(index + 1, states.size()).clear();
        states.add(state);
        if (states.size() > 51) states.removeFirst();
        index = states.size() - 1;
    }

    boolean canUndo() { return index > 0; }
    boolean canRedo() { return index + 1 < states.size(); }
    T undo() { if (canUndo()) index--; return states.get(index); }
    T redo() { if (canRedo()) index++; return states.get(index); }
}
