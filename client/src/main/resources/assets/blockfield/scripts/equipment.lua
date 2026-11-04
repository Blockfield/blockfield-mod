-- Class equipment: draw, inspect and the native SuperbWarfare item use (medical kit, armor plate).
-- A linked item (monitor paired with a drone) has its own "_linked" clips.
-- A virtual medical kit is healing from a thrown one on the ground (MedkitAnimations): "using" is
-- the server-side heal; the renderer ends it after "ground_end".
local BASE_TRACK = 0
local MAIN_TRACK = 1
local main = {start = {}, idle = {}, using = {}, ground = {}, final = {}}
local base = {}

local function track(context)
    return context:getTrack(0, MAIN_TRACK)
end

local function play(context, name, mode, blend)
    context:runAnimation(name, track(context), false, mode, blend or 0)
end

local function variant(context, name)
    if context:isLinked() then return name .. "_linked" end
    return name
end

local function idle(context, blend)
    context:runAnimation(variant(context, "static_idle"), context:getTrack(0, BASE_TRACK), false, LOOP, blend)
end

local function put_away(this, context)
    local duration = context:getPutAwayTime()
    play(context, variant(context, "put_away"), PLAY_ONCE_HOLD, duration * 0.75)
    context:setAnimationProgress(track(context), 1, true)
    context:adjustAnimationProgress(track(context), -duration, false)
    return this.main.final
end

function base.entry(this, context)
    this.linked = context:isLinked()
    idle(context, 0)
end

function base.update(this, context)
    local linked = context:isLinked()
    if linked ~= this.linked then
        this.linked = linked
        idle(context, 0.3)
        if linked then play(context, "link", PLAY_ONCE_STOP, 0.2) end
    end
    if context:takeRemoteStarted() then play(context, "to_face", PLAY_ONCE_HOLD, 0.05) end
    if context:takeRemoteEnded() then play(context, "control_end", PLAY_ONCE_STOP, 0) end
end

function main.start.transition(this, context, input)
    if input == INPUT_DRAW then
        if context:isVirtual() then
            play(context, "ground_use", PLAY_ONCE_HOLD)
            return this.main.ground
        end
        play(context, variant(context, "draw"), PLAY_ONCE_STOP)
        return this.main.idle
    elseif input == INPUT_PUT_AWAY then
        return put_away(this, context)
    end
end

function main.idle.update(this, context)
    if context:isUsing() then context:trigger("start_use") end
end

function main.idle.transition(this, context, input)
    if input == INPUT_INSPECT then
        play(context, variant(context, "inspect"), PLAY_ONCE_STOP, 0.3)
    elseif input == "start_use" then
        play(context, "use", PLAY_ONCE_HOLD, 0.2)
        return this.main.using
    elseif input == INPUT_PUT_AWAY then
        return put_away(this, context)
    end
end

function main.using.entry(this, context)
    this.useCount = context:getStackCount()
end

function main.using.update(this, context)
    local count = context:getStackCount()
    if context:isUsing() and count < this.useCount then
        -- Held use can consume a kit and start the next between render frames.
        play(context, "use", PLAY_ONCE_HOLD, 0.05)
        this.useCount = count
    elseif not context:isUsing() then
        -- The clip ends just before the native use completes, so a held clip means success.
        if context:isHolding(track(context)) then
            context:trigger("finish_use")
        else
            context:trigger("cancel_use")
        end
    end
end

function main.using.transition(this, context, input)
    if input == "finish_use" then
        play(context, "use_end", PLAY_ONCE_STOP, 0.15)
        return this.main.idle
    elseif input == "cancel_use" then
        play(context, variant(context, "static_idle"), LOOP, 0.35)
        return this.main.idle
    elseif input == INPUT_PUT_AWAY then
        return put_away(this, context)
    end
end

function main.ground.update(this, context)
    if not context:isUsing() then context:trigger("end_use") end
end

function main.ground.transition(this, context, input)
    if input == "end_use" then
        play(context, "ground_end", PLAY_ONCE_HOLD, 0.1)
        return this.main.final
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
