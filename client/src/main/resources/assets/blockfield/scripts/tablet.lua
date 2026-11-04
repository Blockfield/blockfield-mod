-- Command tablet: held at the chest, raised to the face while the Blockfield menu is open.
-- "Linked" means the menu is open. A virtual tablet (menu opened with another item in hand)
-- comes straight up to the face and goes back down; the renderer ends it after "lower".
local BASE_TRACK = 0
local MAIN_TRACK = 1
local main = {start = {}, idle = {}, opened = {}, final = {}}
local base = {}

local function track(context)
    return context:getTrack(0, MAIN_TRACK)
end

local function play(context, name, mode, blend)
    context:runAnimation(name, track(context), false, mode, blend or 0)
end

local function put_away(this, context)
    local duration = context:getPutAwayTime()
    play(context, "put_away", PLAY_ONCE_HOLD, duration * 0.75)
    context:setAnimationProgress(track(context), 1, true)
    context:adjustAnimationProgress(track(context), -duration, false)
    return this.main.final
end

function base.entry(this, context)
    context:runAnimation("static_idle", context:getTrack(0, BASE_TRACK), false, LOOP, 0)
end

function main.start.transition(this, context, input)
    if input == INPUT_DRAW then
        if context:isVirtual() then
            play(context, "raise", PLAY_ONCE_HOLD)
            return this.main.opened
        end
        play(context, "draw", PLAY_ONCE_STOP)
        return this.main.idle
    elseif input == INPUT_PUT_AWAY then
        return put_away(this, context)
    end
end

function main.idle.update(this, context)
    if context:isLinked() then context:trigger("open") end
end

function main.idle.transition(this, context, input)
    if input == INPUT_INSPECT then
        play(context, "inspect", PLAY_ONCE_STOP, 0.3)
    elseif input == "open" then
        play(context, "open", PLAY_ONCE_HOLD, 0.1)
        return this.main.opened
    elseif input == INPUT_PUT_AWAY then
        return put_away(this, context)
    end
end

function main.opened.entry(this, context)
    this.breathing = false
end

function main.opened.update(this, context)
    if not context:isLinked() then
        context:trigger("close")
    elseif not this.breathing and context:isHolding(track(context)) then
        this.breathing = true
        play(context, "open_idle", LOOP, 0.15)
    end
end

function main.opened.transition(this, context, input)
    if input == "close" then
        if context:isVirtual() then
            play(context, "lower", PLAY_ONCE_HOLD, 0.05)
            return this.main.final
        end
        play(context, "close", PLAY_ONCE_STOP, 0.05)
        return this.main.idle
    elseif input == INPUT_PUT_AWAY then
        return put_away(this, context)
    end
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
