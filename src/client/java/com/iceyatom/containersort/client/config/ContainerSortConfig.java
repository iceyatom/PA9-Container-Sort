package com.iceyatom.containersort.client.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * ContainerSort configuration, backed by {@code config/containersort.toml} (FR-19).
 *
 * <p>Only a flat {@code key = value} subset of TOML is used, so the file is parsed and
 * written by hand rather than pulling in a TOML library. The file is created with
 * defaults on first launch and can be reloaded at runtime with
 * {@code /containersort reload} (FR-20).
 */
public final class ContainerSortConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("containersort");
	private static final String FILE_NAME = "containersort.toml";

	private static ContainerSortConfig instance = new ContainerSortConfig();

	/** Master toggle — disables all mod behaviour when false. */
	public boolean enabled = true;
	/** Ordering applied after consolidation (FR-07/FR-08). */
	public SortMode sortMode = SortMode.ALPHABETICAL;
	/** Consolidate partial stacks of the same item before reordering (FR-06). */
	public boolean mergePartialStacks = true;
	/** Render the sort button in supported screens; the keybind still works if disabled. */
	public boolean showButton = true;
	/** Corner of the container GUI the button is anchored to. */
	public ButtonPosition buttonPosition = ButtonPosition.TOP_RIGHT;
	/** Delay in milliseconds between simulated slot-click packets (PKT-03). Range 0–250. */
	public int clickDelayMs = 50;
	/** Suppress the button on any container type smaller than this slot count (FR-15). */
	public int minSlotThreshold = 27;
	/** Keep a pre-sort snapshot and allow {@code /containersort undo} (FR-16/FR-17). */
	public boolean enableUndo = true;
	/** Auto-disable the button when a conflicting sort mod is detected (NFR-10). */
	public boolean respectOtherSortMods = true;
	/** Write verbose sort-plan diagnostics to the Fabric log. Development use only. */
	public boolean debugLogging = false;

	private ContainerSortConfig() {
	}

	public static ContainerSortConfig get() {
		return instance;
	}

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	/** Loads the config from disk, creating the file with defaults if it does not exist. */
	public static void load() {
		Path path = configPath();
		ContainerSortConfig cfg = new ContainerSortConfig();
		if (Files.exists(path)) {
			try {
				List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
				for (String raw : lines) {
					String line = raw.trim();
					if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) {
						continue;
					}
					int eq = line.indexOf('=');
					if (eq < 0) {
						continue;
					}
					String key = line.substring(0, eq).trim();
					String value = stripComment(line.substring(eq + 1).trim());
					apply(cfg, key, value);
				}
			} catch (IOException e) {
				LOGGER.warn("[ContainerSort] Failed to read {}; using defaults", path, e);
			}
		}
		cfg.clickDelayMs = Math.max(0, Math.min(250, cfg.clickDelayMs));
		cfg.minSlotThreshold = Math.max(0, cfg.minSlotThreshold);
		instance = cfg;
		// Always rewrite so new options gain their documented defaults after an update.
		cfg.save();
	}

	private static String stripComment(String value) {
		boolean quoted = value.startsWith("\"");
		if (!quoted) {
			int hash = value.indexOf('#');
			if (hash >= 0) {
				value = value.substring(0, hash).trim();
			}
			return value;
		}
		int close = value.indexOf('"', 1);
		return close > 0 ? value.substring(1, close) : value.substring(1);
	}

	private static void apply(ContainerSortConfig cfg, String key, String value) {
		try {
			switch (key) {
				case "enabled" -> cfg.enabled = Boolean.parseBoolean(value);
				case "sort_mode" -> cfg.sortMode = SortMode.valueOf(unquote(value).toUpperCase(Locale.ROOT));
				case "merge_partial_stacks" -> cfg.mergePartialStacks = Boolean.parseBoolean(value);
				case "show_button" -> cfg.showButton = Boolean.parseBoolean(value);
				case "button_position" -> cfg.buttonPosition = ButtonPosition.valueOf(unquote(value).toUpperCase(Locale.ROOT));
				case "click_delay_ms" -> cfg.clickDelayMs = Integer.parseInt(value);
				case "min_slot_threshold" -> cfg.minSlotThreshold = Integer.parseInt(value);
				case "enable_undo" -> cfg.enableUndo = Boolean.parseBoolean(value);
				case "respect_other_sort_mods" -> cfg.respectOtherSortMods = Boolean.parseBoolean(value);
				case "debug_logging" -> cfg.debugLogging = Boolean.parseBoolean(value);
				default -> LOGGER.warn("[ContainerSort] Unknown config key '{}' ignored", key);
			}
		} catch (IllegalArgumentException e) {
			LOGGER.warn("[ContainerSort] Invalid value '{}' for config key '{}'; keeping default", value, key);
		}
	}

	private static String unquote(String value) {
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			return value.substring(1, value.length() - 1);
		}
		return value;
	}

	/** Writes the current values (with documentation comments) back to disk. */
	public void save() {
		String content = """
				# ContainerSort configuration
				# Reload in-game with: /containersort reload

				# Master toggle - disables all mod behaviour when false.
				enabled = %s

				# ALPHABETICAL | ITEM_ID | COUNT_DESC - controls ordering. ITEM_ID is locale-independent.
				sort_mode = "%s"

				# Consolidate partial stacks of the same item before reordering.
				merge_partial_stacks = %s

				# Render the sort button in supported screens. The keybind still works if disabled.
				show_button = %s

				# TOP_RIGHT | TOP_LEFT - corner of the container GUI the button is anchored to.
				button_position = "%s"

				# Delay between simulated slot-click packets during execution. Range 0-250.
				# Raise on high-latency or heavily-moderated servers.
				click_delay_ms = %d

				# Suppress the button on any supported container type smaller than this slot count.
				min_slot_threshold = %d

				# Keep a pre-sort snapshot and allow /containersort undo.
				enable_undo = %s

				# Auto-disable the button when a conflicting sort mod is detected.
				respect_other_sort_mods = %s

				# Write verbose sort-plan diagnostics to the Fabric log. Development use only.
				debug_logging = %s
				""".formatted(enabled, sortMode, mergePartialStacks, showButton, buttonPosition,
				clickDelayMs, minSlotThreshold, enableUndo, respectOtherSortMods, debugLogging);
		try {
			Files.createDirectories(configPath().getParent());
			Files.writeString(configPath(), content, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("[ContainerSort] Failed to write {}", configPath(), e);
		}
	}
}
