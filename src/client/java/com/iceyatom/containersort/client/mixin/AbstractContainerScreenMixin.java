package com.iceyatom.containersort.client.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iceyatom.containersort.client.ModConflicts;
import com.iceyatom.containersort.client.config.ButtonPosition;
import com.iceyatom.containersort.client.config.ContainerSortConfig;
import com.iceyatom.containersort.client.sort.SortExecutor;
import com.iceyatom.containersort.client.sort.SortManager;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

/**
 * Adds the sort button to supported container screens (FR-01) and keeps it in sync
 * with the container's contents.
 *
 * <p><b>What is intercepted and why</b> (NFR-11): all three injection targets are
 * methods declared on {@link AbstractContainerScreen} itself (NFR-12) — in Minecraft
 * 26.2 the concrete {@code ContainerScreen}/{@code ShulkerBoxScreen} subclasses no
 * longer declare {@code init()} at all, so the shared superclass is the narrowest
 * class that owns these methods. Concrete screen types are filtered with an
 * {@code instanceof} guard ({@link SortManager#isSupportedScreen}) so functional
 * containers (furnace, hopper, ...) are never touched (FR-13).
 *
 * <ul>
 *   <li>{@code init()} at TAIL — vanilla widgets are laid out by then, so the button
 *       can be anchored to the container GUI's final position without overlap.</li>
 *   <li>{@code containerTick()} at HEAD — cheap per-tick visibility refresh: the
 *       button only shows while the container holds two or more stacks (FR-02) and is
 *       disabled while a sort sequence is executing.</li>
 *   <li>{@code removed()} at HEAD — aborts any in-flight click sequence the moment the
 *       screen closes, so no clicks are ever sent against a closed handler (FR-18).</li>
 * </ul>
 *
 * <p>No overhead is added to screens that fail the support check beyond a single
 * {@code instanceof} test (NFR-02), and the button's press handler is the only entry
 * point into sorting — the mixin contains no sorting logic itself (NFR-13).
 */
@Mixin(value = AbstractContainerScreen.class, priority = 1100)
public abstract class AbstractContainerScreenMixin extends Screen {
	@Shadow
	protected int leftPos;
	@Shadow
	protected int topPos;
	@Shadow
	@Final
	protected int imageWidth;

	@Unique
	private Button containersort$button;

	protected AbstractContainerScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void containersort$addSortButton(CallbackInfo ci) {
		containersort$button = null;
		AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
		ContainerSortConfig cfg = ContainerSortConfig.get();
		if (!SortManager.isSupportedScreen(self) || !cfg.enabled || !cfg.showButton
				|| ModConflicts.buttonSuppressed()) {
			return;
		}
		int width = 32;
		int height = 12;
		int x = cfg.buttonPosition == ButtonPosition.TOP_LEFT
				? this.leftPos + 6
				: this.leftPos + this.imageWidth - width - 6;
		int y = this.topPos + 4;
		Button button = Button.builder(
						Component.translatable("containersort.button.label"),
						pressed -> SortManager.trySort(self))
				.bounds(x, y, width, height)
				.tooltip(Tooltip.create(Component.translatable("containersort.button.tooltip")))
				.build();
		containersort$button = this.addRenderableWidget(button);
		containersort$refreshButton(self);
	}

	@Inject(method = "containerTick", at = @At("HEAD"))
	private void containersort$containerTick(CallbackInfo ci) {
		if (containersort$button != null) {
			containersort$refreshButton((AbstractContainerScreen<?>) (Object) this);
		}
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void containersort$removed(CallbackInfo ci) {
		AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
		if (SortManager.isSupportedScreen(self)) {
			SortExecutor.abortFor(self.getMenu(), "container screen closed");
		}
	}

	@Unique
	private void containersort$refreshButton(AbstractContainerScreen<?> self) {
		ContainerSortConfig cfg = ContainerSortConfig.get();
		containersort$button.visible = cfg.enabled && cfg.showButton
				&& SortManager.containerSlotCount(self.getMenu()) >= cfg.minSlotThreshold
				&& SortManager.occupiedSlotCount(self.getMenu()) >= 2;
		containersort$button.active = !SortExecutor.isBusy();
	}
}
