package dev.isxander.yacl3.api.controller;

import dev.isxander.yacl3.api.Controller;

// Test stand-in for the pack's YACL jar: the controller function given to
// Option.Builder.controller() must return one of these, never a built Controller.
public interface ControllerBuilder<T> {
    Controller<T> build();
}
