package net.pavinical.sovereign.mixin;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VillagerEntity.class)
public abstract class DisableVillagerTradingMixin {
    @Inject(method = "interactMob", at = @At("HEAD"), cancellable = true)
    private void sovereign$disableVanillaTrading(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        cir.setReturnValue(ActionResult.SUCCESS);
    }
}
