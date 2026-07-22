package com.iceyatom.containersort.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iceyatom.containersort.client.config.ContainerSortConfig;
import com.iceyatom.containersort.client.sort.SortExecutor;
import com.iceyatom.containersort.client.sort.SortManager;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

/**
 * Client entrypoint: loads the config, registers the sort keybind (FR-05), the
 * {@code /containersort} client commands (FR-17/FR-20), and the tick driver that
 * executes planned click sequences. Entirely client-side — no server component
 * (NFR-09).
 */
public class ContainerSortClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("containersort");

	/** Sort trigger keybind; unbound by default, active only in supported screens (FR-05). */
	public static KeyMapping sortKey;

	@Override
	public void onInitializeClient() {
		ContainerSortConfig.load();
		ModConflicts.detect();

		sortKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.containersort.sort", -1, KeyMapping.Category.INVENTORY));

		// Drives in-flight click sequences and abort-on-close detection (FR-18, PKT-03).
		ClientTickEvents.END_CLIENT_TICK.register(SortExecutor::tick);

		// The keybind only fires while a supported container screen is focused (FR-05);
		// vanilla key handling does not tick keybinds inside screens, so the screen's
		// own key events are used instead.
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof AbstractContainerScreen<?> containerScreen
					&& SortManager.isSupportedScreen(screen)) {
				ScreenKeyboardEvents.afterKeyPress(screen).register((s, key) -> {
					if (sortKey != null && sortKey.matches(key)) {
						SortManager.trySort(containerScreen);
					}
				});
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommands.literal("containersort")
					.then(ClientCommands.literal("sort").executes(context -> {
						Screen screen = context.getSource().getClient().gui.screen();
						if (screen instanceof AbstractContainerScreen<?> containerScreen
								&& SortManager.isSupportedScreen(screen)) {
							SortManager.trySort(containerScreen);
							context.getSource().sendFeedback(Component.translatable("containersort.message.sorting"));
						} else {
							context.getSource().sendError(Component.translatable("containersort.message.no_container"));
						}
						return 1;
					}))
					.then(ClientCommands.literal("undo").executes(context -> {
						Screen screen = context.getSource().getClient().gui.screen();
						String error = SortManager.tryUndo(screen);
						if (error == null) {
							context.getSource().sendFeedback(Component.translatable("containersort.message.undoing"));
						} else {
							context.getSource().sendError(Component.literal(error));
						}
						return 1;
					}))
					.then(ClientCommands.literal("reload").executes(context -> {
						SortExecutor.abort("config reloaded");
						ContainerSortConfig.load();
						context.getSource().sendFeedback(Component.translatable("containersort.message.reloaded"));
						return 1;
					})));
		});

		LOGGER.info("[ContainerSort] Initialised (client-side only)");
	}
}
