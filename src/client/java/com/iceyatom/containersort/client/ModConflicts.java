package com.iceyatom.containersort.client;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iceyatom.containersort.client.config.ContainerSortConfig;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Startup detection of other inventory-sorting mods (NFR-10). When one is present and
 * {@code respect_other_sort_mods} is enabled, ContainerSort suppresses its own button
 * (the keybind keeps working) to avoid a doubled-up UI.
 */
public final class ModConflicts {
	private static final Logger LOGGER = LoggerFactory.getLogger("containersort");

	/** Mod IDs known to add their own sort button/keybind to container screens. */
	private static final List<String> KNOWN_SORT_MODS = List.of(
			"inventoryprofilesnext",
			"mousewheelie",
			"inventorysorter",
			"chestcleaner",
			"invsorter",
			"sortme",
			"inventorytweaks"
	);

	private static String detectedMod;

	private ModConflicts() {
	}

	public static void detect() {
		for (String id : KNOWN_SORT_MODS) {
			if (FabricLoader.getInstance().isModLoaded(id)) {
				detectedMod = id;
				LOGGER.info("[ContainerSort] Detected other sorting mod '{}'; the sort button will be hidden while respect_other_sort_mods is true", id);
				return;
			}
		}
	}

	/** True when the sort button should be suppressed because of a detected conflict. */
	public static boolean buttonSuppressed() {
		return detectedMod != null && ContainerSortConfig.get().respectOtherSortMods;
	}
}
