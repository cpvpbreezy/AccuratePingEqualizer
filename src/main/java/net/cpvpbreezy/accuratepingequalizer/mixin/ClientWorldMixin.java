package net.cpvpbreezy.accuratepingequalizer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.cpvpbreezy.accuratepingequalizer.OptimizerPause;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

/**
 * While the equalizer is on, HerosAnchorOptimizer's instant "fake anchor" swap is skipped, so a detonated
 * anchor stays on screen until the server's explosion arrives, one round trip later, like real ping.
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void ape$holdAnchorUntilServer(BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (OptimizerPause.shouldBlockFakeAnchor(state)) {
            cir.setReturnValue(false);
        }
    }
}
