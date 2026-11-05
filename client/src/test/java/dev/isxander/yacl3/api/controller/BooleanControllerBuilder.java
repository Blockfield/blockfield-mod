package dev.isxander.yacl3.api.controller;

import dev.isxander.yacl3.api.Controller;

// Test stand-in for the pack's YACL jar: same create/valueFormatter/build shape
// the reflective screen code resolves, returning a builder that builds a Controller.
// The builder is a named public class because the screen reaches it by reflection.
public interface BooleanControllerBuilder extends ControllerBuilder<Boolean> {
    static BooleanControllerBuilder create(Object option) {
        return new StubBuilder();
    }

    BooleanControllerBuilder valueFormatter(Object formatter);

    @Override
    Controller<Boolean> build();

    final class StubBuilder implements BooleanControllerBuilder {
        @Override
        public BooleanControllerBuilder valueFormatter(Object formatter) {
            return this;
        }

        @Override
        public Controller<Boolean> build() {
            return new Controller<Boolean>() {};
        }
    }
}
