package com.lunares.blockfield.oracle;

import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationState.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.util.ArrayList;
import java.util.List;

/** Complete explicit U1 fixture inputs; no observer configuration or native selector is used. */
public final class ParCoolNativeValues {
    private ParCoolNativeValues() {}

    public static ParCool context() {
        var states = new ArrayList<ActionState>();
        var direction = new Vec3(.3, -.2, .9);
        for (ActionTag tag : ActionTag.values()) {
            List<ActionInput> inputs =
                    switch (tag) {
                        case SLIDE -> List.of(new SlideInput(direction));
                        case HORIZONTAL_WALL_RUN ->
                                List.of(new HorizontalWallRunInput(23, true, direction, direction));
                        case VERTICAL_WALL_RUN ->
                                List.of(new VerticalWallRunInput(-.13, direction));
                        case WALL_SLIDE -> List.of(new WallSlideInput(direction));
                        case HANG_DOWN ->
                                List.of(new HangInput(.25, .125f, false, BarAxis.X, 23, true));
                        case CLING_TO_CLIFF ->
                                List.of(new ClingInput(.125f, FacingDirection.TO_WALL, direction));
                        case DIVE ->
                                List.of(new DiveInput(false, .42, .31, -.13, 3, false, 9, false));
                        case CHARGE_JUMP -> List.of(new ChargeInput(7, 9, 3, true, 7, true));
                        case RIDE_ZIPLINE ->
                                List.of(new ZiplineInput(direction, .17, -.31, .08, false, null));
                        case HIDE_IN_BLOCK -> List.of(new HideInput(direction, false));
                        case QUICK_TURN ->
                                List.of(new AcceptedStart(tag, 0, true, direction, null, 0, true));
                        case SKY_DIVE -> List.of(new IntentInput(false, false, false, false));
                        case ROLL, TAP, VAULT ->
                                List.of(
                                        new DurationInput(
                                                tag == ActionTag.ROLL
                                                        ? 9
                                                        : tag == ActionTag.TAP ? 8 : 11,
                                                new AnimationResources.ConfigRef(
                                                        1,
                                                        1,
                                                        1,
                                                        new AnimationResources.Digest(
                                                                0, 0, 0, 0))));
                        default -> List.of(new NoInput());
                    };
            states.add(new ActionState(tag, false, 0, 17, -1, inputs));
        }
        var grid = new Grid4(0, 0, 0, 0, 0, 0);
        return new ParCool(
                null,
                new AnimationStartReport.StartKey(1, 1),
                new Option(false, 127),
                new Passive(15, 2, 3),
                states,
                new HangGrid(grid, 23),
                new DiveGrid(grid));
    }
}
