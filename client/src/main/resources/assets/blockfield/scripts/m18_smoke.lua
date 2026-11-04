local BASE_TRACK = 0
local MAIN_TRACK = 1
local main = {start = {}, idle = {}, using = {}, holding = {}, throwing = {}, final = {}}
local base = {}

local function track(context)
    return context:getTrack(0, MAIN_TRACK)
end

local function play(context, name, mode, blend)
    context:runAnimation(name, track(context), false, mode, blend or 0)
end

local function transition(this, context, input)
    if input == INPUT_PUT_AWAY then
        local duration = context:getPutAwayTime()
        play(context, "put_away", PLAY_ONCE_HOLD, duration * 0.75)
        context:setAnimationProgress(track(context), 1, true)
        context:adjustAnimationProgress(track(context), -duration, false)
        return this.main.final
    elseif input == "m18_throw" then
        play(context, "throw", PLAY_ONCE_HOLD, 0.08)
        return this.main.throwing
    end
end

function base.entry(this, context)
    context:runAnimation("static_idle", context:getTrack(0, BASE_TRACK), false, LOOP, 0)
end

function main.start.transition(this, context, input)
    if input == INPUT_DRAW then
        play(context, "draw", PLAY_ONCE_STOP)
        return this.main.idle
    end
    return transition(this, context, input)
end

function main.idle.update(this, context)
    if context:isUsing() then context:trigger("start_use") end
end

function main.idle.transition(this, context, input)
    if input == INPUT_INSPECT then
        play(context, "inspect", PLAY_ONCE_STOP, 0.2)
    elseif input == "start_use" then
        play(context, "unlock_safe", PLAY_ONCE_HOLD, 0.08)
        return this.main.using
    else
        return transition(this, context, input)
    end
end

function main.using.update(this, context)
    if not context:isUsing() then
        context:trigger("cancel_use")
    elseif context:isHolding(track(context)) then
        -- Native M18 may release after four ticks; its throw signal interrupts this clip.
        context:trigger("hold_use")
    end
end

function main.using.transition(this, context, input)
    if input == "cancel_use" then
        play(context, "static_idle", LOOP, 0.12)
        return this.main.idle
    elseif input == "hold_use" then
        play(context, "unlock_safe_loop", LOOP, 0.1)
        return this.main.holding
    end
    return transition(this, context, input)
end

function main.holding.update(this, context)
    if not context:isUsing() then context:trigger("cancel_use") end
end

function main.holding.transition(this, context, input)
    if input == "cancel_use" then
        play(context, "static_idle", LOOP, 0.12)
        return this.main.idle
    end
    return transition(this, context, input)
end

function main.throwing.update(this, context)
    if context:isHolding(track(context)) and context:getStackCount() > 0 then
        context:trigger("redraw")
    end
end

function main.throwing.transition(this, context, input)
    if input == "redraw" then
        play(context, "re_draw", PLAY_ONCE_STOP)
        return this.main.idle
    end
    return transition(this, context, input)
end

local M = {main = main}

function M:initialize(context)
    context:ensureTrackLineSize(1)
    context:ensureTracksAmount(0, 2)
end

function M:states()
    return {base, self.main.start}
end

return M
