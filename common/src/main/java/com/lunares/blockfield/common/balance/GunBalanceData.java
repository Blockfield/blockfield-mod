package com.lunares.blockfield.common.balance;

import com.lunares.blockfield.common.mixin.tacz.GunIndexAccessor;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.modifier.AttachmentCacheProperty;
import com.tacz.guns.resource.pojo.data.gun.ChargeData;
import com.tacz.guns.resource.pojo.data.gun.FeedType;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.resource.pojo.data.gun.GunRecoilKeyFrame;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

public final class GunBalanceData {
    private GunBalanceData() {}

    public static ResourceLocation itemKey(ResourceLocation item) {
        return typedKey("item", item);
    }

    public static ResourceLocation itemSource(ResourceLocation key) {
        return typedSource("item", key);
    }

    public static ResourceLocation throwableKey(ResourceLocation data) {
        return typedKey("throwable", data);
    }

    public static ResourceLocation throwableSource(ResourceLocation key) {
        return typedSource("throwable", key);
    }

    public static ResourceLocation meleeKey(ResourceLocation data) {
        return typedKey("melee", data);
    }

    public static ResourceLocation meleeSource(ResourceLocation key) {
        return typedSource("melee", key);
    }

    public record MountedSource(ResourceLocation vehicle, String weaponName) {
        public ResourceLocation sourceId() {
            return typedSource("mounted", mountedKey(vehicle, weaponName));
        }
    }

    /** Native mounted names are case-sensitive and may contain characters invalid in a resource. */
    public static ResourceLocation mountedKey(ResourceLocation vehicle, String weaponName) {
        GunBalanceSchema.require(
                weaponName != null && !weaponName.isEmpty(), "mounted", "empty weapon name");
        byte[] bytes;
        try {
            ByteBuffer encoded =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(CharBuffer.wrap(weaponName));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Invalid mounted weapon UTF-8", invalid);
        }
        GunBalanceSchema.require(
                bytes.length <= 128, "mounted", "weapon name exceeds 128 UTF-8 bytes");
        ResourceLocation key =
                ResourceLocation.fromNamespaceAndPath(
                        "mounted",
                        vehicle.getNamespace()
                                + "/"
                                + vehicle.getPath()
                                + "/"
                                + HexFormat.of().formatHex(bytes));
        GunBalanceSchema.require(
                key.toString().length() <= 256, "mounted", "weapon ID exceeds 256 characters");
        return key;
    }

    public static MountedSource mountedSource(ResourceLocation key) {
        if (!key.getNamespace().equals("mounted")) return null;
        GunBalanceSchema.require(
                key.toString().length() <= 256, "mounted", "weapon ID exceeds 256 characters");
        ResourceLocation source = typedSource("mounted", key);
        int separator = source.getPath().lastIndexOf('/');
        GunBalanceSchema.require(
                separator > 0 && separator < source.getPath().length() - 1,
                key.toString(),
                "expected mounted:namespace/vehicle/UTF8hex-name");
        String hex = source.getPath().substring(separator + 1);
        GunBalanceSchema.require(
                hex.length() <= 256 && hex.length() % 2 == 0 && hex.matches("[0-9a-f]+"),
                key.toString(),
                "noncanonical UTF8hex weapon name");
        String name;
        try {
            name =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(HexFormat.of().parseHex(hex)))
                            .toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Invalid mounted weapon UTF-8", invalid);
        }
        ResourceLocation vehicle =
                ResourceLocation.fromNamespaceAndPath(
                        source.getNamespace(), source.getPath().substring(0, separator));
        GunBalanceSchema.require(
                mountedKey(vehicle, name).equals(key), key.toString(), "noncanonical mounted ID");
        return new MountedSource(vehicle, name);
    }

    private static ResourceLocation typedKey(String kind, ResourceLocation source) {
        return ResourceLocation.fromNamespaceAndPath(
                kind, source.getNamespace() + "/" + source.getPath());
    }

    private static ResourceLocation typedSource(String kind, ResourceLocation key) {
        if (!key.getNamespace().equals(kind)) return null;
        int separator = key.getPath().indexOf('/');
        GunBalanceSchema.require(
                separator > 0 && separator < key.getPath().length() - 1,
                key.toString(),
                "expected " + kind + ":namespace/path");
        ResourceLocation source =
                ResourceLocation.tryParse(
                        key.getPath().substring(0, separator)
                                + ":"
                                + key.getPath().substring(separator + 1));
        GunBalanceSchema.require(
                source != null && typedKey(kind, source).equals(key),
                key.toString(),
                "noncanonical " + kind + " weapon ID");
        return source;
    }

    public static Map<ResourceLocation, CommonGunIndex> rebuild(
            Map<ResourceLocation, GunData> changedData,
            Map<ResourceLocation, CommonGunIndex> originalIndices) {
        Map<ResourceLocation, CommonGunIndex> result = new HashMap<>(originalIndices);
        originalIndices.forEach(
                (id, original) -> {
                    GunData data = changedData.get(original.getPojo().getData());
                    if (data == null) return;
                    validate(data, id.toString(), data);
                    CommonGunIndex index = GunIndexAccessor.blockfield$create();
                    GunIndexAccessor accessor = (GunIndexAccessor) (Object) index;
                    accessor.blockfield$setPojo(original.getPojo());
                    GunIndexAccessor.blockfield$checkIndex(original.getPojo(), index);
                    GunIndexAccessor.blockfield$checkInaccuracy(data);
                    GunIndexAccessor.blockfield$checkRecoil(data);
                    GunIndexAccessor.blockfield$checkScript(data, index);
                    accessor.blockfield$setGunData(data);
                    result.put(id, index);
                });
        return result;
    }

    public static void validate(GunData data, String path) {
        validate(data, path, null);
    }

    public static void validate(GunData data, String path, GunData previousData) {
        GunBalanceSchema.require(
                data != null && data.getAmmoId() != null && data.getAmmoAmount() > 0,
                path,
                "ammo and positive ammo_amount are required");
        GunBalanceSchema.require(
                data.getBolt() != null
                        && data.getReloadData() != null
                        && data.getReloadData().getType() != null
                        && data.getReloadData().getFeed() != null
                        && data.getReloadData().getCooldown() != null
                        && data.getBulletData() != null
                        && data.getBulletData().getIgnite() != null
                        && data.getRecoil() != null
                        && data.getBurstData() != null
                        && data.getMoveSpeed() != null
                        && data.getMeleeData() != null
                        && data.getFireSound() != null,
                path,
                "missing required gun object");
        GunBalanceSchema.require(
                data.getFireModeSet() != null
                        && !data.getFireModeSet().isEmpty()
                        && !data.getFireModeSet().contains(null)
                        && !data.getFireModeSet().contains(FireMode.UNKNOWN)
                        && Set.copyOf(data.getFireModeSet()).size() == data.getFireModeSet().size(),
                path,
                "fire_mode must contain unique known modes");
        int[] magazines = data.getExtendedMagAmmoAmount();
        GunBalanceSchema.require(
                magazines == null || magazines.length == 3,
                path,
                "extended_mag_ammo_amount must have exactly 3 entries");
        float feed = data.getBoltFeedTime();
        GunBalanceSchema.require(
                feed == -1 || feed >= 0 && feed <= data.getBoltActionTime(),
                path,
                "bolt_feed_time must be -1 or within bolt_action_time");
        GunBalanceSchema.require(
                data.getScript() != null
                        || previousData != null
                                && unchanged(data.getReloadData(), previousData.getReloadData())
                        || data.getReloadData().getType() == FeedType.MANUAL
                        || data.getReloadData().getType() == FeedType.INVENTORY
                        || data.getReloadData().getFeed().getEmptyTime()
                                        <= data.getReloadData().getCooldown().getEmptyTime()
                                && data.getReloadData().getFeed().getTacticalTime()
                                        <= data.getReloadData().getCooldown().getTacticalTime(),
                path,
                "reload feed cannot exceed cooldown");
        recoil(data.getRecoil().getPitch(), path + ".recoil.pitch");
        recoil(data.getRecoil().getYaw(), path + ".recoil.yaw");
        if (data.getHeatData() != null) {
            var heat = data.getHeatData();
            GunBalanceSchema.require(
                    heat.getHeatMax() > 0 && heat.getMinInaccuracy() <= heat.getMaxInaccuracy(),
                    path,
                    "heat max must be positive and min_inaccuracy <= max_inaccuracy");
        }
        for (FireMode mode : FireMode.values()) {
            var adjustment = data.getFireModeAdjustData(mode);
            long rpm =
                    (long) data.getRoundsPerMinute()
                            + (adjustment == null ? 0 : adjustment.getRoundsPerMinute());
            GunBalanceSchema.require(
                    rpm >= 1 && rpm <= Integer.MAX_VALUE,
                    path + ".fire_mode_adjust",
                    "effective rpm must be positive and fit an integer");
            if (adjustment != null) {
                GunBalanceSchema.require(
                        data.getBulletData().getDamageAmount() + adjustment.getDamageAmount() >= 0
                                && data.getBulletData().getSpeed() + adjustment.getSpeed() > 0,
                        path + ".fire_mode_adjust",
                        "effective damage/speed invalid");
            }
            if (data.getHeatData() != null) {
                var heat = data.getHeatData();
                GunBalanceSchema.require(
                        Math.min(rpm, 1200) * Math.min(heat.getMinRpmMod(), heat.getMaxRpmMod())
                                >= 1,
                        path + ".heat",
                        "heat-adjusted rpm must remain at least 1");
            }
            ChargeData charge = data.getChargeData(mode);
            if (charge != null)
                GunBalanceSchema.require(
                        charge.getChargeType() != null
                                && charge.getMaxCharge() > 0
                                && charge.getFireThreshold() <= charge.getMaxCharge(),
                        path + ".charging",
                        "charge type/max/threshold invalid");
        }
        if (data.getBulletData().getExtraDamage() != null) {
            var curve = data.getBulletData().getExtraDamage().getDamageAdjust();
            float previous = -1;
            var previousCurve =
                    previousData == null || previousData.getBulletData().getExtraDamage() == null
                            ? null
                            : previousData.getBulletData().getExtraDamage().getDamageAdjust();
            if (curve != null && !unchanged(curve, previousCurve))
                for (var point : curve) {
                    GunBalanceSchema.require(
                            point != null && point.getDistance() > previous,
                            path + ".bullet.extra_damage.damage_adjust",
                            "distances must increase");
                    previous = point.getDistance();
                }
        }
    }

    private static boolean unchanged(Object value, Object previous) {
        return Objects.equals(
                CommonAssetsManager.GSON.toJsonTree(value),
                CommonAssetsManager.GSON.toJsonTree(previous));
    }

    private static void recoil(GunRecoilKeyFrame[] frames, String path) {
        if (frames == null || frames.length == 0) return;
        GunBalanceSchema.require(frames.length >= 2, path, "spline needs at least 2 keyframes");
        float previous = -1;
        for (GunRecoilKeyFrame frame : frames) {
            GunBalanceSchema.require(
                    frame != null
                            && frame.getValue() != null
                            && frame.getValue().length == 2
                            && frame.getValue()[0] <= frame.getValue()[1]
                            && frame.getTime() > previous,
                    path,
                    "keyframes need increasing times and ordered value pairs");
            previous = frame.getTime();
        }
    }

    public record HeldCache(
            AttachmentCacheProperty properties, float putAwayTime, FireMode selectedMode) {}

    public static HeldCache prepareCache(
            LivingEntity shooter,
            Map<ResourceLocation, CommonGunIndex> indices,
            Set<ResourceLocation> changed) {
        ItemStack stack = shooter.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) return null;
        CommonGunIndex index = indices.get(gun.getGunId(stack));
        if (index == null || !changed.contains(index.getPojo().getData())) return null;
        AttachmentCacheProperty cache = new AttachmentCacheProperty();
        ItemStack preparedStack = stack.copy();
        normalizeMode(preparedStack, index.getGunData());
        cache.eval(preparedStack, index.getGunData());
        return new HeldCache(
                cache, index.getGunData().getPutAwayTime(), gun.getFireMode(preparedStack));
    }

    public static void normalizeMode(ItemStack stack, GunData data) {
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun != null && !data.getFireModeSet().contains(gun.getFireMode(stack)))
            gun.setFireMode(stack, data.getFireModeSet().getFirst());
    }

    public static void installCache(LivingEntity shooter, HeldCache cache) {
        if (cache == null) return;
        IGunOperator operator = IGunOperator.fromLivingEntity(shooter);
        ItemStack held = shooter.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(held);
        if (gun != null && gun.getFireMode(held) != cache.selectedMode())
            gun.setFireMode(held, cache.selectedMode());
        operator.updateCacheProperty(cache.properties());
        operator.getDataHolder().currentPutAwayTimeS = cache.putAwayTime();
    }
}
