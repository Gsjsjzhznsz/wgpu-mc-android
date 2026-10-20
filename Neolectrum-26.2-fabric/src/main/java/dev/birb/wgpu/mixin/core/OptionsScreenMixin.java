package dev.birb.wgpu.mixin.core;

import dev.birb.wgpu.gui.OptionPageScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin {
    @Shadow
    @Final
    private static Component VIDEO;

    @Inject(method = "openScreenButton", at = @At("HEAD"), cancellable = true)
    private void wgpuMc$replaceVideoOptionsScreen(Component title, Supplier<Screen> supplier, CallbackInfoReturnable<Button> cir) {
        if (VIDEO.equals(title)) {
            Screen parent = (Screen) (Object) this;
            // `Minecraft.getInstance()` and not `parent.getMinecraft()`: the accessor is a
                        // NeoForge-added member of Screen, and the vanilla jar answers the same
                        // question through the static.
                        cir.setReturnValue(Button.builder(VIDEO, btn -> net.minecraft.client.Minecraft.getInstance().setScreenAndShow(new OptionPageScreen(parent))).build());
        }
    }
}
