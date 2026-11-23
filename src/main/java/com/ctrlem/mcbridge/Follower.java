package com.ctrlem.mcbridge;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * "Follow a player": each tick, turns the player toward the target and presses the real movement
 * keys — forward, sprint when far behind, jump when blocked or in water — the way a person would.
 *
 * <p>It walks straight at the target with no pathfinding, so walls it can't jump, drops and lava
 * are the player's problem. It only knows about players the client can see, so it stops as soon
 * as the target leaves view distance, changes dimension or goes into spectator.
 */
@EventBusSubscriber(modid = CtrlEmMod.MODID, value = Dist.CLIENT)
final class Follower {
    /** Stop walking inside this, so the player keeps up without shoving the target around. */
    private static final double STOP_DISTANCE = 2.5;
    private static final double SPRINT_DISTANCE = 6;
    /** Degrees per tick. Faster looks like an aimbot — to people watching and to anti-cheat. */
    private static final float MAX_TURN_PER_TICK = 20;
    /** Presses are refreshed every tick and lapse on their own a moment after following stops. */
    private static final int KEY_TICKS = 2;

    /** Game thread only. Null when not following. */
    private static UUID targetId;
    private static int ticksLeft;

    private Follower() {}

    /** The named player, or with an empty name the nearest one, among those this client can see. */
    static AbstractClientPlayer findTarget(Minecraft mc, LocalPlayer self, String name) {
        AbstractClientPlayer nearest = null;
        for (AbstractClientPlayer other : mc.level.players()) {
            if (other == self || other.isSpectator()) continue;
            if (!name.isEmpty()) {
                if (other.getPlainTextName().equalsIgnoreCase(name)) return other;
            } else if (nearest == null || self.distanceToSqr(other) < self.distanceToSqr(nearest)) {
                nearest = other;
            }
        }
        return nearest;
    }

    /** Replaces whatever was being followed before. */
    static void start(Player target, int ticks) {
        targetId = target.getUUID();
        ticksLeft = ticks;
    }

    // Before KeyHolder's tick handler, so this tick's key presses take effect this tick.
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onClientTick(ClientTickEvent.Pre event) {
        if (targetId == null) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        // The player can still end it by switching the command (or all of CtrlEm) off in the config.
        if (player == null || mc.level == null || player.isDeadOrDying() || --ticksLeft < 0
                || !Config.ENABLED.get() || !Config.ALLOW_FOLLOW.get()) {
            targetId = null;
            return;
        }
        Player target = mc.level.getPlayerByUUID(targetId);
        if (target == null || target.isRemoved() || target.isSpectator()) {
            targetId = null;
            return;
        }
        // Menus pause it, like any other input; the clock keeps running.
        if (mc.screen != null) return;

        lookAt(player, target.getEyePosition());
        if (player.distanceTo(target) > STOP_DISTANCE) {
            KeyHolder.hold(mc.options.keyUp, KEY_TICKS);
            if (player.distanceTo(target) > SPRINT_DISTANCE) KeyHolder.hold(mc.options.keySprint, KEY_TICKS);
            if (player.isInWater() || (player.horizontalCollision && player.onGround())) {
                KeyHolder.hold(mc.options.keyJump, KEY_TICKS);
            }
        }
    }

    private static void lookAt(LocalPlayer player, Vec3 point) {
        Vec3 eye = player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        // Same formulas as vanilla's LookControl.
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        player.setYRot(Mth.approachDegrees(player.getYRot(), yaw, MAX_TURN_PER_TICK));
        player.setXRot(Mth.approachDegrees(player.getXRot(), pitch, MAX_TURN_PER_TICK));
    }
}
