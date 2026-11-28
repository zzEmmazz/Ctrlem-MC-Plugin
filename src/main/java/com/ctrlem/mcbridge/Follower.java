package com.ctrlem.mcbridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import java.util.UUID;

/** Client-side steering toward a visible player for a limited number of ticks. */
final class Follower {
    private static UUID targetId;
    private static int ticksLeft;
    private Follower() {}

    static AbstractClientPlayer findTarget(Minecraft mc, LocalPlayer self, String name) {
        if (mc.level == null) return null;
        AbstractClientPlayer nearest = null;
        for (AbstractClientPlayer other : mc.level.players()) {
            if (other == self || other.isSpectator()) continue;
            if (!name.isBlank()) {
                if (other.getGameProfile().getName().equalsIgnoreCase(name)) return other;
                continue;
            }
            if (nearest == null || self.distanceToSqr(other) < self.distanceToSqr(nearest)) nearest = other;
        }
        return nearest;
    }

    static void start(Player target, int durationTicks) { targetId = target.getUUID(); ticksLeft = durationTicks; }

    static void onStartTick(Minecraft mc) {
        if (targetId == null) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !player.isAlive() || --ticksLeft < 0 ||
            !Config.INSTANCE.enabled || !Config.allows("follow")) { targetId = null; return; }
        AbstractClientPlayer target = null;
        for (AbstractClientPlayer other : mc.level.players()) {
            if (other.getUUID().equals(targetId)) { target = other; break; }
        }
        if (target == null || target.isRemoved() || target.isSpectator()) { targetId = null; return; }
        if (mc.screen != null) return;
        lookAt(player, target.getEyePosition());
        double distance = player.distanceTo(target);
        if (distance > 2.5) {
            KeyHolder.hold(mc.options.keyUp, 2);
            if (distance > 6) KeyHolder.hold(mc.options.keySprint, 2);
            if (player.isInWater() || (player.horizontalCollision && player.onGround()))
                KeyHolder.hold(mc.options.keyJump, 2);
        }
    }

    private static void lookAt(LocalPlayer player, Vec3 point) {
        Vec3 eye = player.getEyePosition();
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        float yaw = (float)Math.toDegrees(Math.atan2(dz, dx)) - 90;
        float pitch = (float)-Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        player.setYRot(approach(player.getYRot(), yaw));
        player.setXRot(approach(player.getXRot(), pitch));
    }
    private static float approach(float from, float to) {
        return from + Mth.clamp(Mth.wrapDegrees(to - from), -20f, 20f);
    }
}
